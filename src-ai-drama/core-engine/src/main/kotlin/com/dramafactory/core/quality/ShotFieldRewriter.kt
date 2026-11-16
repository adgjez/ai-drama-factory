package com.dramafactory.core.quality

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 第四批：单镜单字段 AI 重写。
 *
 * 分镜页的编辑对话框里，用户经常只想改「画面描述」「动作」「场景上下文」「承接」中的一项，
 * 而不是整镜重新生成（整镜重生成会连带改掉已改好的台词/时长/资产引用，还可能让台词偏离剧本原文）。
 * 这里只把目标字段 + 相邻镜上下文交给大模型，要求它原样保留其他字段，返回单个字符串。
 *
 * 为什么不允许重写 dialogue：
 * 台词必须与剧本逐字一致（StoryboardGate 的 dialogue_not_verbatim 硬闸），
 * 让模型改写台词会直接制造校验错误，所以台词只能在编辑框里人工改。
 */
object ShotFieldRewriter {

    const val FIELD_ACTION = "action"
    const val FIELD_NARRATION = "narration"
    const val FIELD_VISUAL = "visual"
    const val FIELD_SCENE_CONTEXT = "scene_context"
    const val FIELD_CARRY_OVER = "carry_over"

    /** 可交给 AI 重写的字段（dialogue 故意不在内） */
    val REWRITABLE_FIELDS: Set<String> = setOf(
        FIELD_ACTION, FIELD_NARRATION, FIELD_VISUAL, FIELD_SCENE_CONTEXT, FIELD_CARRY_OVER,
    )

    fun isRewritable(field: String): Boolean = field in REWRITABLE_FIELDS

    /** 单镜上下文：只喂必要信息，避免长剧本把 prompt 撑爆 */
    data class ShotContext(
        val shotNo: Int,
        val action: String,
        val dialogue: String? = null,
        val narration: String? = null,
        val visualPrompt: String? = null,
        val sceneContext: String? = null,
        val carryOver: String? = null,
        val durationSeconds: Double = 6.0,
        val sceneTitle: String? = null,
        /** 上一镜动作（帮助模型理解承接，不会改写它） */
        val prevAction: String? = null,
        /** 下一镜动作（帮助模型理解本镜该怎么收尾） */
        val nextAction: String? = null,
        /** 剧本片段（仅用于让重写忠于原文，可选） */
        val scriptExcerpt: String? = null,
    )

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private const val MAX_SCRIPT_EXCERPT = 1200

    /** 动作里不允许出现镜头语言（镜头语言属于 visual 字段） */
    private val CAMERA_WORDS = Regex("镜头|景别|特写|近景|中景|远景|全景|大特写|推镜|拉镜|摇镜|移镜|跟拍|俯拍|仰拍|构图|机位|变焦")

    suspend fun rewrite(
        field: String,
        context: ShotContext,
        instruction: String = "",
        chat: suspend (com.dramafactory.core.model.ChatRequest) -> com.dramafactory.core.model.ChatResponse,
    ): String? {
        if (!isRewritable(field)) return null
        val prompt = buildPrompt(field, context, instruction)
        val resp = chat(
            com.dramafactory.core.model.ChatRequest(
                messages = listOf(com.dramafactory.core.model.ChatMessage("user", prompt)),
                temperature = 0.4,
            ),
        )
        val raw = extractValue(resp.content) ?: return null
        return sanitize(field, raw)
    }

    internal fun buildPrompt(field: String, context: ShotContext, instruction: String): String = buildString {
        append("你是短剧分镜编辑。只重写【").append(fieldLabel(field)).append("】这一个字段，")
        append("其余字段保持不变，本镜发生的故事事件不得改变，不得新增剧本里没有的人物/地点/事件。\n")
        append(fieldRules(field)).append('\n')
        if (instruction.isNotBlank()) {
            append("【用户额外要求】").append(instruction.trim().take(120)).append('\n')
        }
        append("【本镜现状】\n")
        append("- 镜号：").append(context.shotNo)
        if (!context.sceneTitle.isNullOrBlank()) append("（").append(context.sceneTitle).append("）")
        append('\n')
        append("- 动作：").append(context.action.ifBlank { "（空）" }).append('\n')
        context.dialogue?.takeIf { it.isNotBlank() }?.let { append("- 台词：").append(it).append('\n') }
        context.narration?.takeIf { it.isNotBlank() }?.let { append("- 旁白：").append(it).append('\n') }
        context.visualPrompt?.takeIf { it.isNotBlank() }?.let { append("- 画面描述：").append(it).append('\n') }
        context.sceneContext?.takeIf { it.isNotBlank() }?.let { append("- 场景上下文：").append(it).append('\n') }
        context.carryOver?.takeIf { it.isNotBlank() }?.let { append("- 承接：").append(it).append('\n') }
        append("- 时长：").append(context.durationSeconds).append("秒\n")
        context.prevAction?.takeIf { it.isNotBlank() }?.let { append("- 上一镜动作（只作承接参考）：").append(it).append('\n') }
        context.nextAction?.takeIf { it.isNotBlank() }?.let { append("- 下一镜动作（只作收尾参考）：").append(it).append('\n') }
        context.scriptExcerpt?.takeIf { it.isNotBlank() }?.let {
            append("【剧本片段（重写必须忠于它）】\n").append(it.take(MAX_SCRIPT_EXCERPT)).append('\n')
        }
        append("只输出严格 JSON：{\"value\":\"重写后的内容\"}，不要 markdown 代码块，不要解释。")
    }

