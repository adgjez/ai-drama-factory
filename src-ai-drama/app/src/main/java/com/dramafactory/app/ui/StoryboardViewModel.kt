package com.dramafactory.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dramafactory.app.AppGraph
import com.dramafactory.app.data.PersistenceActionExecutor
import com.dramafactory.app.data.ShotEntity
import com.dramafactory.core.quality.ShotFieldRewriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 第十轮：分镜页 ViewModel——AI编剧+导演一键生成分镜。
 *
 * 流程：读本集剧本 → AiStoryboardDirector.generate（拆镜+视觉指令）→
 * 台词逐字粗校验 → 落 shots 表（visual_prompt/duration_seconds）→ UI 展示。
 * 重新生成会先清空本集旧镜（render_tasks 历史保留对账）。
 */
class StoryboardViewModel(private val episodeId: String) : ViewModel() {

    data class UiState(
        val shots: List<com.dramafactory.app.data.ShotEntity> = emptyList(),
        val generating: Boolean = false,
        val message: String? = null,
        /** 第十三轮：shotId → 已出产视频本地路径（COMPLETED且有文件） */
        val videoUris: Map<String, String> = emptyMap(),
        /** v1.7.17：assetId → 显示名，供分镜卡片展示「本镜引用了哪些资产」 */
        val assetNames: Map<String, String> = emptyMap(),
        /** v1.9.2：assetId → 缩略图 URI（remote_url 或本地 image_uri），分镜详情弹窗展示引用资产 */
        val assetThumbs: Map<String, String> = emptyMap(),
        /** 第四批：正在 AI 重写的字段，格式 "shotId|field"（编辑框按钮据此显示 loading） */
        val rewritingField: String? = null,
        /** 第四批：AI 重写成功结果，UI 回填输入框后调 consumeRewriteResult() 清空 */
        val rewriteResult: ShotFieldRewriteResult? = null,
        /** 第五批：渲染入口预检结果（非空时 UI 弹确认；预检只读库、不调用付费接口） */
        val renderPrompt: RenderPrompt? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> get() = _state

    /** 第三批：分镜写入串行锁——updateShot 读整行再 upsert，避免与场次局部 UPDATE 并发互相覆盖。 */
    private val writeLock = Mutex()

    init { refresh() }

    fun refresh() = viewModelScope.launch {
        val rows = runCatching { withContext(Dispatchers.IO) { AppGraph.dao.shotsOf(episodeId) } }
            .getOrDefault(emptyList())
        // 第十三轮：拉已出产视频（COMPLETED 且本地文件存在）
        val vids = runCatching {
            withContext(Dispatchers.IO) { AppGraph.dao.renderStatesOf(episodeId) }
        }.getOrDefault(emptyList())
            .filter { it.state == "COMPLETED" && !it.localFileUri.isNullOrBlank() }
            .associate { it.shotId to it.localFileUri!! }
        // v1.7.17：构建 assetId → 显示名，让分镜页能直接看出每镜引用了哪些资产
        // v1.9.2：同时构建 assetId → 缩略图 URI，分镜详情弹窗展示引用资产长相
        val assetRows = runCatching {
            withContext(Dispatchers.IO) {
                val pid = episodeId.substringBeforeLast("_ep")
                AppGraph.dao.assetsAllOf(pid)
            }
        }.getOrDefault(emptyList())
        val names = assetRows.associate { it.asset_id to AssetCatalog.displayName(it.prompt) }
        val thumbs = assetRows.mapNotNull { e ->
            val uri = e.remote_url ?: e.image_uri
            if (uri.isNullOrBlank()) null else e.asset_id to uri
        }.toMap()
        _state.value = _state.value.copy(shots = rows, videoUris = vids,
            assetNames = names, assetThumbs = thumbs)
    }

    /** AI 一键生成分镜（LLM 不可用时提示） */
    fun generateWithAi() = viewModelScope.launch {
        _state.value = _state.value.copy(generating = true, message = "AI 编剧正在拆解剧本…")
        val script = runCatching {
            withContext(Dispatchers.IO) { AppGraph.dao.episode(episodeId)?.script_json }
        }.getOrNull()
        if (script.isNullOrBlank()) {
            _state.value = _state.value.copy(generating = false,
                message = "本集没有剧本文本。请回项目导入剧本，或到资产页粘贴。")
            return@launch
        }
        // LLM 前置判断：引擎未就绪/key 未配置不发起网络
        // v1.9.13：改用 hasVideoKey()，覆盖 custom-video/custom-image/agnes-* 多槽位，与运行时一致。
        val llmReady = AppGraph.isInitialized && AppGraph.hasVideoKey()
        if (!llmReady) {
            _state.value = _state.value.copy(generating = false,
                message = "未配置 API Key，无法使用AI生成分镜。请到「设置」页配置后重试。")
            return@launch
        }

        // 第十五轮：拉项目资产注入 LLM 提示词，让分镜用 asset_id 引用真资产。
        // v1.7.17：改走 AssetCatalog.build —— 只给母卡、只给已有图的卡，
        // 否则 LLM 会引用 参考图子卡（侧脸/怒容）或未生图的空卡，渲染时静默失锁。
        val projectId = episodeId.substringBeforeLast("_ep")
        val assets = runCatching {
            withContext(Dispatchers.IO) { AppGraph.dao.assetsAllOf(projectId) }
        }.getOrDefault(emptyList())
        val catalog = AssetCatalog.build(assets)
        // v1.9.17：诊断用统计——区分「项目没资产卡」/「有卡但没生图」/「LLM 幻觉 id」三种根因
        val motherCards = assets.filter { it.parent_id.isNullOrBlank() && it.pose_role.isNullOrBlank() }
        val mothersWithImage = motherCards.count {
            !it.remote_url.isNullOrBlank() || !it.image_uri.isNullOrBlank()
        }
        val existingShots = runCatching {
            withContext(Dispatchers.IO) { AppGraph.dao.shotsOf(episodeId) }
        }.getOrDefault(emptyList())
        val existingByNo = existingShots.associateBy { it.shot_no }
        val result = runCatching {
            com.dramafactory.core.quality.AiStoryboardDirector.generate(
                script, chat = { req -> AppGraph.text.chat(req) }, assets = catalog)
        }.getOrElse {
            _state.value = _state.value.copy(generating = false,
                message = "AI 生成分镜失败：${it.message ?: it.javaClass.simpleName}")
            return@launch
        }
        if (result.shots.isEmpty()) {
            _state.value = _state.value.copy(generating = false, message = "AI未能从剧本拆出镜头，请检查剧本内容后重试")
            return@launch
        }

        val generatedByNo = result.shots.associateBy { it.shotNo }
        val shotsToPersist = (result.shots.map { it.shotNo } + existingShots.filter { it.sb_check == "pass" }.map { it.shot_no })
            .distinct().sorted().mapNotNull { no ->
                existingByNo[no]?.takeIf { it.sb_check == "pass" } ?: generatedByNo[no]?.let { s ->
                    com.dramafactory.app.data.ShotEntity(
                        shot_id = "${episodeId}_shot${s.shotNo}",
                        episode_id = episodeId, project_id = projectId, shot_no = s.shotNo,
                        dialogue = s.dialogue, narration = s.narration,
                        action = s.action,
                        beat_ref = s.beatRef, carry_over = s.carryOver, scene_context = s.sceneContext,
                        first_asset_ids = AssetCatalog.encodeRefIds(s.assetIds), last_asset_ids = "[]",
                        visual_prompt = s.visualPrompt, duration_seconds = s.durationSeconds,
                        scene_no = s.sceneNo, scene_title = s.sceneTitle,
                        screen_side = s.screenSide, eyeline = s.eyeline,
                        sb_check = sbCheckFromResult(result.gateErrors[s.shotNo])
                    )
                }
            }
        val entities = shotsToPersist
        try {
            PersistenceActionExecutor.writeShotsBatch(AppGraph.dao, AppGraph.storageGuard, episodeId, entities, "storyboard.generate")
        } catch (e: Throwable) {
            _state.value = _state.value.copy(generating = false, message = "分镜保存失败：${e.message ?: e.javaClass.simpleName}")
            return@launch
        }
        // 第五批：全量重生成后旧镜的渲染任务全部失效，必须清空本集 render_tasks，
        // 否则 QueuePage 会按 checkpoint 旧任务继续渲染已删除/已改内容的镜头（P0）。
        val renderReset = runCatching {
            withContext(Dispatchers.IO) { AppGraph.dao.deleteRenderTasksOf(episodeId) }
        }.isSuccess
        refresh()
        val errCount = result.gateErrors.count { (_, codes) -> codes.any { it !in SOFT_SB_CHECK_CODES } }
        val softCount = result.gateErrors.count { (_, codes) -> codes.isNotEmpty() && codes.all { it in SOFT_SB_CHECK_CODES } }
        // v1.9.17：用 refStats 精确定位「分镜没引用资产」断在哪一环，不再只给一句笼统警告
        val st = result.refStats
        val noAssetNote = when {
            st.catalogSize == 0 && motherCards.isEmpty() ->
                "。⚠ 本项目还没有任何资产卡，分镜未引用资产"
            st.catalogSize == 0 && mothersWithImage == 0 ->
                "。⚠ 你有 ${motherCards.size} 个资产卡，但**一个图都没生成**——资产目录为空，分镜无法引用任何资产。请先到资产页把资产图生成出来，再重生成分镜"
            st.catalogSize == 0 ->
                "。⚠ 资产目录为空（${motherCards.size} 个母卡中仅 $mothersWithImage 个有图且均未过审进目录），分镜未引用资产"
            st.keptRefs == 0 && st.rawRefs == 0 ->
                "。⚠ 目录有 ${st.catalogSize} 项，但 LLM 没有输出任何 asset_ids（指令未跟随）"
            st.keptRefs == 0 && st.rawRefs > 0 ->
                "。⚠ LLM 引用了 ${st.rawRefs} 个资产 id，但**都不在目录内**（目录 ${st.catalogSize} 项）→ 已丢弃 ${st.droppedRefs} 个幻觉 id"
            st.keptRefs > 0 ->
                " · 已引用资产 ${st.keptRefs} 次（目录 ${st.catalogSize} 项）" +
                    (if (st.droppedRefs > 0) "，⚠ 丢弃 ${st.droppedRefs} 个不在目录的 id" else "")
            else -> ""
        }
        _state.value = _state.value.copy(generating = false, message =
            "已生成${result.shots.size}镜" + (if (errCount > 0) "（其中${errCount}镜校验有误，见列表标记）" else "，全部通过校验✓") +
                (if (softCount > 0) "（${softCount}镜有画面描述提示，不阻断渲染）" else "") + noAssetNote +
                (if (renderReset) " · 已重置本集渲染队列" else " · ⚠ 重置渲染队列失败，请到渲染页清空队列"))
    }

    /**
     * 单镜重新生成：只生成指定镜头。
     * 第四批：已通过校验（sb_check=="pass"）的镜头不再直接拒绝——UI 弹二次确认后带 force=true 调进来；
     * force=false 时仍然拦下并给出「会覆盖现有内容」的提示，避免被其他入口静默覆盖。
     */
    fun regenerateShot(shotId: String, force: Boolean = false) = viewModelScope.launch {
        val old = withContext(Dispatchers.IO) { AppGraph.dao.shotKeyframes(shotId) }
            ?: run { _state.value = _state.value.copy(message = "找不到镜头：$shotId"); return@launch }
        if (old.sb_check == "pass" && !force) {
            _state.value = _state.value.copy(message = "镜头 #${old.shot_no} 已通过校验，重新生成会覆盖现有内容，请二次确认")
            return@launch
        }
        _state.value = _state.value.copy(generating = true, message = "正在重新生成镜头 #${old.shot_no}…")
        val script = withContext(Dispatchers.IO) { AppGraph.dao.episode(episodeId)?.script_json }
        if (script.isNullOrBlank()) {
            _state.value = _state.value.copy(generating = false, message = "本集没有剧本文本")
            return@launch
        }
        val projectId = episodeId.substringBeforeLast("_ep")
        val assets = withContext(Dispatchers.IO) { AppGraph.dao.assetsAllOf(projectId) }
        val result = runCatching {
            com.dramafactory.core.quality.AiStoryboardDirector.generate(
                script, chat = { req -> AppGraph.text.chat(req) },
                assets = AssetCatalog.build(assets), targetShotNo = old.shot_no)
        }.getOrElse {
            _state.value = _state.value.copy(generating = false, message = "单镜重生成失败：${it.message ?: it.javaClass.simpleName}")
            return@launch
        }
        val s = result.shots.singleOrNull()
        if (s == null) {
            _state.value = _state.value.copy(generating = false, message = "单镜重生成失败：模型未返回镜头 #${old.shot_no}")
            return@launch
        }
        val updated = old.copy(
            dialogue = s.dialogue, narration = s.narration,
            action = s.action,
            beat_ref = s.beatRef, carry_over = s.carryOver, scene_context = s.sceneContext,
            first_asset_ids = AssetCatalog.encodeRefIds(s.assetIds),
            visual_prompt = s.visualPrompt, duration_seconds = s.durationSeconds,
            sb_check = sbCheckFromResult(result.gateErrors[s.shotNo])
        )
        var renderInvalidateFailure: Throwable? = null
        runCatching {
            withContext(Dispatchers.IO) {
                AppGraph.dao.upsertShot(updated)
                check(AppGraph.dao.shotKeyframes(shotId)?.sb_check == updated.sb_check) { "单镜重生成后读回不一致" }
                // 第五批：镜头内容已变，旧渲染结果失效；删除失败不阻断保存，只在 message 提示。
                runCatching { AppGraph.dao.deleteRenderTask(shotId) }
                    .onFailure { renderInvalidateFailure = it }
            }
        }.onFailure {
            _state.value = _state.value.copy(generating = false, message = "单镜保存失败：${it.message}")
            return@launch
        }
        refresh()
        _state.value = _state.value.copy(generating = false, message =
            "镜头 #${old.shot_no} 已重新生成并完成校验" +
                (if (renderInvalidateFailure != null) "（渲染任务失效失败，可重新入队）" else "，该镜渲染结果已失效✓"))
    }


    // ---- 第十二轮：分镜可操作（编辑/删除/渲染）----

    /** 编辑单镜并落库；action与visual_prompt分开存，UI展示时合并 */
    fun updateShot(
        shotId: String, action: String, dialogue: String?, narration: String?,
        visualPrompt: String?, durationSeconds: Double,
        sceneContext: String? = null, carryOver: String? = null,
    ) = viewModelScope.launch {
        val failure = runCatching {
            withContext(Dispatchers.IO) {
                writeLock.withLock {
                    val row = AppGraph.dao.shotKeyframes(shotId)
                        ?: error("找不到分镜：$shotId")
                    AppGraph.dao.upsertShot(row.copy(
                        action = action.trim(), dialogue = dialogue?.trim()?.ifBlank { null },
                        narration = narration?.trim()?.ifBlank { null }, visual_prompt = visualPrompt?.trim()?.ifBlank { null },
                        scene_context = sceneContext?.trim()?.ifBlank { null },
                        carry_over = carryOver?.trim()?.ifBlank { null },
                        duration_seconds = durationSeconds.coerceIn(1.0, 60.0),
                        // ★第七批：内容改了，旧校验结论不再可信——重置 pending，
                        // 避免「明明改过内容却仍显示校验通过/仍带着旧的 error 拦住渲染」。
                        sb_check = "pending"))
                    val back = AppGraph.dao.shotKeyframes(shotId)
                    check(back?.action == action.trim()) { "分镜编辑后读回不一致：$shotId" }
                }
            }
        }.exceptionOrNull()
        // 第五批：画面语义已变 → 删掉该镜旧 render_task，否则同 shot_id 的 COMPLETED 会让重渲被跳过。
        var renderInvalidateFailure: Throwable? = null
        if (failure == null) {
            renderInvalidateFailure = runCatching {
                withContext(Dispatchers.IO) { AppGraph.dao.deleteRenderTask(shotId) }
            }.exceptionOrNull()
        }
        refresh()
        _state.value = _state.value.copy(message = when {
            failure != null -> "保存分镜失败：${failure.message}"
            renderInvalidateFailure != null ->
                "已保存镜头修改，但渲染任务失效失败：${renderInvalidateFailure?.message}（请到渲染页清空后重新入队）"
            else -> "已保存镜头修改，该镜渲染结果已失效✓"
        })
    }

    /**
     * 第三批：只更新场次/站位/视线四列（DAO 局部 UPDATE，不做整行 upsert）。
     * 与 updateShot 共用 writeLock，防止「读整行→回写」把刚保存的场次列覆盖回旧值。
     */
    fun updateShotSceneInfo(
        shotId: String, sceneNo: Int, sceneTitle: String?,
        screenSide: String?, eyeline: String?,
    ) = viewModelScope.launch {
        val failure = runCatching {
            withContext(Dispatchers.IO) {
                writeLock.withLock {
                    AppGraph.dao.shotKeyframes(shotId) ?: error("找不到分镜：$shotId")
                    val no = sceneNo.coerceAtLeast(0)
                    AppGraph.dao.updateShotSceneInfo(shotId, no,
                        sceneTitle?.trim()?.ifBlank { null },
                        screenSide?.trim()?.ifBlank { null },
                        eyeline?.trim()?.ifBlank { null })
                    val back = AppGraph.dao.shotKeyframes(shotId)
                    check(back?.scene_no == no) { "场次保存后读回不一致：$shotId" }
                    // ★第七批：场次/站位/视线变化会影响轴线与场次校验，旧结论重置。
                    if (back != null && back.sb_check != "pending") {
                        AppGraph.dao.upsertShot(back.copy(sb_check = "pending"))
                    }
                }
            }
        }.exceptionOrNull()
        var renderInvalidateFailure: Throwable? = null
        if (failure == null) {
            renderInvalidateFailure = runCatching {
                withContext(Dispatchers.IO) { AppGraph.dao.deleteRenderTask(shotId) }
            }.exceptionOrNull()
        }
        refresh()
        _state.value = _state.value.copy(message = when {
            failure != null -> "保存场次信息失败：${failure.message}"
            renderInvalidateFailure != null ->
                "已保存场次/站位/视线，但渲染任务失效失败：${renderInvalidateFailure?.message}（请到渲染页清空后重新入队）"
            else -> "已保存场次/站位/视线，该镜渲染结果已失效✓"
        })
    }

    /** 删除单镜 */
    fun deleteShot(shotId: String) = viewModelScope.launch {
        val failure = runCatching {
            withContext(Dispatchers.IO) {
                AppGraph.dao.deleteShot(shotId)
                check(AppGraph.dao.shotKeyframes(shotId) == null) { "分镜删除后仍可读回：$shotId" }
            }
        }.exceptionOrNull()
        // 第五批：删镜同时删 render_task，避免渲染队列里留孤儿任务。
        var renderFailure: Throwable? = null
        if (failure == null) {
            renderFailure = runCatching {
                withContext(Dispatchers.IO) { AppGraph.dao.deleteRenderTask(shotId) }
            }.exceptionOrNull()
        }
        refresh()
        _state.value = _state.value.copy(message = when {
            failure != null -> "删除分镜失败：${failure.message}"
            renderFailure != null -> "分镜已删除，但渲染任务清理失败：${renderFailure?.message}"
            else -> "分镜已删除✓"
        })
    }

    /**
     * 第十二轮：把本集全部分镜入队渲染（复用渲染队列断点续传/预算熔断链路）。
     */
    fun enqueueRender(onQueued: (Int) -> Unit) = viewModelScope.launch {
        val shots = _state.value.shots
        if (shots.isEmpty()) { onQueued(0); return@launch }
        val queue = RenderRuntime.queueFor(episodeId)
        val metas: List<com.dramafactory.core.model.ShotMeta> = shots.map { row ->
            val visual: String = row.visual_prompt?.let { "[$it]" } ?: ""
            val dlg: String = row.dialogue?.let { "「$it」" } ?: ""
            val action = cleanShotAction(row.action, row.visual_prompt)
            com.dramafactory.core.model.ShotMeta(
                shotId = row.shot_id, episodeId = episodeId,
                prompt = "$dlg$action$visual")
        }
        runCatching { queue.enqueueEpisode(episodeId, metas) }
            .onSuccess { onQueued(metas.size) }
            .onFailure { _state.value = _state.value.copy(message = "入队失败：${it.message}") }
    }

    /**
     * 第五批：渲染入口预检——只读 shotsOf(episodeId) 统计镜数与未过审镜，
     * 结果写进 state.renderPrompt 由 UI 弹确认框；这里不调用任何付费/入队接口。
     */
    fun requestRender() = viewModelScope.launch {
        val rows = runCatching {
            withContext(Dispatchers.IO) { AppGraph.dao.shotsOf(episodeId) }
        }.getOrNull()
        if (rows.isNullOrEmpty()) {
            _state.value = _state.value.copy(message = "本集还没有分镜，无法提交渲染")
            return@launch
        }
        val blocked = rows.filter { sbCheckBlocks(it.sb_check) }.map { it.shot_no }
        _state.value = _state.value.copy(renderPrompt = RenderPrompt(rows.size, blocked))
    }

    /** 第五批：关闭渲染确认框（取消或已确认后调用）。 */
    fun dismissRenderPrompt() {
        if (_state.value.renderPrompt != null) {
            _state.value = _state.value.copy(renderPrompt = null)
        }
    }

    // ---- 第四批：场次结构操作（合并 / 拆分 / 单镜跨场移动）----
    //
    // 分镜页按「相邻同场」连续分组渲染（与 StoryboardPage.groupShotsByScene 同口径），
    // 所以场次结构一变，就必须把受影响的镜重新编号成连续 1..N，否则组头会飘。
    // 场次号/标题统一走 dao.updateShotSceneInfo 局部 UPDATE（保留站位/视线/台词等列）；
    // 只有「移到上一场/下一场」需要重排镜号时，才对镜号变化的镜整行 upsert——
    // 数据层没有单列改 shot_no 的 DAO，且本任务不允许改 DramaDatabase.kt。
    // 所有写入都在 writeLock 内串行；upsert 的整行内容取自刚读到的快照。

    /**
     * 第四批：合并场次——把第 [sceneNo] 场所有镜并入上一场（沿用上一场标题），
     * 后面的场整体前移，重新编号成连续 1..N。第一场不可合并。
     */
    fun mergeSceneIntoPrevious(sceneNo: Int) = viewModelScope.launch {
        val outcome = runCatching {
            withContext(Dispatchers.IO) {
                writeLock.withLock {
                    val rows = AppGraph.dao.shotsOf(episodeId)
                    if (rows.isEmpty()) error("本集还没有分镜")
                    val groups = contiguousSceneGroups(rows)
                    val index = groups.indexOfFirst { it.firstOrNull()?.scene_no == sceneNo }
                    if (index < 0) error("找不到第${sceneNo}场")
                    if (index == 0) error("第一场没有上一场，无法合并")
                    val merged = ArrayList<List<ShotEntity>>(groups.size - 1)
                    for (i in groups.indices) {
                        when (i) {
                            index - 1 -> merged.add(groups[i] + groups[index])
                            index -> Unit
                            else -> merged.add(groups[i])
                        }
                    }
                    applySceneLayout(rows, sceneLayoutByGroups(merged))
                    "已把第${sceneNo}场（${groups[index].size}镜）并入上一场，后续场次序号已前移"
                }
            }
        }
        refresh()
        _state.value = _state.value.copy(message = outcome.getOrElse {
            "合并场次失败：${it.message ?: it.javaClass.simpleName}"
        })
    }

    /**
     * 第四批：拆分场次——把第 [sceneNo] 场从镜号 [fromShotNo] 起拆成新场。
     * 拆出的镜换成原场后一位的场次号，后续场整体后移；新场标题缺省「第N场」。
     * [fromShotNo] 必须是本场第 2 镜及以后（从第一镜拆会导致原场为空）。
     */
    fun splitScene(sceneNo: Int, fromShotNo: Int, newTitle: String?) = viewModelScope.launch {
        val outcome = runCatching {
            withContext(Dispatchers.IO) {
                writeLock.withLock {
                    val rows = AppGraph.dao.shotsOf(episodeId)
                    if (rows.isEmpty()) error("本集还没有分镜")
                    val groups = contiguousSceneGroups(rows)
                    val index = groups.indexOfFirst { it.firstOrNull()?.scene_no == sceneNo }
                    if (index < 0) error("找不到第${sceneNo}场")
                    val group = groups[index]
                    if (group.size < 2) error("本场只有一镜，无法拆分")
                    val splitAt = group.indexOfFirst { it.shot_no == fromShotNo }
                    if (splitAt < 0) error("本场没有镜号 #$fromShotNo")
                    if (splitAt == 0) error("不能从本场第一镜拆起（原场会变空）")
                    val splitGroups = ArrayList<List<ShotEntity>>(groups.size + 1)
                    for (i in groups.indices) {
                        if (i == index) {
                            splitGroups.add(group.subList(0, splitAt))
                            splitGroups.add(group.subList(splitAt, group.size))
                        } else {
                            splitGroups.add(groups[i])
                        }
                    }
                    val newSceneNo = index + 2
                    val title = newTitle?.trim()?.ifBlank { null } ?: "第${newSceneNo}场"
                    val layout = HashMap<String, SceneLayout>()
                    splitGroups.forEachIndexed { groupIndex, run ->
                        val runTitle = if (groupIndex == index + 1) title else sceneTitleOf(run)
                        for (row in run) {
                            layout[row.shot_id] = SceneLayout(
                                shotNo = row.shot_no, sceneNo = groupIndex + 1, sceneTitle = runTitle)
                        }
                    }
                    applySceneLayout(rows, layout)
                    "已从镜头 #$fromShotNo 起拆出第${newSceneNo}场（${group.size - splitAt}镜）"
                }
            }
        }
        refresh()
        _state.value = _state.value.copy(message = outcome.getOrElse {
            "拆分场次失败：${it.message ?: it.javaClass.simpleName}"
        })
    }

    /**
     * 第四批：把单镜移到相邻场次（[delta] = -1 上一场 / +1 下一场）。
     * 为了让「连续同场」分组不产生碎片场，会把它真正挪到目标场边界
     * （上一场末尾 / 下一场开头），再按新顺序重排镜号并重编场次号 1..N。
     */
    fun moveShotToScene(shotId: String, delta: Int) = viewModelScope.launch {
        val outcome = runCatching {
            withContext(Dispatchers.IO) {
                writeLock.withLock {
                    val rows = AppGraph.dao.shotsOf(episodeId)
                    if (rows.isEmpty()) error("本集还没有分镜")
                    if (delta != -1 && delta != 1) error("不支持的移动方向：$delta")
                    val shot = rows.firstOrNull { it.shot_id == shotId } ?: error("找不到镜头：$shotId")
                    val groups = contiguousSceneGroups(rows)
                    val groupIndex = groups.indexOfFirst { group -> group.any { it.shot_id == shotId } }
                    if (groupIndex < 0) error("找不到镜头 #${shot.shot_no} 所在场次")
                    val targetIndex = groupIndex + delta
                    if (targetIndex !in groups.indices) {
                        error(if (delta < 0) "镜头 #${shot.shot_no} 已在第一场，无法移到上一场"
                        else "镜头 #${shot.shot_no} 已在最后一场，无法移到下一场")
                    }
                    val targetGroup = groups[targetIndex]
                    val targetTitle = sceneTitleOf(targetGroup)
                    val ordered = rows.toMutableList()
                    ordered.removeAt(ordered.indexOfFirst { it.shot_id == shotId })
                    // 上一场 → 插到上一场末镜之后；下一场 → 插到下一场首镜之前
                    val anchorId = if (delta < 0) targetGroup.last().shot_id else targetGroup.first().shot_id
                    val anchorPos = ordered.indexOfFirst { it.shot_id == anchorId }
                    if (anchorPos < 0) error("目标场次锚点丢失")
                    val insertPos = if (delta < 0) anchorPos + 1 else anchorPos
                    ordered.add(insertPos, shot.copy(
                        scene_no = targetGroup.first().scene_no, scene_title = targetTitle))
                    // 重排后按「相邻同场」重新聚组，连续编号并顺便重排镜号
                    val runs = ArrayList<MutableList<ShotEntity>>()
                    for (row in ordered) {
                        val lastRun = runs.lastOrNull()
                        if (lastRun != null && lastRun.first().scene_no == row.scene_no) lastRun.add(row)
                        else runs.add(mutableListOf(row))
                    }
                    val layout = HashMap<String, SceneLayout>()
                    var position = 0
                    runs.forEachIndexed { runIndex, run ->
                        val title = sceneTitleOf(run)
                        for (row in run) {
                            position += 1
                            layout[row.shot_id] = SceneLayout(
                                shotNo = position, sceneNo = runIndex + 1, sceneTitle = title)
                        }
                    }
                    applySceneLayout(rows, layout)
                    "镜头 #${shot.shot_no} 已移到${if (delta < 0) "上一场" else "下一场"}，镜号已按新顺序重排"
                }
            }
        }
        refresh()
        _state.value = _state.value.copy(message = outcome.getOrElse {
            "移动镜头失败：${it.message ?: it.javaClass.simpleName}"
        })
    }

    /** 第四批：组内第一个非空场次标题（组头展示口径）。 */
    private fun sceneTitleOf(rows: List<ShotEntity>): String? =
        rows.firstOrNull { !it.scene_title.isNullOrBlank() }?.scene_title?.trim()?.ifBlank { null }

    /** 第四批：按组顺序生成连续 1..N 的场次映射（每组标题取组内首个非空标题）。 */
    private fun sceneLayoutByGroups(groups: List<List<ShotEntity>>): Map<String, SceneLayout> {
        val layout = HashMap<String, SceneLayout>()
        groups.forEachIndexed { index, rows ->
            val title = sceneTitleOf(rows)
            for (row in rows) {
                layout[row.shot_id] = SceneLayout(
                    shotNo = row.shot_no, sceneNo = index + 1, sceneTitle = title)
            }
        }
        return layout
    }

    /**
     * 第四批：把场次结构目标状态落库并读回校验。
     * ① 镜号变化（只有跨场移动会触发）→ 整行 upsert，行内容取自刚读到的快照；
     * ② 场次号/标题变化 → dao.updateShotSceneInfo 局部 UPDATE（保留站位/视线/台词/时长等列）。
     */
    private suspend fun applySceneLayout(rows: List<ShotEntity>, layout: Map<String, SceneLayout>) {
        for (row in rows) {
            val target = layout[row.shot_id] ?: continue
            if (target.shotNo == row.shot_no) continue
            AppGraph.dao.upsertShot(row.copy(shot_no = target.shotNo))
        }
        for (row in rows) {
            val target = layout[row.shot_id] ?: continue
            if (target.sceneNo == row.scene_no && target.sceneTitle == row.scene_title) continue
            AppGraph.dao.updateShotSceneInfo(
                row.shot_id, target.sceneNo, target.sceneTitle, row.screen_side, row.eyeline)
        }
        val back = AppGraph.dao.shotsOf(episodeId)
        check(back.size == rows.size) { "场次操作后镜数不一致：${rows.size} → ${back.size}" }
        for (row in back) {
            val target = layout[row.shot_id] ?: error("场次操作后找不到镜头：${row.shot_id}")
            check(row.shot_no == target.shotNo) { "镜号读回不一致：${row.shot_no} ≠ ${target.shotNo}" }
            check(row.scene_no == target.sceneNo) { "场次号读回不一致：${row.scene_no} ≠ ${target.sceneNo}" }
            check(row.scene_title == target.sceneTitle) {
                "场次标题读回不一致：${row.scene_title} ≠ ${target.sceneTitle}"
            }
        }
        // ★第七批：场次结构变了（拆/合/移动），轴线与场次校验的旧结论必须重置。
        back.filter { it.shot_id in layout.keys && it.sb_check != "pending" }
            .forEach { AppGraph.dao.upsertShot(it.copy(sb_check = "pending")) }
    }

    // ---- 第四批：单镜单字段 AI 重写（台词不提供：StoryboardGate 有逐字校验）----

    /**
     * 第四批：只重写某一镜的单个字段（动作/旁白/画面描述/场景上下文/承接），
     * 不整镜重生成，避免连带改掉台词、时长、资产引用与已通过的校验结果。
     * 模型返回 null（不合格/不可重写）时不落库，保留原值。
     */
    fun rewriteShotField(shotId: String, field: String, instruction: String? = null) = viewModelScope.launch {
        if (!ShotFieldRewriter.isRewritable(field)) {
            _state.value = _state.value.copy(message = "该字段不支持 AI 重写（台词需与剧本逐字一致，只能人工改）")
            return@launch
        }
        if (!AppGraph.isInitialized || !AppGraph.hasAnyTextKey()) {
            _state.value = _state.value.copy(message = "未配置文本模型 API Key，无法使用 AI 重写。请到「设置」页配置后重试。")
            return@launch
        }
        _state.value = _state.value.copy(
            rewritingField = "$shotId|$field", message = null, rewriteResult = null)
        var renderInvalidateFailure: Throwable? = null
        val outcome = runCatching {
            // ① 只读快照（网络请求不占 writeLock，避免重写期间 UI 保存被卡住）
            val snapshot = withContext(Dispatchers.IO) {
                val rows = AppGraph.dao.shotsOf(episodeId)
                val index = rows.indexOfFirst { it.shot_id == shotId }
                if (index < 0) error("找不到镜头：$shotId")
                ShotRewriteSnapshot(
                    row = rows[index],
                    prevAction = rows.getOrNull(index - 1)?.action,
                    nextAction = rows.getOrNull(index + 1)?.action,
                    script = runCatching { AppGraph.dao.episode(episodeId)?.script_json }.getOrNull(),
                )
            }
            val row = snapshot.row
            val context = ShotFieldRewriter.ShotContext(
                shotNo = row.shot_no,
                action = row.action.orEmpty(),
                dialogue = row.dialogue,
                narration = row.narration,
                visualPrompt = row.visual_prompt,
                sceneContext = row.scene_context,
                carryOver = row.carry_over,
                durationSeconds = row.duration_seconds,
                sceneTitle = row.scene_title,
                prevAction = snapshot.prevAction,
                nextAction = snapshot.nextAction,
                scriptExcerpt = snapshot.script,
            )
            val value = ShotFieldRewriter.rewrite(
                field = field,
                context = context,
                instruction = instruction?.trim().orEmpty(),
                chat = { req -> AppGraph.text.chat(req) },
            ) ?: error("模型没有返回可用的重写内容，已保留原文")
            // ② 落库：只改目标列，其余列整行保留；落库前重读最新行，避免覆盖这期间的其它保存
            withContext(Dispatchers.IO) {
                writeLock.withLock {
                    val latest = AppGraph.dao.shotKeyframes(shotId) ?: error("找不到镜头：$shotId")
                    // ★第七批：AI 重写改了画面语义，旧校验结论（pass/error/软提示）全部失效。
                    val updated = when (field) {
                        ShotFieldRewriter.FIELD_ACTION -> latest.copy(action = value, sb_check = "pending")
                        ShotFieldRewriter.FIELD_NARRATION -> latest.copy(narration = value, sb_check = "pending")
                        ShotFieldRewriter.FIELD_VISUAL -> latest.copy(visual_prompt = value, sb_check = "pending")
                        ShotFieldRewriter.FIELD_SCENE_CONTEXT -> latest.copy(scene_context = value, sb_check = "pending")
                        ShotFieldRewriter.FIELD_CARRY_OVER -> latest.copy(carry_over = value, sb_check = "pending")
                        else -> error("该字段不支持 AI 重写：$field")
                    }
                    AppGraph.dao.upsertShot(updated)
                    val back = AppGraph.dao.shotKeyframes(shotId)
                    check(back != null && rewrittenValueOf(back, field) == value) {
                        "AI 重写落库后读回不一致：$shotId/$field"
                    }
                    // 第五批：字段已变，旧渲染结果失效（失败不阻断重写落库，只在 message 提示）。
                    runCatching { AppGraph.dao.deleteRenderTask(shotId) }
                        .onFailure { renderInvalidateFailure = it }
                }
            }
            row.shot_no to value
        }
        refresh()
        val message = outcome.fold(
            onSuccess = { pair ->
                "已重写镜头 #${pair.first} 的${shotFieldLabel(field)}" +
                    (if (renderInvalidateFailure != null) "（渲染任务失效失败，可重新入队）" else "，该镜渲染结果已失效✓")
            },
            onFailure = { e -> "AI 重写未生效：${e.message ?: e.javaClass.simpleName}" },
        )
        val result = outcome.getOrNull()?.let { ShotFieldRewriteResult(shotId, field, it.second) }
        _state.value = _state.value.copy(rewritingField = null, message = message, rewriteResult = result)
    }

    /** 第四批：UI 已把重写结果回填编辑框，清掉结果避免重复回填。 */
    fun consumeRewriteResult() {
        if (_state.value.rewriteResult != null) {
            _state.value = _state.value.copy(rewriteResult = null)
        }
    }

    /** 第四批：读出某一字段当前值（重写落库后的读回校验用）。 */
    private fun rewrittenValueOf(row: ShotEntity, field: String): String? = when (field) {
        ShotFieldRewriter.FIELD_ACTION -> row.action
        ShotFieldRewriter.FIELD_NARRATION -> row.narration
        ShotFieldRewriter.FIELD_VISUAL -> row.visual_prompt
        ShotFieldRewriter.FIELD_SCENE_CONTEXT -> row.scene_context
        ShotFieldRewriter.FIELD_CARRY_OVER -> row.carry_over
        else -> null
    }

    /** 第四批：字段中文名（message 与 UI 标题用）。 */
    private fun shotFieldLabel(field: String): String = when (field) {
        ShotFieldRewriter.FIELD_ACTION -> "动作"
        ShotFieldRewriter.FIELD_NARRATION -> "旁白"
        ShotFieldRewriter.FIELD_VISUAL -> "画面描述"
        ShotFieldRewriter.FIELD_SCENE_CONTEXT -> "场景上下文"
        ShotFieldRewriter.FIELD_CARRY_OVER -> "承接"
        else -> field
    }
}

/** 第四批：场次结构操作的目标状态（镜号 + 场次号 + 场次标题）。 */
private data class SceneLayout(val shotNo: Int, val sceneNo: Int, val sceneTitle: String?)

/** 第四批：单字段 AI 重写前的只读快照（读一次，网络请求后再重读最新行落库）。 */
private data class ShotRewriteSnapshot(
    val row: ShotEntity,
    val prevAction: String?,
    val nextAction: String?,
    val script: String?,
)

/**
 * 第四批：按「相邻 scene_no 相等」连续分组（分镜页组头与 VM 场次操作共用同一口径，
 * scene_no=0 自然归入「未分场」）。
 */
internal fun contiguousSceneGroups(rows: List<ShotEntity>): List<List<ShotEntity>> {
    val groups = ArrayList<MutableList<ShotEntity>>()
    for (row in rows) {
        val last = groups.lastOrNull()
        if (last != null && last.first().scene_no == row.scene_no) last.add(row)
        else groups.add(mutableListOf(row))
    }
    return groups.map { it.toList() }
}

/** 第四批：单字段 AI 重写结果（shotId + 字段 + 新内容），UI 据此回填编辑框。 */
data class ShotFieldRewriteResult(
    val shotId: String,
    val field: String,
    val value: String,
)

/**
 * 第五批：渲染入口预检结果。blockedShotNos = sb_check 含 error 的镜号（整集渲染会被运行时闸门中止）。
 */
data class RenderPrompt(val totalShots: Int, val blockedShotNos: List<Int>)

/** 第五批：该镜的 sb_check 是否会阻断整集渲染（与 RenderRuntime 的 error 判定同口径）。 */
private fun sbCheckBlocks(sb: String?): Boolean =
    sb != null && sb != "pass" && sb.contains("error")
