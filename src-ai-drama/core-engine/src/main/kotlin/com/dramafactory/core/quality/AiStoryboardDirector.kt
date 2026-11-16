package com.dramafactory.core.quality

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 第十轮：AI 编剧 + AI 导演 文字管线。
 * 第十五轮：注入项目已审批资产清单(asset snapshot)，要求 LLM 用 asset_id 引用而非自由发挥，
 *          解决「分镜与资产图严重不符」——LLM 拿不到角色/场景/道具的 prompt 描述就凭空编。
 *
 * - 编剧（storyboard）：剧本文本 + 资产目录 → 大模型拆成镜头表。每镜含：
 *   shot_no / action / dialogue（逐字）/ narration / duration_seconds / characterNames /
 *   assetIds（引用的真实 asset_id 列表）/ beatRef。
 * - 导演（visual）：基于编剧产物 + 资产名 → 生成 visual_prompt。
 *
 * 两次 LLM 调用均要求严格 JSON 输出；解析容错 + 重试≤3；
 * 结果过 StoryboardGate 六铁律校验，错误镜标记但不阻塞其余镜落库。
 * TextProvider 注入可测（App层接 AgnesProvider.chat）。
 */
object AiStoryboardDirector {

    /** 注入给 LLM 的资产快照（仅含必要字段，不泄漏整张表） */
    data class AssetSnapshot(
        val id: String,
        val kind: String,         // character / scene / prop
        val name: String,         // 角色/场景/道具名（中文）
        val description: String,  // 详细描述（用于 LLM 理解但不会原样写入分镜）
    )

    /** 一镜的完整产出（编剧+导演合并视图） */
    data class Shot(
        val shotNo: Int,
        val action: String,
        val dialogue: String? = null,
        val narration: String? = null,
        val durationSeconds: Double = 6.0,
        val characterNames: List<String> = emptyList(),
        /** 第十五轮：本镜引用的真实 asset_id 列表（来自 catalog 注入），渲染时据此拉图入锁脸 */
        val assetIds: List<String> = emptyList(),
        val beatRef: String? = null,
        val carryOver: String? = null,
        /** 跨镜必须显式继承的时间/天气/空间/光线状态，避免场景缺省后模型自由发挥。 */
        val sceneContext: String? = null,
        /** 导演视觉指令（运镜/景别/构图） */
        val visualPrompt: String? = null,
        /** 场次编号：同一场戏同号、从1连续递增；0=模型未给，由 stabilizeShots 归一化 */
        val sceneNo: Int = 0,
        /** 场次简短标题（如「深夜办公室」），分镜页按场分组用 */
        val sceneTitle: String? = null,
        /** 本镜主体站位：左/右/中；用于 180 度轴线一致性校验 */
        val screenSide: String? = null,
        /** 本镜主体视线方向：左/右/上/下/对方；正反打两侧视线应相反 */
        val eyeline: String? = null,
    )

    /**
     * v1.9.17：资产引用统计——诊断「分镜没引用资产」到底断在哪一环。
     * - catalogSize=0：目录本身为空（资产没生图等）→ LLM 无从引用；
     * - catalogSize>0 且 rawRefs=0：LLM 压根没输出 asset_ids（指令未跟随）；
     * - rawRefs>0 且 keptRefs=0：LLM 引用了但 id 都不在目录内（幻觉）→ 被静默丢弃。
     */
    data class RefStats(
        val catalogSize: Int,
        val rawRefs: Int,
        val keptRefs: Int,
    ) {
        val droppedRefs: Int get() = (rawRefs - keptRefs).coerceAtLeast(0)
    }

    data class Result(
        val shots: List<Shot>,
        val usedLlm: Boolean,
        val gateErrors: Map<Int, List<String>>,
        val refStats: RefStats = RefStats(0, 0, 0),
    )

