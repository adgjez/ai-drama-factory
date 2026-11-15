package com.dramafactory.app.ui

import com.dramafactory.app.AppGraph
import com.dramafactory.app.ui.AssetCatalog
import com.dramafactory.core.pipeline.DefaultPipelineOrchestrator
import com.dramafactory.core.pipeline.DefaultRenderQueue
import com.dramafactory.core.provider.SharedHttp
import com.dramafactory.core.quality.StoryboardGate
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.jvm.javaio.toInputStream

/**
 * 渲染队列运行时 —— 按集懒建/复用DefaultRenderQueue实例 + 编排器恢复入口。
 *
 * DefaultRenderQueue是「单episode单worker」形态（构造注入VideoProvider等），
 * App层按episodeId维护实例表；recoverOnBoot经queueFor回调逐集恢复。
 */
object RenderRuntime {

    @Volatile private var orchestrator: DefaultPipelineOrchestrator? = null
    private val queues = HashMap<String, DefaultRenderQueue>()

    /**
     * 取某集的渲染队列（无则创建）。downloader把videoUrl下载到app缓存目录并校验size>0。
     * ★第五轮加固：episodeId为空防御 + AppGraph未初始化时抛可读错误由调用方降级，绝不NPE。
     */
    @Synchronized
    fun queueFor(episodeId: String): DefaultRenderQueue {
        val ep = episodeId.ifBlank { "default" }
        return queues.getOrPut(ep) {
            DefaultRenderQueue(
                scope = appScope(),
                videoProvider = AppGraph.video,
                checkpointStore = AppGraph.checkpointStore,
                budgetGuard = AppGraph.budgetGuard,
                downloader = { videoUrl, shotId -> downloadClip(videoUrl, shotId) },
                projectIdOf = { e -> e.substringBeforeLast("_ep") },
                // ★F1 修复：从 shots 表读该镜的 dialogue/narration/action 组装真实提交 prompt。
                // 旧实现未给 shotPromptResolver 接线 → 每镜 prompt 恒为「全程使用中文普通话配音」
                // （与剧本无关，烧真钱出随机画面）。shotId 形如 "{episodeId}_shot{n}"，
                // 取 episodeId 后查 shots 表回填该镜文本。若查不到（如 Room 未初始化）安全退化为空三元组。
                shotPromptResolver = { shotId ->
                    val shot = runCatching { AppGraph.dao.shotKeyframes(shotId) }.getOrNull()
                    // ★质量修复 B：scene_context 改由 shotSceneContextProvider 单独传递（避免重复拼接）
                    Triple(shot?.dialogue ?: "", shot?.narration ?: "", cleanShotAction(shot?.action, shot?.visual_prompt))
                },
                // v1.7.18：视频参数透传（设置页可调；每镜提交前读取，改参数即时生效）
                videoParamsProvider = { _ -> AppGraph.videoParams },
                // ★质量修复 B/F：镜头语言 / 场景锚点 / 镜头时长（落库字段直通队列；prompt 与帧数都用）
                shotSceneContextProvider = { shotId ->
                    runCatching { AppGraph.dao.shotKeyframes(shotId)?.scene_context }.getOrNull() ?: ""
                },
                shotCinematicProvider = { shotId ->
                    runCatching {
                        val shot = AppGraph.dao.shotKeyframes(shotId)
                        val visual = shot?.visual_prompt?.takeIf { it.isNotBlank() } ?: ""
                        val style = AppGraph.currentPreset().globalPromptSuffix
                        if (visual.isBlank()) style else "景别与运镜：$visual。影调：$style"
                    }.getOrDefault("")
                },
                shotDurationProvider = { shotId ->
                    runCatching { AppGraph.dao.shotKeyframes(shotId)?.duration_seconds }.getOrNull() ?: 0.0
                },
                // ★v1.8.0 质量三闸接线（对齐 pavo fidelity_gate + storyboard 六铁律 + shot_director 开场帧重渲染）
                // 提交前保真闸：把该镜分镜条目编译为 StoryboardGate.Entry 跑 FidelityGate。
                // 未绑定资产的镜 fail-soft 跳过（避免误杀纯场景/动作镜，整集 sb_check 仍覆盖）。
                fidelityGateEntryProvider = { shotId ->
                    runCatching { AppGraph.dao.shotKeyframes(shotId) }.getOrNull()?.let { shot ->
                        val assetIds = AssetCatalog.parseRefIds(shot.first_asset_ids)
                        if (assetIds.isEmpty()) null else {
                            val beatIdx = shot.beat_ref?.filter { it.isDigit() }?.toIntOrNull() ?: shot.shot_no
                            val beatRef = shot.beat_ref?.takeIf { it.isNotBlank() } ?: "beat_%02d".format(shot.shot_no)
                            StoryboardGate.Entry(
                                shotId = shot.shot_id,
                                index = shot.shot_no,
                                panel = StoryboardGate.Panel(
                                    action = shot.action ?: "",
                                    dialogue = parseShotDialogue(shot.dialogue),
                                    duration = shot.duration_seconds,
                                ),
                                beatRef = beatRef,
                                beatIndex = beatIdx,
                                associateAssetIds = assetIds,
                                carryOver = shot.carry_over ?: "",
                                durationSeconds = shot.duration_seconds,
                            )
                        }
                    }
                },
                // catalog 已批准（有图）资产集合：FidelityGate A.1 资产真实校验用
                catalogApprovedIdsProvider = { epId ->
                    val pid = epId.substringBeforeLast("_ep")
                    runCatching { AppGraph.dao.assetsAllOf(pid) }.getOrNull()
                        // ★质量修复 G：被 QC 拒绝(REJECTED)的资产不算「已批准」，否则差图照样进保真闸/参考图
                        ?.filter { it.audit_state == "approved" }
                        ?.filter { !it.remote_url.isNullOrBlank() || !it.image_uri.isNullOrBlank() }
                        ?.map { it.asset_id }?.toSet() ?: emptySet()
                },
                // ★v1.10：已拒绝资产集合（硬拦）与项目真实资产全集（编造 ID 识别）
                catalogRejectedIdsProvider = { epId ->
                    val pid = epId.substringBeforeLast("_ep")
                    runCatching { AppGraph.dao.assetsAllOf(pid) }.getOrNull()
                        ?.filter { it.audit_state == "rejected" }
                        ?.filter { !it.asset_id.isNullOrBlank() }
                        ?.map { it.asset_id }?.toSet() ?: emptySet()
                },
                catalogKnownIdsProvider = { epId ->
                    val pid = epId.substringBeforeLast("_ep")
                    runCatching { AppGraph.dao.assetsAllOf(pid) }.getOrNull()
                        ?.filter { !it.asset_id.isNullOrBlank() }
                        ?.map { it.asset_id }?.toSet() ?: emptySet()
                },
                // ★v1.10：未审核资产放行（只告警不阻断）——修复旧资产 audit_state=pending 导致整集渲染全灭
                allowUnreviewedAssets = true,
                // ★第二批 P0（B-G1）：严格目录模式——批准集合为空也必须逐资产校验，不再 fail-open
                catalogStrict = true,
                // 整集六铁律：任一镜 sb_check 含 error → 整集中止（fail-closed）
                storyboardBlockedProvider = { epId ->
                    runCatching { AppGraph.dao.shotsOf(epId) }.getOrNull()?.let { shots ->
                        shots.any { it.sb_check.contains("error", ignoreCase = true) && it.sb_check != "pass" }
                    }
                },
                // 开场帧重渲染（不去头改重渲染）：已有写回首帧→直接用；没有→按剧本 i2i 合成电影开场静帧
                openingFrameProvider = { shotId -> synthesizeOpeningFrame(shotId) },
                // v1.9.24 TD-7 修复：资产锁脸三件套此前只在 QueueViewModel 接到 queueFor("default")，
                // 真实 episode 队列（queueFor(episodeId)）永远拿到空 lambda → 道具 ref 从不注入、跨镜不锁脸。
                // 现统一在 queueFor 构造期接齐，每个 episode 实例均生效。
                // 图生视频关键帧（首帧 + 尾帧）
                shotKeyframeResolver = { shotId ->
                    AppGraph.dao.shotKeyframes(shotId)?.let { it.first_image_uri to it.last_image_uri }
                        ?: (null to null)
                },
                // 角色/场景资产参考图（i2i 锁脸）：优先本镜 first_asset_ids，空则项目级兜底
                shotAssetImageResolver = { shotId ->
                    val epId = shotId.substringBeforeLast("_shot").takeIf { it.contains("_ep") } ?: shotId
                    val projectId = epId.substringBeforeLast("_ep").ifBlank { epId }
                    runCatching {
                        val shot = AppGraph.dao.shotKeyframes(shotId)
                            ?: AppGraph.dao.shotsOf(epId).firstOrNull { it.shot_id == shotId }
                        val refIds = AssetCatalog.parseRefIds(shot?.first_asset_ids)
                        // ★v1.10：参考图「只认 approved」放宽为「排除 rejected」——未审核资产不再让整集无参考图，
                        // 质检拒绝的差图仍然硬隔离（配合 fidelity 闸的 gate_asset_rejected）
                        val all = AppGraph.dao.assetsAllOf(projectId).filter { it.audit_state != "rejected" }
                        AssetCatalog.resolveRefUris(all, refIds)
                            .ifEmpty { AssetCatalog.fallbackUris(all) }
                    }.getOrDefault(emptyList())
                },
                // 视频参考（仅当前模型支持视频参考时返回，否则空）
                shotReferenceVideoResolver = { shotId ->
                    val cfg = AppGraph.dao.verifiedConfig("video")
                    val models = AppGraph.video.listModels()
                    val supported = (models.firstOrNull { it.id == (cfg?.model ?: "agnes") } ?: models.first())
                        .supportsVideoReference
                    if (supported) AppGraph.dao.shotReferenceVideo(shotId) else null
                },
                // v1.9.28：图床上传器（Android 侧实现：content:// 等复制到 App 内部 uploads 目录并走图床 API）
                // 暂时用占位，如果配置了图床服务则上传，否则透传让远端 400（由 ValidationError 捕获）
                mediaUrlResolver = { uri -> AppGraph.uploadImageToCloud(uri) },
            )
        }
    }

