package com.dramafactory.core.assemble

import kotlin.math.roundToLong

/**
 * 成片字幕时间轴（第七批 T4 / P1-1 修复）：按 xfade 转场对齐。
 *
 * 背景：MovieAssemblerImpl.renderXfade 的链式 xfade offset 递推是
 *   O1 = d0 - T；O_i = Σ_{k<i} d_k - i*T   （T = 转场秒数）
 * 即第 i 镜在成片里的真实起点比简单累计 [Σd] 提前 i*T。
 * 旧实现（AppGraph.composeFilmIfReady / LibraryPage 手动合成）都用 [cursorMs + 150] 起算，
 * 第 i 镜字幕晚 i*T+150ms（10 镜 0.3s 转场约错 3.15s）。
 *
 * 本类把「镜头时长 → 字幕 cue」的唯一口径收敛到一处：SRT 侧与预览侧共用，
 * 禁止再在 UI 层手写累加公式。
 */
object SubtitleTimeline {

    /** 单镜时长与字幕文本；[durationMs] 为该镜在成片时间轴上的占位时长。 */
    data class ShotTiming(val durationMs: Long, val text: String)

    /** 字幕最短可见时长（end <= start + MIN_CUE_MS 时把 end 撑到 start + MIN_CUE_MS）。 */
    const val MIN_CUE_MS: Long = 300L

    /**
     * 构建字幕 cue 列表（输出顺序与 [shots] 输入顺序一致，空文本镜跳过）。
     *
     * @param shots 按 shot_no 升序的镜头时长/文本
     * @param transitionSeconds 成片实际使用的 xfade 转场秒数（NONE 传 0.0）
     * @param leadMs 字幕相对镜头的提前量（旧口径 +150ms 保持不变）
     * @param tailTrimMs 字幕相对镜尾的收缩量（旧口径 -100ms 保持不变）
     */
    fun build(
        shots: List<ShotTiming>,
        transitionSeconds: Double = 0.0,
        leadMs: Long = 150L,
        tailTrimMs: Long = 100L,
    ): List<MovieAssembler.SubtitleCue> {
        if (shots.isEmpty()) return emptyList()
        // T = round(transitionSeconds * 1000)；与 xfade offset 的秒级递推保持同一取整口径
        val transitionMs = (transitionSeconds * 1000.0).roundToLong()
        var consumedMs = 0L
        val cues = ArrayList<MovieAssembler.SubtitleCue>(shots.size)
        shots.forEachIndexed { index, shot ->
            // 第 i 镜起点 O_i = Σ_{j<i} d_j − i*T（与 xfade 链式 offset 递推严格对齐）
            val originMs = consumedMs - index.toLong() * transitionMs
            if (shot.text.isNotBlank()) {
                val startMs = (originMs + leadMs).coerceAtLeast(0L)
                val endMs = (originMs + shot.durationMs - tailTrimMs)
                    .coerceAtLeast(startMs + MIN_CUE_MS)
                cues += MovieAssembler.SubtitleCue(shot.text, startMs, endMs)
            }
            consumedMs += shot.durationMs
        }
        return cues
    }
}
