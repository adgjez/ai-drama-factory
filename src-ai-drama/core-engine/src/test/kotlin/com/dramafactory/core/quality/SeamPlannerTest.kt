package com.dramafactory.core.quality

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 第六批：接缝帧规划器单测。
 * 核心契约：同场相邻链在一起；跨场/场次号缺失/集尾一律硬切，不共享帧。
 */
class SeamPlannerTest {

    private fun refs(vararg pairs: Pair<Int, Int>) = pairs.map { SeamPlanner.ShotRef(it.first, it.second) }

    @Test
    fun plan_同场连续镜头链成接缝() {
        val links = SeamPlanner.plan(refs(1 to 1, 2 to 1, 3 to 1))
        assertEquals(3, links.size)
        assertEquals(2, links[0].lastToShotNo, "镜1尾帧应复用镜2首帧")
        assertEquals(3, links[1].lastToShotNo, "镜2尾帧应复用镜3首帧")
        assertNull(links[2].lastToShotNo, "集尾没有下一镜，不写尾帧")
        assertTrue(links[2].hardCutAfter)
        assertFalse(links[0].hardCutAfter)
        assertTrue(links[0].chainedToNext)
    }

    @Test
    fun plan_跨场硬切不共享接缝帧() {
        // 镜1/2 第一场，镜3/4 第二场
        val links = SeamPlanner.plan(refs(1 to 1, 2 to 1, 3 to 2, 4 to 2))
        assertEquals(2, links[0].lastToShotNo)
        assertNull(links[1].lastToShotNo, "场尾硬切：不能复用下一场首帧")
        assertTrue(links[1].hardCutAfter)
        assertEquals(4, links[2].lastToShotNo)
        assertNull(links[3].lastToShotNo)
    }

    @Test
    fun plan_单镜成场两端都硬切() {
        val links = SeamPlanner.plan(refs(1 to 1, 2 to 2, 3 to 3))
        assertTrue(links.all { it.lastToShotNo == null })
        assertTrue(links.all { it.hardCutAfter })
    }

    @Test
    fun plan_场次号缺失时不接力() {
        val links = SeamPlanner.plan(refs(1 to 0, 2 to 0, 3 to 1))
        assertNull(links[0].lastToShotNo, "sceneNo<=0 视为未知场，禁止跨镜接缝")
        assertNull(links[1].lastToShotNo)
        assertNull(links[2].lastToShotNo)
    }

    @Test
    fun plan_输入乱序时按镜号排序且场次判定正确() {
        val links = SeamPlanner.plan(refs(3 to 1, 1 to 1, 2 to 1))
        assertEquals(listOf(1, 2, 3), links.map { it.shotNo })
        assertEquals(2, links[0].lastToShotNo)
        assertEquals(3, links[1].lastToShotNo)
        assertNull(links[2].lastToShotNo)
    }

    @Test
    fun plan_空列表返回空() {
        assertTrue(SeamPlanner.plan(emptyList()).isEmpty())
        assertTrue(SeamPlanner.tailTargets(emptyList()).isEmpty())
    }

    @Test
    fun tailTargets_只给出有下一镜的镜头() {
        val map = SeamPlanner.tailTargets(refs(1 to 1, 2 to 1, 3 to 2))
        assertEquals(mapOf(1 to 2), map)
    }
}
