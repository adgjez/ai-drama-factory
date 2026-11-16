package com.dramafactory.core.orchestrate

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * AI 全托管五阶段编排器（T014 §2.2，架构对齐）。
 *
 * 五阶段顺序：EXTRACT_ASSETS → GENERATE_IMAGES → AUDIT → GENERATE_STORYBOARD → ENQUEUE_RENDER
 * 严格顺序执行，任一阶段抛异常 → 立即追加 ERROR 事件 → 停止 → 已建项目/集/资产/分镜保留。
 *
 * 纯 Kotlin 类（core-engine 模块），所有外部依赖以函数引用注入，JVM 可单测。
 * :app 模块负责将 AppGraph 真实实现绑定到 DefaultAiOrchestrator 实例。
 */
/** ★第五批 P0（task-21）：画面类缺陷最多重生成 2 次（共审 3 张）；不可重试原因直接止损，不再为无图/无 Key 重生成付费。 */
private const val MAX_AUDIT_REGENERATIONS = 2

interface AiOrchestrator {

    /** 五阶段流水线实时进度（StateFlow 追加式日志流） */
    val events: StateFlow<List<ProgressEvent>>

    /** 当前编排运行中的 episodeId；未启动为 null */
    val currentEpisodeId: StateFlow<String?>

    /** 一键成片主入口（文本<100 抛 InputTooShort；模型不可用抛 ModelBlocked）
     * @param brief 用户已确认的成片 Brief（T014 任务2）；传入则把风格约束折叠进流水线 */
    suspend fun run(
        scriptText: String,
        textModelId: String = "",
        onAutoCreatedProject: (projectId: String, episodeId: String) -> Unit = { _, _ -> },
        brief: Brief? = null,
    ): Result<AiOrchestrator.PipelineRun>

    /** 从指定阶段重试：已完成阶段结果不重复调用 */
    suspend fun retryFrom(fromStage: PipelineStage5): Result<PipelineRun>

    /** 断点续跑：读已落库的 stage_flags → 返回最后一成功阶段 */
    suspend fun recoveryState(episodeId: String): PipelineStage5?

    sealed class AiError(val msg: String) : RuntimeException(msg) {
        class InputTooShort(val _msg: String) : AiError(_msg)
        class ModelBlocked(val _msg: String, val modelId: String) : AiError(_msg)
        class StageFailed(val _msg: String, val stage: PipelineStage5, val causeMsg: String) : AiError(_msg)
    }

    data class PipelineRun(
        val projectId: String,
        val episodeId: String,
        val success: Boolean,
        val lastStage: PipelineStage5,
        val errors: List<AiError>,
    )
}

/** 五阶段枚举 */
enum class PipelineStage5(val label: String) {
    EXTRACT_ASSETS("①提取资产"),
    GENERATE_IMAGES("②生成图像"),
    AUDIT("③质量审计"),
    GENERATE_STORYBOARD("④生成分镜"),
    ENQUEUE_RENDER("⑤入队渲染"),
    ENQUEUE_RENDER_DONE("✓ 完成"),
}

/** 流式进度事件 */
data class ProgressEvent(
    val stage: PipelineStage5,
    val subStep: Int,
    val message: String,
    val elapsedMs: Long,
    val stageElapsedMs: Long,
    val level: Level = Level.INFO,
    val error: String? = null,
) {
    enum class Level { INFO, WARN, ERROR }

    val isTerminal: Boolean
        get() = (stage == PipelineStage5.ENQUEUE_RENDER && level == Level.ERROR) ||
                 stage == PipelineStage5.ENQUEUE_RENDER_DONE
}

/**
 * 默认编排器实现（业务纯 Kotlin，JVM 可测）。
 *
 * 依赖以函数注入；:app 模块将 AppGraph 真实实现接到这些函数上。
 */
