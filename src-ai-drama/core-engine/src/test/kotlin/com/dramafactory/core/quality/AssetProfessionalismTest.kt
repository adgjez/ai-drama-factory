package com.dramafactory.core.quality

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 资产提取专业化（影视美术拆解口径 / 别名去重 / 现代题材解除古装锁定）回归用例 */
class AssetProfessionalismTest {

    // ---------- 提取：影视美术字段与别名 ----------

    @Test
    fun `parseJson 合并 costume 与时段进外观描述`() {
        val assets = LlmAssetExtractor.parseJson(
            """{"characters":[{"name":"林见夕·集团总裁","desc":"黑长直发，冷白皮，鹅蛋脸","costume":"黑色高定西装，真丝白衬衫，细高跟","aliases":["女总裁","林总"]}],
                "scenes":[{"name":"顶层办公室·夜·内","desc":"落地窗，深色胡桃木办公桌","time_of_day":"夜间，室内暖光","aliases":["办公室"]}],
                "props":[]}"""
        )
        assertEquals(2, assets.size)
        val c = assets.single { it.kind == "character" }
        assertTrue(c.desc.contains("黑色高定西装"), "costume 应并入角色外观：" + c.desc)
        assertTrue(c.aliases.contains("林总"), "别名应解析：" + c.aliases)
        val s = assets.single { it.kind == "scene" }
        assertTrue(s.desc.contains("夜间"), "时段应并入场景描述：" + s.desc)
    }

    @Test
    fun `parseJson 同一实体的不同称呼只保留一张卡`() {
        val assets = LlmAssetExtractor.parseJson(
            """{"characters":[{"name":"女秘书","desc":"职业装女性，齐肩短发","aliases":["小满","秘书"]},
                               {"name":"小满","desc":"女秘书，职业装女性","aliases":["女秘书"]}],
                "scenes":[],"props":[]}"""
        )
        assertEquals(1, assets.size, "别名命中应合并为一张卡：" + assets)
    }

    @Test
    fun `merge 别名感知且用剧本正则补漏`() {
        val merged = LlmAssetExtractor.merge(
            llmAssets = listOf(LlmAssetExtractor.Asset("character", "女秘书", "职业装", listOf("小满"))),
            regexNames = listOf(
                Triple("character", "小满", Unit),
                Triple("character", "前台小姐", Unit),
                Triple("prop", "手机", Unit),
            ),
        )
        assertEquals(3, merged.size, "别名小满不应重复成卡，缺的前台小姐/手机应补漏：" + merged)
        assertTrue(merged.any { it.name == "前台小姐" })
        assertTrue(merged.any { it.kind == "prop" && it.name == "手机" })
    }

    @Test
    fun `cardPrompt 统一为 name 冒号 desc`() {
        val a = LlmAssetExtractor.Asset("character", "女秘书·集团秘书", "职业装女性，齐肩短发，黑框眼镜")
        assertEquals("女秘书·集团秘书：职业装女性，齐肩短发，黑框眼镜", LlmAssetExtractor.cardPrompt(a))
        assertEquals("手机", LlmAssetExtractor.cardPrompt(LlmAssetExtractor.Asset("prop", "手机", "")))
    }

    // ---------- 扩写：现代题材不再强行古装 ----------

    @Test
    fun `instruction 现代题材解除禁现代元素并保留当代器物`() {
        val instr: String = AssetPromptEnricher.instruction(
            "character", "当代都市（2020年代）", emptyList(), modernAllowed = true)
        assertTrue(instr.contains("保留"), "现代题材应要求保留当代服饰器物：" + instr)
        assertTrue(instr.contains("手机") && instr.contains("办公室"), "应点名当代器物")
        assertTrue(instr.contains("不要强行添加古装"), "应明确不要强加古装")
        assertFalse(instr.contains("严禁任何现代元素"), "现代题材不应再禁现代元素")
    }

    @Test
    fun `instruction 历史题材默认仍禁现代元素`() {
        val instr: String = AssetPromptEnricher.instruction("scene", "西汉", emptyList())
        assertTrue(instr.contains("严禁任何现代元素"), "历史题材默认必须禁现代元素")
        assertFalse(instr.contains("不要强行添加古装"))
    }

    @Test
    fun `enrich 现代题材把现代指令透传给文本模型`() = runTest {
        var seen = ""
        val fakeChat: suspend (String) -> String = { msg ->
            seen = msg
            "A 32-year-old female CEO in a tailored black suit, shoulder-length hair"
        }
        val result: String = AssetPromptEnricher.enrich(
            chat = fakeChat, kind = "character", name = "林见夕·集团总裁",
            eraLabel = "当代都市（2020年代）", modernAllowed = true, fallback = "林见夕·集团总裁")
        assertTrue(seen.contains("不要强行添加古装"), "发给模型的指令应带现代题材约束")
        assertTrue(result.contains("CEO"), "应保留模型产出的描述：" + result)
    }
}
