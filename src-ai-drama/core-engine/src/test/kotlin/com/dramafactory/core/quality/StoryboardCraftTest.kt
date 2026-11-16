package com.dramafactory.core.quality

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 第八批：分镜画面质检/兜底模板单测（纯确定性逻辑，不调网络）。 */
class StoryboardCraftTest {

    private fun shot(
        no: Int,
        action: String,
        visual: String? = null,
        scene: String? = null,
        carry: String? = null,
    ) = AiStoryboardDirector.Shot(
        shotNo = no,
        action = action,
        visualPrompt = visual,
        sceneContext = scene,
        carryOver = carry,
    )

    private val goodVisual = "中近景，平视略仰，人物居左占画幅三分之一，前景虚化的桌面边缘、背景百叶窗切出冷光；" +
        "主光来自右前方电脑屏，冷蓝偏青，浅景深；镜头极缓推近，轻微手持呼吸感；空气里浮着细尘。"

    @Test
    fun audit_动作混入镜头语言() {
        val codes = StoryboardCraft.audit(listOf(shot(1, "镜头推近床头台灯，画面聚焦凌乱床单", goodVisual)))
            .map { it.code }
        assertTrue(codes.contains("action_camera"), "应识别镜头语言混入动作：$codes")
    }

    @Test
    fun audit_情绪未外化() {
        val codes = StoryboardCraft.audit(listOf(shot(1, "她神情复杂地站在门口", goodVisual))).map { it.code }
        assertTrue(codes.contains("action_vague"), "应识别抽象情绪：$codes")
    }

    @Test
    fun audit_动作太短() {
        val codes = StoryboardCraft.audit(listOf(shot(1, "起身", goodVisual))).map { it.code }
        assertTrue(codes.contains("action_thin"), "应识别过短动作：$codes")
    }

    @Test
    fun audit_画面缺项() {
        val codes = StoryboardCraft.audit(listOf(shot(1, "她推门进入", "近景缓推"))).map { it.code }
        assertTrue(codes.contains("visual_thin"), "过短画面应提示：$codes")
        assertTrue(codes.contains("visual_no_light"), "缺光线应提示：$codes")
        assertTrue(codes.contains("visual_no_composition"), "缺构图应提示：$codes")
    }

    @Test
    fun audit_画面缺失() {
        val codes = StoryboardCraft.audit(listOf(shot(1, "她推门进入"))).map { it.code }
        assertTrue(codes.contains("visual_missing"), "无画面描述应提示：$codes")
    }

    @Test
    fun audit_场景过薄与承接写成剧情意图() {
        val codes = StoryboardCraft.audit(listOf(shot(1, "她推门进入", goodVisual, scene = "室内", carry = "准备起身去浴室")))
            .map { it.code }
        assertTrue(codes.contains("scene_thin"), "场景过薄应提示：$codes")
        assertTrue(codes.contains("carry_intent"), "承接写剧情意图应提示：$codes")
    }

    @Test
    fun fallbackVisual_满足自检且包含景别光线构图() {
        val s = shot(1, "她推门进入", scene = "深夜，办公室，主光来自右前方电脑屏，冷蓝色温")
        val visual = StoryboardCraft.fallbackVisual(s)
        assertTrue(visual.length >= 40, "兜底画面不能过短：$visual")
        val codes = StoryboardCraft.audit(listOf(s.copy(visualPrompt = visual))).map { it.code }
        assertTrue(codes.none { it.startsWith("visual_") }, "兜底画面不应触发画面缺项：$codes")
    }

    @Test
    fun parseCraft_解析润色结果并保留空字段() {
        val json = """{"shots":[{"shot_no":1,"action":"林晚合上电脑，屏幕蓝光暗下去","visual":"中景，平视，她居左，背景是熄屏的办公区，冷光收束；镜头静止，轻微呼吸感手持"},{"shot_no":2,"action":"","visual":""}]}"""
        val patches = AiStoryboardDirector.parseCraft(json)
        assertEquals(1, patches.size)
        assertEquals("林晚合上电脑，屏幕蓝光暗下去", patches[1]?.action)
        assertTrue(patches[1]?.visual?.contains("中景") == true)
    }

    @Test
    fun parseCraft_非JSON返回空() {
        assertTrue(AiStoryboardDirector.parseCraft("不是 JSON").isEmpty())
    }
}
