package com.dramafactory.core.pipeline

import com.dramafactory.core.model.CheckpointEntry
import com.dramafactory.core.model.PollResult
import com.dramafactory.core.model.ProviderError
import com.dramafactory.core.model.VideoParams
import com.dramafactory.core.model.QueueSnapshot
import com.dramafactory.core.model.ShotMeta
import com.dramafactory.core.model.ShotState
import com.dramafactory.core.provider.BudgetGuard
import com.dramafactory.core.provider.CheckpointStore
import com.dramafactory.core.provider.MediaUrlResolver
import com.dramafactory.core.provider.RenderQueue
import com.dramafactory.core.provider.VideoProvider
import com.dramafactory.core.quality.FidelityGate
import com.dramafactory.core.quality.StoryboardGate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 渲染队列 —— 单消费者协程（架构§2）。
 *
 * 每镜流程：BudgetGuard判定 → 【先落SUBMITTING意图】→ submit（过限速门）
 * → video_id到手【立即同步】markSubmitted落库 → 自适应轮询(30s→60s)
 * → 下载clip(size>0校验，失败仅重试取回不重提) → COMPLETED。
 * 401/预算超限 → 项目级PAUSED；单镜失败不拖垮队列。
 *
 * P0-1 防重复付费不变量：
 * - submitVideo 之前 SUBMITTING 意图已同步持久化；
 * - video_id 到手后第一件事是同步 markSubmitted，之后才做任何其他事；
 * - 已发出HTTP但结果不明（瞬断/解析失败/429歧义）→ RECONCILE 待对账，绝不盲目重提。
 * P0-2 单消费者不变量：enqueueEpisode 重入时旧 worker cancel 后必须 join 退出才启动新 worker。
 */
