package com.dramafactory.app

import com.dramafactory.app.data.AssetEntity
import com.dramafactory.app.data.DramaDatabase
import com.dramafactory.app.data.EpisodeEntity
import com.dramafactory.app.data.PersistenceActionExecutor
import com.dramafactory.app.data.PersistenceWriteException
import com.dramafactory.app.data.ProjectEntity
import com.dramafactory.app.data.ShotEntity
import com.dramafactory.app.storage.StorageUnavailableException
import com.dramafactory.app.ui.AssetCatalog
import com.dramafactory.core.orchestrate.DefaultAiOrchestrator
import com.dramafactory.core.orchestrate.PipelineStage5

/**
 * TD-4：从 AppGraph.init 抽出的 AI 全托管编排器装配（原 AppGraph.init 内 553–742 行的大段 lambda 构造）。
 * 以 `AppGraph` 扩展函数形式存在：可访问 AppGraph 的 internal/public 成员（agnes / dao / image /
 * currentEraKey / textProviderFor / enrichAssetPrompt / agnesKeyReady / storageGuard 等）。
 *
 * P0 持久化可靠性：所有落库 lambda（建项目/建集/资产/分镜/URL/checkpoint）一律先过存储闸门，
 * 写后按精确 ID 读回并比对关键字段，任一步失败抛 [PersistenceWriteException] 使
 * [DefaultAiOrchestrator] 的 PipelineRun 进入失败契约（success=false），禁止半成功报告。
 */
/** ★第二批 P0（B-G5）：审计早退（无图/无 Key/取图解码失败/异常）也必须把结论落库，
 *  否则上一轮可能残留的 approved 会继续让脏图进渲染参考。 */
private suspend fun AppGraph.markAssetAuditPending(assetId: String, reason: String) {
    runCatching {
        dao.setAssetQuality(
            assetId = assetId, qualityScore = null, auditState = "pending",
            defectsJson = null, rejectReason = reason, g1ErrorCode = null,
            faceRatio = null, poseRole = null, updatedAt = System.currentTimeMillis(),
        )
    }
}

