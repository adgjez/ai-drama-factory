package com.dramafactory.core.quality

/**
 * 大模型资产自动提取（影视美术资产拆解口径）。
 *
 * 旧实现（AssetsLogic.ScriptAssetExtractor）为纯正则，只能识别「角色：/场景：/道具：」清单行。
 * 第十三轮引入 LLM 提取，但 prompt 只要 30 字以内的 desc，产物是「女秘书·职业装女性」这类
 * 只有身份、没有美术设定的裸卡，直接喂参考图模型必然不专业。
 *
 * 本版（资产专业化重写）按影视美术指导的拆解标准产出：
 * - 角色：姓名·身份标签；desc 60-120 字（性别年龄体型 / 发型发色 / 脸型五官 / 服装形制颜色面料 / 鞋包配饰 / 标志细节）；
 * - 场景：地点·时段·内景或外景；desc 60-120 字（空间尺度 / 结构材质 / 家具陈设 / 光源方向与时辰 / 主色调）；
 * - 道具：物名·关键状态；desc 30-60 字（形制 / 材质 / 尺寸比例 / 颜色 / 磨损状态 / 剧情作用）；
 * - aliases 记录同一实体的其它称呼，用于去重合并，绝不重复建卡；
 * - 命名禁止只用职业泛称，必须带可见识别特征（便于直接生成参考图与跨镜一致性）。
 *
 * 解析失败/超时由调用方回退正则提取（保底不空手而归）。TextProvider 注入可测。
 */
object LlmAssetExtractor {

    data class Asset(
        val kind: String,          // character / scene / prop
        val name: String,          // 专业命名：姓名·身份标签 / 地点·时段·内外景 / 物名·状态
        val desc: String,          // 影视美术外观设定（可含 costume / 时段补充）
        /** 同一实体的其它称呼（别名），只用于去重合并，不参与生图 prompt */
        val aliases: List<String> = emptyList(),
    )

    data class ExtractResult(val assets: List<Asset>, val usedLlm: Boolean)

    /** 文本截断上限：保留更多原文，避免角色/场景在后半段才出现时被漏提 */
    private const val MAX_CHARS = 12000

    private const val SYSTEM_PROMPT = """你是资深影视美术指导兼制片资产师，负责把小说/剧本拆成可直接投产的资产清单。
只输出严格 JSON（不要 markdown 代码块、不要任何解释），格式：
{"characters":[{"name":"姓名·身份标签","desc":"60-120字影视美术外观设定","aliases":["别名1","别名2"],"costume":"本剧主要造型/换装说明 20-40字"}],"scenes":[{"name":"地点·时段·内景或外景","desc":"60-120字空间美术设定","aliases":["别名"],"time_of_day":"时辰/天光"}],"props":[{"name":"具体物名·关键状态","desc":"30-60字器物设定","aliases":["别名"]}]}

【提取范围】只提取会出现在画面里、且影响叙事的资产：
- characters 2-8 个：有台词/动作/情绪戏的主要角色；群演、路人不算。
- scenes 3-10 个：承载戏份的实景空间；同一地点日夜变化合并为一条，在 desc 注明日/夜。
- props 不超过 10 个：推动剧情或反复出现的道具；一次性背景杂物不算。

【命名规范】禁止只用职业泛称，必须带可见识别特征：
- 角色：「姓名·身份与标志特征」，如「林见夕·32岁女总裁」「顾沉·退役安保顾问」；剧本无姓名时用「身份·外观特征」。
- 场景：「地点·时段·内景/外景」，如「总裁办公室·傍晚·内景」「地下车库·深夜·内景」。
- 道具：「物名·关键状态」，如「手机·屏幕碎裂」「合同·文件夹」。

【desc 必须可直接拍、可直接生成参考图；禁止空泛词：美丽、帅气、气氛神秘、科技感、高级感、精致、绝美】
- 角色：性别、年龄感、体型身高、发型发色、脸型与五官特征、服装形制/颜色/面料、鞋包配饰、标志性体态或细节；costume 单列本剧主要造型（有换装写「第X场后：…」）。
- 场景：空间尺度、建筑结构与材质、家具陈设、主要道具、光源方向与时辰天光、主色调与氛围。
- 道具：形制、材质、尺寸比例、颜色、磨损/使用状态、剧情作用。

【去重】同一人物/地点/道具的不同称呼只保留一条，其它称呼写进 aliases（如「林总」「见夕」）。
【风格】以剧本本身的时代与类型为准，不强行加古装或现代元素。"""

