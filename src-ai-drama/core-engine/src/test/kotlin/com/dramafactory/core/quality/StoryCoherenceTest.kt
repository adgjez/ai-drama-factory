package com.dramafactory.core.quality

import kotlin.test.Test
import kotlin.test.assertTrue

class StoryCoherenceTest {
    @Test fun detectsContextAssetAndRhythmBreaks() {
        val shots = listOf(
            AiStoryboardDirector.Shot(1, "开始", durationSeconds = 6.0, assetIds = listOf("a")),
            AiStoryboardDirector.Shot(3, "跳转", durationSeconds = 12.0, assetIds = listOf("bad")),
        )
        val codes = StoryCoherence.validate(shots, setOf("a")).map { it.code }
        assertTrue("shot_order_gap" in codes)
        assertTrue("carry_over_missing" in codes)
        assertTrue("asset_unbound" in codes)
        assertTrue("rhythm_duration" in codes)
    }

    @Test
    fun 雨夜进入室内不得无依据变成阳光明媚() {
        val shots = listOf(
            AiStoryboardDirector.Shot(1, "站在门外", durationSeconds = 6.0,
                sceneContext = "雨夜，院门外，昏暗湿滑"),
            AiStoryboardDirector.Shot(2, "站在门厅", durationSeconds = 6.0,
                carryOver = "她推门进入门厅，门外雨声仍在",
                sceneContext = "阳光明媚，室内门厅")
        )
        val codes = StoryCoherence.validate(shots).map { it.code }
        assertTrue("scene_context_conflict" !in codes, "有明确推门承接时允许空间/光线变化")

        val invalid = shots[1].copy(carryOver = "她站在门厅")
        assertTrue("scene_context_conflict" in StoryCoherence.validate(listOf(shots[0], invalid)).map { it.code })
    }

    @Test
    fun 旁白交代时间推移或同场景换光源不误报() {
        // 用户实际遇到的误报：同一间办公室，傍晚→夜晚已由旁白交代，电脑蓝光换成手机光不应再报冲突。
        val shots = listOf(
            AiStoryboardDirector.Shot(1, "女秘书敲键盘", durationSeconds = 8.0,
                narration = "时间一分一秒地过去，不知不觉已经到晚上。",
                sceneContext = "傍晚，办公室内，电脑屏幕蓝光映脸"),
            AiStoryboardDirector.Shot(2, "手机屏幕亮起", durationSeconds = 6.0,
                carryOver = "女秘书手持手机，屏幕显示信息，办公室环境不变",
                sceneContext = "夜晚，办公室内，手机屏幕亮光")
        )
        val codes = StoryCoherence.validate(shots).map { it.code }
        assertTrue("scene_context_conflict" !in codes, "旁白已明确交代时间流逝，不应误报")

        // 同一时间段、同一地点，只是光源从电脑屏换成手机屏：不是场景冲突。
        val sameTime = listOf(
            shots[0],
            shots[1].copy(sceneContext = "傍晚，办公室内，手机屏幕亮光")
        )
        assertTrue("scene_context_conflict" !in StoryCoherence.validate(sameTime).map { it.code },
            "同一场景换光源属于正常拍法，不应报冲突")
    }

    @Test
    fun 跨镜缺少场景上下文应报错() {
        val shots = listOf(
            AiStoryboardDirector.Shot(1, "站在门外", durationSeconds = 6.0, sceneContext = "雨夜，院门外，昏暗"),
            AiStoryboardDirector.Shot(2, "推门", durationSeconds = 6.0, carryOver = "她推门进入")
        )
        assertTrue("scene_context_missing" in StoryCoherence.validate(shots).map { it.code })
    }

    @Test
    fun 连续同景别与连续同时长给设计软提示() {
        // 4 镜都写「中景」且都是 6 秒：应同时给出 rhythm_monotone 与 rhythm_flat（软提示，不进自动修复）。
        val shots = (1..4).map {
            AiStoryboardDirector.Shot(it, "她在办公室里走动", durationSeconds = 6.0,
                carryOver = if (it == 1) null else "保持当前状态",
                sceneContext = "夜晚，办公室内", visualPrompt = "中景，平视")
        }
        val codes = StoryCoherence.validate(shots).map { it.code }
        assertTrue("rhythm_monotone" in codes, "连续同景别应给软提示，实际：$codes")
        assertTrue("rhythm_flat" in codes, "连续同时长应给软提示，实际：$codes")
    }

    @Test
    fun 场次切换后缺少建立镜头给设计软提示() {
        val shots = listOf(
            AiStoryboardDirector.Shot(1, "她在办公室收拾文件", durationSeconds = 6.0,
                sceneContext = "夜晚，办公室内", visualPrompt = "中景，平视"),
            AiStoryboardDirector.Shot(2, "她推门走到院子里", durationSeconds = 7.0,
                carryOver = "她推门离开办公室，走进院子",
                sceneContext = "夜晚，院门外，下着小雨", visualPrompt = "特写，仰拍"),
        )
        val codes = StoryCoherence.validate(shots).map { it.code }
        assertTrue("establishing_missing" in codes, "换场后首镜用特写应给建立镜头提示，实际：$codes")
    }
}