class DefaultAiOrchestrator(
    private val createProject: suspend (name: String) -> String = { "" },
    private val createEpisode: suspend (projectId: String, scriptText: String) -> String = { _, _ -> "" },
    private val checkModel: suspend (modelId: String) -> Result<Unit> = { Result.success(Unit) },
    private val extractAssets: suspend (text: String, modelId: String) -> Result<List<AiAsset>> =
        { _, _ -> Result.success(emptyList()) },
    private val generateImage: suspend (asset: AiAsset) -> Result<String> = { Result.failure(Exception("no image provider")) },
    private val auditAsset: suspend (asset: AiAsset) -> Result<AuditResult> =
        { Result.success(AuditResult(passed = true)) },
    // 第十五轮：加 projectId 参数，AppGraph 侧可据此从 DB 拉已抽取的资产注入 LLM
    private val generateShots: suspend (projectId: String, scriptText: String, modelId: String) -> Result<List<AiShot>> =
        { _, _, _ -> Result.success(emptyList()) },
    private val enqueueRender: suspend (episodeId: String, shots: List<AiShot>) -> Result<Int> =
        { _, _ -> Result.success(0) },
    /**
     * ★第二批 P0：自动关键帧阶段（首帧 + 尾帧接力）。
     * 分镜落库后调用；返回成功合成的镜数。实现方（:app）用「场景底图 + 角色参考图」做 img2img，
     * 把首帧写回 first_image_uri，并令镜 N 的尾帧 = 镜 N+1 的首帧以保证图生视频连续。
     * 默认 0：core 单测无图像服务时保持原行为，不阻断流水线。
     */
    private val generateKeyframes: suspend (episodeId: String, shots: List<AiShot>) -> Int =
        { _, _ -> 0 },
    private val persistAssets: suspend (episodeId: String, assets: List<AiAsset>) -> Unit = { _, _ -> },
    private val persistShots: suspend (episodeId: String, shots: List<AiShot>) -> Unit = { _, _ -> },
    private val writeCheckpoint: suspend (episodeId: String, lastSuccessStage: PipelineStage5,
                                           assetCount: Int, shotCount: Int, renderEnqueued: Boolean,
                                           failedStage: PipelineStage5?) -> Unit = { _, _, _, _, _, _ -> },
    private val readCheckpoint: suspend (episodeId: String) -> PipelineStage5? = { null },
    /** ★F4 修复：断点续跑时按 episodeId 读回真实剧本（episodes.script_json）。
     *  默认返回空串——:app 层（AppGraph）注入从 Room 读取真实脚本的实现。 */
    private val readScript: suspend (episodeId: String) -> String = { "" },
    /** ★第二批 P0：断点续跑时按 episodeId 读回已落库资产（fromStage 晚于①阶段时使用） */
    private val readAssets: suspend (episodeId: String) -> List<AiAsset> = { emptyList() },
    /** ★第二批 P0：断点续跑时按 episodeId 读回已落库分镜（fromStage = ⑤ 时使用） */
    private val readShots: suspend (episodeId: String) -> List<AiShot> = { emptyList() },
    /** 激活的文本模型 id 提供方（AppGraph 注入当前激活的 DeepSeek 模型 id；不能硬编码 default） */
    private val activeTextModelIdProvider: () -> String = { "default" },
    /** ★第五批 P0（task-21）：画面类缺陷重试用尽后的拒绝结论落库（assetId、原因、defects JSON）。
     *  默认空实现：core 单测 / desktop 无 DB 时保持原行为；:app 注入 dao.setAssetQuality(audit_state=rejected)。 */
    private val onAssetAuditRejected: suspend (assetId: String, rejectReason: String, defectsJson: String?, qualityScore: Double?) -> Unit =
        { _, _, _, _ -> },
) : AiOrchestrator {

    private val _events = MutableStateFlow<List<ProgressEvent>>(emptyList())
    override val events: StateFlow<List<ProgressEvent>> = _events
    private val _currentEpisodeId = MutableStateFlow<String?>(null)
    override val currentEpisodeId: StateFlow<String?> = _currentEpisodeId
    /** 最近一次 run 使用的剧本：readScript 读回失败/为空时的续跑兜底（仍用真实剧本，不用占位符） */
    private var lastScriptText: String = ""
    /** 最近一次读取/生成的资产与分镜：续跑时 readAssets/readShots 无实现（默认空）的兜底，避免空集合直接失败 */
    private var lastAssets: List<AiAsset> = emptyList()
    private var lastShots: List<AiShot> = emptyList()

    private val t0: Long
        get() = System.currentTimeMillis()

    private fun emit(stage: PipelineStage5, subStep: Int, message: String,
                     st0: Long, level: ProgressEvent.Level = ProgressEvent.Level.INFO,
                     error: String? = null) {
        _events.value += ProgressEvent(
            stage = stage, subStep = subStep, message = message,
            elapsedMs = System.currentTimeMillis() - t0,
            stageElapsedMs = System.currentTimeMillis() - st0,
            level = level, error = error,
        )
    }

    override suspend fun run(
        scriptText: String,
        textModelId: String,
        onAutoCreatedProject: (projectId: String, episodeId: String) -> Unit,
        brief: Brief?,
    ): Result<AiOrchestrator.PipelineRun> {
        if (scriptText.length < 100) {
            throw AiOrchestrator.AiError.InputTooShort("请粘贴≥100字剧本")
        }
        // 文本模型 id 兜底：优先用传入的，否则用当前激活的文本模型（activeTextModelId），
        // 不能硬编码 "default"——store 里 DeepSeek 的 id 是其真实模型名，否则 checkModel 必失败
        val resolvedModelId = textModelId.ifBlank { activeTextModelIdProvider() }
        checkModel(resolvedModelId).getOrNull()
            ?: throw AiOrchestrator.AiError.ModelBlocked(
                "文本模型 $resolvedModelId 未验证或 Key 为空", resolvedModelId)

        // T014 任务2：若用户已确认 brief，把 Brief 折叠进脚本（供资产/分镜提取参考风格约束）
        val effectiveScript = if (brief != null && brief.confirmed) {
            "${brief.toPromptFragment()}\n\n$scriptText"
        } else {
            scriptText
        }

        lastScriptText = effectiveScript
        return runStages(effectiveScript, resolvedModelId, onAutoCreatedProject,
            PipelineStage5.EXTRACT_ASSETS)
    }

    override suspend fun retryFrom(fromStage: PipelineStage5): Result<AiOrchestrator.PipelineRun> {
        val epId = currentEpisodeId.value
        if (epId.isNullOrBlank()) {
            throw AiOrchestrator.AiError.StageFailed("当前无进行中的编排", fromStage, "no running episode")
        }
        // ★F4 修复：续跑时读回真实剧本（episodes.script_json），不再用 RETRY_STUB 占位。
        // 读回失败/为空时退回最近一次 run 的剧本（仍是真实剧本，不是占位符）。
        val script = (try { readScript(epId) } catch (t: Throwable) {
            if (t is CancellationException) throw t
            ""
        }).ifBlank { lastScriptText }
        if (script.isBlank()) return Result.failure(AiOrchestrator.AiError.StageFailed("续跑剧本为空", fromStage, "empty script"))
        val projectId = epId.substringBeforeLast("_ep").ifBlank { epId }
        return runStages(script, activeTextModelIdProvider(),
            { _, _ -> }, fromStage, projectId, epId)
    }

    override suspend fun recoveryState(episodeId: String): PipelineStage5? = readCheckpoint(episodeId)

    private suspend fun runStages(
        scriptText: String,
        modelId: String,
        onAutoCreatedProject: (String, String) -> Unit,
        fromStage: PipelineStage5,
        existingProjectId: String = "",
        existingEpisodeId: String = "",
    ): Result<AiOrchestrator.PipelineRun> {
        _events.value = emptyList()
        val stages = PipelineStage5.entries.filter { it != PipelineStage5.ENQUEUE_RENDER_DONE }.toList()
        val startIdx = stages.indexOf(fromStage).coerceAtLeast(0)

        var projectId = existingProjectId
        var episodeId = existingEpisodeId
        if (episodeId.isNotBlank()) _currentEpisodeId.value = episodeId
        var lastStage = fromStage
        var assetCount = 0
        var shotCount = 0
        var assets: List<AiAsset> = emptyList()
        var shots: List<AiShot> = emptyList()

        try {
            // ★第二批 P0（A-G7）：每个阶段独立判断 shouldRun。旧实现把 ②③④⑤ 全嵌在 ① 的 if 内，
            // retryFrom(非①) 实际什么都不做；而测试 helper 用 Thread 吞断言，长期假通过。
            if (startIdx > 0) {
                emit(fromStage, 0, "续跑：从 ${fromStage.label} 继续", t0)
                if (projectId.isBlank() || episodeId.isBlank()) {
                    throwAi("续跑上下文缺失", fromStage, t0, "缺少 projectId/episodeId")
                }
            }

            // ---- ① extractAssets ----
            if (shouldRun(startIdx, PipelineStage5.EXTRACT_ASSETS)) {
                val st = t0
                lastStage = PipelineStage5.EXTRACT_ASSETS
                emit(PipelineStage5.EXTRACT_ASSETS, 0, "开始提取资产…", st)

                projectId = createProject("AI草稿-${formatDate()}")
                if (projectId.isBlank()) throwAi("自动建项目失败", PipelineStage5.EXTRACT_ASSETS, st, "empty id")
                episodeId = createEpisode(projectId, scriptText)
                if (episodeId.isBlank()) throwAi("自动建集失败", PipelineStage5.EXTRACT_ASSETS, st, "empty id")

                _currentEpisodeId.value = episodeId
                onAutoCreatedProject(projectId, episodeId)

                val extracted = runCatching { extractAssets(scriptText, modelId) }
                    .getOrElse { Result.failure<List<AiAsset>>(it) }
                assets = extracted.getOrElse {
                    throwAi("资产提取失败", PipelineStage5.EXTRACT_ASSETS, st,
                        it.message ?: it.javaClass.simpleName)
                }
                assetCount = assets.size
                lastAssets = assets
                emit(PipelineStage5.EXTRACT_ASSETS, assetCount, "已提取 ${assetCount} 张资产", st)
                persistAssets(episodeId, assets)
                writeCheckpoint(episodeId, PipelineStage5.EXTRACT_ASSETS, assetCount, 0, false, null)
            }

            // ---- ② generateImages ----
            if (shouldRun(startIdx, PipelineStage5.GENERATE_IMAGES)) {
                lastStage = PipelineStage5.GENERATE_IMAGES
                if (assets.isEmpty()) assets = readAssets(episodeId).ifEmpty { lastAssets }
                if (assets.isEmpty()) {
                    throwAi("续跑缺少已提取资产", PipelineStage5.GENERATE_IMAGES, t0, "assets empty")
                }
                assetCount = assets.size
                lastAssets = assets
                val stG = t0
                emit(PipelineStage5.GENERATE_IMAGES, 0, "开始生成图像…", stG)
                var imgOk = 0
                for ((i, a) in assets.withIndex()) {
                    // P0：生成回调返回 Result.failure（如网络/模型失败）→ 标红放行；
                    // 但**抛出**的异常（如 URL 回填读回不一致、存储闸门拦截）必须向上传播，
                    // 让 PipelineRun 进入失败契约，绝不把持久化失败当成可忽略的图像失败继续。
                    val imageResult = try {
                        generateImage(a)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (t: Throwable) {
                        throw t
                    }
                    if (imageResult.isSuccess) imgOk++
                    else {
                        val cause = imageResult.exceptionOrNull()?.message ?: "unknown image generation failure"
                        emit(PipelineStage5.GENERATE_IMAGES, i + 1, "图像${i + 1}生成失败：${cause.take(120)}", stG,
                            ProgressEvent.Level.WARN, cause)
                    }
                }
                emit(PipelineStage5.GENERATE_IMAGES, assets.size, "图像生成 ${imgOk}/${assets.size} 成功", stG)
                writeCheckpoint(episodeId, PipelineStage5.GENERATE_IMAGES, assetCount, 0, false, null)
            }

            // ---- ③ audit ----
            if (shouldRun(startIdx, PipelineStage5.AUDIT)) {
                lastStage = PipelineStage5.AUDIT
                if (assets.isEmpty()) assets = readAssets(episodeId).ifEmpty { lastAssets }
                if (assets.isEmpty()) {
                    throwAi("续跑缺少已审计资产", PipelineStage5.AUDIT, t0, "assets empty")
                }
                assetCount = assets.size
                lastAssets = assets
                val stA = t0
                emit(PipelineStage5.AUDIT, 0, "开始质量审计…", stA)
                var auditedOk = 0
                var failedCount = 0
                for ((i, a) in assets.withIndex()) {
                    var passed = false
                    var regenerated = 0
                    var lastReason = "未达质量线"
                    var lastDefects: List<String> = emptyList()
                    var lastErrorCode: String? = null
                    var lastScore: Double? = null
                    var nonRetryable = false
                    // ★第五批 P0（task-21）：审计三分类止损。旧实现不管原因一律重生成 2 次（3 倍付费），
                    // 无图/无 Key/取图解码失败/审计调用异常重生成也永远不会通过。现在只有画面类缺陷才重生成，
                    // 且每次把上一轮 defects 反馈进 prompt；不可重试原因立即停止。
                    while (true) {
                        val r = auditAsset(a)
                        // 结构化取消必须穿透：factory 的 runCatching 可能把 CancellationException 包成 failure
                        val auditFailure = r.exceptionOrNull()
                        if (auditFailure is CancellationException) throw auditFailure
                        val res = r.getOrNull()
                        if (r.isSuccess && res != null && res.passed) {
                            passed = true
                            break
                        }
                        lastReason = r.exceptionOrNull()?.message ?: res?.reason ?: "未达质量线"
                        lastDefects = res?.defects ?: emptyList()
                        lastErrorCode = res?.errorCode
                        lastScore = res?.score
                        val verdict = res?.verdict ?: AuditVerdict.NON_RETRYABLE
                        if (r.isFailure || verdict == AuditVerdict.NON_RETRYABLE) {
                            nonRetryable = true
                            break
                        }
                        if (regenerated >= MAX_AUDIT_REGENERATIONS) break
                        val feedbackPrompt = withAuditFeedback(a.prompt, lastDefects)
                        val regen = try {
                            generateImage(a.copy(prompt = feedbackPrompt))
                        } catch (e: CancellationException) {
                            throw e
                        } catch (t: Throwable) {
                            Result.failure<String>(t)
                        }
                        val regenFailure = regen.exceptionOrNull()
                        if (regenFailure is CancellationException) throw regenFailure
                        if (regen.isFailure) {
                            emit(PipelineStage5.AUDIT, i + 1,
                                "资产${i + 1}审计未过且重生成失败：${(regen.exceptionOrNull()?.message ?: "unknown").take(80)}",
                                stA, ProgressEvent.Level.WARN)
                            break
                        }
                        regenerated++
                        emit(PipelineStage5.AUDIT, i + 1,
                            "资产${i + 1}审计未过（${lastReason.take(60)}），已按质检反馈重新生成第 $regenerated 次",
                            stA, ProgressEvent.Level.WARN)
                    }
                    if (passed) {
                        auditedOk++
                    } else if (nonRetryable) {
                        // 不可重试：不重生成、不标 rejected（无 Key/取图失败可能只是环境问题），
                        // factory 侧已落 audit_state=pending + reject_reason，环境恢复后可重审。
                        failedCount++
                        emit(PipelineStage5.AUDIT, i + 1,
                            "资产${i + 1}不可重试（${lastReason.take(80)}），已停止重生成，需到资产页处理",
                            stA, ProgressEvent.Level.WARN, lastErrorCode ?: lastReason)
                    } else {
                        // 画面类缺陷重试用尽：rejected + 原因 + defects 落库，禁止进入渲染参考。
                        failedCount++
                        try {
                            onAssetAuditRejected(a.assetId, lastReason, defectsJson(lastDefects), lastScore)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (t: Throwable) {
                            emit(PipelineStage5.AUDIT, i + 1,
                                "资产${i + 1}拒绝结论落库失败：${(t.message ?: t.javaClass.simpleName).take(80)}",
                                stA, ProgressEvent.Level.WARN)
                        }
                        emit(PipelineStage5.AUDIT, i + 1,
                            "已自动重试 $MAX_AUDIT_REGENERATIONS 次仍未过审，资产${i + 1}被拒绝，需到资产页重新生成（${lastReason.take(80)}）",
                            stA, ProgressEvent.Level.WARN)
                    }
                }
                emit(PipelineStage5.AUDIT, assets.size,
                    "审计通过 $auditedOk/${assets.size}，未过 $failedCount（不可重试/重试用尽，需到资产页处理）", stA)
                writeCheckpoint(episodeId, PipelineStage5.AUDIT, assetCount, 0, false, null)
            }

            // ---- ④ generateStoryboard ----
            if (shouldRun(startIdx, PipelineStage5.GENERATE_STORYBOARD)) {
                lastStage = PipelineStage5.GENERATE_STORYBOARD
                if (assetCount == 0) {
                    assets = readAssets(episodeId).ifEmpty { lastAssets }
                    assetCount = assets.size
                }
                val stS = t0
                emit(PipelineStage5.GENERATE_STORYBOARD, 0, "开始生成分镜…", stS)
                val shotsResult = runCatching { generateShots(projectId, scriptText, modelId) }
                    .getOrElse { Result.failure<List<AiShot>>(it) }
                shots = shotsResult.getOrElse {
                    throwAi("分镜生成失败", PipelineStage5.GENERATE_STORYBOARD, stS,
                        it.message ?: it.javaClass.simpleName)
                }
                shotCount = shots.size
                lastShots = shots
                emit(PipelineStage5.GENERATE_STORYBOARD, shotCount, "已生成 ${shotCount} 镜", stS)
                persistShots(episodeId, shots)
                // ★第二批 P0：分镜落库后自动合成逐镜首帧，并让尾帧接力下一镜首帧。
                // 失败只 WARN 不阻断：无参考图/图像服务不可用时渲染侧仍可回落原始首帧逻辑。
                val kfCount = try {
                    generateKeyframes(episodeId, shots)
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    emit(PipelineStage5.GENERATE_STORYBOARD, shotCount,
                        "关键帧阶段异常（已跳过）：${(t.message ?: t.javaClass.simpleName).take(120)}", stS,
                        ProgressEvent.Level.WARN, t.message)
                    0
                }
                if (kfCount > 0) {
                    emit(PipelineStage5.GENERATE_STORYBOARD, shotCount,
                        "已自动合成 $kfCount/$shotCount 镜首帧（尾帧接力下一镜）", stS)
                } else {
                    emit(PipelineStage5.GENERATE_STORYBOARD, shotCount,
                        "关键帧未生成（无可用参考图或图像服务不可用），渲染将回落首帧逻辑", stS,
                        ProgressEvent.Level.WARN)
                }
                writeCheckpoint(episodeId, PipelineStage5.GENERATE_STORYBOARD, assetCount, shotCount, false, null)
            }

            // ---- ⑤ enqueueRender ----
            if (shouldRun(startIdx, PipelineStage5.ENQUEUE_RENDER)) {
                lastStage = PipelineStage5.ENQUEUE_RENDER
                if (shots.isEmpty()) shots = readShots(episodeId).ifEmpty { lastShots }
                if (shots.isEmpty()) {
                    throwAi("续跑缺少分镜", PipelineStage5.ENQUEUE_RENDER, t0, "shots empty")
                }
                shotCount = shots.size
                lastShots = shots
                val stE = t0
                emit(PipelineStage5.ENQUEUE_RENDER, 0, "开始入队渲染…", stE)
                val enq = enqueueRender(episodeId, shots)
                if (!enq.isSuccess) {
                    throwAi("入队渲染失败", PipelineStage5.ENQUEUE_RENDER, stE, "enqueue failed")
                }
                val queued = enq.getOrNull() ?: 0
                emit(PipelineStage5.ENQUEUE_RENDER, queued, "已入队 $queued 镜", stE)
                emit(PipelineStage5.ENQUEUE_RENDER_DONE, 0, "五阶段全部完成 ✓", stE)
                writeCheckpoint(episodeId, PipelineStage5.ENQUEUE_RENDER, assetCount, shotCount, true, null)
                lastStage = PipelineStage5.ENQUEUE_RENDER_DONE
            }

            lastAssets = assets
            lastShots = shots
            return Result.success(AiOrchestrator.PipelineRun(
                projectId = projectId, episodeId = episodeId,
                success = true, lastStage = lastStage, errors = emptyList()))
        } catch (e: AiOrchestrator.AiError) {
            return Result.success(AiOrchestrator.PipelineRun(
                projectId = projectId.ifBlank { "unknown" },
                episodeId = episodeId.ifBlank { "unknown" },
                success = false, lastStage = lastStage, errors = listOf(e)))
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            val cause = t.message ?: t.javaClass.simpleName
            // 错误文案带上根因，便于 UI 直接显示与单测断言（旧实现只报阶段名，真实原因丢失）
            val err = AiOrchestrator.AiError.StageFailed(
                "流水线异常：${lastStage.label}：$cause", lastStage, cause)
            emit(lastStage, 0, err.msg, t0, ProgressEvent.Level.ERROR, cause)
            return Result.success(AiOrchestrator.PipelineRun(
                projectId = projectId.ifBlank { "unknown" },
                episodeId = episodeId.ifBlank { "unknown" },
                success = false, lastStage = lastStage, errors = listOf(err)))
        }
    }

    /** task-21：把上一轮质检缺陷反馈进重生成 prompt（旧实现重生成沿用同一 prompt，等于盲重试）。 */
    private fun withAuditFeedback(original: String, defects: List<String>): String {
        val summary = if (defects.isNotEmpty()) defects.joinToString("、").take(200)
                      else "画面质量未达标（构图/角色面部/风格一致性）"
        return original + "\n上一版质检未过：" + summary + "；请避免并改进构图/服装/光线"
    }

    /** task-21：defects 列表 -> JSON；模型输出的引号替换为空格，保证落库 JSON 合法。 */
    private fun defectsJson(defects: List<String>): String? {
        if (defects.isEmpty()) return null
        return defects.joinToString(",", prefix = "[", postfix = "]") { d ->
            // 转义 JSON 字符串必需字符，避免模型输出的引号/换行/反斜杠破坏落库 JSON
            val safe = d.replace("\\", " ").replace("\"", " ").replace("\n", " ").replace("\r", " ")
            "\"" + safe + "\""
        }
    }

    private fun throwAi(msg: String, stage: PipelineStage5, st0: Long, cause: String): Nothing {
        val err = AiOrchestrator.AiError.StageFailed(msg, stage, cause)
        emit(stage, 0, msg, st0, ProgressEvent.Level.ERROR, msg)
        throw err
    }

    private fun shouldRun(startIdx: Int, stage: PipelineStage5): Boolean {
        val all = PipelineStage5.entries.filter { it != PipelineStage5.ENQUEUE_RENDER_DONE }.toList()
        return all.indexOf(stage) >= startIdx
    }

    private fun formatDate(): String {
        val cal = java.util.Calendar.getInstance()
        return java.lang.String.format("%02d%02d-%02d%02d",
            cal.get(java.util.Calendar.MONTH) + 1,
            cal.get(java.util.Calendar.DAY_OF_MONTH),
            cal.get(java.util.Calendar.HOUR_OF_DAY),
            cal.get(java.util.Calendar.MINUTE))
    }

    // ---------- 依赖类型 ----------
    data class AiAsset(val assetId: String, val kind: String, val name: String, val prompt: String)
    /** 第十五轮：assetIds 为分镜引用的真实资产ID，persistShots 写入 first_asset_ids */
    data class AiShot(
        val shotNo: Int,
        val action: String,
        val dialogue: String? = null,
        val assetIds: List<String> = emptyList(),
        // ★质量修复 B：以下字段此前在 factory 映射时被整体丢弃，导致落库后视频端拿不到镜头语言/场景/时长
        val narration: String? = null,
        val visualPrompt: String? = null,
        val sceneContext: String? = null,
        val carryOver: String? = null,
        val beatRef: String? = null,
        val durationSeconds: Double = 6.0,
        // ★第三批：场次结构与轴线/视线（追加末尾带默认值，兼容既有位置参数构造）
        val sceneNo: Int = 0,
        val sceneTitle: String? = null,
        val screenSide: String? = null,
        val eyeline: String? = null,
    )
    /** task-21：审计结论三分类；verdict 默认按 passed 推导，兼容既有「只传 passed/score/reason」的构造。 */
    enum class AuditVerdict { PASSED, RETRYABLE_CONTENT_FAILURE, NON_RETRYABLE }

    data class AuditResult(
        val passed: Boolean,
        val score: Double? = null,
        val reason: String? = null,
        val verdict: AuditVerdict = if (passed) AuditVerdict.PASSED else AuditVerdict.RETRYABLE_CONTENT_FAILURE,
        val defects: List<String> = emptyList(),
        val errorCode: String? = null,
    )
}