    /**
     * LLM 提取主入口。
     * @param chat 文本模型调用（App层注入 Provider.chat）
     * @param text 小说/剧本文本（截断至 [MAX_CHARS] 字防 token 溢出）
     */
    suspend fun extract(
        text: String,
        chat: suspend (com.dramafactory.core.model.ChatRequest) -> com.dramafactory.core.model.ChatResponse,
    ): ExtractResult {
        val clipped = if (text.length > MAX_CHARS) text.take(MAX_CHARS) + "\n…(后文略)" else text
        // 重试≤2次（网络抖动 / JSON 不纯）
        repeat(2) { attempt ->
            runCatching {
                val resp = chat(com.dramafactory.core.model.ChatRequest(messages = listOf(
                    com.dramafactory.core.model.ChatMessage("user", "$SYSTEM_PROMPT\n\n【剧本/小说文本】\n$clipped"))))
                val assets = parseJson(resp.content)
                if (assets.isNotEmpty()) return ExtractResult(assets, usedLlm = true)
            }
            kotlinx.coroutines.delay(500L * (attempt + 1))
        }
        return ExtractResult(emptyList(), usedLlm = false)
    }

    /** 容错解析：剥 markdown 代码栅栏 → 定位首个{…末个} → 解析 name/desc/aliases/costume/time_of_day */
    fun parseJson(content: String): List<Asset> {
        var s = content.trim()
        if (s.startsWith("```")) {   // 剥 ```json … ```
            s = s.removePrefix("```json").removePrefix("```")
            s = s.substringBeforeLast("```").trim()
        }
        val l = s.indexOf('{'); val r = s.lastIndexOf('}')
        if (l < 0 || r <= l) return emptyList()
        return try {
            val obj = kotlinx.serialization.json.Json.parseToJsonElement(
                s.substring(l, r + 1)).let { it as? kotlinx.serialization.json.JsonObject }
                ?: return emptyList()
            val out = mutableListOf<Asset>()
            for ((key, kind) in mapOf("characters" to "character", "scenes" to "scene", "props" to "prop")) {
                val arr = obj[key] as? kotlinx.serialization.json.JsonArray ?: continue
                for (e in arr) {
                    val o = e as? kotlinx.serialization.json.JsonObject ?: continue
                    fun str(k: String): String =
                        (o[k] as? kotlinx.serialization.json.JsonPrimitive)?.content?.trim() ?: ""
                    val name = str("name")
                    if (name.isEmpty()) continue
                    val desc = buildDesc(str("desc"), str("costume"), str("time_of_day"), kind)
                    val aliases = parseAliases(o["aliases"])
                    // LLM 偶尔会把无人机等可操作物体塞进 characters；这类资产没有人格，
                    // 必须在进入 App 资产库前纠正为道具，避免角色棚拍/锁脸流程误处理。
                    val normalizedKind = normalizeKind(kind, name, desc)
                    out += Asset(normalizedKind, name, desc.ifBlank { name }, aliases)
                }
            }
            dedupeByAlias(out)
        } catch (_: Throwable) { emptyList() }
    }

