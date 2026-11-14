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
}
