package com.dramafactory.core.quality

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** v1.9.46 回归：时代约束已从生成链路删除，正向与负向都不再注入朝代内容。 */
class NegativeSeparationTest {
    private val preset = StylePreset.HAN_DEFAULT

    @Test
    fun 时代禁词表恒为空() {
        assertTrue(preset.forbiddenEraTerms.isEmpty(), "forbiddenEraTerms 应恒为空")
    }

    @Test
    fun 正面prompt不含任何时代内容() {
        val p = preset.withEraConstraints("女主·冷艳·黑长直")
        assertFalse(p.contains("西汉"), "正面不应注入朝代 positive：$p")
        assertFalse(p.contains("深衣"), "正面不应注入汉代服饰红线：$p")
        assertFalse(p.contains("木构与夯土建筑"), "正面不应注入建筑红线：$p")
    }

    @Test
    fun negativePromptFor只含通用负向() {
        val neg = preset.negativePromptFor()
        assertTrue(neg.isNotBlank(), "画质/一致性类通用负向应保留")
        assertFalse(neg.contains("手机"), "时代禁词不应折叠进 negative：$neg")
        assertFalse(neg.contains("塑料"), "时代禁词不应折叠进 negative：$neg")
    }

    @Test
    fun 放行项不再影响negative() {
        val neg = preset.negativePromptFor(allowed = listOf("游标卡尺"))
        assertFalse(neg.contains("手机"), "时代禁词已移除，放行表自然失效")
        assertFalse(neg.contains("游标卡尺"))
    }

    @Test
    fun 正面只追加通用样式suffix() {
        val p = preset.withEraConstraints("书斋夜读")
        assertTrue(p.startsWith("书斋夜读"))
        assertTrue(p.contains(StylePreset.DEFAULT_GLOBAL_SUFFIX), "应追加通用 suffix")
        assertFalse(p.contains("西汉"))
    }
}
