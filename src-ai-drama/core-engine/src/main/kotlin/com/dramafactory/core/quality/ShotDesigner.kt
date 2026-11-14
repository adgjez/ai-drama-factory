package com.dramafactory.core.quality

/**
 * 分镜节奏设计器（确定性后处理，不依赖模型）。
 *
 * 大模型拆镜最常见的两个毛病：
 *  1. 所有镜头都给同一个 5~8 秒——成片像 PPT 匀速翻页，没有节奏；
 *  2. 景别覆盖全靠导演一句「景别+运镜」，连续多镜同景别，观众失去空间感。
 *
 * 这里在分镜生成之后、导演视觉指令之前，按镜头功能确定性地重排时长：
 *  - 开场钩子简短、场次建立镜留长、冲突/动作镜短切、情绪停留镜最长、对话镜中等、转场镜略长；
 *  - 连续三镜同长时强制抬一档，避免匀速节奏。
 *
 * 景别解析 [shotSizeOf] 同时供 StoryCoherence 做软提示、分镜页做节奏摘要。
 */
object ShotDesigner {

    /** 镜头功能分类：决定建议时长。 */
    enum class ShotFunction { HOOK, ESTABLISHING, TRANSITION, ACTION, EMOTION, DIALOGUE, DEFAULT }

    /** 允许区间（视频模型 2.5 支持 4~12 秒，这里取 4~10 秒更稳，也给剪辑留余量）。 */
    const val MIN_SECONDS = 4.0
    const val MAX_SECONDS = 10.0

    private fun durationFor(function: ShotFunction): Double = when (function) {
        ShotFunction.HOOK -> 6.0
        ShotFunction.ESTABLISHING -> 7.0
        ShotFunction.TRANSITION -> 7.5
        ShotFunction.ACTION -> 5.0
        ShotFunction.EMOTION -> 8.5
        ShotFunction.DIALOGUE -> 6.5
        ShotFunction.DEFAULT -> 6.0
    }

    private val ACTION_WORDS = Regex("冲|追|跑|打|踢|摔|砸|夺|抢|扑|闪|躲|挥|拔|撞|逃|爆炸|急|快|出手|扭打|推倒|踹")
    private val EMOTION_WORDS = Regex("凝视|沉默|停顿|呼吸|颤抖|泪|哽咽|良久|迟疑|犹豫|攥紧|握紧|心口|背影|缓缓|特写")
    private val TRANSITION_WORDS = Regex("推门|开门|关门|走进|走出|转身离开|穿过|上车|下车|电梯|画面一转|转场|空镜")

    private val SIZE_RULES = listOf(
        "大特写" to Regex("大特写|极特写"),
        "中景" to Regex("中近景|中景"),
        "全景" to Regex("全景|远景|大全景|建立镜头"),
        "近景" to Regex("近景"),
        "特写" to Regex("特写"),
    )

    /** 从视觉指令/动作里解析景别；解析不到返回 null。 */
    fun shotSizeOf(shot: AiStoryboardDirector.Shot): String? =
        shotSizeOf(listOfNotNull(shot.visualPrompt, shot.action).joinToString(" "))

    fun shotSizeOf(text: String?): String? {
        val s = text?.trim().orEmpty()
        if (s.isEmpty()) return null
        return SIZE_RULES.firstOrNull { it.second.containsMatchIn(s) }?.first
    }

    /**
     * 按镜头功能重排时长。输出保持 shotNo 升序；空列表原样返回。
     * 只在 AI 生成路径调用，人工编辑过的时长不会被覆盖。
     */
    fun refine(shots: List<AiStoryboardDirector.Shot>): List<AiStoryboardDirector.Shot> {
        if (shots.isEmpty()) return shots
        val ordered = shots.sortedBy { it.shotNo }
        var previousScene: String? = null
        val functions = ordered.mapIndexed { index, shot ->
            val scene = shot.sceneContext?.trim().orEmpty()
            val sceneChanged = index > 0 && scene.isNotBlank() && scene != previousScene
            if (scene.isNotBlank()) previousScene = scene
            when {
                index == 0 -> ShotFunction.HOOK
                sceneChanged -> ShotFunction.ESTABLISHING
                TRANSITION_WORDS.containsMatchIn(shot.action) -> ShotFunction.TRANSITION
                EMOTION_WORDS.containsMatchIn(shot.action) -> ShotFunction.EMOTION
                ACTION_WORDS.containsMatchIn(shot.action) -> ShotFunction.ACTION
                !shot.dialogue.isNullOrBlank() -> ShotFunction.DIALOGUE
                else -> ShotFunction.DEFAULT
            }
        }
        val durations = avoidMonotony(functions.map { durationFor(it) })
        return ordered.mapIndexed { index, shot -> shot.copy(durationSeconds = durations[index]) }
    }

    /** 连续三镜同长时给第三镜抬一档，避免匀速节奏。 */
    private fun avoidMonotony(input: List<Double>): List<Double> {
        if (input.size < 3) return input
        val out = input.toMutableList()
        for (i in 2 until out.size) {
            if (out[i] == out[i - 1] && out[i - 1] == out[i - 2]) {
                out[i] = if (out[i] + 1.0 <= MAX_SECONDS) out[i] + 1.0 else out[i] - 1.0
            }
        }
        return out
    }
}
