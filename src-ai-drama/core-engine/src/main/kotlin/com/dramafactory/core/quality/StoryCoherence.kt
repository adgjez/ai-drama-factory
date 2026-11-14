package com.dramafactory.core.quality

/** 跨镜与故事连贯性确定性校验，保持与旧模型解耦。 */
object StoryCoherence {
    data class Context(val previousShotNo: Int? = null, val previousCarryOver: String? = null,
                       val activeAssetIds: Set<String> = emptySet())
    data class Issue(val code: String, val shotNo: Int, val message: String)

    fun validate(shots: List<AiStoryboardDirector.Shot>, approvedAssetIds: Set<String> = emptySet()): List<Issue> {
        val issues = mutableListOf<Issue>()
        var previous: AiStoryboardDirector.Shot? = null
        for (shot in shots.sortedBy { it.shotNo }) {
            if (previous != null && shot.shotNo != previous!!.shotNo + 1)
                issues += Issue("shot_order_gap", shot.shotNo, "镜号未连续")
            if (previous != null && shot.carryOver.isNullOrBlank())
                issues += Issue("carry_over_missing", shot.shotNo, "跨镜上下文缺少 carry_over")
            if (previous != null && previous.sceneContext?.isNotBlank() == true && shot.sceneContext.isNullOrBlank())
                issues += Issue("scene_context_missing", shot.shotNo, "跨镜缺少时间/天气/空间上下文")
            if (previous != null && environmentChangedWithoutTransition(previous, shot))
                issues += Issue("scene_context_conflict", shot.shotNo, "跨镜时间/天气/空间位置发生变化但缺少转场依据")
            if (approvedAssetIds.isNotEmpty() && shot.assetIds.any { it !in approvedAssetIds })
                issues += Issue("asset_unbound", shot.shotNo, "镜头包含未批准资产")
            if (shot.durationSeconds !in 4.0..10.0)
                issues += Issue("rhythm_duration", shot.shotNo, "单镜时长应为4-10秒")
            previous = shot
        }
        issues += designIssues(shots.sortedBy { it.shotNo })
        return issues
    }

    // ---------------------------------------------------------------------
    // 场景连续性判定（v2）
    // 旧实现把“室内/室外/灯光/明暗”等任意词面差异都当冲突，且只在 carry_over 里找转场词。
    // 结果：同一间办公室从「电脑屏幕蓝光」换成「手机屏幕光」、或旁白已交代时间流逝，也会误报
    // scene_context_conflict，而任一镜报错会整集中止渲染。这里改为结构化比较：
    //   1) 只比较真正影响连续性的事实：室内/室外、时间段、天气；
    //   2) 明暗/光源变化（灯光、屏幕光、台灯…）不再单独判冲突——同一场景内光线变化是正常拍法；
    //   3) 转场依据在前后镜的 action/dialogue/narration/carry_over 里找，不只看 carry_over。
    // ---------------------------------------------------------------------
    private data class EnvState(
        val indoor: Boolean,
        val outdoor: Boolean,
        val timeSlot: String?,
        val weather: Set<String>,
    )

    private val INDOOR = Regex("室内|屋内|房间内|办公室内|室内|门厅|堂屋|客厅|卧室|机房|车厢|电梯|店里|店里")
    private val OUTDOOR = Regex("室外|户外|门外|院门外|院内|院中|庭院|院子|街道|巷|野外|林中|雨中|雪中|天台|阳台|车外")

    private val WEATHER_RULES = listOf(
        "雨" to Regex("雨"),
        "雪" to Regex("雪"),
        "雾" to Regex("雾"),
        "晴" to Regex("晴|阳光明媚|阳光"),
        "阴" to Regex("阴天|阴云|阴沉|天色阴沉"),
    )

    private val TIME_SLOT_RULES = listOf(
        "清晨" to Regex("清晨|黎明|拂晓|早晨|上午"),
        "正午" to Regex("中午|正午|午间"),
        "下午" to Regex("下午|午后"),
        "傍晚" to Regex("傍晚|黄昏|夕阳|日落|天色渐暗"),
        "夜晚" to Regex("夜晚|晚上|深夜|夜里|入夜|夜色|雨夜|雪夜|夜"),
        "白天" to Regex("白天|日间|阳光明媚|天亮"),
    )

    private val TRANSITION = Regex("进入|离开|推门|开门|关门|转场|切换|来到|走进|走出|穿过|回到|转身|冲进|闯进|跨进|迈入")
    private val TIME_SHIFT = Regex("过了|随后数小时|天亮|次日|翌日|第二天|时间推移|多年后|不知不觉|一分一秒|到晚上|入夜|天色|夕阳|时间.*过去|许久|良久|过了一会儿")
    private val WEATHER_SHIFT = Regex("雨停|放晴|转晴|转阴|下起|天色|风起|乌云")

    private fun envStateOf(text: String): EnvState {
        val s = text.trim()
        return EnvState(
            indoor = INDOOR.containsMatchIn(s),
            outdoor = OUTDOOR.containsMatchIn(s),
            timeSlot = TIME_SLOT_RULES.firstOrNull { it.second.containsMatchIn(s) }?.first,
            weather = WEATHER_RULES.filter { it.second.containsMatchIn(s) }.map { it.first }.toSet(),
        )
    }

