// v1.9.32：Agnes Video 2.5 提交参数修正回归测试
// 覆盖：size 按画面阶梯推导（flash 仍强制 720P）、aspect_ratio 六种白名单比例、
// negative_prompt 缺失时负向兜底句折入正向 prompt 且幂等（不重复拼接）。
package com.dramafactory.core

import com.dramafactory.core.model.VideoSubmitRequest
import com.dramafactory.core.pipeline.DefaultRateGate
import com.dramafactory.core.provider.AgnesProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class AgnesVideo25ParamsTest {

    private class MockApi {
        var lastBody: String = ""
        fun client(): HttpClient = HttpClient(MockEngine { req ->
            lastBody = (req.body as? io.ktor.http.content.OutgoingContent.ByteArrayContent)
                ?.bytes()?.decodeToString() ?: ""
            respond(
                """{"video_id":"vid-1","status":"queued"}""",
                HttpStatusCode.OK,
                headersOf("Content-Type" to listOf("application/json")),
            )
        })
    }

    private fun provider(model: String, api: MockApi) = AgnesProvider(
        rateGate = DefaultRateGate(0) {}, apiKeyProvider = { "sk-t" },
        client = api.client(), sleeper = {}, videoModelOverride = model,
    )

    private fun submit(model: String, width: Int, height: Int): String {
        val api = MockApi()
        runBlocking {
            provider(model, api).submitVideo(
                VideoSubmitRequest(shotId = "s1", prompt = "主角推门而入", width = width, height = height),
            )
        }
        return api.lastBody
    }

    @Test
    fun `25 size按画面短边阶梯推导 flash固定720P`() {
        // 归一后 704x1280：短边 704（≤720）→ 720P，竖屏 9:16
        val small = submit("agnes-video-2.5", 720, 1280)
        assertTrue(small.contains("\"size\":\"720P\""), small)
        assertTrue(small.contains("\"aspect_ratio\":\"9:16\""), small)

        // 归一后 768x960：短边 768（>720 且 <=960）→ 960P
        val mid = submit("agnes-video-2.5", 800, 1000)
        assertTrue(mid.contains("\"size\":\"960P\""), mid)

        // 归一后 1024x1920：短边 1024 > 960 → 2K，竖屏 9:16
        val hd = submit("agnes-video-2.5", 1080, 1920)
        assertTrue(hd.contains("\"size\":\"2K\""), hd)
        assertTrue(hd.contains("\"aspect_ratio\":\"9:16\""), hd)

        // 归一后 2176x3840：短边 2176 > 960 → 2K
        val uhd = submit("agnes-video-2.5", 2200, 3840)
        assertTrue(uhd.contains("\"size\":\"2K\""), uhd)

        // flash 仅支持 720P：即使传入 1080x1920 也不得升档（其他值会被网关 400）
        val flash = submit("agnes-video-2.5-flash", 1080, 1920)
        assertTrue(flash.contains("\"size\":\"720P\""), flash)
        assertFalse(flash.contains("\"size\":\"960P\""), flash)
    }

    @Test
    fun `25 aspect_ratio 六种白名单比例取最近值`() {
        assertEquals("9:16", AgnesProvider.video25AspectRatio(720, 1280))
        assertEquals("16:9", AgnesProvider.video25AspectRatio(1280, 720))
        assertEquals("1:1", AgnesProvider.video25AspectRatio(1000, 1000))
        assertEquals("3:4", AgnesProvider.video25AspectRatio(480, 640))
        assertEquals("4:3", AgnesProvider.video25AspectRatio(640, 480))
        assertEquals("21:9", AgnesProvider.video25AspectRatio(1280, 480))
        // 非法/超出白名单范围的比例回退竖屏默认
        assertEquals("9:16", AgnesProvider.video25AspectRatio(0, 0))
        assertEquals("9:16", AgnesProvider.video25AspectRatio(2000, 400))
    }

    @Test
    fun `25 负向兜底句折入正向prompt且不重复拼接`() {
        val fallback = AgnesProvider.VIDEO25_NEGATIVE_FALLBACK

        // 上游 prompt 已包含兜底句：不得再追加一次，也不得出现 negative_prompt 字段
        val api1 = MockApi()
        runBlocking {
            provider("agnes-video-2.5", api1).submitVideo(
                VideoSubmitRequest(
                    shotId = "s1", prompt = "主角推门而入，$fallback",
                    negativePrompt = "低质量, 变形, 多余手指",
                ),
            )
        }
        assertEquals(1, Regex(Regex.escape(fallback)).findAll(api1.lastBody).count(), api1.lastBody)
        assertFalse(api1.lastBody.contains("negative_prompt"), "2.5 协议无 negative_prompt 字段")

        // 正常路径：带负向词时恰好折入一次
        val api2 = MockApi()
        runBlocking {
            provider("agnes-video-2.5", api2).submitVideo(
                VideoSubmitRequest(shotId = "s2", prompt = "雨夜街头", negativePrompt = "低质量, 变形"),
            )
        }
        assertEquals(1, Regex(Regex.escape(fallback)).findAll(api2.lastBody).count(), api2.lastBody)

        // 无负向词：不注入兜底句
        val api3 = MockApi()
        runBlocking {
            provider("agnes-video-2.5", api3).submitVideo(
                VideoSubmitRequest(shotId = "s3", prompt = "雨夜街头"),
            )
        }
        assertEquals(0, Regex(Regex.escape(fallback)).findAll(api3.lastBody).count(), api3.lastBody)
    }
}
