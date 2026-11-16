package com.dramafactory.core.assemble

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * MovieAssembler 三级策略 JVM 单测（T014 §2.4）。
 *
 * 注入桩 [StubExecutor] 绕过真实 ffmpeg，只测试策略选择、降级链与命令组装。
 * 覆盖：
 *   1) concat-copy 成功 → Success(CONCAT_COPY)
 *   2) concat 失败 → 归一化成功 → Success(NORMALIZE)
 *   3) 三级降级 → 分段导出（每 8 镜一段）
 *   4) 三级全失败 → Failure(SEGMENTED)
 *   5) clips 为空 / 空文件 → IllegalArgumentException
 *   6) Strategy / ColorGradePreset 枚举与 FfmpegAssembler 对齐
 *   7) 字幕 sidecar .srt 导出
 *   8) ★第二批 P0：xfade 转场（offset 递推 + acrossfade）
 *   9) ★第二批 P0：Transition.NONE 默认仍走 concat 硬切（旧行为不变）
 *  10) ★第二批 P0：BGM amix normalize=0 + 原子替换
 *  11) ★第二批 P0：loudnorm 后处理命令（aresample/alimiter）
 *  12) ★第二批 P0：probe 回填真实成片时长
 *  13) ★第二批 P0：无音轨镜补 anullsrc 后归一化成功（D4）
 *  14) ★第二批 P0：补音轨失败时纯视频降级必须带 warning（不静默）
 */
class MovieAssemblerTest {

    private class StubExecutor(
        private val concatReturnCode: Int = 0,
        private val normalizeReturnCode: Int = 0,
        private val segmentReturnCode: Int = 0,
        private val xfadeReturnCode: Int = 0,
        private val mixReturnCode: Int = 0,
        private val ensureAudioReturnCode: Int = 0,
        /** 归一化连续尝试的脚本化返回码（优先级高于 normalizeReturnCode） */
        private val normalizeSequence: MutableList<Int>? = null,
    ) : MovieAssemblerExecutor {
        val calls = mutableListOf<String>()

        override fun execute(args: List<String>): Pair<Int, String> {
            val joined = args.joinToString(" ")
            calls += joined
            val outputFile = args.lastOrNull()?.let { File(it) }
            fun writeOk(rc: Int, err: String = ""): Pair<Int, String> {
                if (rc == 0) outputFile?.writeBytes("ok".toByteArray())
                return rc to err
            }
            // 顺序很重要：xfade / 混音命令里同时含 -c:v libx264 / loudnorm，必须先判定
            if (joined.contains("xfade=")) return writeOk(xfadeReturnCode)
            if (joined.contains("amix=") || joined.contains("loudnorm=") || joined.contains("alimiter=")) {
                return writeOk(mixReturnCode)
            }
            if (joined.contains("-f lavfi")) return writeOk(ensureAudioReturnCode)
            if (joined.contains("-f concat") && !joined.contains("_part")) return writeOk(concatReturnCode)
            if (joined.contains("_norm.mp4") || joined.contains("h264_mediacodec")) {
                val rc = if (normalizeSequence != null && normalizeSequence.isNotEmpty()) {
                    normalizeSequence.removeAt(0)
                } else {
                    normalizeReturnCode
                }
                return writeOk(rc)
            }
            if (joined.contains("_part")) return writeOk(segmentReturnCode)
            if (joined.contains("-c:v libx264") || joined.contains("-vf")) return writeOk(0)
            return 0 to ""
        }
    }

    private fun tmpDir(): File = Files.createTempDirectory("movieasm").toFile().also { it.deleteOnExit() }

    private fun makeClips(dir: File, n: Int, size: Int = 64): List<File> =
        List(n) { i ->
            File(dir, "clip_$i.mp4").apply { writeBytes("x".repeat(size).toByteArray()) }
        }

    private fun probeWith(durationMs: Long, hasAudio: Boolean): MovieAssembler.MovieAssemblerProbe =
        MovieAssembler.MovieAssemblerProbe { MovieAssembler.ClipInfo(durationMs = durationMs, hasAudio = hasAudio) }

    /** 断言必须冒泡：旧实现用 Thread + join，失败只进 system-err，JUnit 仍报 PASSED（假通过）。 */
    private fun runSuspending(block: suspend () -> Unit): Unit {
        runBlocking { withTimeout(20_000) { block() } }
    }