    private fun fieldLabel(field: String): String = when (field) {
        FIELD_ACTION -> "动作 action"
        FIELD_NARRATION -> "旁白 narration"
        FIELD_VISUAL -> "画面描述 visual"
        FIELD_SCENE_CONTEXT -> "场景上下文 scene_context"
        FIELD_CARRY_OVER -> "承接 carry_over"
        else -> field
    }

    private fun fieldRules(field: String): String = when (field) {
        FIELD_ACTION -> "要求：纯动作描述，15-45字，写清谁在画面哪一侧、朝向谁、做了什么；" +
            "严禁出现镜头语言（景别/运镜/构图/光线/色调）、台词、音效拟声词。"
        FIELD_NARRATION -> "要求：一句旁白，10-40字，只补充情绪或时间推移，不写画面动作细节，不写台词，不写音效。"
        FIELD_VISUAL -> "要求：景别+运镜+构图+光线影调，20-60字，例如「中景缓推，女秘书居左，电脑蓝光映脸，背景办公区纵深虚化」；" +
            "不要重复动作内容，不要写台词或音效。"
        FIELD_SCENE_CONTEXT -> "要求：时间+天气+空间位置，10-50字；必须与上一镜环境保持一致或写明剧本已交代的变化原因，" +
            "不要凭空新增天气/时间/光源。"
        FIELD_CARRY_OVER -> "要求：本镜结束时角色/场景/道具的状态，10-50字，供下一镜承接；只写仍在场的事实，不预测后续剧情。"
        else -> ""
    }

    /** 从模型输出里取 value：优先严格 JSON，其次去掉代码块标记，最后退回原文 */
    internal fun extractValue(content: String): String? {
        val trimmed = content.trim()
        if (trimmed.isBlank()) return null
        val candidates = listOf(
            trimmed,
            trimmed.removePrefix("\u0060\u0060\u0060json").removePrefix("\u0060\u0060\u0060").removeSuffix("\u0060\u0060\u0060").trim(),
        )
        for (c in candidates) {
            val start = c.indexOf('{')
            val end = c.lastIndexOf('}')
            if (start >= 0 && end > start) {
                val slice = c.substring(start, end + 1)
                runCatching {
                    val obj = json.parseToJsonElement(slice) as? JsonObject ?: return@runCatching null
                    val v = (obj["value"] ?: obj["text"] ?: obj["content"]) as? JsonPrimitive ?: return@runCatching null
                    v.content
                }.getOrNull()?.takeIf { it.isNotBlank() }?.let { return it }
            }
        }
        // 兜底：模型直接输出了一段纯文本（常见于小模型）
        val plain = candidates.last().trim().trim('"', '\'', '\u0060')
        return plain.takeIf { it.isNotBlank() && !it.startsWith("{") }
    }

    /** 归一化并做字段级安全校验；不合格返回 null（调用方不落库，保持原值） */
    internal fun sanitize(field: String, raw: String): String? {
        var v = raw.trim()
            .removePrefix("value:").removePrefix("value：").trim()
            .trim('"', '\'', '\u0060', '「', '」').trim()
        v = v.replace(Regex("\\s+"), " ")
        if (v.isBlank()) return null
        val limit = when (field) {
            FIELD_ACTION -> 80
            FIELD_NARRATION -> 80
            FIELD_VISUAL -> 100
            FIELD_SCENE_CONTEXT -> 100
            FIELD_CARRY_OVER -> 100
            else -> 100
        }
        if (v.length > limit) v = v.take(limit)
        if (field == FIELD_ACTION && CAMERA_WORDS.containsMatchIn(v)) return null
        if (field == FIELD_ACTION && v.length < 6) return null
        if (field == FIELD_VISUAL && v.length < 8) return null
        // 旁白里出现引号对白特征时拒绝（防止把台词塞进旁白被配音念出来）
        if (field == FIELD_NARRATION && (v.contains('「') || v.contains('」') || v.contains('"'))) return null
        return v
    }
}