    private const val MAX_SCRIPT_CHARS = 24000
    private const val WRITER_PROMPT = """你是短剧分镜编剧。把给定的剧本/小说片段拆成视频镜头表。
只输出严格 JSON，不要markdown代码块。格式：
{"shots":[{"shot_no":1,"action":"画面中发生的具体动作（30字内，纯动作描述）","scene_context":"时间+天气+空间位置，第一镜建立基准，后续镜头必须继承或写明变化原因","dialogue":"该镜台词原文（无台词则省略）","narration":"旁白（无则省略）","duration_seconds":6,"characters":["角色名"],"asset_ids":["a_xxx","a_yyy"],"scene_no":1,"scene_title":"深夜办公室","screen_side":"左","eyeline":"看向对方","beat_ref":"B01","carry_over":"本镜结束时角色/场景/道具的状态，供下一镜承接"}]}
规则：
- 每镜4-10秒（推荐5-8秒）；一场戏2-5镜；台词必须与原文逐字一致不得改写；shot_no从1连续递增。
- 节奏设计：不要所有镜头一样长。开场钩子4-6秒；场景建立镜头6-8秒；对话正反打5-7秒；冲突/动作/追逐4-5.5秒；情绪停留/沉默/独白8-10秒；每3-4镜至少出现一次明显时长变化。
- 段落结构：同一场戏先用1个建立镜头交代空间与人物关系，再进入对话或动作；换场时用一个可理解的转场镜（推门/转身/跟拍/空镜）并把转场写进 carry_over。
- 场次结构：同一场戏的镜头 scene_no 必须相同、从1连续递增，禁止跳号；scene_title 用4-8字概括本场（如"深夜办公室""医院走廊"）；每场2-5镜，只有过场/闪回允许单镜成场。
- 轴线与视线（180度规则）：对话戏里两个角色固定分居画面左右，同一角色不要在相邻镜头换到另一侧；screen_side 写本镜主体站位（左/右/中）；正反打时两人 eyeline 必须相反（一个"看向左"、一个"看向右"），不允许两镜都看同一侧；确需越轴/反打/过肩时，在 action 或 carry_over 里明确写"越轴/反打/过肩/绕到另一侧"。
- 景别与机位：相邻两镜不要同景别；对话戏用正反打（过肩/近景/特写交替）；冲突高潮用近景+特写；交代空间用全景/中景。
- 镜头内调度：action 要写清人物在画面哪一侧、朝向谁、与前景/背景的关系（如"林见夕居左，顾沉背身入画右侧"）。
- 短剧片段通常4-12镜；如果输入是长剧本，必须覆盖提供文本中的全部关键事件，镜头数量可以超过12，绝不能为了满足数量上限而省略后半段。
- 每镜必须填写 scene_context：明确时间、天气、空间位置（光线/影调交给导演视觉指令，不要在这里重复堆砌）。第一镜建立全局基准；后续镜头默认继承上一镜，只有剧本明确或 carry_over 写明转场原因时才允许改变。
- 严禁无解释地改变时间、天气、室内外或空间位置。后续镜头必须继承上一镜已明确的环境状态；只有剧本或转场因果明确要求时才允许改变，不得自行补全未提供的时间、天气或光源。
- 室外→室内、室内→室外或跨地点切换，carry_over 必须写明进入/离开/推门/转场等因果；没有转场依据时保持上一镜环境，不得跳变。
- 除第一镜外，每镜必须填写 carry_over：明确写出上一镜结束后仍在场的角色、空间、道具状态，或写明可理解的转场因果。禁止从室外无解释跳到室内、从角色无解释跳到静物。
- asset_ids（可选，不强制绑定）：只有当该镜 action 里确实出现下方【资产目录】中的角色/场景/道具时，才填对应 asset_id（可为空数组）；不要为了“引用资产”强行给每一镜绑定。渲染期会按人物/场景名在镜头文本里软解析资产，并自动合成接缝帧，留空完全不影响出片；禁止编造目录里不存在的 id。目录为空时直接省略该字段。
- action 中引用角色时优先使用资产目录中的“名字”（中文），便于后续软解析；目录为空时用剧本原名。
- 音效、环境声、拟声词（叮、咚、砰、哗啦、嗡嗡、马蹄声等）严禁写入 dialogue 或 narration——dialogue 只能是角色开口说的话；音效写进 action（如"门铃响起，江雪抬头"）。配音会把 dialogue 里的每个字读出来，写"叮咚"角色就会念"叮咚"。"""