    @Test
    fun `策略一 concat-copy 成功`() {
        runSuspending {
            val dir = tmpDir()
            val clips = makeClips(dir, 3)
            val output = File(dir, "out.mp4")
            val assembler = MovieAssemblerImpl(executor = StubExecutor(concatReturnCode = 0))
            val result = assembler.assemble(clips, output)
            assertTrue(result is MovieAssembler.AssembleResult.Success)
            assertEquals(MovieAssembler.Strategy.CONCAT_COPY, (result as MovieAssembler.AssembleResult.Success).strategy)
        }
    }

    @Test
    fun `字幕轨在成片旁导出同名 srt`() {
        runSuspending {
            val dir = tmpDir()
            val clips = makeClips(dir, 2)
            val output = File(dir, "out.mp4")
            val cues = listOf(
                MovieAssembler.SubtitleCue("第一句台词", 0, 2000),
                MovieAssembler.SubtitleCue("第二句旁白", 2000, 4000),
            )
            val assembler = MovieAssemblerImpl(executor = StubExecutor(concatReturnCode = 0))
            val result = assembler.assemble(clips, output, subtitles = cues)
            assertTrue(result is MovieAssembler.AssembleResult.Success)
            val srt = (result as MovieAssembler.AssembleResult.Success).subtitleFile
            assertNotNull(srt, "应导出字幕 sidecar")
            val text = srt.readText()
            assertTrue(text.contains("第一句台词"))
            assertTrue(text.contains("00:00:02,000 --> 00:00:04,000"), "SRT 时间轴格式错误：$text")
        }
    }

    @Test
    fun `策略二 concat 失败_归一化成功`() {
        runSuspending {
            val dir = tmpDir()
            val clips = makeClips(dir, 3)
            val output = File(dir, "out.mp4")
            val assembler = MovieAssemblerImpl(executor = StubExecutor(concatReturnCode = 1, normalizeReturnCode = 0))
            val result = assembler.assemble(clips, output)
            assertTrue(result is MovieAssembler.AssembleResult.Success)
            assertEquals(MovieAssembler.Strategy.NORMALIZE, (result as MovieAssembler.AssembleResult.Success).strategy)
            assertTrue(result.output.name.endsWith("_norm.mp4"))
        }
    }

    @Test
    fun `策略三 三段导出_17镜拆成3段`() {
        runSuspending {
            val dir = tmpDir()
            val clips = makeClips(dir, 17)
            val output = File(dir, "out.mp4")
            val assembler = MovieAssemblerImpl(executor = StubExecutor(concatReturnCode = 1, normalizeReturnCode = 1, segmentReturnCode = 0))
            val result = assembler.assemble(clips, output)
            assertTrue(result is MovieAssembler.AssembleResult.Segmented)
            assertEquals(3, (result as MovieAssembler.AssembleResult.Segmented).parts.size)
        }
    }

    @Test
    fun `三级全失败_Failure_SEGMENTED`() {
        runSuspending {
            val dir = tmpDir()
            val clips = makeClips(dir, 2)
            val output = File(dir, "out.mp4")
            val assembler = MovieAssemblerImpl(executor = StubExecutor(concatReturnCode = 1, normalizeReturnCode = 1, segmentReturnCode = 1))
            val result = assembler.assemble(clips, output)
            assertTrue(result is MovieAssembler.AssembleResult.Failure)
            assertEquals(MovieAssembler.Strategy.SEGMENTED, (result as MovieAssembler.AssembleResult.Failure).strategy)
        }
    }

    @Test
    fun `empty_clips_throws_IllegalArgumentException`() {
        runSuspending {
            val dir = tmpDir()
            val output = File(dir, "out.mp4")
            val assembler = MovieAssemblerImpl(executor = StubExecutor())
            assertFailsWith<IllegalArgumentException> {
                assembler.assemble(emptyList(), output)
            }
        }
    }

    @Test
    fun `clip_with_zero_bytes_throws_IllegalArgumentException`() {
        runSuspending {
            val dir = tmpDir()
            val emptyClip = File(dir, "empty.mp4") // 0 字节
            val output = File(dir, "out.mp4")
            val assembler = MovieAssemblerImpl(executor = StubExecutor())
            assertFailsWith<IllegalArgumentException> {
                assembler.assemble(listOf(emptyClip), output)
            }
        }
    }