internal fun AppGraph.buildAiOrchestrator(): DefaultAiOrchestrator =
    DefaultAiOrchestrator(
        activeTextModelIdProvider = { textModelRouter.activeTextModelId() },
        createProject = { name ->
            val gate = requireStorageReady()
            if (gate != null) throw StorageUnavailableException(gate.code, gate.userMessage, gate.diagnosticId)
            PersistenceActionExecutor.writeProject(dao, storageGuard, name, "pipeline.createProject")
                .entityIds.first()
        },
        createEpisode = { projectId, scriptText ->
            val gate = requireStorageReady()
            if (gate != null) throw StorageUnavailableException(gate.code, gate.userMessage, gate.diagnosticId)
            val epId = "${projectId}_ep1"
            // ★F3 修复：按剧本自动推断时代红线（LLM 优先，规则兜底），替换原写死 "han"。
            // 第十三轮 EraDetector 与人工模式（ViewModels:370-374）同策略。
            val llmReady = agnesKeyReady()
            currentEraKey = runCatching {
                com.dramafactory.core.quality.EraDetector.detect(scriptText, llmReady) { req ->
                    agnes.chat(req)
                }
            }.getOrElse { com.dramafactory.core.quality.EraDetector.Detection("neutral", "", false) }.eraKey
            val flags = DramaDatabase.Companion.AiStageFlags
            val stageFlags =
                flags.put(flags.putBool("", flags.AI_MANAGED, true),
                    flags.PROJECT_ID, projectId)
            // 剧本写入 + 按 episode_id 读回 + script_json 比对；失败即抛
            PersistenceActionExecutor.writeEpisode(
                dao, storageGuard,
                EpisodeEntity(
                    episode_id = epId, project_id = projectId, ep_no = 1,
                    script_json = scriptText, stage_flags = stageFlags,
                ),
                actionId = "pipeline.createEpisode",
                expectedScript = scriptText,
            )
            epId
        },
        checkModel = { modelId ->
            if (modelId.isBlank()) {
                dao.verifiedConfig("text")?.let { Result.success(Unit) }
                    ?: Result.failure(com.dramafactory.core.model.ProviderError.AuthError("未验证文本模型"))
            } else {
                // TD-5：checkModel 本身是 suspend λ，validate 亦是 suspend；
                // 去掉 runBlocking，改为在编排器协程上下文直接 await，消除网络阻塞（原写法会卡线程）。
                runCatching {
                    textModelRouter.validate(modelId).getOrThrow()
                }
            }
        },
        extractAssets = { text, _ ->
            runCatching {
                // 文字模型走用户自选(DeepSeek等)，key 多候选兜底（修 text-agnes 读不到）
                val tp = textProviderFor()
                val r = com.dramafactory.core.quality.LlmAssetExtractor.extract(text) { req ->
                    tp.chat(req)
                }
                r.assets.map { a ->
                    DefaultAiOrchestrator.AiAsset(
                        assetId = "a_${System.nanoTime()}",
                        kind = a.kind, name = a.name, prompt = a.desc,
                    )
                }
            }
        },
        generateImage = { asset ->
            // 存储闸门：被阻断直接抛（fatal），不触达 Provider
            val gate = requireStorageReady()
            if (gate != null) throw StorageUnavailableException(gate.code, gate.userMessage, gate.diagnosticId)
            runCatching {
                if (asset.assetId.isBlank() || asset.kind.isBlank() || asset.prompt.isBlank()) {
                    throw IllegalArgumentException("资产字段不完整：id=${asset.assetId}, kind=${asset.kind}")
                }
                val preset = com.dramafactory.core.quality.EraDetector.presetFor(currentEraKey)
                // TD-5：generateImage 在编排器协程上下文中执行，避免阻塞主线程。
                // ★F3：按剧本推断时代预设，不写死具体时代。
                com.dramafactory.app.ui.AssetImageGenerator.generate(
                    provider = image, kind = asset.kind,
                    basePrompt = enrichAssetPrompt(asset.kind, asset.prompt), preset = preset)
            }.fold(
                onSuccess = { url ->
                    // P0：URL 回填必须按 asset_id 读回比对；失败抛 PersistenceWriteException（冒泡出 lambda，
                    // 由编排器 GENERATE_IMAGES 循环向上传播，使 PipelineRun 进入失败契约），绝不返回"已生成"假成功。
                    PersistenceActionExecutor.setAssetRemoteUrlVerified(dao, storageGuard, asset.assetId, url, asset.assetId)
                    Result.success(url)
                },
                onFailure = { Result.failure(it) },
            )
        },
        auditAsset = { asset ->
            // ★F2 修复：真实质量审计——调用 AssetAuditor.audit（G1 文件级硬校验 + G2 多模态打分），
            // 替换原直接返回 passed=true 的「假通过」（原实现关闭了 PRD F03 两层闸门）。
            // ★第二批 P0 fail-closed：未生成图像/未配置 Key/取图解码失败 = 审核未通过（passed=false），
            // ★第五批 task-21：标记 NON_RETRYABLE，由编排器立即止损（为无图/无 Key 重生成不会通过）。
            // 注意：λ 返回类型必须是 Result<AuditResult>，故整体包在 runCatching 内；
            // 异常会变为 Result.failure（NON_RETRYABLE），由编排器 AUDIT 阶段立即止损、不再盲重生成。
            runCatching {
                val remoteUrl = runCatching { dao.assetRemoteUrl(asset.assetId) }.getOrNull()
                // ★第二批 P0 fail-closed："没法审" 不再等于 "通过"。
                // 旧实现无图/无 Key/取图失败/解码失败一律 passed=true，等于关掉质量闸；
                // 现在一律 passed=false：前四类（无图/无Key/取图/解码）标 NON_RETRYABLE 立即止损，
                // 只有画面类缺陷（QualityEngine REJECTED）才走「重生成→再审」，重试用尽落 rejected。
                if (remoteUrl.isNullOrBlank()) {
                    markAssetAuditPending(asset.assetId, "audit_no_image")
                    return@runCatching DefaultAiOrchestrator.AuditResult(
                        passed = false,
                        reason = "audit_no_image",
                        verdict = DefaultAiOrchestrator.AuditVerdict.NON_RETRYABLE,
                        errorCode = "audit_no_image",
                    )
                }
                if (!agnesKeyReady()) {
                    markAssetAuditPending(asset.assetId, "audit_no_key")
                    return@runCatching DefaultAiOrchestrator.AuditResult(
                        passed = false,
                        reason = "audit_no_key",
                        verdict = DefaultAiOrchestrator.AuditVerdict.NON_RETRYABLE,
                        errorCode = "audit_no_key",
                    )
                }
                val bytes = MediaHelpers.fetchImageBytes(remoteUrl)
                    ?: return@runCatching DefaultAiOrchestrator.AuditResult(
                        passed = false,
                        reason = "audit_image_fetch_failed",
                        verdict = DefaultAiOrchestrator.AuditVerdict.NON_RETRYABLE,
                        errorCode = "audit_image_fetch_failed",
                    ).also {
                        markAssetAuditPending(asset.assetId, "audit_image_fetch_failed")
                    }
                val dataUri = MediaHelpers.downscaleToDataUri(bytes)
                    ?: return@runCatching DefaultAiOrchestrator.AuditResult(
                        passed = false,
                        reason = "audit_image_decode_failed",
                        verdict = DefaultAiOrchestrator.AuditVerdict.NON_RETRYABLE,
                        errorCode = "audit_image_decode_failed",
                    ).also {
                        markAssetAuditPending(asset.assetId, "audit_image_decode_failed")
                    }
                val describer = com.dramafactory.core.quality.AssetAuditor.agnesDescriber(agnes, "")
                val engine = com.dramafactory.app.ui.QualityEngine()
                val outcome = engine.auditAsset(
                    imageBytes = bytes, imageDataUri = dataUri,
                    description = asset.prompt, assetType = asset.kind,
                    describer = describer,
                )
                // ★质量修复 G：审计结论必须落库（旧实现只回传不写 DB）。
                // 否则渲染侧只看到「有 URL」，被 QC 拒绝的图照样被逐镜当参考图（audit_state 永远 pending）。
                runCatching {
                    dao.setAssetQuality(
                        assetId = asset.assetId,
                        qualityScore = outcome.qualityScore,
                        auditState = outcome.auditState.name.lowercase(),
                        defectsJson = outcome.defectsJson(),
                        rejectReason = outcome.rejectReason,
                        g1ErrorCode = outcome.g1ErrorCode,
                        faceRatio = outcome.faceRatio,
                        poseRole = outcome.poseRole,
                        updatedAt = System.currentTimeMillis(),
                    )
                }
                DefaultAiOrchestrator.AuditResult(
                    passed = outcome.auditState == com.dramafactory.core.model.AuditState.APPROVED,
                    score = outcome.qualityScore,
                    reason = outcome.rejectReason,
                    // ★第五批 task-21：质量引擎的未通过属于画面类缺陷（构图/脸崩/风格不符），可重生成；
                    // 无图/无 Key/取图解码失败走上面的 NON_RETRYABLE 早退，不再为不可修复原因 3 倍付费。
                    verdict = if (outcome.auditState == com.dramafactory.core.model.AuditState.APPROVED)
                        DefaultAiOrchestrator.AuditVerdict.PASSED
                    else DefaultAiOrchestrator.AuditVerdict.RETRYABLE_CONTENT_FAILURE,
                    defects = outcome.defects,
                    errorCode = outcome.g1ErrorCode,
                )
            }.onFailure { markAssetAuditPending(asset.assetId, it.message ?: "audit_exception") }
        },
        onAssetAuditRejected = { assetId, rejectReason, defectsJson, qualityScore ->
            // ★第五批 P0（task-21）：画面类缺陷重试用尽 → audit_state=rejected + 原因 + defects 落库，
            // 渲染侧据此硬拦，不再把未过审的图当参考图/关键帧。
            try {
                dao.setAssetQuality(
                    assetId = assetId, qualityScore = qualityScore, auditState = "rejected",
                    defectsJson = defectsJson, rejectReason = rejectReason, g1ErrorCode = null,
                    faceRatio = null, poseRole = null, updatedAt = System.currentTimeMillis(),
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (t: Throwable) {
                // 拒绝结论落库失败不阻断流水线（编排器侧已 emit WARN）
            }
        },
        generateShots = { pid, script, _ ->
            runCatching {
                // 文字模型走用户自选(DeepSeek等)，key 多候选兜底
                val tp = textProviderFor()
                // 第十五轮：从 DB 拉本项目已抽取/已生成的资产注入 LLM，让分镜用 asset_id 引用
                val assets = runCatching { dao.assetsAllOf(pid) }.getOrDefault(emptyList())
                // v1.7.17：与 StoryboardViewModel 共用同一套目录构造规则
                // ★第二批 P0：AI 流水线的分镜目录只用已获批资产（审计阶段先于分镜）
                // ★v1.10：审计不可用 / 旧资产审核态为 pending 时 approved 目录会为空，分镜将失去全部资产绑定；
                // 回退到「未拒绝」目录（拒绝态仍排除），保证分镜有资产可绑。
                val approvedCatalog = com.dramafactory.app.ui.AssetCatalog.build(assets, onlyApproved = true)
                val catalog = approvedCatalog.ifEmpty {
                    com.dramafactory.app.ui.AssetCatalog.build(assets, onlyApproved = false)
                }
                val r = com.dramafactory.core.quality.AiStoryboardDirector.generate(
                    script, chat = { req -> tp.chat(req) }, assets = catalog)
                r.shots.map { s ->
                    // ★质量修复 B：完整映射导演字段（旧实现只传 4 个参数，visual/scene/carry/duration 全丢）
                    DefaultAiOrchestrator.AiShot(
                        shotNo = s.shotNo,
                        action = s.action ?: "",
                        dialogue = s.dialogue,
                        assetIds = s.assetIds,
                        narration = s.narration,
                        visualPrompt = s.visualPrompt,
                        sceneContext = s.sceneContext,
                        carryOver = s.carryOver,
                        beatRef = s.beatRef,
                        durationSeconds = s.durationSeconds,
                        sceneNo = s.sceneNo,
                        sceneTitle = s.sceneTitle,
                        screenSide = s.screenSide,
                        eyeline = s.eyeline,
                    )
                }
            }
        },
        // ★第二批 P0：自动关键帧阶段——分镜落库后逐镜合成首帧并做尾帧接力。
        // 失败/无参考图时返回 0，编排器只发 WARN，渲染侧回落原始首帧逻辑。
        generateKeyframes = { episodeId, shots ->
            try {
                com.dramafactory.app.ui.RenderRuntime.synthesizeEpisodeKeyframes(episodeId, shots.size)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Throwable) {
                0
            }
        },
        enqueueRender = { episodeId, shots ->
            val metas = shots.map {
                com.dramafactory.core.model.ShotMeta(
                    shotId = "${episodeId}_shot${it.shotNo}",
                    episodeId = episodeId,
                    prompt = it.action,
                )
            }
            val queue = com.dramafactory.app.ui.RenderRuntime.queueFor(episodeId)
            // TD-5：enqueueRender 本身是 suspend λ，去掉 runBlocking，在协程上下文直接 await
            runCatching { queue.enqueueEpisode(episodeId, metas) }
                .map { metas.size }
        },
        persistAssets = { episodeId, assets ->
            val projectId = episodeId.substringBeforeLast("_ep")
            val gate = requireStorageReady()
            if (gate != null) throw StorageUnavailableException(gate.code, gate.userMessage, gate.diagnosticId)
            val entities = assets.map { a ->
                AssetEntity(
                    asset_id = a.assetId,
                    project_id = projectId,
                    kind = a.kind,
                    prompt = a.name + "：" + a.prompt,
                    updated_at = System.currentTimeMillis(),
                )
            }
            // 全有或失败：第 N 项失败立即抛、不继续第 N+1 项，禁止半成功报告
            PersistenceActionExecutor.writeAssetsBatch(dao, storageGuard, projectId, entities, "pipeline.persistAssets")
        },
        persistShots = { episodeId, shots ->
            val projectId = episodeId.substringBeforeLast("_ep")
            val gate = requireStorageReady()
            if (gate != null) throw StorageUnavailableException(gate.code, gate.userMessage, gate.diagnosticId)
            val entities = shots.map { s ->
                ShotEntity(
                    shot_id = "${episodeId}_shot${s.shotNo}",
                    episode_id = episodeId,
                    project_id = projectId,
                    shot_no = s.shotNo,
                    action = s.action,
                    dialogue = s.dialogue,
                    // ★质量修复 B：导演字段落库（旧实现丢弃 visual/scene/carry/duration → 视频端只剩台词+action）
                    narration = s.narration,
                    scene_context = s.sceneContext,
                    visual_prompt = s.visualPrompt,
                    beat_ref = s.beatRef,
                    carry_over = s.carryOver,
                    duration_seconds = s.durationSeconds,
                    scene_no = s.sceneNo,
                    scene_title = s.sceneTitle,
                    screen_side = s.screenSide,
                    eyeline = s.eyeline,
                    first_asset_ids = AssetCatalog.encodeRefIds(s.assetIds),
                    last_asset_ids = "[]",
                )
            }
            // 写后按 episode_id 读回校验数量与集合；失败即抛，流水线进入失败契约
            PersistenceActionExecutor.writeShotsBatch(dao, storageGuard, episodeId, entities, "pipeline.persistShots")
        },
        writeCheckpoint = { episodeId, stage, assetCount, shotCount, renderEnqueued, failed ->
            val gate = requireStorageReady()
            if (gate != null) throw StorageUnavailableException(gate.code, gate.userMessage, gate.diagnosticId)
            val flags = DramaDatabase.Companion.AiStageFlags
            val cur = dao.episode(episodeId)
            var f = cur?.stage_flags ?: "{}"
            f = flags.put(f, flags.LAST_SUCCESS_STAGE, stage.name)
            f = flags.putInt(f, flags.ASSET_COUNT, assetCount)
            f = flags.putBool(f, flags.RENDER_ENQUEUED, renderEnqueued)
            failed?.let { f = flags.put(f, flags.FAILED_STAGE, it.name) }
            val target = cur?.copy(stage_flags = f)
                ?: throw PersistenceWriteException("pipeline.writeCheckpoint", "checkpoint", listOf(episodeId), "checkpoint 缺少真实剧集记录")
            dao.upsertEpisode(target)
            // P0：checkpoint 写后读回比对
            val back = dao.episode(episodeId)
            if (back?.stage_flags != f) {
                throw PersistenceWriteException("pipeline.writeCheckpoint", "checkpoint", listOf(episodeId),
                    "checkpoint 写入后读回不一致")
            }
        },
        readCheckpoint = { episodeId ->
            val flags = DramaDatabase.Companion.AiStageFlags
            val stageName = flags.getString(
                dao.episode(episodeId)?.stage_flags,
                flags.LAST_SUCCESS_STAGE)
            if (stageName != null) runCatching { PipelineStage5.valueOf(stageName) }.getOrNull()
            else null
        },
        // ★F4 修复：断点续跑时读回真实剧本（episodes.script_json），替换 DefaultAiOrchestrator 内的 "RETRY_STUB" 占位
        readScript = { episodeId ->
        val episode = dao.episode(episodeId)
            ?: throw PersistenceWriteException("pipeline.readScript", "readScript", listOf(episodeId), "剧本不存在")
        episode.script_json ?: ""
        },
    )