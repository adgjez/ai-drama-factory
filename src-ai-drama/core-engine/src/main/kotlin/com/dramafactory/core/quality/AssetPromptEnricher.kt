package com.dramafactory.core.quality

/**
 * 资产图 prompt 的 LLM 扩写器（core 层，app / desktop 共用）。
 *
 * 剧本提取的资产卡可能只是裸名词（如「王莽」「漠北草原·日·外」），直接喂图像模型出图质量差。
 * 本模块在生图前用文本 LLM 把资产卡扩写成一段**符合当前时代/风格设定、聚焦主体**的视觉描述，
 * 再交给 [AssetPromptBuilder] 套类型约束（纯色底 / 空场无人 / 禁词 / 尾部强指令）。
 *
 * 资产专业化重写要点：
 * - 按影视美术设定图标准描述主体（角色：年龄体型/发型发色/脸型五官/服装形制颜色面料/配饰；
 *   场景：空间尺度/结构材质/家具陈设/光源时辰/主色调；道具：形制/材质/尺寸/颜色/磨损状态）；
 * - 只描述**主体本身**，绝不写背景、环境、构图、镜头、光线布置——由 [AssetPromptBuilder] 统一追加；
 * - 时代不再写死「古装」：eraLabel 由调用方按剧本推断结果传入；
 *   现代/当代题材（neutral/modern/fantasy 预设）通过 [modernAllowed] 关闭「禁现代元素」约束，
 *   避免把现代剧资产强行扩成古装（旧版写死古装+禁现代，是现代题材出图不专业的直接原因）；
 * - 输出以英文为主（图像模型对英文理解更强），可夹中文关键术语；纯文本、无 JSON、无前缀。
 * - 与具体文本 Provider 解耦：调用方把「发一条 user 消息并返回模型文本」的函数注入 [enrich]。
 */
object AssetPromptEnricher {

    /** 各类型扩写的影视美术侧重点（注入系统指令），只描述主体自身。 */
    private fun kindFocus(kind: String): String = when (kind.lowercase()) {
        AssetPromptBuilder.KIND_CHARACTER ->
            "人物：性别与年龄感、体型身高、发型发色、脸型与五官特征、服装形制与颜色面料、鞋包配饰、标志性体态细节；" +
            "符合当前时代设定的服饰形制与发式、面料质地、单人平视；只考虑这一个人本身；" +
            "双手必须空置、自然下垂，不描述任何手持的器物、兵器、刀剑、简牍、书卷、杯盏、杖或道具"
        AssetPromptBuilder.KIND_SCENE ->
            "场景：空间尺度、建筑形制与结构材质、地面墙面材质、家具与陈设、主要器物、时辰天光与光源方向、主色调；" +
            "符合当前时代设定的建筑与陈设；这是空场空镜，绝不出现任何人物"
        AssetPromptBuilder.KIND_PROP ->
            "器物：形制、材质与工艺、尺寸比例、颜色、磨损与使用状态、标志性细节；" +
            "符合当前时代设定的形制与材质；孤立单件，绝不出现人物或手持的人手"
        else -> "主体本身"
    }

    /** 构造发给 LLM 的指令文本。
     * @param modernAllowed true=现代/当代/架空题材，允许现代元素且不要强加古装；false=历史题材，禁现代元素。
     */
    fun instruction(
        kind: String,
        eraLabel: String,
        forbidden: List<String>,
        modernAllowed: Boolean = false,
    ): String = buildString {
        append("你是资深影视美术指导，为短剧图像模型撰写资产设定图提示词。")
        append("当前时代/风格设定：$eraLabel。")
        append("请仅针对「${kindFocus(kind)}」把给定的资产名称扩写成一段聚焦主体本身的视觉描述。\n")
        append("规则：\n")
        append("1. 用 2-4 句以英文为主的描述（可夹中文关键术语），不要 JSON、不要前缀、不要任何解释。\n")
        append("2. 只描述主体自身外观，禁止描写背景、环境、构图、镜头、光线布置（由下游统一追加）。\n")
        append("   ★若主体是人物：双手必须空置，禁止出现 holding / carrying / wielding / in hand 等持握表述，")
        append("也不要写兵器、简牍、书卷、杯盏、杖等任何被拿在手里的物件（实物由道具资产单独生成）。\n")
        if (modernAllowed) {
            append("3. 这是现代/当代或架空题材：保留剧本里的现代服饰与器物（如西装、职业装、手机、电脑、办公室、" +
                "汽车、城市街景），不要强行添加古装、朝代服饰或古代建筑；主体必须与剧本设定的年代一致。\n")
        } else {
            append("3. 严禁任何现代元素：无电力、无工业、无塑料、无玻璃幕墙、无现代服装与招牌文字。\n")
        }
        append("4. 描述必须具体可执行：写清形制、材质、颜色、尺寸比例或工艺细节，禁止空泛词" +
            "（美丽、帅气、气氛神秘、科技感、高级感、精致、绝美等）。\n")
        if (forbidden.isNotEmpty()) {
            append("5. 避免出现这些概念：${forbidden.take(12).joinToString("、")}.\n")
        }
    }

    /** 清洗 LLM 原始输出：去代码块/引号/前后空白，压缩多余空白 */
    fun clean(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        return raw
            .replace(Regex("^```[a-zA-Z]*\\s*"), "")
            .replace(Regex("\\s*```$"), "")
            .trim()
            .replace(Regex("^[\"“‘『【\\s]+"), "")
            .replace(Regex("[\"”’』】\\s]+$"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    /**
     * 调用文本 LLM 扩写资产卡。
     * @param chat 发送单条 user 消息并返回模型文本的函数（由 App 层用 TextProvider 包裹）。
     * @param fallback 扩写失败/返回空时回退的文本（通常即资产卡原文）。
     * @param modernAllowed 现代/当代/架空题材传 true（关闭禁现代元素约束）。
     * @return 清洗后的扩写文本；chat 抛异常或返回空时回退 [fallback]。
     */
    suspend fun enrich(
        chat: suspend (String) -> String,
        kind: String,
        name: String,
        eraLabel: String = "时代未指定（以剧本设定为准）",
        forbidden: List<String> = emptyList(),
        fallback: String = name,
        modernAllowed: Boolean = false,
    ): String {
        val userMsg = "${instruction(kind, eraLabel, forbidden, modernAllowed)}\n资产名称：$name"
        val raw = runCatching { chat(userMsg) }.getOrNull().orEmpty()
        val cleaned = clean(raw)
        if (cleaned.isBlank()) return fallback
        // 人物扩写若仍出现持握表述，直接丢弃回退原卡——
        // 下游的正向约束救不回已经写进主体描述的「手持某物」，必须从源头掐掉。
        if (kind.lowercase() == AssetPromptBuilder.KIND_CHARACTER && HELD_ITEM_HINT.containsMatchIn(cleaned)) {
            return fallback
        }
        return cleaned
    }

    /** 人物扩写结果里的「持握」痕迹（中英）。命中即判定该次扩写不可用，回退原卡。 */
    private val HELD_ITEM_HINT = Regex(
        """\b(holding|carrying|wielding|gripping|clutching|in\s+(his|her|their)?\s*hands?)\b""" +
        """|手持|拿在手里|握着|手持着""",
        RegexOption.IGNORE_CASE)
}