    private const val DIRECTOR_PROMPT = """你是短剧摄影导演。为每个镜头写一条中文画面描述（visual字段）。
只输出严格 JSON：{"visuals":[{"shot_no":1,"visual":"景别+运镜+构图+光线/影调，30-60字"}]}
要求：先写机位语言（如"近景缓推，人物居左，背景纵深虚化"），再补充本镜的光线/影调/氛围（如"电脑屏幕蓝光映脸、办公室冷色调"）；必须与 scene_context、剧本及资产描述一致，不得自行新增剧本未写的时间、天气或光源；不要重复动作内容。
若该镜填了 asset_ids：写 visual 时要与对应资产的长相/场景/道具一致（如"近景缓推张角道长"而非"近景缓推一古装男子"）；未填 asset_ids 时按 action 写，不得虚构具体演员长相，也不得因为没绑定资产就省略机位语言。
镜头语言要求：每镜画面描述必须包含机位高度（平视/俯拍/仰拍）、景别、运镜，以及光比/色温/景深/前景遮挡中的至少一项；相邻镜头景别必须有变化，不得连续两镜同一景别；对话戏用过肩或正反打，冲突戏用近景+快速运镜，情绪戏用长焦特写+浅景深。
轴线要求：同一场对话戏保持180度轴线——同一角色在相邻镜头里必须停在同一侧（screen_side），正反打两人视线方向相反；确需反打/越轴时在 visual 里写清"反打/过肩/越轴"，不要无理由把角色换到另一侧。
跨镜连贯：必须继承上一镜的 carry_over、角色/场景资产与动作结果，禁止无因果跳转；通过 beat_ref 控制因果顺序与节奏。"""

