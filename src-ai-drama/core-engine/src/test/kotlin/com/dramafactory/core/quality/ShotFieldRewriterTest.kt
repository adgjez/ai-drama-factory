package com.dramafactory.core.quality

import com.dramafactory.core.model.ChatRequest
import com.dramafactory.core.model.ChatResponse
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 第四批：单镜单字段 AI 重写的解析/校验回归 */
class ShotFieldRewriterTest {

    private val ctx = ShotFieldRewriter.ShotContext(
        shotNo = 2,
        action = "女秘书坐在电脑前敲键盘",
        dialogue = "主人：在哪",
        visualPrompt = "中景缓推，女秘书居左",
        sceneContext = "傍晚·办公室·室内",
        carryOver = "女秘书仍在电脑前",
        prevAction = "女秘书抬头看向手机",
        nextAction = "女秘书点开手机屏幕",
    )

    private fun chatReturning(content: String): suspend (ChatRequest) -> ChatResponse =
        { _: ChatRequest -> ChatResponse(content, content) }

    @Test
    fun 重写动作走严格JSON并去掉引号() = runBlocking {
        withTimeout(5_000) {
            val out = ShotFieldRewriter.rewrite(
                ShotFieldRewriter.FIELD_ACTION, ctx,
                chat = chatReturning("{\"value\":\"女秘书抬头看向手机，神情焦虑\"}"),
            )
            assertEquals("女秘书抬头看向手机，神情焦虑", out)
        }
    }

    @Test
    fun 动作里出现镜头语言会被拒绝() = runBlocking {
        withTimeout(5_000) {
            val out = ShotFieldRewriter.rewrite(
                ShotFieldRewriter.FIELD_ACTION, ctx,
                chat = chatReturning("{\"value\":\"中景缓推，女秘书抬头看向手机\"}"),
            )
            assertNull(out, "动作字段不能混入镜头语言，应拒绝")
        }
    }

    @Test
    fun 台词不允许AI重写且不发起请求() = runBlocking {
        withTimeout(5_000) {
            var called = false
            val out = ShotFieldRewriter.rewrite("dialogue", ctx, chat = { req: ChatRequest ->
                called = true; ChatResponse("{}", "{}")
            })
            assertNull(out)
            assertTrue(!called, "dialogue 不是可重写字段，不应请求模型")
        }
    }

    @Test
    fun 代码块包裹的JSON也能解析() {
        val raw = "\u0060\u0060\u0060json\n{\"value\":\"深夜办公室，只亮着电脑屏幕的光\"}\n\u0060\u0060\u0060"
        assertEquals("深夜办公室，只亮着电脑屏幕的光", ShotFieldRewriter.extractValue(raw))
    }

    @Test
    fun 模型直接输出纯文本时走兜底() {
        assertEquals("深夜办公室，只亮着电脑屏幕的光", ShotFieldRewriter.extractValue("深夜办公室，只亮着电脑屏幕的光"))
    }

    @Test
    fun 旁白里出现引号对白会被拒绝() {
        assertNull(ShotFieldRewriter.sanitize(ShotFieldRewriter.FIELD_NARRATION, "她说「主人，我回来了」"))
        assertEquals("时间一分一秒过去", ShotFieldRewriter.sanitize(ShotFieldRewriter.FIELD_NARRATION, "时间一分一秒过去"))
    }

    @Test
    fun 画面描述过短会被拒绝() {
        assertNull(ShotFieldRewriter.sanitize(ShotFieldRewriter.FIELD_VISUAL, "特写"))
        assertEquals("中景缓推，女秘书居左，电脑蓝光映脸", ShotFieldRewriter.sanitize(ShotFieldRewriter.FIELD_VISUAL, "中景缓推，女秘书居左，电脑蓝光映脸"))
    }
}