    /** 把 costume（主要造型）/ time_of_day（天光）并入 desc，保证下游一次拿到完整视觉信息。 */
    private fun buildDesc(desc: String, costume: String, timeOfDay: String, kind: String): String {
        val sb = StringBuilder(desc.trim())
        if (costume.isNotBlank() && !sb.contains(costume)) {
            if (sb.isNotEmpty()) sb.append("；")
            sb.append("主要造型：").append(costume)
        }
        if (kind == "scene" && timeOfDay.isNotBlank() && !sb.contains(timeOfDay)) {
            if (sb.isNotEmpty()) sb.append("；")
            sb.append("时段与天光：").append(timeOfDay)
        }
        return sb.toString().trim()
    }

    /** aliases 兼容数组与分隔字符串两种模型输出。 */
    private fun parseAliases(el: kotlinx.serialization.json.JsonElement?): List<String> {
        val raw = when (el) {
            is kotlinx.serialization.json.JsonArray -> el.mapNotNull {
                (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.trim()
            }
            is kotlinx.serialization.json.JsonPrimitive -> el.content.split('、', '，', ',', '/', '／')
            else -> emptyList()
        }
        return raw.map { it.trim() }.filter { it.isNotEmpty() && it.length <= 20 }.distinct()
    }

    /** 同一实体的别名级去重：name 或 aliases 命中已见集合即视为重复。 */
    private fun dedupeByAlias(assets: List<Asset>): List<Asset> {
        val seen = mutableSetOf<String>()
        val out = mutableListOf<Asset>()
        for (a in assets) {
            val keys = (listOf(a.name) + a.aliases).map(::normalizeName).filter { it.isNotBlank() }
            if (keys.any { it in seen }) continue
            seen += keys
            out += a
        }
        return out
    }

    /** 名称归一化（去空白/标点、小写），仅用于去重比较。 */
    private fun normalizeName(raw: String): String =
        raw.lowercase().replace(Regex("[\\s\\p{Punct}·・、，,。.；;：:（）()《》【】\\[\\]]"), "")

    /** 把明显的非人格实体从 LLM 的 character 误分类中纠正为 prop。 */
    private fun normalizeKind(kind: String, name: String, desc: String): String {
        if (kind != "character") return kind
        val value = "$name $desc"
        val objectTerms = listOf(
            "无人机", "机器人", "摄像机", "相机", "手机", "电脑", "汽车", "车辆",
            "摩托车", "飞机", "直升机", "武器", "手枪", "步枪", "炸弹", "外卖盒"
        )
        val personalityTerms = listOf("角色", "驾驶员", "飞行员", "队长", "将军", "小姐", "先生")
        return if (objectTerms.any(value::contains) && personalityTerms.none(value::contains)) "prop" else kind
    }

    /**
     * 合并策略：LLM 结果与正则结果按「名称+别名」归一化去重合并（LLM 优先带 desc，正则补漏），
     * 上限各 kind 12 条防爆量。旧实现直接丢弃正则结果，导致 LLM 漏提时无兜底。
     */
    fun merge(llmAssets: List<Asset>, regexNames: List<Triple<String, String, Unit>>): List<Asset> {
        val seen = mutableSetOf<String>()
        val out = mutableListOf<Asset>()
        fun keysOf(a: Asset): List<String> =
            (listOf(a.name) + a.aliases).map(::normalizeName).filter { it.isNotBlank() }
        for (a in llmAssets) {
            val keys = keysOf(a)
            if (keys.any { it in seen }) continue
            seen += keys
            out += a
        }
        for ((kind, name, _) in regexNames) {
            val k = normalizeName(name)
            if (k.isBlank() || k in seen) continue
            seen += k
            out += Asset(kind, name, "")
        }
        return out.groupBy { it.kind }.flatMap { (_, list) -> list.take(12) }
    }

    /** 资产卡文本（App 落库/展示/下游生图统一口径）：`name：desc`，无 desc 时只用 name。 */
    fun cardPrompt(a: Asset): String = if (a.desc.isBlank()) a.name else a.name + "：" + a.desc
}