    /**
     * 编剧+导演两段式生成。
     * @param chat 文本模型调用
     * @param script 剧本文本（截断6000字）
     * @param assets 项目已审批的资产目录（可空，空时回退到原行为，asset_ids 全空）
     */
    suspend fun generate(
        script: String,
        chat: suspend (com.dramafactory.core.model.ChatRequest) -> com.dramafactory.core.model.ChatResponse,
        assets: List<AssetSnapshot> = emptyList(),
        targetShotNo: Int? = null,
    ): Result {
        val clipped = if (script.length > MAX_SCRIPT_CHARS) script.take(MAX_SCRIPT_CHARS) + "\n…(后文略，已达单次生成上限，请分段生成)" else script
        val catalogBlock = renderCatalog(assets)

        // —— 编剧：拆镜 ——
        // v1.9.17：parseShots 额外返回引用统计；空列表仍触发重试（对齐原 repeatRetry 语义）
        val parsed: Pair<List<Shot>, RefStats>? = repeatRetry {
            val writerMsg = if (catalogBlock.isNotBlank()) {
                "$WRITER_PROMPT${targetShotNo?.let { "\n\n【单镜重生成】只输出镜头$it，禁止生成或改写其他镜头。" } ?: ""}\n\n【资产目录】\n$catalogBlock\n\n【剧本】\n$clipped"
            } else {
                "$WRITER_PROMPT${targetShotNo?.let { "\n\n【单镜重生成】只输出镜头$it，禁止生成或改写其他镜头。" } ?: ""}\n\n【剧本】\n$clipped"
            }
            val resp = chat(com.dramafactory.core.model.ChatRequest(messages = listOf(
                com.dramafactory.core.model.ChatMessage("user", writerMsg))))
            val p = parseShots(resp.content, assets)
            if (p.first.isEmpty()) null else p
        }
        var shots = parsed?.first ?: return Result(emptyList(), usedLlm = false, gateErrors = emptyMap(),
            refStats = RefStats(assets.size, 0, 0))
        val refStats = parsed.second
        if (targetShotNo != null) {
            shots = shots.filter { it.shotNo == targetShotNo }
            if (shots.isEmpty()) return Result(emptyList(), usedLlm = true, gateErrors = emptyMap(), refStats = refStats)
        }

        // 先做确定性连贯性校验；发现 carry_over/镜号/时长等问题时，
        // 把上一镜上下文和具体错误回传给模型，只修复问题镜头，最多两轮。
        shots = repairCoherenceIfNeeded(shots, clipped, assets, chat)

        // 模型在长文本中偶尔漏填承接字段；先做确定性归一化，再跑最终硬闸，避免同一错误反复让用户重试。
        shots = if (targetShotNo == null) stabilizeShots(shots, assets.map { it.id }.toSet()) else shots

        // 确定性节奏设计：按镜头功能重排时长（4~10 秒），避免模型把所有镜都写成同一个秒数。
        // 单镜重生成（targetShotNo != null）时不动时长，保护人工编辑结果。
        if (targetShotNo == null) shots = ShotDesigner.refine(shots)

        // —— 导演：视觉指令 ——
        val visuals: Map<Int, String> = runCatching {
            val brief = shots.joinToString("\n") { s ->
                val assetNames = s.assetIds.mapNotNull { id -> assets.firstOrNull { it.id == id }?.let { "${it.kind}:${it.name}" } }
                val tail = if (assetNames.isNotEmpty()) " [资产：${assetNames.joinToString("、")}]" else ""
                val previous = shots.firstOrNull { it.shotNo == s.shotNo - 1 }
                val context = if (previous != null) {
                    " [上一镜结束：${previous.carryOver ?: previous.action}；上一镜场景：${previous.sceneContext ?: "缺失"}；本镜场景：${s.sceneContext ?: "缺失"}；本镜承接：${s.carryOver ?: "缺失"}]"
                } else " [开场镜头；场景：${s.sceneContext ?: "缺失"}]"
                "镜头${s.shotNo}：${s.action}$tail$context"
            }
            val directorMsg = if (catalogBlock.isNotBlank()) {
                "$DIRECTOR_PROMPT\n\n【资产目录】\n$catalogBlock\n\n$brief"
            } else {
                "$DIRECTOR_PROMPT\n\n$brief"
            }
            repeatRetry {
                val resp = chat(com.dramafactory.core.model.ChatRequest(messages = listOf(
                    com.dramafactory.core.model.ChatMessage("user", directorMsg))))
                parseVisuals(resp.content)
            } ?: emptyMap()
        }.getOrDefault(emptyMap())

        // 先挂上导演视觉指令，再做校验：景别覆盖类设计软提示需要 visual_prompt 才能判断。
        val withVisual = shots.map { it.copy(visualPrompt = visuals[it.shotNo]) }
        // —— 忠实性粗校验（台词逐字）+ 分镜设计软提示（景别/节奏）——
        val gateErrors = mutableMapOf<Int, List<String>>()
        StoryCoherence.validate(withVisual, assets.map { it.id }.toSet()).groupBy { it.shotNo }
            .forEach { (shotNo, issues) -> gateErrors[shotNo] = issues.map { it.code } }
        for (s in withVisual) {
            val errs = mutableListOf<String>()
            s.dialogue?.let { d ->
                if (d.isNotBlank() && !verbatimIn(d.trim(), script)) errs += "dialogue_not_verbatim"
            }
            if (s.action.isBlank()) errs += "action_empty"
            // 合并而不是覆盖：台词错误不能把场景/节奏提示吃掉。
            if (errs.isNotEmpty()) gateErrors[s.shotNo] = gateErrors[s.shotNo].orEmpty() + errs
        }

        return Result(
            shots = withVisual,
            usedLlm = true,
            gateErrors = gateErrors,
            refStats = refStats)
    }

