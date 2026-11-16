package com.dramafactory.core.quality

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AssetPromptEnricherTest {

    @Test
    fun clean_去掉代码块与前后空白() {
        assertEquals("a young han dynasty scholar in deep robe",
            AssetPromptEnricher.clean("```\na young han dynasty scholar in deep robe\n```"))
        assertEquals("a pottery jar on plain background",
            AssetPromptEnricher.clean("\"a pottery jar on plain background\""))
        assertEquals("single line", AssetPromptEnricher.clean("  single    line  "))
    }

    @Test
    fun enrich_正常返回英文视觉描述并清洗() = runTest {
        val fakeChat: suspend (String) -> String = { "```\nA weathered wooden gate tower, rammed-earth walls, Han dynasty\n```" }
        val result: String = AssetPromptEnricher.enrich(
            chat = fakeChat, kind = "scene", name = "城楼")
        assertTrue(result.contains("gate tower") && result.contains("rammed-earth"), "应保留模型产出的主体描述: $result")
        assertFalse(result.startsWith("```"), "不应残留代码块标记")
    }

    @Test
    fun enrich_LLM抛异常时回退裸词() = runTest {
        val boom: suspend (String) -> String = { throw IllegalStateException("401") }
        val result: String = AssetPromptEnricher.enrich(chat = boom, kind = "character", name = "王莽", fallback = "王莽")
        assertEquals("王莽", result, "chat 抛异常应安全回退裸词")
    }

    @Test
    fun enrich_LLM返回空时回退裸词() = runTest {
        val empty: suspend (String) -> String = { "" }
        val result: String = AssetPromptEnricher.enrich(chat = empty, kind = "prop", name = "简牍", fallback = "简牍")
        assertEquals("简牍", result)
    }

    @Test
    fun instruction_按类型注入不同侧重点() {
        val charInstr: String = AssetPromptEnricher.instruction("character")
        val sceneInstr: String = AssetPromptEnricher.instruction("scene")
        val propInstr: String = AssetPromptEnricher.instruction("prop")
        assertTrue(charInstr.contains("人物"), "角色指令应强调人物")
        assertTrue(sceneInstr.contains("空场") || sceneInstr.contains("建筑"), "场景指令应强调空场/建筑")
        assertTrue(propInstr.contains("器物") || propInstr.contains("形制"), "道具指令应强调器物形制")
    }

    @Test
    fun instruction_不再注入任何时代或禁词() {
        for (kind in listOf("character", "scene", "prop")) {
            val instr: String = AssetPromptEnricher.instruction(kind)
            assertTrue(instr.contains("剧本"), "[$kind] 年代风格应以剧本为唯一依据：$instr")
            assertFalse(instr.contains("现代元素"), "[$kind] 不应再禁现代元素")
            assertFalse(instr.contains("塑料"), "[$kind] 不应再透传时代禁词")
            assertFalse(instr.contains("古装"), "[$kind] 不应强加古装")
        }
    }

    // ---------- v1.9.11：禁止人物扩写出「手持道具」 ----------

    @Test
    fun instruction_人物扩写明确禁止持握表述() {
        val instr: String = AssetPromptEnricher.instruction("character")
        assertTrue(instr.contains("双手必须空置"), "应要求双手空置")
        assertTrue(instr.contains("holding") && instr.contains("in hand"), "应点名禁用 holding / in hand 表述")
    }

    @Test
    fun enrich_人物扩写含持握表述时回退裸词() = runTest {
        val holdingChat: suspend (String) -> String =
            { "A Han dynasty general in deep robe, holding a bronze sword, plain studio backdrop" }
        val result: String = AssetPromptEnricher.enrich(
            chat = holdingChat, kind = "character", name = "将军", fallback = "将军")
        assertEquals("将军", result, "人物扩写含 holding 应被拦截并回退裸词")
    }

    @Test
    fun enrich_人物扩写含中文持握词时回退裸词() = runTest {
        val cnHolding: suspend (String) -> String = { "一位汉代将军，身披甲胄，手持长剑，棚拍纯色背景" }
        val result: String = AssetPromptEnricher.enrich(
            chat = cnHolding, kind = "character", name = "将军", fallback = "将军")
        assertEquals("将军", result, "中文「手持」同样应被拦截")
    }

    @Test
    fun enrich_场景扩写不受人物持握拦截影响() = runTest {
        val sceneChat: suspend (String) -> String =
            { "A wooden gate tower with rammed-earth walls, overcast sky" }
        val result: String = AssetPromptEnricher.enrich(
            chat = sceneChat, kind = "scene", name = "城楼", fallback = "城楼")
        assertTrue(result.contains("gate tower"), "场景不应受人物持握拦截影响，实际: $result")
    }
}