    @Test
    fun `Strategy 枚举标签与 FfmpegAssembler 对齐`() {
        assertEquals(MovieAssembler.Strategy.CONCAT_COPY.label, FfmpegAssembler.Strategy.CONCAT_COPY.label)
        assertEquals(MovieAssembler.Strategy.NORMALIZE.label, FfmpegAssembler.Strategy.NORMALIZE.label)
        assertEquals(MovieAssembler.Strategy.SEGMENTED.label, FfmpegAssembler.Strategy.SEGMENTED.label)
    }

    @Test
    fun `ColorGradePreset 枚举与 FfmpegAssembler 对齐`() {
        assertEquals(FfmpegAssembler.ColorGradePreset.CINEMA.filter, MovieAssembler.ColorGradePreset.CINEMA.filter)
        assertEquals(FfmpegAssembler.ColorGradePreset.COOL.filter, MovieAssembler.ColorGradePreset.COOL.filter)
        assertEquals(FfmpegAssembler.ColorGradePreset.WARM.filter, MovieAssembler.ColorGradePreset.WARM.filter)
        assertEquals(FfmpegAssembler.ColorGradePreset.NEUTRAL.filter, MovieAssembler.ColorGradePreset.NEUTRAL.filter)
        assertEquals(4, MovieAssembler.ColorGradePreset.values().size)
    }

    // ---------- 第二批 P0 新增 ----------

    @Test
    fun `转场 FADE 生成 xfade 与 acrossfade 且 offset 递推`() {
        runSuspending {
            val dir = tmpDir()
            val clips = makeClips(dir, 2)
            val output = File(dir, "out.mp4")
            val executor = StubExecutor()
            val assembler = MovieAssemblerImpl(executor = executor, probe = probeWith(4000, hasAudio = true))
            val result = assembler.assemble(
                clips, output,
                MovieAssembler.AssembleOptions(
                    transition = MovieAssembler.Transition.FADE,
                    transitionSeconds = 0.3,
                ),
            )
            assertTrue(result is MovieAssembler.AssembleResult.Success)
            val success = result as MovieAssembler.AssembleResult.Success
            assertEquals(MovieAssembler.Strategy.NORMALIZE, success.strategy)
            assertEquals(output.absolutePath, success.output.absolutePath, "转场成片应落在请求的 output 路径")
            val call = assertNotNull(executor.calls.firstOrNull { it.contains("xfade=") }, "应生成 xfade 命令")
            assertTrue(call.contains("xfade=transition=fade:duration=0.3:offset=3.700"), "offset 递推错误：$call")
            assertTrue(call.contains("acrossfade=d=0.3:c1=tri:c2=tri"), "缺少 acrossfade：$call")
            assertTrue(call.contains("-c:v libx264"), "转场应优先 libx264：$call")
            assertTrue(call.contains("-t 7.700"), "总时长应为 Σd-(n-1)T=7.7：$call")
            assertEquals(4.0, success.durationSeconds, 0.001)
        }
    }

    @Test
    fun `Transition NONE 默认仍走 concat 硬切`() {
        runSuspending {
            val dir = tmpDir()
            val clips = makeClips(dir, 3)
            val output = File(dir, "out.mp4")
            val executor = StubExecutor()
            val assembler = MovieAssemblerImpl(executor = executor, probe = probeWith(4000, hasAudio = true))
            val result = assembler.assemble(clips, output)
            assertTrue(result is MovieAssembler.AssembleResult.Success)
            assertEquals(MovieAssembler.Strategy.CONCAT_COPY, (result as MovieAssembler.AssembleResult.Success).strategy)
            assertTrue(executor.calls.none { it.contains("xfade=") }, "NONE 不应触发 xfade")
        }
    }