    /**
     * 连贯性定向修复：只把失败镜头、上一镜状态和错误原因发给模型，
     * 避免整集重写导致每次结果漂移。解析失败或模型拒绝时保留原镜头，
     * 最终仍由 StoryCoherence 做硬校验。
     */
    private suspend fun repairCoherenceIfNeeded(
        initial: List<Shot>,
        script: String,
        assets: List<AssetSnapshot>,
        chat: suspend (com.dramafactory.core.model.ChatRequest) -> com.dramafactory.core.model.ChatResponse,
    ): List<Shot> {
        var current = initial
        repeat(2) {
            val issues = StoryCoherence.validate(current, assets.map { it.id }.toSet())
                .filter { it.code in setOf("carry_over_missing", "scene_context_missing", "shot_order_gap", "rhythm_duration", "asset_unbound") }
            if (issues.isEmpty()) return current
            val targets = issues.map { it.shotNo }.toSet()
            val context = current.filter { it.shotNo in targets || it.shotNo - 1 in targets }
                .joinToString("\n") { s ->
                    "镜头${s.shotNo}：action=${s.action}; dialogue=${s.dialogue ?: "无"}; narration=${s.narration ?: "无"}; scene_context=${s.sceneContext ?: "缺失"}; scene_no=${s.sceneNo}; scene_title=${s.sceneTitle ?: "缺失"}; screen_side=${s.screenSide ?: "缺失"}; eyeline=${s.eyeline ?: "缺失"}; carry_over=${s.carryOver ?: "缺失"}; duration=${s.durationSeconds}; asset_ids=${s.assetIds}"
                }
            val errors = issues.joinToString("\n") { "镜头${it.shotNo}: ${it.code} - ${it.message}" }
            val prompt = """你是分镜修复导演。只修复指定镜头，不要重写其他镜头。
严格只输出 JSON：{"shots":[{"shot_no":2,"action":"...","dialogue":"原台词（无则省略）","narration":"原旁白（无则省略）","scene_context":"...","scene_no":1,"scene_title":"深夜办公室","screen_side":"左","eyeline":"看向对方","duration_seconds":6,"characters":["角色名"],"asset_ids":[],"beat_ref":"B02","carry_over":"..."}]}
要求：scene_context 必须明确继承上一镜的时间、天气、空间、光线；若有变化，carry_over 必须写明转场因果（如推门进入、离开、切换地点）；保持原故事事件不变；dialogue/narration 必须原样照抄【相关镜头】里该镜已有的台词旁白，不得改写、删除或新增；asset_ids 只能使用资产目录中的 id；时长限定4-10秒（推荐5-8秒）。
【错误】
$errors
【相关镜头】
$context
【资产目录】
${renderCatalog(assets)}
【原剧本】
$script"""
            val response = runCatching {
                chat(com.dramafactory.core.model.ChatRequest(messages = listOf(
                    com.dramafactory.core.model.ChatMessage("user", prompt))))
            }.getOrNull() ?: return@repeat
            val repaired = parseShots(response.content, assets).first
                .filter { it.shotNo in targets }
            if (repaired.isEmpty()) return@repeat
            val byNo = repaired.associateBy { it.shotNo }
            // 只覆盖本次真正要修的字段；模型没返回的台词/旁白/人物/承接一律保留原值，
            // 避免“修一条连贯性警告，顺手把台词旁白清空”。
            current = current.map { original ->
                val rep = byNo[original.shotNo] ?: return@map original
                original.copy(
                    action = rep.action.ifBlank { original.action },
                    dialogue = rep.dialogue ?: original.dialogue,
                    narration = rep.narration ?: original.narration,
                    durationSeconds = rep.durationSeconds,
                    assetIds = if (rep.assetIds.isEmpty() && original.assetIds.isNotEmpty()) original.assetIds else rep.assetIds,
                    beatRef = rep.beatRef ?: original.beatRef,
                    carryOver = rep.carryOver ?: original.carryOver,
                    sceneContext = rep.sceneContext ?: original.sceneContext,
                    sceneNo = if (rep.sceneNo > 0) rep.sceneNo else original.sceneNo,
                    sceneTitle = rep.sceneTitle ?: original.sceneTitle,
                    screenSide = rep.screenSide ?: original.screenSide,
                    eyeline = rep.eyeline ?: original.eyeline,
                    characterNames = if (rep.characterNames.isNotEmpty()) rep.characterNames else original.characterNames,
                )
            }
        }
        return current
    }


