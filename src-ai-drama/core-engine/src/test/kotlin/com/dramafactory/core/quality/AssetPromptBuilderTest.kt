package com.dramafactory.core.quality

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertEquals

/**
 * v1.7.17 回归单测：资产出图 prompt 组装。
 *
 * 背景：角色卡一直有背景干扰，根因是角色资产也套了 globalPromptSuffix
 * （cinematic / shallow depth of field / vertical 9:16 framing），
 * 这些场景化语义逼模型补环境，与「纯色无干扰背景」正面对冲。
 */
class AssetPromptBuilderTest {

    private val preset = StylePreset()

    @Test fun `角色 prompt 丢弃场景化 suffix`() {
        val p = AssetPromptBuilder.finalPrompt(preset, "character", "张角，灰袍道长")
        assertFalse(p.contains("vertical 9:16 framing"), "竖屏构图会逼模型补环境，角色卡不该要")
        assertFalse(p.contains("cinematic"), "cinematic 打光与纯色棚拍底冲突")
        assertFalse(p.contains("shallow depth of field"), "景深虚化与干净底冲突")
    }

    @Test fun `角色 prompt 带棚拍 suffix 与纯色背景指令`() {
        val p = AssetPromptBuilder.finalPrompt(preset, "character", "张角")
        assertTrue(p.contains(preset.characterStudioSuffix))
        assertTrue(p.contains("plain solid color background"))
        assertTrue(p.contains("isolated subject"))
    }

    /**
     * v1.7.21 修正：旧断言写反了（要求 redline > backdrop，等于把背景指令埋在 100+ 禁词之前）。
     * 图像模型对 prompt **尾部**权重最高，最关键的一条约束必须在最末尾。
     */
    @Test fun `尾部强指令排在最末尾，高于禁词与质量负向`() {
        for (kind in listOf("character", "scene", "prop")) {
            val p = AssetPromptBuilder.finalPrompt(preset, kind, "测试")
            val redline = p.indexOf("Do NOT include")
            val quality = p.indexOf("Negative prompt (soft)")
            val anchor = p.indexOf("【画面要求·最高优先级】")
            assertTrue(redline > 0, "$kind 应有禁词段")
            assertTrue(quality > redline, "$kind 质量负向应在禁词之后")
            assertTrue(anchor > quality, "$kind 尾部强指令必须在质量负向之后（模型尾部权重最高）")
            // v1.9.11：原断言为「anchor 位于末尾 400 字符内」，但角色尾部强指令加了中英空手指令后
            // 长度自然超过 400，硬编码距离会误报。改用 endsWith——语义更严格且不受长度影响。
            val anchorText = preset.tailAnchorFor(kind)
            assertTrue(p.trimEnd().endsWith(anchorText.trimEnd()),
                "$kind 尾部强指令必须紧贴 prompt 末尾（以它结尾）")
        }
    }

    @Test fun `尾部强指令内容按类型区分`() {
        val c = AssetPromptBuilder.finalPrompt(preset, "character", "张角")
        assertTrue(c.contains("BACKGROUND REQUIREMENT (HIGHEST PRIORITY)"))
        assertTrue(c.contains("solid white background"), "角色尾部要锁死纯色底")

        val s = AssetPromptBuilder.finalPrompt(preset, "scene", "破庙内景")
        assertTrue(s.contains("EMPTY SCENE REQUIREMENT (HIGHEST PRIORITY)"))
        assertTrue(s.contains("no people"), "场景尾部要锁死无人")

        val p = AssetPromptBuilder.finalPrompt(preset, "prop", "墨玉书简")
        assertTrue(p.contains("PROP SHOT REQUIREMENT (HIGHEST PRIORITY)"))
        assertTrue(p.contains("no hands"), "道具尾部要锁死无手")
    }

    /**
     * v1.7.21：禁词膨胀回归闸。
     * 旧版三类卡均塞了 100+ 项（含 gun / train / refrigerator / skateboard 等无关项），
     * 真正管用的 scene background / environment / people 被稀释成路人，约束等于失效。
     */
    @Test fun `禁词精简，不把完整时代红线塞进图像 prompt`() {
        for (kind in listOf("character", "scene", "prop")) {
            val p = AssetPromptBuilder.finalPrompt(preset, kind, "测试")
            val notInclude = p.substringAfter("Do NOT include:", "").substringBefore("Negative prompt (soft):")
            val items = notInclude.trim().trimEnd('.').split(",").map { it.trim() }.filter { it.isNotEmpty() }
            // v1.9.11：45 → 55。角色补入「持握类」禁词（holding / in hand 等）以治「角色手持道具」，
            // v1.7.21 反稀释的本意是别把 150+ 完整红线塞进来，55 仍远低于该量级。
            assertTrue(items.size <= 55, "$kind 禁词应精简（实际 ${items.size} 项）：$items")
            assertFalse(p.contains("refrigerator"), "$kind 不该出现与资产外观无关的时代禁词")
            assertFalse(p.contains("skateboard"))
            assertFalse(p.contains("airplane"))
        }
        // v1.9.46：时代红线已从生成链路移除，视频端 negative 也不再折叠时代禁词
        assertFalse(preset.sceneNegativePromptFor().contains("手机"), "场景负向不应再含时代禁词")
        assertFalse(preset.studioNegativePromptFor().contains("smartphone"))
    }

    @Test fun `角色 prompt 不注入时代红线`() {
        val p = AssetPromptBuilder.finalPrompt(preset, "character", "张角")
        assertFalse(p.contains("西汉"), "不应注入朝代 positive")
        assertFalse(p.contains("深衣"), "不应注入汉代服饰红线")
        assertFalse(p.contains("木构与夯土建筑"), "不应注入建筑诱导词")
        assertTrue(p.contains("plain solid color background"), "棚拍纯色底约束仍保留")
    }

