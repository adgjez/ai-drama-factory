package com.dramafactory.core.quality

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RenderPreflightTest {

    private fun shot(
        no: Int,
        id: String = "s$no",
        action: String = "她抬头看向门口",
        dialogue: String? = null,
        narration: String? = null,
        duration: Double = 6.0,
        first: String? = "http://x/$no.png",
        last: String? = null,
        assets: List<String> = emptyList(),
        check: String? = "pass",
        sceneNo: Int = 1,
    ) = RenderPreflight.ShotInput(
        shotNo = no, shotId = id, action = action, dialogue = dialogue, narration = narration,
        durationSeconds = duration, firstImageUri = first, lastImageUri = last,
        boundAssetIds = assets, sbCheck = check, sceneNo = sceneNo,
    )

    @Test
    fun all_green_is_ok() {
        val r = RenderPreflight.check(listOf(shot(1), shot(2, last = "http://x/2b.png")))
        assertTrue(r.ok)
        assertEquals(2, r.total)
        assertEquals(2, r.renderable)
        assertEquals(0, r.blockedShots)
        assertTrue(r.warnings.isEmpty())
        assertTrue(r.summary().contains("可渲 2/2"))
    }

    @Test
    fun empty_prompt_blocks() {
        val r = RenderPreflight.check(listOf(shot(1, action = "", dialogue = " ", narration = null)))
        assertFalse(r.ok)
        assertEquals(1, r.blockedShots)
        assertEquals("empty_prompt", r.blockers.single().code)
        assertEquals(0, r.renderable)
    }

    @Test
    fun storyboard_error_blocks_with_reason() {
        val r = RenderPreflight.check(listOf(shot(1, check = "error:scene_context_missing")))
        assertEquals("storyboard_blocked", r.blockers.single().code)
        assertTrue(r.blockers.single().message.contains("scene_context_missing"))
    }

    @Test
    fun rejected_and_unknown_assets_block() {
        val known = setOf("a1", "a2")
        val rejected = setOf("a2")
        val r = RenderPreflight.check(
            listOf(
                shot(1, assets = listOf("a1")),
                shot(2, assets = listOf("a2")),
                shot(3, assets = listOf("fake_9")),
            ),
            knownAssetIds = known,
            rejectedAssetIds = rejected,
        )
        assertEquals(listOf("asset_rejected"), r.issues.filter { it.shotNo == 2 }.map { it.code })
        assertEquals(listOf("asset_unknown"), r.issues.filter { it.shotNo == 3 }.map { it.code })
        assertEquals(1, r.renderable)
        assertEquals(2, r.blockedShots)
    }

    @Test
    fun unknown_assets_skipped_when_catalog_unavailable() {
        val r = RenderPreflight.check(listOf(shot(1, assets = listOf("whatever"))))
        assertTrue(r.ok)
    }

    @Test
    fun duration_and_missing_keyframe_are_warnings_only() {
        val r = RenderPreflight.check(listOf(shot(1, duration = 0.0, first = null), shot(2)))
        assertTrue(r.ok)
        assertEquals(1, r.warningShots)
        val codes = r.warnings.map { it.code }.toSet()
        assertEquals(setOf("duration_missing", "no_keyframe"), codes)
        assertEquals(2, r.renderable)
    }

    @Test
    fun budget_shortfall_reports_but_does_not_block() {
        val r = RenderPreflight.check(listOf(shot(1), shot(2), shot(3)), budgetRemaining = 2)
        assertTrue(r.ok)
        assertEquals("budget_shortfall", r.issues.last().code)
        assertTrue(r.issues.last().message.contains("2"))
    }

    @Test
    fun blocked_shot_not_double_counted_as_warning() {
        val r = RenderPreflight.check(listOf(shot(1, action = "", duration = 0.0)))
        assertEquals(1, r.blockedShots)
        assertEquals(0, r.warningShots)
        assertTrue(r.warnings.isEmpty())
    }
}