    private fun stabilizeShots(shots: List<Shot>, approvedAssetIds: Set<String>): List<Shot> {
        var previous: Shot? = null
        // 场次归一：模型给的 scene_no 只用于判断“换没换场”，最终按出现顺序重排成 1..N，
        // 保证连续、无跳号；同一场内每镜继承场标题，缺省补“第N场”。
        var sceneNo = 0
        var lastRawScene = Int.MIN_VALUE
        var currentTitle: String? = null
        return shots.sortedBy { it.shotNo }.mapIndexed { index, raw ->
            val rawScene = raw.sceneNo
            if (index == 0) {
                sceneNo = 1
                lastRawScene = if (rawScene > 0) rawScene else 1
                currentTitle = raw.sceneTitle?.takeIf { it.isNotBlank() } ?: "第1场"
            } else if (rawScene > 0 && rawScene != lastRawScene) {
                sceneNo += 1
                lastRawScene = rawScene
                currentTitle = raw.sceneTitle?.takeIf { it.isNotBlank() } ?: "第${sceneNo}场"
            }
            val fixed = raw.copy(
                shotNo = index + 1,
                sceneNo = sceneNo,
                sceneTitle = raw.sceneTitle?.takeIf { it.isNotBlank() } ?: currentTitle,
                screenSide = normalizeSide(raw.screenSide),
                eyeline = normalizeEyeline(raw.eyeline),
                durationSeconds = raw.durationSeconds.coerceIn(ShotDesigner.MIN_SECONDS, ShotDesigner.MAX_SECONDS),
                assetIds = if (approvedAssetIds.isEmpty()) raw.assetIds else raw.assetIds.filter { it in approvedAssetIds }.distinct(),
                carryOver = if (index == 0) raw.carryOver else raw.carryOver
                    ?.takeIf { it.isNotBlank() }
                    ?: "承接上一镜：${previous?.action?.take(80).orEmpty()}",
                sceneContext = raw.sceneContext?.takeIf { it.isNotBlank() }
                    ?: previous?.sceneContext
            ).also { previous = it }
            fixed
        }
    }

    /** 画面站位归一：左/右/中，无法判断返回 null（避免把“左侧逆光”等描述误当站位保留）。 */
    private fun normalizeSide(value: String?): String? {
        val text = value?.trim().orEmpty()
        if (text.isEmpty()) return null
        return when {
            text.contains("左") -> "左"
            text.contains("右") -> "右"
            text.contains("中") || text.contains("正") -> "中"
            else -> null
        }
    }

    /** 视线方向归一：左/右/上/下/对方。 */
    private fun normalizeEyeline(value: String?): String? {
        val text = value?.trim().orEmpty()
        if (text.isEmpty()) return null
        return when {
            text.contains("对方") || text.contains("看对方") || text.contains("对视") -> "对方"
            text.contains("左") -> "左"
            text.contains("右") -> "右"
            text.contains("上") -> "上"
            text.contains("下") -> "下"
            else -> null
        }
    }

    private fun renderCatalog(assets: List<AssetSnapshot>): String {
        if (assets.isEmpty()) return ""
        return assets.joinToString("\n") { a ->
            // 只给 LLM 必要信息：id / 种类 / 名 / 描述。description 限制 200 字内避免超长
            val desc = if (a.description.length > 200) a.description.take(200) + "…" else a.description
            "- id=${a.id} | kind=${a.kind} | name=${a.name} | desc=${desc}"
        }
    }

    /** 重试≤max次直到非null且非空列表结果 */
    private suspend fun <T> repeatRetry(max: Int = 3, block: suspend () -> T?): T? {
        repeat(max) { attempt ->
            val r = try { block() } catch (_: Throwable) { null }
            if (r != null && !(r is List<*> && r.isEmpty())) return r
            kotlinx.coroutines.delay(400L * (attempt + 1))
        }
        return null
    }

    /** 台词逐字校验：原文包含该句（忽略首尾空白）即视为逐字进入 */
    fun verbatimIn(line: String, script: String): Boolean = script.contains(line)

