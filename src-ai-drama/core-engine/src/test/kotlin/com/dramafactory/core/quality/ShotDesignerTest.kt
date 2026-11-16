package com.dramafactory.core.quality

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ShotDesignerTest {

    private fun shot(
        no: Int,
        action: String,
        dialogue: String? = null,
        scene: String? = "夜晚，办公室内",
        visual: String? = null,
    ) = AiStoryboardDirector.Shot(
        shotNo = no, action = action, dialogue = dialogue,
        sceneContext = scene, visualPrompt = visual,
    )

    @Test
    fun refine_所有时长落在4到10秒且不再全等() {
        val shots = listOf(
            shot(1, "林见夕推门走进办公室"),
            shot(2, "她坐下敲键盘，手机放在桌角"),
            shot(3, "手机屏幕亮起，显示主人信息"),
            shot(4, "她猛地站起，冲过去夺过文件"),
            shot(5, "她凝视窗外，呼吸很轻"),
            shot(6, "她攥紧手机，沉默良久"),
        )
        val refined = ShotDesigner.refine(shots)
        assertEquals(6, refined.size)
        assertEquals(listOf(1, 2, 3, 4, 5, 6), refined.map { it.shotNo }, "输出必须保持镜号顺序")
        assertTrue(refined.all { it.durationSeconds in ShotDesigner.MIN_SECONDS..ShotDesigner.MAX_SECONDS },
            "所有时长必须落在 4~10 秒：${refined.map { it.durationSeconds }}")
        assertTrue(refined.map { it.durationSeconds }.distinct().size >= 3,
            "节奏设计后不应所有镜同长：${refined.map { it.durationSeconds }}")
        assertTrue(refined.first().durationSeconds <= 6.0, "开场钩子镜应偏短（<=6 秒）")
        val actionShot = refined.first { it.action.contains("冲过去") }
        val emotionShot = refined.first { it.action.contains("凝视") }
        assertTrue(actionShot.durationSeconds < emotionShot.durationSeconds,
            "动作镜(${actionShot.durationSeconds}s)应短于情绪停留镜(${emotionShot.durationSeconds}s)")
    }

    @Test
    fun refine_连续三镜同长会被打散() {
        // 全部是中性动作、无台词、同场景 → 初始分类都是 DEFAULT（6 秒），必须被 avoidMonotony 打散。
        val shots = (1..5).map { shot(it, "她坐在椅子里") }
        val durations = ShotDesigner.refine(shots).map { it.durationSeconds }
        assertTrue(durations != List(5) { 6.0 }, "连续同长必须被打散：$durations")
        for (i in 2 until durations.size) {
            assertTrue(!(durations[i] == durations[i - 1] && durations[i - 1] == durations[i - 2]),
                "不允许出现三镜同长：$durations")
        }
    }

    @Test
    fun refine_长台词镜头会被加长到容纳朗读时长() {
        val longLine = "时间一分一秒地过去，不知不觉已经到晚上，窗外最后一点天光也沉了下去，办公室里只剩下键盘的声音"
        val shots = listOf(shot(1, "她坐在办公桌前，看着屏幕", dialogue = longLine))
        val refined = ShotDesigner.refine(shots)
        val seconds = refined.single().durationSeconds
        val spoken = ShotDesigner.spokenSeconds(longLine)
        assertTrue(spoken >= 8.0, "长台词朗读时长应超过 8 秒：$spoken")
        assertTrue(seconds >= 8.0, "镜头时长必须容纳台词朗读（实际 ${seconds}s，朗读 ${spoken}s）")
        assertTrue(seconds <= ShotDesigner.MAX_SECONDS, "不能超过 10 秒上限")
    }

    @Test
    fun spokenSeconds_按中英文与空值估算() {
        assertEquals(0.0, ShotDesigner.spokenSeconds(null, "  "))
        val twentyFour = "一二三四五六七八九十一二三四五六七八九十一二三四"
        assertEquals(5.4, ShotDesigner.spokenSeconds(twentyFour), 0.01)
        assertTrue(ShotDesigner.spokenSeconds("Hello world") > 0.0)
        assertTrue(ShotDesigner.spokenSeconds(twentyFour, "Hello") > ShotDesigner.spokenSeconds(twentyFour))
    }

    @Test
    fun shotSizeOf_按顺序解析景别() {
        assertEquals("大特写", ShotDesigner.shotSizeOf("大特写，瞳孔收缩"))
        assertEquals("中景", ShotDesigner.shotSizeOf("中景缓推，人物居左"))
        assertEquals("中景", ShotDesigner.shotSizeOf("中近景过肩"))
        assertEquals("全景", ShotDesigner.shotSizeOf("大全景俯拍，交代空间"))
        assertEquals("近景", ShotDesigner.shotSizeOf("近景，平视"))
        assertEquals("特写", ShotDesigner.shotSizeOf("特写急推"))
        assertEquals(null, ShotDesigner.shotSizeOf("她走进房间"))
        val withVisual = shot(1, "她走进房间", visual = "全景，跟拍")
        assertEquals("全景", ShotDesigner.shotSizeOf(withVisual))
    }

    @Test
    fun rhythmCurve_给出逐镜强度与场次() {
        val shots = listOf(
            shot(1, "手机突然响起，她猛地站起", scene = "夜晚，办公室内").copy(sceneNo = 1, durationSeconds = 6.0),
            shot(2, "她坐下，凝视窗外", scene = "夜晚，办公室内").copy(sceneNo = 1, durationSeconds = 8.0),
            shot(3, "她走进院子", scene = "夜晚，院中").copy(sceneNo = 2, durationSeconds = 7.0),
        )
        val curve = ShotDesigner.rhythmCurve(shots)
        assertEquals(3, curve.size)
        assertEquals(listOf(1, 2, 3), curve.map { it.shotNo })
        assertEquals(listOf(1, 1, 2), curve.map { it.sceneNo })
        assertTrue(curve.all { it.intensity in 0.0..10.0 }, "强度必须落在 0~10：${curve.map { it.intensity }}")
        assertTrue(curve.first().intensity > curve[1].intensity, "开场钩子强度应高于情绪停留镜")
    }

    @Test
    fun refine_按场次号判定建立镜而非比较场景文本() {
        // 两场 scene_context 文本完全相同（室内），但 scene_no 变了：换场首镜应被当作建立镜（7 秒）。
        val shots = listOf(
            shot(1, "她坐在桌前敲键盘", scene = "夜晚，办公室内").copy(sceneNo = 1),
            shot(2, "她抬头看了一眼门口", scene = "夜晚，办公室内").copy(sceneNo = 2),
        )
        val refined = ShotDesigner.refine(shots)
        assertEquals(7.0, refined[1].durationSeconds, "换场首镜应按建立镜给 7 秒：${refined.map { it.durationSeconds }}")
    }
}