    @Test fun `场景与道具不受棚拍约束影响`() {
        val scene = AssetPromptBuilder.finalPrompt(preset, "scene", "破庙内景")
        assertTrue(scene.contains("cinematic"), "场景本就该有电影感")
        assertFalse(scene.contains(preset.studioBackdropPositive), "场景不该被要求纯色底")

        val prop = AssetPromptBuilder.finalPrompt(preset, "prop", "墨玉书简")
        assertFalse(prop.contains(preset.studioBackdropPositive))
    }

    @Test fun `场景卡禁人物，避免模型往空景塞人`() {
        val scene = AssetPromptBuilder.finalPrompt(preset, "scene", "破庙内景")
        assertTrue(scene.contains("no people"), "场景必须显式要求无人")
        assertTrue(scene.contains("empty location"), "要求空场")
        val redline = scene.substringAfter("Do NOT include", "")
        assertTrue(redline.contains("people"), "人物禁词须并入 Do NOT include（图像端无 negative 字段）")
        assertTrue(redline.contains("person"))
        assertTrue(redline.contains("crowd"))
    }

    @Test fun `道具卡纯色底且去掉环境化 suffix`() {
        val prop = AssetPromptBuilder.finalPrompt(preset, "prop", "墨玉书简")
        assertTrue(prop.contains("plain solid color background"), "道具要纯色底")
        assertTrue(prop.contains("isolated single object"), "道具要孤立单品，不带环境")
        assertFalse(prop.contains("cinematic"), "cinematic 打光会逼模型补环境")
        assertFalse(prop.contains("vertical 9:16 framing"), "竖屏构图与产品图式冲突")
        assertFalse(prop.contains("木构与夯土建筑"), "道具不该被要求描绘建筑")
        val redline = prop.substringAfter("Do NOT include", "")
        assertTrue(redline.contains("environment"), "环境禁词须并入正向")
        assertTrue(redline.contains("person"), "道具图不该出现人")
    }

    @Test fun `禁词只并英文，中文禁词不进正向`() {
        val p = AssetPromptBuilder.finalPrompt(preset, "character", "张角")
        assertTrue(p.contains("Do NOT include"), "英文禁词应并入正向（图像端无 negative_prompt 字段）")
        assertFalse(p.contains("手机"), "中文禁词并入正向只会稀释权重")
        assertTrue(p.contains("holding"), "类型专用英文禁词（持握类）仍应并入")
    }

    /**
     * v1.9.46：时代红线整体删除——三类资产卡都不再注入任何朝代/形制内容，
     * 建筑、服饰、器物风格完全由剧本与资产描述本身决定。
     */
    @Test fun `三类资产卡都不注入时代红线`() {
        for (kind in listOf("character", "scene", "prop")) {
            val p = AssetPromptBuilder.finalPrompt(preset, kind, "测试")
            assertFalse(p.contains("西汉"), "[$kind] 不应注入朝代名")
            assertFalse(p.contains("深衣"), "[$kind] 不应注入汉代服饰红线")
            assertFalse(p.contains("木构与夯土建筑"), "[$kind] 不应注入建筑红线")
            assertFalse(p.contains("简牍竹简"), "[$kind] 不应注入汉代器物红线")
            assertFalse(p.contains("火烛照明"), "[$kind] 不应注入时代照明")
        }
    }

    @Test fun `constrained 版本不含禁词与质量负向`() {
        val c = AssetPromptBuilder.constrained(preset, "character", "张角")
        assertFalse(c.contains("Do NOT include"))
        assertFalse(c.contains("Negative prompt (soft)"))
        assertTrue(c.contains("张角"))
    }

    @Test fun `sizeFor 按类型给画幅`() {
        assertEquals("1024x1024", AssetPromptBuilder.sizeFor(preset, "character"))
        assertEquals("1024x768", AssetPromptBuilder.sizeFor(preset, "scene"))
        assertEquals("768x768", AssetPromptBuilder.sizeFor(preset, "prop"))
        assertEquals("1024x1024", AssetPromptBuilder.sizeFor(preset, "local"))
        assertEquals("1024x1024", AssetPromptBuilder.sizeFor(preset, "CHARACTER"), "大小写不敏感")
    }

    // ---------- v1.9.46：各时代预设都不再注入时代红线 ----------

    @Test fun `各时代预设资产卡都不注入时代红线`() {
        for (key in listOf("han", "tang", "modern")) {
            val pr = EraDetector.presetFor(key)
            for (kind in listOf("character", "scene", "prop")) {
                val p = AssetPromptBuilder.finalPrompt(pr, kind, "测试")
                assertFalse(p.contains("西汉") || p.contains("盛唐") || p.contains("当代"),
                    "[$key/$kind] 不应注入时代名：$p")
                assertFalse(p.contains("木构与夯土建筑"), "[$key/$kind] 不应注入建筑红线")
                assertFalse(p.contains("深衣"), "[$key/$kind] 不应注入服饰红线")
            }
        }
        val scene = AssetPromptBuilder.finalPrompt(EraDetector.presetFor("modern"), "scene", "写字楼大厅")
        assertTrue(scene.contains("no people"), "空场无人约束保留")
        assertTrue(AssetPromptBuilder.finalPrompt(EraDetector.presetFor("han"), "prop", "墨玉书简")
            .contains("plain solid color background"), "道具纯色底约束保留")
    }
}