    /**
     * 第十五轮：解析 shots；asset_ids 限定为 catalog 里的 id（防止 LLM 幻觉乱写），
     * 非 catalog 的 id 丢弃（不入 shot.assetIds 也不报错）。
     */
    internal fun parseShots(content: String, assets: List<AssetSnapshot> = emptyList()): Pair<List<Shot>, RefStats> {
        val empty = emptyList<Shot>()
        val obj = jsonOf(content) ?: return empty to RefStats(assets.size, 0, 0)
        val arr = obj["shots"] as? JsonArray ?: return empty to RefStats(assets.size, 0, 0)
        val validIds = assets.map { it.id }.toSet()
        val out = mutableListOf<Shot>()
        var rawRefs = 0
        var keptRefs = 0
        for (e in arr) {
            val o = e as? JsonObject ?: continue
            fun str(k: String): String = (o[k] as? JsonPrimitive)?.content?.trim() ?: ""
            val no = (o["shot_no"] as? JsonPrimitive)?.content?.toIntOrNull() ?: (out.size + 1)
            val action = str("action")
            if (action.isEmpty()) continue
            val chars = (o["characters"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }
                ?: emptyList()
            val rawAssetIds = (o["asset_ids"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }
                ?: emptyList()
            val assetIds = if (validIds.isEmpty()) emptyList()
                else rawAssetIds.filter { it in validIds }.distinct()
            rawRefs += rawAssetIds.size
            keptRefs += assetIds.size
            // v1.9.35 音效守卫：AI 把环境音（叮咚等）写进 dialogue/narration 时，
            // 配音会朗读出来（角色念"叮咚"）——纯音效剥离出对话，降级并入 action。
            // v1.9.37 守卫v2：分类规则扩展（旁白/叠字/BGM 提示），StripPartial 表示
            // 只剥离括号标注、保留残留真实台词（kept 非空 → dialogue 置 kept）。
            var actionFinal = action
            fun stripToAction(v: SoundEffectFilter.Verdict.Strip) {
                actionFinal = "$actionFinal（音效：${v.text}）"
            }
            val dialogueRaw = str("dialogue").ifBlank { null }
            val dialogue = when (val d = SoundEffectFilter.classify(dialogueRaw)) {
                is SoundEffectFilter.Verdict.Strip -> { stripToAction(d); null }
                is SoundEffectFilter.Verdict.StripPartial -> {
                    stripToAction(d.let { SoundEffectFilter.Verdict.Strip(it.stripped) })
                    if (d.kept.isBlank()) null else d.kept
                }
                else -> dialogueRaw
            }
            val narrationRaw = str("narration").ifBlank { null }
            val narration = when (val d = SoundEffectFilter.classify(narrationRaw)) {
                is SoundEffectFilter.Verdict.Strip -> { stripToAction(d); null }
                is SoundEffectFilter.Verdict.StripPartial -> {
                    stripToAction(d.let { SoundEffectFilter.Verdict.Strip(it.stripped) })
                    if (d.kept.isBlank()) null else d.kept
                }
                else -> narrationRaw
            }
            out += Shot(
                shotNo = no, action = actionFinal,
                dialogue = dialogue,
                narration = narration,
                durationSeconds = (o["duration_seconds"] as? JsonPrimitive)?.content?.toDoubleOrNull() ?: 6.0,
                characterNames = chars,
                assetIds = assetIds,
                beatRef = str("beat_ref").ifBlank { null },
                carryOver = str("carry_over").ifBlank { null },
                sceneContext = str("scene_context").ifBlank { null },
                sceneNo = (o["scene_no"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0,
                sceneTitle = str("scene_title").ifBlank { null },
                screenSide = normalizeSide(str("screen_side")),
                eyeline = normalizeEyeline(str("eyeline")))
        }
        return out.sortedBy { it.shotNo } to RefStats(assets.size, rawRefs, keptRefs)
    }

    internal fun parseVisuals(content: String): Map<Int, String> {
        val obj = jsonOf(content) ?: return emptyMap()
        val arr = obj["visuals"] as? JsonArray ?: return emptyMap()
        val out = mutableMapOf<Int, String>()
        for (e in arr) {
            val o = e as? JsonObject ?: continue
            val no = (o["shot_no"] as? JsonPrimitive)?.content?.toIntOrNull() ?: continue
            val v = (o["visual"] as? JsonPrimitive)?.content?.trim() ?: continue
            if (v.isNotBlank()) out[no] = v
        }
        return out
    }

    /** 容错JSON提取：剥```栅栏 → 首个{到末个} → parse */
    private fun jsonOf(content: String): JsonObject? {
        var s = content.trim()
        if (s.startsWith("```")) {
            s = s.removePrefix("```json").removePrefix("```")
            s = s.substringBeforeLast("```").trim()
        }
        val l = s.indexOf('{'); val r = s.lastIndexOf('}')
        if (l < 0 || r <= l) return null
        return try {
            kotlinx.serialization.json.Json.parseToJsonElement(s.substring(l, r + 1)) as? JsonObject
        } catch (_: Throwable) { null }
    }
}