    private fun environmentChangedWithoutTransition(
        previous: AiStoryboardDirector.Shot,
        current: AiStoryboardDirector.Shot,
    ): Boolean {
        val beforeText = previous.sceneContext?.trim().orEmpty()
        val afterText = current.sceneContext?.trim().orEmpty()
        if (beforeText.isBlank() || afterText.isBlank() || beforeText == afterText) return false
        val before = envStateOf(beforeText)
        val after = envStateOf(afterText)

        val placeChanged = (before.indoor && after.outdoor) || (before.outdoor && after.indoor)
        val weatherChanged = before.weather.isNotEmpty() && after.weather.isNotEmpty() && before.weather != after.weather
        val timeChanged = before.timeSlot != null && after.timeSlot != null && before.timeSlot != after.timeSlot
        if (!placeChanged && !weatherChanged && !timeChanged) return false

        // 转场/时间推移依据：前后镜的动作、台词、旁白、承接都算；scene_context 自身不算（否则等于自证）。
        val evidence = listOfNotNull(
            previous.action, previous.dialogue, previous.narration, previous.carryOver,
            current.action, current.dialogue, current.narration, current.carryOver,
        ).joinToString(" ")
        if (TRANSITION.containsMatchIn(evidence)) return false
        if (timeChanged && !placeChanged && !weatherChanged && TIME_SHIFT.containsMatchIn(evidence)) return false
        if (weatherChanged && !placeChanged && WEATHER_SHIFT.containsMatchIn(evidence)) return false
        // 同一地点、同一时间段内的明暗/光源差异不判冲突。
        return true
    }

    // ---------------------------------------------------------------------
    // 分镜设计软提示（只提示、不自动修复）：
    //   rhythm_monotone      连续 3 镜以上同景别 → 空间层次丢失
    //   rhythm_flat          连续 4 镜以上同时长 → 成片匀速像 PPT
    //   establishing_missing 场次切换后的首镜不是全景/中景 → 空间没交代
    //   dialogue_pace        台词/旁白朗读时长超过镜头时长 → 成片会被加速或截断
    // 这些码不在 AiStoryboardDirector 的修复清单里，分镜页按软提示显示，不阻断渲染。
    // ---------------------------------------------------------------------
    private fun designIssues(shots: List<AiStoryboardDirector.Shot>): List<Issue> {
        if (shots.isEmpty()) return emptyList()
        val issues = mutableListOf<Issue>()

        var sizeStart = 0
        for (i in 1..shots.size) {
            val size = ShotDesigner.shotSizeOf(shots[i - 1])
            val nextSize = if (i < shots.size) ShotDesigner.shotSizeOf(shots[i]) else null
            val same = i < shots.size && size != null && size == nextSize
            if (!same) {
                val run = i - sizeStart
                if (run >= 3 && size != null)
                    issues += Issue("rhythm_monotone", shots[sizeStart].shotNo, "连续${run}镜同一景别（${size}），建议拉开景别层次")
                sizeStart = i
            }
        }

        var durationStart = 0
        for (i in 1..shots.size) {
            val same = i < shots.size && shots[i].durationSeconds == shots[i - 1].durationSeconds
            if (!same) {
                val run = i - durationStart
                if (run >= 4)
                    issues += Issue("rhythm_flat", shots[durationStart].shotNo, "连续${run}镜同为${shots[durationStart].durationSeconds}秒，节奏偏平")
                durationStart = i
            }
        }

        for (shot in shots) {
            val spoken = ShotDesigner.spokenSeconds(shot.dialogue, shot.narration)
            if (spoken > 0.0 && shot.durationSeconds > 0.0 && shot.durationSeconds < spoken - 0.8) {
                issues += Issue(
                    "dialogue_pace",
                    shot.shotNo,
                    "台词/旁白约" + String.format(java.util.Locale.US, "%.1f", spoken) +
                        "秒，镜头仅" + String.format(java.util.Locale.US, "%.1f", shot.durationSeconds) + "秒，建议加长或拆镜",
                )
            }
        }

        for (i in 1 until shots.size) {
            val before = envStateOf(shots[i - 1].sceneContext?.trim().orEmpty())
            val after = envStateOf(shots[i].sceneContext?.trim().orEmpty())
            if (!envChanged(before, after)) continue
            val size = ShotDesigner.shotSizeOf(shots[i])
            if (size == null || size == "特写" || size == "大特写" || size == "近景")
                issues += Issue("establishing_missing", shots[i].shotNo, "场次切换后首镜缺少全景/中景建立镜头")
        }
        return issues
    }

    private fun envChanged(before: EnvState, after: EnvState): Boolean {
        val placeChanged = (before.indoor && after.outdoor) || (before.outdoor && after.indoor)
        val weatherChanged = before.weather.isNotEmpty() && after.weather.isNotEmpty() && before.weather != after.weather
        val timeChanged = before.timeSlot != null && after.timeSlot != null && before.timeSlot != after.timeSlot
        return placeChanged || weatherChanged || timeChanged
    }
}