    @Test
    fun `BGM 混音 amix normalize=0 且原子替换`() {
        runSuspending {
            val dir = tmpDir()
            val clips = makeClips(dir, 2)
            val output = File(dir, "out.mp4")
            val bgm = File(dir, "bgm.m4a").apply { writeBytes("bgm".toByteArray()) }
            val executor = StubExecutor()
            val assembler = MovieAssemblerImpl(executor = executor)
            val result = assembler.assemble(clips, output, MovieAssembler.AssembleOptions(bgm = bgm))
            assertTrue(result is MovieAssembler.AssembleResult.Success)
            val success = result as MovieAssembler.AssembleResult.Success
            assertEquals(output.absolutePath, success.output.absolutePath)
            val mixCall = assertNotNull(executor.calls.firstOrNull { it.contains("amix=") }, "应生成 amix 命令")
            assertTrue(mixCall.contains("amix=inputs=2:duration=first:dropout_transition=0:normalize=0"),
                "amix 必须 normalize=0（否则人声减半）：$mixCall")
            assertTrue(mixCall.contains("-stream_loop -1"), "BGM 应循环铺满：$mixCall")
            assertTrue(output.exists() && output.length() > 0, "原子替换后成片必须存在")
        }
    }

    @Test
    fun `loudnorm 后处理含 aresample 与 alimiter`() {
        runSuspending {
            val dir = tmpDir()
            val clips = makeClips(dir, 2)
            val output = File(dir, "out.mp4")
            val executor = StubExecutor()
            val assembler = MovieAssemblerImpl(executor = executor)
            val result = assembler.assemble(clips, output, MovieAssembler.AssembleOptions(loudnessNormalize = true))
            assertTrue(result is MovieAssembler.AssembleResult.Success)
            val call = assertNotNull(executor.calls.firstOrNull { it.contains("loudnorm=") }, "应生成 loudnorm 命令")
            assertTrue(call.contains("loudnorm=I=-16:TP=-1.5:LRA=11"), "响度目标错误：$call")
            assertTrue(call.contains("aresample=48000"), "loudnorm 后应回 48k：$call")
            assertTrue(call.contains("alimiter=limit=0.98"), "缺限幅器：$call")
        }
    }

    @Test
    fun `probe 回填成片真实时长`() {
        runSuspending {
            val dir = tmpDir()
            val clips = makeClips(dir, 2)
            val output = File(dir, "out.mp4")
            val assembler = MovieAssemblerImpl(
                executor = StubExecutor(concatReturnCode = 0),
                probe = probeWith(6000, hasAudio = true),
            )
            val result = assembler.assemble(clips, output)
            assertTrue(result is MovieAssembler.AssembleResult.Success)
            assertEquals(6.0, (result as MovieAssembler.AssembleResult.Success).durationSeconds, 0.001)
        }
    }

    @Test
    fun `无音轨镜补 anullsrc 后归一化成功`() {
        runSuspending {
            val dir = tmpDir()
            val clips = makeClips(dir, 2)
            val output = File(dir, "out.mp4")
            // 第一次带音频归一化失败 → 补静音轨 → 第二次成功
            val executor = StubExecutor(concatReturnCode = 1, normalizeSequence = mutableListOf(1, 0))
            val assembler = MovieAssemblerImpl(executor = executor)
            val result = assembler.assemble(clips, output)
            assertTrue(result is MovieAssembler.AssembleResult.Success)
            assertEquals(MovieAssembler.Strategy.NORMALIZE, (result as MovieAssembler.AssembleResult.Success).strategy)
            assertTrue(executor.calls.any { it.contains("anullsrc=r=48000:cl=stereo") },
                "无音轨镜应补 anullsrc（D4）")
            val retry = executor.calls.filter { it.contains("_norm.mp4") }
            assertTrue(retry.size >= 2, "应重试带音频归一化：$retry")
            assertTrue(retry.last().contains("-map [aout]"), "重试仍须带音轨：${retry.last()}")
        }
    }

    @Test
    fun `补音轨失败时纯视频降级必须带 warning`() {
        runSuspending {
            val dir = tmpDir()
            val clips = makeClips(dir, 2)
            val output = File(dir, "out.mp4")
            // 带音频归一化失败、补静音轨失败、纯视频归一化成功
            val executor = StubExecutor(
                concatReturnCode = 1,
                normalizeSequence = mutableListOf(1, 0),
                ensureAudioReturnCode = 1,
            )
            val assembler = MovieAssemblerImpl(executor = executor)
            val result = assembler.assemble(clips, output)
            assertTrue(result is MovieAssembler.AssembleResult.Success)
            val success = result as MovieAssembler.AssembleResult.Success
            assertTrue(success.output.name.endsWith("_norm.mp4"))
            assertTrue(success.warnings.any { it.contains("纯视频") },
                "纯视频降级不得静默，必须出现在 warnings：${success.warnings}")
        }
    }
}