class DefaultRenderQueue(
    private val scope: CoroutineScope,
    private val videoProvider: VideoProvider,
    private val checkpointStore: CheckpointStore,
    private val budgetGuard: BudgetGuard,
    /** 下载videoUrl到本地文件并返回(uri,size)；size必须>0才算completed */
    private val downloader: suspend (videoUrl: String, shotId: String) -> Pair<String, Long>,
    /** 轮询间隔策略：默认自适应30s→60s；测试可注入0 */
    var pollIntervalMs: suspend (submittedAt: Long) -> Long = { _ ->
        30_000L
    },
    /** 提交prompt组装：shotId → (dialogue,narration,action+场景锚点)，由分镜层提供 */
    var shotPromptResolver: suspend (shotId: String) -> Triple<String, String, String> =
        { _ -> Triple("", "", "") },
    /** 首尾帧解析：shotId → (firstUri,lastUri) */
    var shotKeyframeResolver: suspend (shotId: String) -> Pair<String?, String?> = { _ -> null to null },
    // v1.7.2：角色/场景资产参考图解析器（套用 pavo 锁脸逻辑）。给定 shotId 返回该镜
    // 应注入视频生成的资产参考图 URI 列表（角色主锚图为主），使角色长相跨镜一致。
    var shotAssetImageResolver: suspend (shotId: String) -> List<String> = { _ -> emptyList() },
    /** 第六轮：视频参考解析：shotId → referenceVideoUri（仅当模型标记支持时由上游填充） */
    var shotReferenceVideoResolver: suspend (shotId: String) -> String? = { _ -> null },
    // v1.8.0：质量三闸接线（对齐 pavo fidelity_gate + storyboard 六铁律 + shot_director 开场帧重渲染）
    /** 提交前保真闸数据：给定 shotId 返回编译好的分镜条目（含台词/资产/时长/beat/carry_over） */
    var fidelityGateEntryProvider: suspend (shotId: String) -> StoryboardGate.Entry? = { _ -> null },
    /** catalog 已批准资产集合（FidelityGate A.1 资产真实校验用） */
    var catalogApprovedIdsProvider: suspend (episodeId: String) -> Set<String> = { _ -> emptySet() },
    /** ★第二批 P0（B-G1）：严格目录模式。批准集合为空时也逐资产校验（fail-closed），
     *  防止「没有任何已批准资产」时整条资产校验被短路跳过。生产端（AppGraph 接线）置 true；
     *  默认 false 保持既有单测/旧调用行为。 */
    var catalogStrict: Boolean = false,
    /** ★v1.10：已拒绝（rejected）资产集合——质检拒绝的资产硬拦，不再进入渲染 */
    var catalogRejectedIdsProvider: suspend (episodeId: String) -> Set<String> = { _ -> emptySet() },
    /** ★v1.10：项目真实资产 ID 全集；null=未配置（退回仅 approved 判定） */
    var catalogKnownIdsProvider: suspend (episodeId: String) -> Set<String>? = { _ -> null },
    /** ★v1.10：未审核（pending）资产是否放行（warn 不阻断）。生产端置 true，避免旧资产把整集渲染全灭 */
    var allowUnreviewedAssets: Boolean = true,
    /** 整集六铁律是否未通过（读 shots.sb_check；有 error → 整集中止）。null=未配置(放行) */
    var storyboardBlockedProvider: suspend (episodeId: String) -> Boolean? = { _ -> null },
    /** 开场帧重渲染（不去头改重渲染）：返回重渲染后首帧 URI；null=用原始 first（逃生开关） */
    var openingFrameProvider: suspend (shotId: String) -> String? = { _ -> null },
    // v1.7.18：视频参数提供器（分辨率/帧数/帧率）。App 层从设置持久化读取，
    // null 表示用 VideoSubmitRequest 默认值。每镜提交前查询，改参数即时生效。
    var videoParamsProvider: suspend (shotId: String) -> VideoParams? = { _ -> null },
    /** ★质量修复 B：场景连续性 / 镜头语言 / 镜头时长（来自 shots 表，供 prompt 与帧数使用） */
    var shotSceneContextProvider: suspend (String) -> String = { "" },
    var shotCinematicProvider: suspend (String) -> String = { "" },
    var shotDurationProvider: suspend (String) -> Double = { 0.0 },
    /** ★第五批 P0：空白分镜止损。台词/旁白/动作全空（孤儿镜、分镜文本丢失）时绝不提交付费任务。
     *  默认 false 保持既有单测行为；生产端（RenderRuntime）置 true。 */
    var blockEmptyPrompt: Boolean = false,
    /** ★第五批 P0：COMPLETED 但本地 clip 丢失时，用落库的远端 URL 重取（绝不重新提交付费任务） */
    var recordedRemoteUrlOf: suspend (shotId: String) -> String? = { _ -> null },
    /** ★第五批 P0：轮询到成片 URL 时先落库（配合 recordedRemoteUrlOf，让付费 clip 可重新取回） */
    var remoteUrlRecorder: suspend (shotId: String, videoUrl: String) -> Unit = { _, _ -> },
    /** ★第五批 P0：本地 clip 是否仍在（uri,size）；默认视为存在（单测无文件系统） */
    var clipFileChecker: (uri: String, expectedSize: Long) -> Boolean = { _, _ -> true },
    // v1.9.28：本地媒体 URI → 公网 URL（图床）。2.5 要求参考媒体公开可访问，
    // content:///file:///data: 必须先上传再提交。默认恒等透传（无图床/单测）。
    // 上传失败抛异常 → 本镜按 ValidationError 标 FAILED（可读原因），不烧钱提交。
    var mediaUrlResolver: MediaUrlResolver = MediaUrlResolver.IDENTITY,
    private val projectIdOf: (episodeId: String) -> String = { "" },
) : RenderQueue {

    /** v1.9.28：本队列实例级 URL 缓存——同一张图多镜复用只上传一次；失败不缓存（重试可再传） */
    private val publicUrlCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    private val _state = MutableStateFlow(QueueSnapshot())
    override val state: StateFlow<QueueSnapshot> get() = _state

    @Volatile private var paused = false
    @Volatile private var pausedReason: String? = null
    /** v1.7.18：暂停状态只读访问（AI 助手/UI 状态展示用） */
    val isPaused: Boolean get() = paused
    val pauseReason: String? get() = pausedReason
    /** P1-5：用户对budget_exceeded确认放行后，允许越过预算门提交（一次性，提交后即复位） */
    @Volatile private var budgetConfirmed = false
    private val cancelledShots = mutableSetOf<String>()
    private val submittedAtMap = mutableMapOf<String, Long>()
    private var worker: Job? = null
    /** ★第五批 P0：入队/剪枝/自愈串行化（旧实现无锁：两次 enqueue 竞争会各起 worker 重复提交付费） */
    private val enqueueMutex = Mutex()

    /** P1-4：取回失败重试退避上限（指数退避封顶） */
    companion object {
        /** 由镜头时长（秒, 24fps）换算 Agnes 合法帧数 8n+1（clamp 9..441）；时长非法返回 null。 */
        fun numFramesForDuration(durationSeconds: Double, fps: Int = 24): Int? {
            if (durationSeconds <= 0.0) return null
            val target = Math.round(durationSeconds * fps).toInt()
            val n = Math.round((target - 1) / 8.0).toInt()
            return (n * 8 + 1).coerceIn(9, 441)
        }

        const val FETCH_RETRY_BASE_MS = 2_000L
        const val FETCH_RETRY_CAP_MS = 60_000L
        /** ★F5 修复：取回失败重试次数上限。达到上限后保持 SUBMITTED 退出本 repoll，
         *  避免已付费镜头因缓存目录不可写等原因「永久空转」（下次 recoverOnBoot 经 pendingRepoll 重试一次）。 */
        const val FETCH_RETRY_MAX = 8
    }

    override suspend fun enqueueEpisode(episodeId: String, shots: List<ShotMeta>) {
        // ★第五批 P0：入队/剪枝/自愈/重建 worker 全部串行化（旧实现无锁，双击「开始渲染」
        // 会两个 worker 并行抢同一 PENDING 镜 → 同一镜重复提交、重复扣费）。
        enqueueMutex.withLock {
            // ★第五批 P0：空分镜不得启动 worker。旧实现会对着历史 render_tasks 的 PENDING/SUBMITTING
            // 行继续跑（用户看到「清空队列后开始渲染没反应/反而重提旧任务」的根因之一）。
            if (shots.isEmpty()) {
                updateSnapshot(episodeId)
                _state.value = _state.value.copy(running = false)
                return
            }
            // 入队前过load-or-merge：复用checkpoint权威态
            checkpointStore.loadOrMerge(episodeId, shots)
            // ★第五批 P0：剪掉已不存在于分镜的 checkpoint 行（幽灵任务），
            // 否则 recoverOnBoot/本循环会为已删除镜头重新提交付费任务。
            runCatching { checkpointStore.pruneToShots(episodeId, shots.map { it.shotId }.toSet()) }
            // 重新入队=用户重新点「开始渲染」：清掉上一轮的取消标记（旧实现只增不清，
            // 同队列实例里被取消过的镜永远被跳过）。
            cancelledShots.removeAll { sid -> shots.any { it.shotId == sid } }
            // ★第五批 P0：已 COMPLETED 但本地 clip 被系统清理的镜，先用落库的远端 URL 重新取回，
            // 绝不重新提交付费任务。
            healCompletedClips(episodeId)
            // P1-5：budget_exceeded 暂停必须等用户确认，enqueue不得自动续跑烧钱
            if (pausedReason != "budget_exceeded") {
                paused = false; pausedReason = null
            }
            // P0-2：旧worker必须真正退出（cancel是异步的）才能启动新worker，
            // 否则两个worker短暂并行会同时选中同一PENDING镜重复提交。
            worker?.cancel()
            worker?.join()
            worker = scope.launch {
                // 主循环：反复扫描checkpoint直至全部终态。
                // 暂停（预算/401/弱网）时不退出——delay等待用户resume后自动续跑剩余PENDING镜
                while (true) {
                    if (paused) { delay(100); continue }
                    updateSnapshot(episodeId)
                    // 先re-poll已提交镜（恢复路径），绝不重新submit已提交任务
                    val repolls = checkpointStore.pendingRepoll(episodeId)
                    if (repolls.isNotEmpty()) {
                        for (entry in repolls) {
                            if (paused) break
                            repoll(entry.shotId, entry.providerTaskId!!)
                        }
                        continue
                    }
                    val cp = checkpointStore.getEpisode(episodeId)
                    val next = cp?.shots?.firstOrNull {
                        it.shotId !in cancelledShots && it.state == ShotState.PENDING
                    }
                    if (next == null) break   // 无待处理镜：队列跑完
                    processShot(episodeId, next.shotId)
                    updateSnapshot(episodeId)
                }
                updateSnapshot(episodeId)
                _state.value = _state.value.copy(running = false)
            }
        }
    }

    /**
     * ★第五批 P0：本地 clip 丢失自愈。
     * 付费已完成的镜头（COMPLETED）若本地文件被系统清理，用落库的远端 URL 重新下载并 markCompleted；
     * 没有远端 URL 时标 RECONCILE 交人工对账，绝不重新 submit（重提=重复付费）。
     */
    private suspend fun healCompletedClips(episodeId: String) {
        val cp = runCatching { checkpointStore.getEpisode(episodeId) }.getOrNull() ?: return
        for (entry in cp.shots) {
            if (entry.state != ShotState.COMPLETED) continue
            val uri = entry.localFileUri ?: continue
            val present = runCatching { clipFileChecker(uri, entry.fileSize) }.getOrDefault(true)
            if (present) continue
            val remote = runCatching { recordedRemoteUrlOf(entry.shotId) }.getOrNull()
            if (remote.isNullOrBlank()) {
                runCatching {
                    checkpointStore.markReconcile(entry.shotId,
                        "local clip missing and no remote url; manual reconcile required")
                }
                continue
            }
            try {
                val (newUri, size) = downloader(remote, entry.shotId)
                checkpointStore.markCompleted(entry.shotId, newUri, size)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                runCatching {
                    checkpointStore.markReconcile(entry.shotId,
                        "local clip re-download failed: " + (e.message ?: "unknown"))
                }
            }
        }
    }

    private suspend fun updateSnapshot(episodeId: String) {
        val cp = checkpointStore.getEpisode(episodeId)
        _state.value = QueueSnapshot(
            episodeId = episodeId,
            totalShots = cp?.shots?.size ?: 0,
            completedShots = cp?.completedCount ?: 0,
            running = true,
            pausedReason = pausedReason,
        )
    }

    private suspend fun resolvePublicUrl(uri: String?, shotId: String): String? {
        if (uri.isNullOrBlank()) return null
        if (!MediaUrlResolver.needsUpload(uri)) return uri
        return try {
            publicUrlCache.getOrPut(uri) {
                // v1.9.28：由于协程 getOrPut 不能包含 suspend，用 runBlocking（因为这个缓存仅队列单线程写，不会饿死）
                // 更正：getOrPut 会锁 ConcurrentHashMap 的分段，在其中 runBlocking 会导致底层调度器挂起，
                // 由于 processShot 本就在 suspend 下，我们手写 double-checked locking 避免锁内 suspend。
                ""
            }.takeIf { it.isNotBlank() } ?: run {
                val pub = mediaUrlResolver.resolve(uri)
                if (pub.isNotBlank()) publicUrlCache[uri] = pub
                pub
            }
        } catch (e: Exception) {
            throw ProviderError.ValidationError("无法将参考媒体转为公网URL: ${e.message}")
        }
    }

    /** 单镜：意图落库→提交→落库→轮询→下载。任何单镜异常不得拖垮整个队列（PRD §6.1崩溃率约束） */
    private suspend fun processShot(episodeId: String, shotId: String) {
        val projectId = projectIdOf(episodeId)
        try {
            // 预算闸门：将超上限 → 队列暂停等待用户确认
            // P1-5：用户已对budget_exceeded显式确认 → 放行本次提交（确认后复位，防无限越权）
            if (!budgetGuard.canSubmit(projectId) && !budgetConfirmed) {
                pause("budget_exceeded")
                return
            }
            budgetConfirmed = false
            // ★第五批 P0：SUBMITTING 意图已移到所有校验之后、submitVideo 之前。
            // 旧实现先落 SUBMITTING 再跑闸门：闸门拦截 / storyboard_gate 暂停会留下卡死的 SUBMITTING，
            // 恢复时被误判为「已提交结果未知→需对账」，用户看到莫名的待对账镜。

            // ★v1.8.0 质量三闸·整集六铁律（提交前整集一次）：任一镜 sb_check=error → 整集中止，
            // 绝不烧钱出未过审的镜。对齐 pavo storyboard 六铁律 gate（fail-closed）。
            val epBlocked = runCatching { storyboardBlockedProvider(episodeId) }.getOrNull()
            if (epBlocked == true) { pause("storyboard_gate"); return }

            val (dialogue, narration, action) = shotPromptResolver(shotId)
            // ★质量修复 B：把导演的镜头语言与场景锚点带进最终视频 prompt（旧链路只剩台词+action）
            val sceneContext = runCatching { shotSceneContextProvider(shotId) }.getOrDefault("")
            val cinematic = runCatching { shotCinematicProvider(shotId) }.getOrDefault("")
            val shotDuration = runCatching { shotDurationProvider(shotId) }.getOrDefault(0.0)
            // ★第五批 P0：空白分镜止损——台词/旁白/动作全空时提交等于烧钱出随机画面（孤儿镜高发）
            if (blockEmptyPrompt && dialogue.isBlank() && narration.isBlank() && action.isBlank()) {
                runCatching {
                    checkpointStore.markFailed(shotId, "empty_prompt: 分镜台词/旁白/动作均为空，未提交以免浪费额度")
                }
                return
            }
            val prompt = com.dramafactory.core.provider.ChineseAudioInjector.buildShotPrompt(
                dialogue, narration, action, sceneContext, cinematic)

            // ★v1.8.0 质量三闸·提交前保真闸（FidelityGate A.1-A.7 确定性校验）：
            // 资产真实 / 台词逐字 / 时长镜序 / 禁编造 / 禁时间逆转 / 状态不漂移 / 跨镜一致。
            // blocked 的镜直接不提交（对齐 pavo：blocked 镜 continue，记 render_manifest.blocked_shots）。
            val gateEntry = runCatching { fidelityGateEntryProvider(shotId) }.getOrNull()
            if (gateEntry != null) {
                val catIds = runCatching { catalogApprovedIdsProvider(episodeId) }.getOrNull() ?: emptySet()
                val rejectedIds = runCatching { catalogRejectedIdsProvider(episodeId) }.getOrNull() ?: emptySet()
                val knownIds = runCatching { catalogKnownIdsProvider(episodeId) }.getOrNull()
                val gr = FidelityGate.gateShot(
                    entry = gateEntry,
                    motionPrompt = prompt,
                    catalogApprovedIds = catIds,
                    submittedDuration = if (shotDuration > 0.0) shotDuration else gateEntry.panel.duration,
                    expectedIndex = gateEntry.index,
                    requireCatalog = catalogStrict,
                    catalogRejectedIds = rejectedIds,
                    catalogKnownIds = knownIds,
                    allowUnreviewedAssets = allowUnreviewedAssets,
                )
                if (gr.blocked) {
                    val reason = gr.issues.filter { it.severity == "error" }
                        .joinToString("; ") { "${it.code}: ${it.message}" }
                    runCatching { checkpointStore.markFailed(shotId, "fidelity_blocked: $reason") }
                    return
                }
            }

            val (firstRaw, last) = shotKeyframeResolver(shotId)
            // ★v1.8.0 开场帧重渲染（不去头改重渲染，对齐 pavo head_trim 教训）：
            // 连续剧分镜严禁裁掉视频开头（首句台词常落开场 0.5s，去头必切对白），
            // 改为用重渲染后的电影开场帧作为首关键帧。openingFrameProvider 返回重渲染帧 URI，
            // 空=用原始 first（逃生开关，资产图本身就是理想开场帧时用）。
            val first = runCatching { openingFrameProvider(shotId) }.getOrNull() ?: firstRaw
            val referenceVideo = shotReferenceVideoResolver(shotId)
            // v1.7.2：套用 pavo 锁脸——每镜注入角色/场景资产参考图（i2i），保证跨镜长相一致
            val assetImages = shotAssetImageResolver(shotId)
            // v1.9.28：本地媒体转公网 URL（图床）。2.5 要求参考媒体公开可访问，
            // content:///file:///data: 在此统一上传；http(s) 透传。上传失败→ValidationError
            // 标 FAILED（可读原因），此时远端未建任务、未计费，可安全失败。
            val pubFirst = resolvePublicUrl(first, shotId)
            val pubLast = resolvePublicUrl(last, shotId)
            val pubRefVideo = resolvePublicUrl(referenceVideo, shotId)
            val pubAssets = assetImages.map { resolvePublicUrl(it, shotId)!! }
            // v1.7.18：设置页可调的视频参数（分辨率/帧数/帧率）透传；未配置时用模型默认
            val vp = runCatching { videoParamsProvider(shotId) }.getOrNull()
            // v1.7.10：视频端官方支持 negative_prompt（agnes-video-v20 文档确认），用于抑制
            // 人物漂移/脸部崩坏/剧烈抖动；英文抑制强于中文。锁脸专用负向模板（用户文档提供）。
            val videoNegative = "face drift, identity change, inconsistent character, deformed face, " +
                "deformed hands, extra fingers, severe shaking, motion blur overshoot, blurry, watermark, text"
            // ★第五批 P0：生死线前置条件——只有真正的 PENDING（非已 SUBMITTED/COMPLETED）才允许
            // 转 SUBMITTING 并提交；条件化落库失败即跳过该镜，杜绝重复付费。
            if (!checkpointStore.tryMarkSubmitting(shotId)) return
            val taskId = videoProvider.submitVideo(
                com.dramafactory.core.model.VideoSubmitRequest(
                    shotId = shotId, prompt = prompt,
                    firstImageUri = pubFirst, lastImageUri = pubLast,
                    referenceVideoUri = pubRefVideo,
                    inputImages = pubAssets,
                    negativePrompt = videoNegative,
                    // ★质量修复 F：未显式配置时默认竖屏 720x1280（旧默认 448x832 成片模糊且非 9:16）
                    width = vp?.width ?: 720,
                    height = vp?.height ?: 1280,
                    // 帧数按本镜真实时长换算 8n+1（旧链路所有镜固定 121 帧≈5s，时长字段形同虚设）
                    numFrames = vp?.numFrames ?: numFramesForDuration(shotDuration)
                        ?: com.dramafactory.core.model.VideoSubmitRequest.DEFAULT_NUM_FRAMES,
                    frameRate = vp?.frameRate ?: com.dramafactory.core.model.VideoSubmitRequest.DEFAULT_FRAME_RATE,
                )
            )
            // ★P0-1生死线第二步：HTTP 2xx/video_id一返回就【同步落库】，且这是拿到id后的第一个动作
            checkpointStore.markSubmitted(shotId, taskId)
            submittedAtMap[shotId] = System.currentTimeMillis()
            budgetGuard.consumeSubmitted(projectId)
            repoll(shotId, taskId)
        } catch (e: CancellationException) {
            // P1-3：用户取消≠业务失败。原样上抛保持结构化并发语义，绝不污染checkpoint
            throw e
        } catch (e: ProviderError.AuthError) {
            // 401全局语义：队列自动pause+横幅引导设置页，不烧重试（架构§4.8）。
            // 意图已落库：恢复后走RECONCILE/SUBMITTED路径对账或重提前先核实
            pause("auth_401")
        } catch (e: ProviderError.ValidationError) {
            // 明确的模型不存在/渠道不可用：远端未创建任务，直接失败并提示换模型。
            runCatching { checkpointStore.markFailed(shotId, e.message ?: "validation") }
        } catch (e: ProviderError.ReconcileRequired) {
            // P0-1：响应已计费但video_id缺失/响应体异常——落库原始响应待对账，绝不静默重提
            runCatching { checkpointStore.markReconcile(shotId, "${e.message} | raw=${e.rawBody.take(400)}") }
        } catch (e: Exception) {
            // 其余（网络瞬断/5xx耗尽/429耗尽）：HTTP请求可能已到达服务端，
            // 计费状态未知 → 标RECONCILE而非FAILED，防止恢复后重复付费
            runCatching { checkpointStore.markReconcile(shotId, e.message ?: "unknown") }
        }
    }

    /**
     * 轮询已知video_id至终态（恢复与正常路径共用，绝不重新submit）。
     * P1-4：pollResult瞬断在轮内退避重试；下载失败仅重试取回（镜保持SUBMITTED），
     * 与「生成失败」严格区分——已付费镜头永不因取回问题脱离re-pool通道。
     */
    private suspend fun repoll(shotId: String, taskId: String) {
        var fetchBackoff = FETCH_RETRY_BASE_MS
        var fetchFails = 0
        while (!paused && shotId !in cancelledShots) {
            val r = try {
                videoProvider.pollResult(taskId)
            } catch (e: CancellationException) {
                throw e   // P1-3：取消原样传播
            } catch (e: ProviderError.AuthError) {
                pause("auth_401"); return
            } catch (e: Exception) {
                delay(fetchBackoff); fetchBackoff = minOf(fetchBackoff * 2, FETCH_RETRY_CAP_MS)
                continue  // 瞬断：退避后继续轮询同一video_id
            }
            when (r) {
                is PollResult.Completed -> {
                    // ★第五批 P0：先落库远端成片 URL（付费资产可追溯/可重取），再下载本地 clip
                    runCatching { remoteUrlRecorder(shotId, r.videoUrl) }
                    try {
                        val (uri, size) = downloader(r.videoUrl, shotId)
                        checkpointStore.markCompleted(shotId, uri, size)
                        return
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // 取回失败≠生成失败：保持SUBMITTED，退避后重新下载
                        fetchFails++
                        if (fetchFails >= FETCH_RETRY_MAX) {
                            // ★F5：取回失败达到上限，保持 SUBMITTED 退出本 repoll，避免永久空转；
                            // 恢复路径（pendingRepoll）会在下次 recoverOnBoot 时重试一次。
                            return
                        }
                        delay(fetchBackoff); fetchBackoff = minOf(fetchBackoff * 2, FETCH_RETRY_CAP_MS)
                    }
                }
                is PollResult.Failed -> {
                    checkpointStore.markFailed(shotId, r.reason); return
                }
                is PollResult.InProgress -> {
                    fetchBackoff = FETCH_RETRY_BASE_MS   // 正常轮询，重置取回退避
                    delay(pollIntervalMs(submittedAtMap[shotId] ?: 0L))
                }
            }
        }
    }

    override fun cancelShot(shotId: String) { cancelledShots += shotId }

    override suspend fun pause() { paused = true; pausedReason = "manual" }

    suspend fun pause(reason: String) { paused = true; pausedReason = reason }

    override suspend fun resume(confirmedByUser: Boolean) {
        // 预算超限需用户确认弹窗才放行（US6）；其余原因自动恢复
        if (pausedReason == "budget_exceeded" && !confirmedByUser) return
        // P1-5：确认放行 → 允许越过预算门提交（一次性），并清暂停让worker续跑
        if (pausedReason == "budget_exceeded" && confirmedByUser) budgetConfirmed = true
        paused = false; pausedReason = null
    }
}
