package com.dramafactory.core.assemble

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 第七批 T4 / P1-1 回归：字幕时间轴必须与 xfade 转场后的成片实际起点对齐。
 */
class SubtitleTimelineTest {

    private fun timing(durationMs: Long, text: String) = SubtitleTimeline.ShotTiming(durationMs, text)

    private fun cue(text: String, startMs: Long, endMs: Long) =
        MovieAssembler.SubtitleCue(text, startMs, endMs)

    @Test
    fun build_withoutTransition_matchesLegacyCumulative() {
        val cues = SubtitleTimeline.build(
            listOf(timing(5000, "A"), timing(4000, "B"), timing(3000, "C")),
            transitionSeconds = 0.0,
        )
        assertEquals(
            listOf(cue("A", 150, 4900), cue("B", 5150, 8900), cue("C", 9150, 11900)),
            cues,
        )
    }

    @Test
    fun build_withTransition_alignsToXfadeOffsets() {
        // T = 300ms：O = [0, 4700, 8400]（= Σd - i*T），而非简单累计 [0, 5000, 9000]
        val cues = SubtitleTimeline.build(
            listOf(timing(5000, "A"), timing(4000, "B"), timing(3000, "C")),
            transitionSeconds = 0.30,
        )
        assertEquals(
            listOf(cue("A", 150, 4900), cue("B", 4850, 8600), cue("C", 8550, 11300)),
            cues,
        )
    }

    @Test
    fun build_skipsBlankText() {
        val cues = SubtitleTimeline.build(
            listOf(timing(2000, ""), timing(3000, "  "), timing(2000, "B")),
            transitionSeconds = 0.0,
        )
        // 空文本镜仍推进时间轴：B 的起点 = 2000+3000，start = 5000+150
        assertEquals(listOf(cue("B", 5150, 6900)), cues)
    }

    @Test
    fun build_extendsTooShortCueToMinimumVisibility() {
        // 100ms 镜：end = O + d - 100 = 0 <= start + 300 → 撑到 450
        val cues = SubtitleTimeline.build(listOf(timing(100, "X")), transitionSeconds = 0.0)
        assertEquals(listOf(cue("X", 150, 450)), cues)
    }
}