    /**
     * ★质量修复 C：自动开场帧合成（i2i）。
     * 旧链路 first_image_uri 的唯一真实写入点是 QueuePage 里人工从相册选图 → 流水线产出的镜
     * 95% 没有首帧，视频模型只能从纯文字起步，人物/场景每镜重掷。
     * 这里在提交前用「场景底图 + 角色参考图」做一次 img2img，把电影开场静帧写回 first_image_uri
     * 以便复用与审计；任何一步失败返回 null，队列自动回落到原始 first 逻辑（绝不阻断渲染）。
     */
    private suspend fun synthesizeOpeningFrame(shotId: String): String? {
        return try {
        val dao = AppGraph.dao
        val epId = shotId.substringBeforeLast("_shot").takeIf { it.contains("_ep") } ?: shotId
        val shot = dao.shotKeyframes(shotId) ?: dao.shotsOf(epId).firstOrNull { it.shot_id == shotId }
        val existing = shot?.first_image_uri?.takeIf { it.isNotBlank() }
        if (existing != null) return existing
        val projectId = epId.substringBeforeLast("_ep").ifBlank { epId }
        // ★v1.10：同参考图策略——未审核资产可用于开场帧合成，拒绝态仍然排除
        val all = dao.assetsAllOf(projectId).filter { it.audit_state != "rejected" }
        val refIds = AssetCatalog.parseRefIds(shot?.first_asset_ids)
        val refUris = AssetCatalog.resolveRefUris(all, refIds)
            .ifEmpty { AssetCatalog.fallbackUris(all) }
            .filter { it.isNotBlank() }.distinct().take(4)
        if (refUris.isEmpty()) return null
        val sceneBase = all.firstOrNull {
            it.kind == "scene" && (!it.remote_url.isNullOrBlank() || !it.image_uri.isNullOrBlank())
        }?.let { it.remote_url?.takeIf { s -> s.isNotBlank() } ?: it.image_uri }
        val base = sceneBase ?: refUris.first()
        // 场景锚点 + 导演镜头语言一起作为「本镜开场画面描述」（旧链路两者都没有）
        val frameStart = listOfNotNull(
            shot?.scene_context?.takeIf { it.isNotBlank() },
            shot?.visual_prompt?.takeIf { it.isNotBlank() },
        ).ifEmpty { listOfNotNull(shot?.action) }.joinToString("；")
        val (prompt, negative) = com.dramafactory.core.quality.ShotDirector.buildOpeningFramePrompt(
            frameStart = frameStart,
            binding = com.dramafactory.core.quality.ShotDirector.Binding(
                baseUri = base,
                assetUris = refUris.filter { it != base },
                legend = "参考图片为角色/场景设定图，仅用于保持人物长相与场景一致，不要照搬棚拍背景。",
            ),
            stylePreset = AppGraph.currentPreset(),
        )
        val inputs = (listOfNotNull(sceneBase) + refUris).distinct()
        val url = try {
            AppGraph.image.generateImage(com.dramafactory.core.model.ImageGenRequest(
                prompt = prompt, size = "720x1280", inputImages = inputs))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // 各图像 Provider 支持的尺寸/参数不同（Agnes 图像端拒绝 negative_prompt、尺寸档位各异）：
            // 退回默认尺寸且不带负向词再试一次；仍失败由外层 runCatching 返回 null 回落原始首帧逻辑。
            AppGraph.image.generateImage(com.dramafactory.core.model.ImageGenRequest(
                prompt = prompt, inputImages = inputs))
        }
        val publicUrl = AppGraph.uploadImageToCloud(url)
        try {
            dao.setShotKeyframes(shotId, publicUrl, shot?.last_image_uri)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Throwable) {
            // 单镜写库失败不阻断整集，返回 URL 供本次渲染兜底使用
        }
        publicUrl
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * ★第二批 P0：编排器分镜落库后自动合成整集关键帧。
     * 逐镜生成首帧（i2i，复用 [synthesizeOpeningFrame]），再做尾帧接力：
     * 镜 N 的尾帧 = 镜 N+1 的首帧，保证图生视频首尾连续。
     * 任何一镜失败只跳过该镜，绝不阻断流水线；返回成功合成的镜数。
     */
    suspend fun synthesizeEpisodeKeyframes(episodeId: String, maxShots: Int = 24): Int {
        return try {
        // 无可用图像 Key 时直接跳过：否则会逐镜走到 Provider 才失败，白跑 24 次（A-G4）
        if (!AppGraph.agnesKeyReady()) return 0
        val dao = AppGraph.dao
        val shots = dao.shotsOf(episodeId).sortedBy { it.shot_no }
            .take(maxShots.coerceAtLeast(1))
        if (shots.isEmpty()) return 0
        val byId = shots.associateBy { it.shot_id }
        val firsts = LinkedHashMap<String, String>()
        val freshlySynthesized = LinkedHashSet<String>()
        for (shot in shots) {
            val hadFirst = !shot.first_image_uri.isNullOrBlank()
            val uri = synthesizeOpeningFrame(shot.shot_id)
            if (!uri.isNullOrBlank()) {
                firsts[shot.shot_id] = uri
                if (!hadFirst) freshlySynthesized += shot.shot_id
            }
        }
        val ids = shots.map { it.shot_id }
        var written = 0
        ids.forEachIndexed { i, sid ->
            val first = firsts[sid] ?: return@forEachIndexed
            // 尾帧接力：下一镜首帧优先；下一镜失败或末镜时保留本镜已有尾帧，
            // 绝不把人工设置的尾帧覆盖成 null（A-G2）。
            val nextFirst = ids.getOrNull(i + 1)?.let { firsts[it] }
            val lastToUse = nextFirst ?: byId[sid]?.last_image_uri
            try {
                dao.setShotKeyframes(sid, first, lastToUse)
                if (sid in freshlySynthesized) written++
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Throwable) {
                // 写库失败即视为未合成，不虚报成功（A-G3）
            }
        }
        written
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Throwable) {
            0
        }
    }

    /** UI/Service便捷入口 */
    fun queue(): DefaultRenderQueue = queueFor("default")

    /** 编排器（含恢复入口），懒建并绑定queueFor */
    @Synchronized
    fun orchestrator(): DefaultPipelineOrchestrator =
        orchestrator ?: DefaultPipelineOrchestrator(
            checkpointStore = AppGraph.checkpointStore,
            queue = null,
            queueFor = { ep -> queueFor(ep) },
        ).also { orchestrator = it }

    /** 进程重启恢复总入口：读checkpoint → repoll已提交镜 → 续跑队列（P1-6） */
    suspend fun recoverOnBoot() = orchestrator().recoverOnBoot()

    private var scopeRef: kotlinx.coroutines.CoroutineScope? = null
    internal fun bindScope(scope: kotlinx.coroutines.CoroutineScope) { scopeRef = scope }
    private fun appScope(): kotlinx.coroutines.CoroutineScope =
        // ★第五轮加固：不再error()抛异常（QueueViewModel构造期触发会直接闪退），
        // 未接线时兜底自建SupervisorJob作用域（测试/ContentProvider早期路径）。
        scopeRef ?: kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
            .also { fallbackScope = it }

    @Volatile private var fallbackScope: kotlinx.coroutines.CoroutineScope? = null

    /**
     * clip下载器：写缓存目录文件，size必须>0才算completed（架构§5约束）。
     * 失败抛异常 → 队列保持SUBMITTED仅重试取回，绝不重新提交已付费任务。
     */
    private suspend fun downloadClip(videoUrl: String, shotId: String): Pair<String, Long> {
        val dir = cacheDir()
        // ★F5 修复：校验 mkdirs() 结果，目录不可写立即抛明确错误，不再静默丢弃返回值。
        if ((!dir.exists() && !dir.mkdirs()) || !dir.isDirectory) {
            throw IllegalStateException("无法创建/访问 clip 缓存目录：${dir.absolutePath}")
        }
        val f = java.io.File(dir, "$shotId.mp4")
        // TD-3：付费 clip 下载统一走 Ktor SharedHttp（全项目唯一网络栈），获得超时/重试/拦截器与统一日志，
        // 不再用裸 HttpURLConnection（无超时保护、Android 连接池泄漏与 TLS 历史问题）。
        // 失败抛异常 → 队列保持 SUBMITTED 仅重试取回，绝不重新提交已付费任务（与架构§5一致）。
        SharedHttp.client.prepareGet(videoUrl).execute { resp ->
            if (resp.status.value !in 200..299) {
                throw IllegalStateException("下载clip失败 http=${resp.status.value} shot=$shotId")
            }
            val input = resp.bodyAsChannel().toInputStream()
            f.outputStream().use { out ->
                val buf = ByteArray(DEFAULT_BUFFER_SIZE)
                var read: Int
                while (input.read(buf).also { read = it } > 0) {
                    out.write(buf, 0, read)
                }
            }
        }
        val size = f.length()
        check(size > 0) { "下载clip为空文件 shot=$shotId" }
        return f.absolutePath to size
    }

    /**
     * ★F5 修复：已付费镜头的 clip 下载目录统一用 App Context 的 cacheDir，
     * 与全项目其它路径（AppGraph.appContext / AssetsPage / LibraryPage）保持一致，
     * 不再用 java.io.tmpdir（ART 上取值不可控，且与全项目策略不一致）。
     * 兜底顺序：appContext().cacheDir → appContext().filesDir/clips → 最后才退 java.io.tmpdir。
     */
    private fun cacheDir(): java.io.File {
        val ctx = AppGraph.appContext()
        val base = ctx?.cacheDir ?: ctx?.filesDir
        if (base != null) return java.io.File(base, "clips")
        return java.io.File(System.getProperty("java.io.tmpdir") ?: "/tmp", "ai-drama-clips")
    }

    /**
     * 把 shots 表 dialogue 字符串解析为 StoryboardGate.DialogueLine 列表（提交前保真闸逐字校验用）。
     * 支持纯文本 / 多行 / "角色：台词" 形态；JSON 数组退化为按条取 text 字段。
     */
    private fun parseShotDialogue(raw: String?): List<StoryboardGate.DialogueLine> {
        if (raw.isNullOrBlank()) return emptyList()
        val trimmed = raw.trim()
        if (trimmed.startsWith("[")) {
            return runCatching {
                val arr = org.json.JSONArray(trimmed)
                (0 until arr.length()).mapNotNull { i ->
                    val o = arr.optJSONObject(i) ?: return@mapNotNull null
                    val t = o.optString("text").ifEmpty { o.optString("line") }
                    if (t.isBlank()) null else StoryboardGate.DialogueLine(text = t)
                }
            }.getOrElse { emptyList() }
        }
        return trimmed.split("\n").mapNotNull { line ->
            if (line.isBlank()) null else StoryboardGate.DialogueLine(text = line.trim())
        }
    }
}