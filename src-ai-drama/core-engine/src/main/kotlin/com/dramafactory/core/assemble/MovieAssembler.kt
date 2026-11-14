package com.dramafactory.core.assemble

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * 端上成片合成器（T014 §2.4，架构对齐 FfmpegAssembler 三级策略）。
 *
 * 与 FfmpegAssembler 关系：
 * - 共享 [MovieAssembler.AssembleResult] / [MovieAssembler.Strategy] / [MovieAssembler.ColorGradePreset]
 *   契约（全部复用自 FfmpegAssembler 的语义，此处重定义枚举以脱离 FfmpegAssembler 的
 *   "内部类"耦合，标签字符串与 FfmpegAssembler 一致，供断言对齐）。
 * - 端侧执行器从 JVM 命令行 ffmpeg 换成 ffmpeg-kit community 维护版 8.1.7 的 Java API
 *   (`com.arthenica.ffmpegkit.FFmpegKit.executeWithArgumentsAsync` + `FFmpegSession` 回调)；
 *   该 AAR 为 GPL-3.0（含 libx264/x265/xvid/vidstab 等 GPL 编码器），不是 LGPL。
 *   复核证据：dev.ffmpegkit-maintained:ffmpeg-kit-min-gpl:8.1.7 的 libavutil 里
 *   CONFIG 串含 --enable-gpl --enable-libx264 --enable-libx265 --enable-libxvid。
 *   因 ffmpeg-kit 是 Android AAR，本 core-engine 模块不直接依赖；具体执行器/探测器注入。
 *
 * 三级策略（与 FfmpegAssembler.assemble 严格对齐）：
 *   1. concat -c copy（同规格，秒级）
 *   2. scale+pad 到 720x1280 + h264_mediacodec（端侧硬编）；无音轨镜先补静音轨，失败显式告警
 *   3. 分段导出（每 8 镜一段），返回 Segmented，落 multi_parts_uris
 *
 * 第二批 P0 增强（按 batch2-post-audit.md P-1..P-12）：
 * - 可选 xfade 交叉溶解 + acrossfade 音频交叉淡化（[Transition]，默认 NONE 保持旧硬切行为）；
 *   总时长 = Σ单镜时长 - (镜数-1) * T。
 * - 可选 BGM 混音 + loudnorm 响度归一化（amix 必须 normalize=0，否则人声被减半）。
 * - 字幕：ffmpeg-kit min-gpl 无 libass/freetype，不能烧录；导出同名 sidecar .srt。
 * - 成片时长：注入 [MovieAssembler.MovieAssemblerProbe] 后回填真实时长（否则保持 0.0 兼容旧行为）。
 * - 后处理一律「写临时文件成功后再原子替换」，绝不先删原片再 copy。
 *
 * **连续剧禁去头（pavo head_trim 教训，v1.8.0 固化）**：合成阶段不对首镜开头裁剪——
 * 首句台词常落开场 0.5s，去头必切对白。独立音频对齐（[AudioShotAlign]）只在尾部 pad/trim，
 * 此处合成只做 concat/归一化/转场/后处理，不裁头。
 *
 * JVM 单测注入 `executor = stub` 绕过真实 ffmpeg 调用，只测试策略选择、降级链与命令组装。
 */
interface MovieAssembler {

    /** 合成进度回调（UI 可见） */
    val progress: StateFlow<MovieAssembleProgress>

    /**
     * 合成整集。
     * @param clips 已按 shot_no 升序的本地单镜 mp4 文件列表（必须全部存在且 >0 字节）
     * @param output 目标 mp4 文件，位于 Context.getFilesDir()/movies/
     * @param grade 统一色彩分级配方
     * @return AssembleResult
     *
     * @throws IllegalArgumentException 若 clips 为空或存在空文件
     * @throws MovieAssembler.NotAvailableException 若 ffmpeg-kit 未初始化/不可用
     */
    /**
     * 成片字幕轨（sidecar .srt）。
     * ffmpeg-kit min-gpl 不含 libass/freetype，无法烧录字幕；导出与成片同名的 .srt，
     * 播放器/剪辑软件可直接加载。若要烧录需换含 libass/freetype 的构建（未验证是否存在）。
     */
    data class SubtitleCue(val text: String, val startMs: Long, val endMs: Long)

    /** 镜头转场（xfade 滤镜），NONE 保持旧硬切行为。 */
    enum class Transition(val xfadeName: String) {
        NONE("none"),
        FADE("fade"),
        DISSOLVE("dissolve"),
        FADE_WHITE("fadewhite"),
    }

    /** 单镜探测信息（时长/音轨/分辨率/帧率）。探测失败时 probe 返回 null。 */
    data class ClipInfo(
        val durationMs: Long,
        val hasAudio: Boolean,
        val width: Int = 0,
        val height: Int = 0,
        val fps: Double = 0.0,
    )

    /**
     * 单镜探测能力（端侧用 ffmpeg-kit FFprobeKit 反射实现；JVM/桌面可注入 fake，null = 不探测）。
     * 探测失败必须返回 null，调用方按旧行为降级（转场跳过、时长保持 0.0）。
     */
    fun interface MovieAssemblerProbe {
        fun probe(file: File): ClipInfo?
    }

    /** 一次性合成参数（新增能力走这里，旧 [assemble] 6 参签名保持不变以兼容现有实现/调用方）。 */
    data class AssembleOptions(
        /** 统一色彩分级配方 */
        val grade: ColorGradePreset = ColorGradePreset.CINEMA,
        /** 台词/旁白字幕；非空时在成片旁导出同名 .srt */
        val subtitles: List<SubtitleCue> = emptyList(),
        /** 背景音乐文件；非空时按低音量混入成片音轨 */
        val bgm: File? = null,
        /** 是否做响度归一化（loudnorm -16 LUFS）；人声成片建议开启 */
        val loudnessNormalize: Boolean = false,
        /** 镜头间转场；NONE 保持旧行为 */
        val transition: Transition = Transition.NONE,
        /** 转场时长（秒），仅 transition != NONE 且探测到足够时长时生效 */
        val transitionSeconds: Double = 0.30,
    )

    suspend fun assemble(
        clips: List<File>,
        output: File,
        grade: ColorGradePreset = ColorGradePreset.CINEMA,
        /** 台词/旁白字幕；非空时在成片旁导出同名 .srt */
        subtitles: List<SubtitleCue> = emptyList(),
        /** 背景音乐文件；非空时按低音量混入成片音轨 */
        bgm: File? = null,
        /** 是否做响度归一化（loudnorm -16 LUFS）；人声成片建议开启 */
        loudnessNormalize: Boolean = false,
    ): AssembleResult

    /**
     * 扩展入口：支持转场等新能力。
     *
     * 接口默认实现忽略 [AssembleOptions.transition]（直接委托旧 6 参方法），
     * 因此 [EmptyMovieAssembler] 一类旧实现/桌面实现无需改动即可继续编译；
     * [MovieAssemblerImpl] 覆盖后真正应用转场。
     */
    suspend fun assemble(clips: List<File>, output: File, options: AssembleOptions): AssembleResult =
        assemble(clips, output, options.grade, options.subtitles, options.bgm, options.loudnessNormalize)

    /** 与 FfmpegAssembler 对齐的产物结果。 */
    sealed class AssembleResult {
        data class Success(
            val output: File,
            val strategy: Strategy,
            val elapsedMs: Long,
            /** 成片真实时长（秒）；未注入 probe 或无有效探测时保持 0.0（兼容旧行为） */
            val durationSeconds: Double = 0.0,
            /** 同名 .srt 字幕文件（未生成字幕时为 null） */
            val subtitleFile: File? = null,
            /** 非致命告警（转场跳过、后处理降级等），UI 应透出，不得静默 */
            val warnings: List<String> = emptyList(),
        ) : AssembleResult()
        data class Segmented(
            val parts: List<File>,
            val elapsedMs: Long,
        ) : AssembleResult()
        data class Failure(val strategy: Strategy, val message: String) : AssembleResult()
    }

    /** 三级策略枚举，与 FfmpegAssembler.Strategy 语义一致。 */
    enum class Strategy(val label: String) {
        CONCAT_COPY("concat-copy"),
        NORMALIZE("mediacodec归一化"),
        SEGMENTED("分段导出");
    }

    /** 统一色彩分级配方（与 FfmpegAssembler.ColorGradePreset 完全对齐）。 */
    enum class ColorGradePreset(val filter: String) {
        CINEMA("eq=contrast=1.08:brightness=-0.02:saturation=1.06,colortemperature=warm=0.06,format=yuv420p"),
        COOL("eq=contrast=1.06:saturation=1.04,colortemperature=warm=-0.08,format=yuv420p"),
        WARM("eq=contrast=1.06:saturation=1.08,colortemperature=warm=0.12,format=yuv420p"),
        NEUTRAL("format=yuv420p"),
    }

    data class MovieAssembleProgress(
        val stage: AssembleStage,
        val step: Int,
        val total: Int,
        val message: String,
        val elapsedMs: Long,
    )
    enum class AssembleStage { GRADE, CONCAT, NORMALIZE, PROBE, DONE }

    /** ffmpeg-kit 未加载/不可用 —— 触发云端合成回退提示（架构§六 R1 备选）。 */
    class NotAvailableException(message: String) : Exception(message)
}

/**
 * ffmpeg-kit 调用抽象。
 *
 * JVM 单测注入桩实现（返回固定 exitCode + 可选写入文件），绕过真实 ffmpeg。
 * 端侧生产实现绑定 `com.arthenica.ffmpegkit.FFmpegKit`（Android AAR，不进 core-engine classpath）。
 *
 * @param args ffmpeg 命令参数列表
 * @return 返回 (exitCode, stderr/log 尾部)；exitCode==0 视为成功（配合输出文件存在性判定）
 */
fun interface MovieAssemblerExecutor {
    fun execute(args: List<String>): Pair<Int, String>
}

/**
 * 具体实现：三级策略 + 可选转场/后处理 + 可注入 executor/probe。
 *
 * 端侧实例化时传入 [androidFfmpegKitExecutor]（可选 [androidFfmpegKitProbe]），
 * 得到与 FfmpegAssembler 语义对齐的合成行为；JVM 单测传入桩 executor/probe，绕过 native 依赖。
 */
class MovieAssemblerImpl(
    private val executor: MovieAssemblerExecutor,
    private val probe: MovieAssembler.MovieAssemblerProbe? = null,
) : MovieAssembler {

    private val _progress = MutableStateFlow(
        MovieAssembler.MovieAssembleProgress(
            stage = MovieAssembler.AssembleStage.DONE,
            step = 0, total = 0, message = "ready", elapsedMs = 0,
        )
    )
    override val progress: StateFlow<MovieAssembler.MovieAssembleProgress> = _progress

    private fun emit(stage: MovieAssembler.AssembleStage, step: Int, total: Int, msg: String, t0: Long) {
        _progress.value = MovieAssembler.MovieAssembleProgress(stage, step, total, msg, System.currentTimeMillis() - t0)
    }

    override suspend fun assemble(
        clips: List<File>,
        output: File,
        grade: MovieAssembler.ColorGradePreset,
        subtitles: List<MovieAssembler.SubtitleCue>,
        bgm: File?,
        loudnessNormalize: Boolean,
    ): MovieAssembler.AssembleResult = assembleInternal(
        clips, output,
        MovieAssembler.AssembleOptions(
            grade = grade, subtitles = subtitles, bgm = bgm,
            loudnessNormalize = loudnessNormalize,
        ),
    )

    override suspend fun assemble(
        clips: List<File>,
        output: File,
        options: MovieAssembler.AssembleOptions,
    ): MovieAssembler.AssembleResult = assembleInternal(clips, output, options)

    private fun assembleInternal(
        clips: List<File>,
        output: File,
        options: MovieAssembler.AssembleOptions,
    ): MovieAssembler.AssembleResult {
        require(clips.isNotEmpty()) { "无镜片段可拼接" }
        clips.forEach { require(it.exists() && it.length() > 0) { "片段不存在或为空: ${it.absolutePath}" } }
        output.parentFile?.mkdirs()
        val t0 = System.currentTimeMillis()
        val total = clips.size
        val warnings = mutableListOf<String>()
        emit(MovieAssembler.AssembleStage.DONE, 0, total, "start assemble ${total} clips", t0)

        // 统一色彩分级：对每镜走 grade filter，失败则用原文件降级（不阻断整集）
        val gradedFiles: List<File> = gradeClips(clips, output, options.grade, total, t0)

        // 可选转场：xfade + acrossfade。探测不到/时长不足/编码失败都会显式告警并回退硬切。
        tryXfade(gradedFiles, output, options, total, t0, warnings)?.let { return it }

        // 一级快路径：concat -c copy
        val concatListFile = File.createTempFile("movie_concat", ".txt").apply { deleteOnExit() }
        concatListFile.writeText(gradedFiles.joinToString("\n") { "file '${it.absolutePath}'" })
        emit(MovieAssembler.AssembleStage.CONCAT, 0, total, "concat-copy attempt", t0)
        val (rcConcat, errConcat) = executor.execute(listOf(
            "-y", "-f", "concat", "-safe", "0",
            "-i", concatListFile.absolutePath,
            "-c", "copy", output.absolutePath,
        ))
        if (rcConcat == 0 && output.exists() && output.length() > 0) {
            emit(MovieAssembler.AssembleStage.DONE, total, total, "concat-copy done", t0)
            return applyPostProcessing(
                base = MovieAssembler.AssembleResult.Success(
                    output = output,
                    strategy = MovieAssembler.Strategy.CONCAT_COPY,
                    elapsedMs = System.currentTimeMillis() - t0,
                    durationSeconds = 0.0,
                ),
                output = output, options = options, warnings = warnings, total = total, t0 = t0,
            )
        }
        if (errConcat.isNotBlank()) warnings += "concat-copy 失败：${errConcat.take(200)}"

        // 二级归一化路径：scale+pad 到 720x1280 + h264_mediacodec（端侧硬编）
        // ★D4：旧实现带音频归一化失败后直接回退纯视频，整片静默丢音轨；
        // 这里改为「直接带音频 → 逐镜补静音轨后重试 → 才回退纯视频并显式告警」。
        val normOut = File(output.parentFile, "${output.nameWithoutExtension}_norm.mp4")
        normOut.parentFile?.mkdirs()
        fun normFilter(inputs: List<File>, withAudio: Boolean): String {
            val video = inputs.indices.joinToString(";") { i ->
                "[$i:v]scale=720:1280:force_original_aspect_ratio=decrease," +
                    "pad=720:1280:(ow-iw)/2:(oh-ih)/2,setsar=1[v$i]"
            } + ";" + inputs.indices.joinToString("") { "[v$it]" } +
                "concat=n=${inputs.size}:v=1:a=0[vout]"
            if (!withAudio) return video
            val audio = inputs.indices.joinToString(";") { i ->
                "[$i:a]aresample=async=1:first_pts=0[a$i]"
            } + ";" + inputs.indices.joinToString("") { "[a$it]" } +
                "concat=n=${inputs.size}:v=0:a=1[aout]"
            return "$video;$audio"
        }
        fun normArgs(inputs: List<File>, withAudio: Boolean): List<String> = buildList {
            add("-y")
            inputs.forEach { addAll(listOf("-i", it.absolutePath)) }
            addAll(listOf("-filter_complex", normFilter(inputs, withAudio)))
            if (withAudio) addAll(listOf("-map", "[vout]", "-map", "[aout]"))
            else addAll(listOf("-map", "[vout]"))
            addAll(listOf("-c:v", "h264_mediacodec", "-b:v", "2M", "-r", "24"))
            if (withAudio) addAll(listOf("-c:a", "aac", "-b:a", "128k"))
            add(normOut.absolutePath)
        }
        emit(MovieAssembler.AssembleStage.NORMALIZE, 0, total, "normalize + mediacodec", t0)
        var rcNorm = -1
        var normHadAudio = false
        var normInputs = gradedFiles
        var (rcNormFirst, errNormFirst) = executor.execute(normArgs(normInputs, true))
        if (rcNormFirst == 0 && normOut.exists() && normOut.length() > 0) {
            rcNorm = 0
            normHadAudio = true
        } else {
            // D4：带音频失败，逐镜补静音轨后重试（原音轨保留为第一音轨；无音轨镜补 anullsrc）
            emit(MovieAssembler.AssembleStage.NORMALIZE, 0, total, "retry: ensure audio tracks", t0)
            val saDir = File(output.parentFile, "${output.nameWithoutExtension}_sa")
            val ensured = gradedFiles.mapIndexed { i, f -> ensureAudioTrack(f, i, saDir) }
            if (ensured.all { it != null }) {
                normInputs = ensured.map { it!! }
                val (rcRetry, errRetry) = executor.execute(normArgs(normInputs, true))
                if (rcRetry == 0 && normOut.exists() && normOut.length() > 0) {
                    rcNorm = 0
                    normHadAudio = true
                } else {
                    warnings += "带音频归一化重试失败：${errRetry.take(200)}"
                }
            } else {
                warnings += "部分镜头无音轨且补静音轨失败，尝试纯视频归一化"
            }
            if (rcNorm != 0) {
                // 最后回退：纯视频（应极少发生）。显式告警，不静默变静音。
                val (rcVideo, errVideo) = executor.execute(normArgs(normInputs, false))
                if (rcVideo == 0 && normOut.exists() && normOut.length() > 0) {
                    rcNorm = 0
                    normHadAudio = false
                    warnings += "归一化回退为纯视频（成片无音轨）：${errNormFirst.take(120)}"
                    emit(MovieAssembler.AssembleStage.NORMALIZE, 0, total,
                        "normalize fallback: video only (no audio)", t0)
                } else {
                    warnings += "归一化失败：${errVideo.take(200)}"
                }
            }
        }
        if (rcNorm == 0 && normOut.exists() && normOut.length() > 0) {
            emit(MovieAssembler.AssembleStage.DONE, total, total,
                if (normHadAudio) "normalize done (audio)" else "normalize done (no audio)", t0)
            return applyPostProcessing(
                base = MovieAssembler.AssembleResult.Success(
                    output = normOut,
                    strategy = MovieAssembler.Strategy.NORMALIZE,
                    elapsedMs = System.currentTimeMillis() - t0,
                    durationSeconds = 0.0,
                ),
                output = output, options = options, warnings = warnings, total = total, t0 = t0,
            )
        }

        // 三级降级路径：分段导出（每 8 镜一段），保留 concat-copy 语义
        val parts = gradedFiles.chunked(8).mapIndexedNotNull { idx, chunk ->
            val part = File(output.parentFile, "${output.nameWithoutExtension}_part${idx + 1}.mp4")
            val partListFile = File.createTempFile("movie_part${idx + 1}", ".txt").apply { deleteOnExit() }
            partListFile.writeText(chunk.joinToString("\n") { "file '${it.absolutePath}'" })
            val (prc, _) = executor.execute(listOf(
                "-y", "-f", "concat", "-safe", "0",
                "-i", partListFile.absolutePath,
                "-c", "copy", part.absolutePath,
            ))
            if (prc == 0 && part.exists() && part.length() > 0) part else null
        }
        return if (parts.isNotEmpty()) {
            emit(MovieAssembler.AssembleStage.DONE, total, total, "segmented ${parts.size} parts", t0)
            MovieAssembler.AssembleResult.Segmented(parts = parts, elapsedMs = System.currentTimeMillis() - t0)
        } else {
            val detail = (warnings + "三级策略均失败：concat/rc=$rcConcat norm/rc=$rcNorm").joinToString("；")
            MovieAssembler.AssembleResult.Failure(
                strategy = MovieAssembler.Strategy.SEGMENTED,
                message = detail,
            )
        }
    }

    /**
     * 逐镜色彩分级。
     *
     * ★D6：min-gpl 8.1.7 实测含 libx264（AAR CONFIG 含 --enable-libx264，且为 GPL-3.0），
     * 旧注释"community 版是 LGPL 不含 libx264"是错的。优先 libx264 -crf 19 保证画质，
     * 失败再回退 ffmpeg 内置 mpeg4（-qscale:v 3 有损档）以兼容异常构建；两档都失败用原片。
     */
    private fun gradeClips(
        clips: List<File>,
        output: File,
        grade: MovieAssembler.ColorGradePreset,
        total: Int,
        t0: Long,
    ): List<File> {
        val gradedDir = File(output.parentFile, "${output.nameWithoutExtension}_graded")
        gradedDir.mkdirs()
        val filter = grade.filter
        return clips.mapIndexed { idx, clip ->
            emit(MovieAssembler.AssembleStage.GRADE, idx + 1, total, "grade clip ${idx + 1}/$total", t0)
            val gOut = File(gradedDir, "g_%03d.mp4".format(idx))
            val codecAttempts = listOf(
                listOf("-c:v", "libx264", "-preset", "veryfast", "-crf", "19"),
                listOf("-c:v", "mpeg4", "-qscale:v", "3"),
            )
            var ok = false
            for (codec in codecAttempts) {
                val args = listOf("-y", "-i", clip.absolutePath, "-vf", filter) +
                    codec + listOf("-c:a", "copy", gOut.absolutePath)
                val (rc, _) = executor.execute(args)
                if (rc == 0 && gOut.exists() && gOut.length() > 0) {
                    ok = true
                    break
                }
            }
            if (ok) gOut else {
                emit(MovieAssembler.AssembleStage.GRADE, idx + 1, total,
                    "grade failed clip ${idx + 1}, use raw", t0)
                clip
            }
        }
    }

    /**
     * 可选转场：xfade + acrossfade。
     * 返回 null 表示未执行/失败（调用方继续走硬切三级策略）；所有跳过原因写 [warnings]。
     */
    private fun tryXfade(
        gradedFiles: List<File>,
        output: File,
        options: MovieAssembler.AssembleOptions,
        total: Int,
        t0: Long,
        warnings: MutableList<String>,
    ): MovieAssembler.AssembleResult? {
        val transition = options.transition
        if (transition == MovieAssembler.Transition.NONE) return null
        if (gradedFiles.size < 2) {
            warnings += "转场跳过：少于 2 个镜头"
            return null
        }
        val p = probe
        if (p == null) {
            warnings += "转场跳过：未注入时长探测能力"
            return null
        }
        emit(MovieAssembler.AssembleStage.PROBE, 0, total, "probe clip durations for transition", t0)
        val infos = gradedFiles.map { runCatching { p.probe(it) }.getOrNull() }
        if (infos.any { it == null || it.durationMs <= 0 }) {
            warnings += "转场跳过：无法探测全部镜头时长"
            return null
        }
        val transitionSeconds = options.transitionSeconds.coerceIn(0.1, 2.0)
        val transitionMs = (transitionSeconds * 1000).toLong()
        if (infos.any { it!!.durationMs <= transitionMs + 50 }) {
            warnings += "转场跳过：存在时长不足 ${transitionSeconds}s 的镜头"
            return null
        }
        // 保证每镜都有音轨；无音轨镜补 anullsrc（acrossfade 需要两条音轨）
        val saDir = File(output.parentFile, "${output.nameWithoutExtension}_sa")
        val prepared = ArrayList<File>(gradedFiles.size)
        for (i in gradedFiles.indices) {
            val info = infos[i]!!
            if (info.hasAudio) {
                prepared += gradedFiles[i]
            } else {
                val ensured = ensureAudioTrack(gradedFiles[i], i, saDir)
                if (ensured == null) {
                    warnings += "转场跳过：镜头 ${i + 1} 无音轨且补静音失败"
                    return null
                }
                prepared += ensured
            }
        }
        val merged = renderXfade(prepared, infos.map { it!! }, output, transition, transitionSeconds, total, t0, warnings)
            ?: return null
        emit(MovieAssembler.AssembleStage.DONE, total, total,
            "xfade transition done (${transition.xfadeName})", t0)
        val base = MovieAssembler.AssembleResult.Success(
            output = merged,
            strategy = MovieAssembler.Strategy.NORMALIZE,
            elapsedMs = System.currentTimeMillis() - t0,
            durationSeconds = 0.0,
        )
        return applyPostProcessing(base, output, options, warnings, total, t0)
    }

    /**
     * xfade + acrossfade 合成命令。
     *
     * filter_complex 结构（N 镜）：
     *   每镜：[i:v]scale/pad 720x1280,setsar=1,fps=24,settb=AVTB,setpts=PTS-STARTPTS[v i]
     *         [i:a]aresample=48000,aformat=fltp/stereo,asetpts=PTS-STARTPTS[a i]
     *   链式：[上一段视频][v i]xfade=transition=X:duration=T:offset=O[x i]
     *         [上一段音频][a i]acrossfade=d=T:c1=tri:c2=tri[ax i]
     *   offset 递推：O1 = d0 - T；O_i = Σ_{k<i} d_k - i*T。
     * 成片总时长 = Σd - (N-1)*T（用 -t 收尾，避免尾部多余帧）。
     */
    private fun renderXfade(
        inputs: List<File>,
        infos: List<MovieAssembler.ClipInfo>,
        output: File,
        transition: MovieAssembler.Transition,
        transitionSeconds: Double,
        total: Int,
        t0: Long,
        warnings: MutableList<String>,
    ): File? {
        val tmp = File(output.parentFile ?: File("."), "${output.nameWithoutExtension}_xfade.mp4")
        tmp.parentFile?.mkdirs()
        runCatching { tmp.delete() }
        val sb = StringBuilder()
        for (i in inputs.indices) {
            sb.append("[$i:v]scale=720:1280:force_original_aspect_ratio=decrease,")
                .append("pad=720:1280:(ow-iw)/2:(oh-ih)/2,setsar=1,fps=24,settb=AVTB,setpts=PTS-STARTPTS[v$i];")
                .append("[$i:a]aresample=48000,aformat=sample_fmts=fltp:channel_layouts=stereo,asetpts=PTS-STARTPTS[a$i];")
        }
        var lastVideo = "v0"
        var lastAudio = "a0"
        var offset = infos[0].durationMs / 1000.0 - transitionSeconds
        for (i in 1 until inputs.size) {
            val vOut = "vx$i"
            val aOut = "ax$i"
            sb.append("[$lastVideo][v$i]xfade=transition=${transition.xfadeName}:duration=")
                .append(transitionSeconds.toString())
                .append(":offset=").append(fmtSeconds(offset))
                .append("[$vOut];")
            sb.append("[$lastAudio][a$i]acrossfade=d=")
                .append(transitionSeconds.toString())
                .append(":c1=tri:c2=tri[$aOut];")
            lastVideo = vOut
            lastAudio = aOut
            offset += infos[i].durationMs / 1000.0 - transitionSeconds
        }
        val filter = sb.toString().trimEnd(';')
        val totalDuration = (infos.sumOf { it.durationMs } / 1000.0) - (inputs.size - 1) * transitionSeconds
        val head = listOf("-y") +
            inputs.flatMap { listOf("-i", it.absolutePath) } +
            listOf("-filter_complex", filter, "-map", "[$lastVideo]", "-map", "[$lastAudio]")
        val tail = listOf(
            "-pix_fmt", "yuv420p", "-r", "24",
            "-c:a", "aac", "-b:a", "160k", "-ar", "48000", "-ac", "2",
            "-movflags", "+faststart", "-t", fmtSeconds(totalDuration), tmp.absolutePath,
        )
        val attempts = listOf(
            head + listOf("-c:v", "libx264", "-preset", "veryfast", "-crf", "20") + tail,
            // 兜底：端侧硬编（不接受 -crf/-preset）
            head + listOf("-c:v", "h264_mediacodec", "-b:v", "2M") + tail,
        )
        for ((idx, args) in attempts.withIndex()) {
            emit(MovieAssembler.AssembleStage.NORMALIZE, idx, total, "xfade encode attempt ${idx + 1}", t0)
            val (rc, err) = executor.execute(args)
            if (rc == 0 && tmp.exists() && tmp.length() > 0) {
                val replaced = safeReplace(tmp, output)
                if (replaced != null) return output
                warnings += "转场成片替换失败"
                return null
            }
            warnings += "转场编码 attempt ${idx + 1} 失败 rc=$rc ${err.take(160)}"
            runCatching { tmp.delete() }
        }
        return null
    }

    /**
     * 后处理：字幕 sidecar + BGM 混音 + 响度归一化，并回填真实时长。
     * 全部可失败降级：字幕写不出/BGM 混音失败都不影响成片本身（但必须进 warnings，不静默）。
     */
    private fun applyPostProcessing(
        base: MovieAssembler.AssembleResult.Success,
        output: File,
        options: MovieAssembler.AssembleOptions,
        warnings: MutableList<String>,
        total: Int,
        t0: Long,
    ): MovieAssembler.AssembleResult {
        val cues = options.subtitles.filter { it.text.isNotBlank() && it.endMs > it.startMs }
        val srt = if (cues.isNotEmpty()) writeSubtitleSidecar(cues, output) else null
        if (srt != null) {
            emit(MovieAssembler.AssembleStage.DONE, total, total, "subtitle sidecar: ${srt.name}", t0)
        }
        val bgm = options.bgm
        val wantAudio = options.loudnessNormalize || (bgm != null && bgm.exists() && bgm.length() > 0)
        var result: MovieAssembler.AssembleResult.Success = base
        if (wantAudio) {
            emit(MovieAssembler.AssembleStage.NORMALIZE, total, total, "post audio mix", t0)
            val mixed = mixAudio(base.output, output, bgm, options.loudnessNormalize, warnings)
            result = if (mixed != null) {
                base.copy(
                    output = mixed,
                    subtitleFile = srt,
                    elapsedMs = System.currentTimeMillis() - t0,
                )
            } else {
                warnings += "后处理（BGM/响度）失败，保留原音轨"
                emit(MovieAssembler.AssembleStage.NORMALIZE, total, total,
                    "post audio mix failed, keep original audio", t0)
                base.copy(subtitleFile = srt)
            }
        } else {
            result = base.copy(subtitleFile = srt)
        }
        // D3：用探测到的成片真实时长回填（probe 不可用时保持原值 0.0，兼容旧行为）
        val probedDuration = probe?.let { runCatching { it.probe(result.output) }.getOrNull() }
            ?.durationMs?.takeIf { it > 0 }?.let { it / 1000.0 }
        return result.copy(
            durationSeconds = probedDuration ?: result.durationSeconds,
            warnings = (result.warnings + warnings).distinct(),
        )
    }

    /** 写同名 .srt 字幕；失败返回 null（不阻断成片）。 */
    private fun writeSubtitleSidecar(
        cues: List<MovieAssembler.SubtitleCue>,
        output: File,
    ): File? = runCatching {
        fun ts(ms0: Long): String {
            val ms = ms0.coerceAtLeast(0)
            return "%02d:%02d:%02d,%03d".format(
                ms / 3_600_000, (ms % 3_600_000) / 60_000, (ms % 60_000) / 1_000, ms % 1_000)
        }
        val body = cues.sortedBy { it.startMs }.mapIndexed { i, c ->
            "${i + 1}\n${ts(c.startMs)} --> ${ts(c.endMs)}\n${c.text.trim().replace("\n", " ")}\n"
        }.joinToString("\n")
        val srt = File(output.parentFile ?: File("."), output.nameWithoutExtension + ".srt")
        srt.writeText(body)
        srt
    }.getOrNull()

    /**
     * BGM 混音 + 响度归一化。
     *
     * ★D5：
     * - amix 必须 normalize=0（默认按输入数缩放，人声会减半）；
     * - loudnorm 之后接 aresample=48000 + alimiter，避免 loudnorm 内部 192kHz 直进 AAC 与削波；
     * - 一律先写临时文件，成功后再通过 [safeReplace] 原子替换，绝不先 delete 原片再 copy。
     */
    private fun mixAudio(
        baseFile: File,
        output: File,
        bgm: File?,
        loudness: Boolean,
        warnings: MutableList<String>,
    ): File? {
        output.parentFile?.mkdirs()
        val tmp = File(output.parentFile ?: File("."), output.nameWithoutExtension + "_mix.mp4")
        runCatching { tmp.delete() }
        val aacOut = listOf("-c:a", "aac", "-b:a", "160k", "-ar", "48000", "-ac", "2")
        val attempts = mutableListOf<List<String>>()
        if (bgm != null) {
            val a0 = if (loudness) {
                "[0:a]loudnorm=I=-16:TP=-1.5:LRA=11,aresample=48000[a0]"
            } else {
                "[0:a]aresample=48000[a0]"
            }
            val bg = "[1:a]aresample=48000,aformat=sample_fmts=fltp:channel_layouts=stereo,volume=0.22[bgm]"
            val mix = if (loudness) {
                "[a0][bgm]amix=inputs=2:duration=first:dropout_transition=0:normalize=0[mix];" +
                    "[mix]alimiter=limit=0.98[aout]"
            } else {
                "[a0][bgm]amix=inputs=2:duration=first:dropout_transition=0:normalize=0[aout]"
            }
            attempts += listOf(
                "-y", "-i", baseFile.absolutePath, "-stream_loop", "-1", "-i", bgm.absolutePath,
                "-filter_complex", "$a0;$bg;$mix",
                "-map", "0:v:0", "-map", "[aout]", "-c:v", "copy",
            ) + aacOut + listOf("-movflags", "+faststart", tmp.absolutePath)
            // 成片无音轨时，退化为「原视频 + BGM」
            val bgmOnly = if (loudness) {
                "[1:a]aresample=48000,aformat=sample_fmts=fltp:channel_layouts=stereo," +
                    "volume=0.22,loudnorm=I=-16:TP=-1.5:LRA=11,alimiter=limit=0.98[aout]"
            } else {
                "[1:a]aresample=48000,aformat=sample_fmts=fltp:channel_layouts=stereo," +
                    "volume=0.22[aout]"
            }
            attempts += listOf(
                "-y", "-i", baseFile.absolutePath, "-stream_loop", "-1", "-i", bgm.absolutePath,
                "-filter_complex", bgmOnly,
                "-map", "0:v:0", "-map", "[aout]", "-c:v", "copy",
            ) + aacOut + listOf("-shortest", "-movflags", "+faststart", tmp.absolutePath)
        } else if (loudness) {
            attempts += listOf(
                "-y", "-i", baseFile.absolutePath,
                "-map", "0:v:0", "-map", "0:a:0?",
                "-af", "loudnorm=I=-16:TP=-1.5:LRA=11,aresample=48000,alimiter=limit=0.98",
                "-c:v", "copy",
            ) + aacOut + listOf("-movflags", "+faststart", tmp.absolutePath)
        }
        for ((idx, args) in attempts.withIndex()) {
            val (rc, err) = executor.execute(args)
            if (rc == 0 && tmp.exists() && tmp.length() > 0) {
                val replaced = safeReplace(tmp, output)
                if (replaced != null) return output
                warnings += "后处理文件替换失败 attempt ${idx + 1}"
            } else {
                warnings += "后处理 attempt ${idx + 1} 失败 rc=$rc ${err.take(120)}"
            }
            runCatching { tmp.delete() }
        }
        return null
    }

    /**
     * 用临时文件原子替换 [output]：先把旧文件改名为 .bak，替换成功才删 .bak；
     * 替换失败恢复 .bak。绝不先 delete 原片（D5）。
     */
    private fun safeReplace(tmp: File, output: File): File? = runCatching {
        if (!tmp.exists() || tmp.length() == 0L) return@runCatching null
        val parent = output.parentFile ?: return@runCatching null
        parent.mkdirs()
        val backup = File(parent, output.name + ".bak")
        if (backup.exists()) backup.delete()
        val hadOutput = output.exists()
        if (hadOutput && !output.renameTo(backup)) {
            output.copyTo(backup, overwrite = true)
            output.delete()
        }
        val moved = tmp.renameTo(output) ||
            runCatching { tmp.copyTo(output, overwrite = true); true }.getOrDefault(false)
        if (!moved || !output.exists() || output.length() == 0L) {
            if (output.exists()) runCatching { output.delete() }
            if (hadOutput && backup.exists()) runCatching { backup.renameTo(output) }
            return@runCatching null
        }
        runCatching { backup.delete() }
        output
    }.getOrNull()

    /**
     * 给单镜补一条音轨：输入已有音轨时保留为第一音轨（[i:a] 取到的仍是原声），
     * 输入无音轨时第一音轨即 anullsrc 静音。视频流直接 copy，不做二次编码。
     */
    private fun ensureAudioTrack(clip: File, idx: Int, dir: File): File? {
        dir.mkdirs()
        val out = File(dir, "sa_%03d.mp4".format(idx))
        val args = listOf(
            "-y", "-i", clip.absolutePath,
            "-f", "lavfi", "-i", "anullsrc=r=48000:cl=stereo",
            "-map", "0:v:0", "-map", "0:a?", "-map", "1:a",
            "-c:v", "copy", "-c:a", "aac", "-b:a", "128k", "-ar", "48000", "-ac", "2",
            "-shortest", out.absolutePath,
        )
        val (rc, _) = executor.execute(args)
        return if (rc == 0 && out.exists() && out.length() > 0) out else null
    }

    private fun fmtSeconds(v: Double): String = String.format(java.util.Locale.US, "%.3f", v)
}

/**
 * 端侧 executor：用 Java 反射调用 ffmpeg-kit community 维护版 8.1.7
 * (com.arthenica.ffmpegkit.FFmpegKit，包名与原版一致，drop-in 兼容)。
 *
 * 说明：core-engine 是 JVM 纯 Kotlin 模块，不能直接依赖 Android AAR；生产端通过
 * 此 factory 在 app 模块初始化 [MovieAssemblerImpl] 时注入。反射兜底兼容未来 ffmpeg-kit 版本。
 *
 * @throws MovieAssembler.NotAvailableException 当 ffmpeg-kit 类/native 不可用
 */
fun androidFfmpegKitExecutor(): MovieAssemblerExecutor {
    return MovieAssemblerExecutor { args ->
        try {
            // v1.6.4 修：远程把 ffmpeg-kit 换成 community-maintained 8.1.7，
            // 其顶层类名从 FFmpeg 改为 FFmpegKit（com.arthenica.ffmpegkit.FFmpeg 不存在了）。
            // 优先新类名（8.1.7），回退到老类名（jitpack 5.1 兼容）。
            val fc = runCatching { Class.forName("com.arthenica.ffmpegkit.FFmpegKit") }
                .getOrElse { Class.forName("com.arthenica.ffmpegkit.FFmpeg") }
            val sc = runCatching { Class.forName("com.arthenica.ffmpegkit.FFmpegSession") }.getOrNull()
                ?: Class.forName("com.arthenica.ffmpegkit.FFmpegSession")
            val rc = Class.forName("com.arthenica.ffmpegkit.ReturnCode")
            // v1.6.9 修：删掉死代码 lv.getField("QUIET")（8.1.7 的 Level 字段是 AV_LOG_QUIET，
            // getField("QUIET") 抛 NoSuchFieldException，不在 catch 列表 -> 逃逸成「合成失败: QUIET」）。
            // 异步执行路径。
            // 不等执行完成（老版 executeFFmpeg 是同步等）。直接 getReturnCode 拿到的是创建时
            // 初始值（SUCCESS），永远成功 → 合成看似成功实际没跑。
            // 解决：放弃同步 API，统一走异步 + CountDownLatch 等 FFmpegSessionCompleteCallback。
            @Suppress("UNCHECKED_CAST")
            val callback = Class.forName("com.arthenica.ffmpegkit.FFmpegSessionCompleteCallback")
            @Suppress("UNCHECKED_CAST")
            val async = runCatching { fc.getMethod("executeWithArgumentsAsync", Array<String>::class.java, callback) }
                .recoverCatching { fc.getMethod("executeAsyncFFmpeg", Array<String>::class.java, callback) }
                .getOrNull() ?: throw MovieAssembler.NotAvailableException("ffmpeg-kit 找不到 executeWithArgumentsAsync 方法")
            val latch = java.util.concurrent.CountDownLatch(1)
            var result: Any? = null
            val cb = java.lang.reflect.Proxy.newProxyInstance(
                callback.classLoader, arrayOf(callback),
                { _, m, a ->
                    if (m.name == "apply") { result = a?.firstOrNull(); latch.countDown() }
                    null
                })
            async.invoke(null, args.toTypedArray(), cb)
            val completed = latch.await(30, java.util.concurrent.TimeUnit.MINUTES)
            if (!completed) throw MovieAssembler.NotAvailableException("ffmpeg-kit 30分钟未完成")
            val session = result ?: throw MovieAssembler.NotAvailableException("ffmpeg-kit 回调 session 为空")
            val getRc = sc.getMethod("getReturnCode")
            val getVal = rc.getMethod("getValue")
            // ★D15：旧实现 stderr 恒为 ""，失败原因彻底丢失；这里取 session.getOutput()
            // （ffmpeg log）尾部 4000 字符作为第二返回值，供上层 Failure/warnings 使用。
            val output = runCatching {
                val getOutput = sc.getMethod("getOutput")
                getOutput.invoke(session) as? String
            }.getOrNull().orEmpty()
            (getVal.invoke(getRc.invoke(session)) as Int) to output.takeLast(4000)
        } catch (e: UnsatisfiedLinkError) {
            throw MovieAssembler.NotAvailableException("ffmpeg-kit native 未加载: ${e.message}. 需要升级到 v1.5 远程合成方案")
        } catch (e: NoClassDefFoundError) {
            throw MovieAssembler.NotAvailableException("ffmpeg-kit 类缺失: ${e.message}. 需要升级到 v1.5 远程合成方案")
        } catch (e: ClassNotFoundException) {
            throw MovieAssembler.NotAvailableException("ffmpeg-kit 未找到: ${e.message}. 需要升级到 v1.5 远程合成方案")
        } catch (e: NoSuchFieldException) {
            throw MovieAssembler.NotAvailableException("ffmpeg-kit 字段缺失: ${e.message}")
        } catch (e: NoSuchMethodException) {
            throw MovieAssembler.NotAvailableException("ffmpeg-kit 方法缺失: ${e.message}")
        } catch (e: MovieAssembler.NotAvailableException) {
            throw e
        } catch (e: Exception) {
            // ★D15：不再让 InvocationTargetException 等反射异常逃逸成「合成失败: QUIET」类事故
            throw MovieAssembler.NotAvailableException("ffmpeg-kit 调用异常: ${e.message}")
        }
    }
}

/**
 * 端侧 probe：反射 ffmpeg-kit FFprobeKit 取单镜时长/音轨/分辨率/帧率。
 *
 * 任何一环失败都返回 null（调用方按旧行为降级，不得因探测失败阻断合成）。
 * API 依据：FFprobeKit.getMediaInformation(String) 同步返回 MediaInformationSession，
 * getMediaInformation() 取 MediaInformation，getDuration() 秒字符串，getStreams() 取流信息。
 */
fun androidFfmpegKitProbe(): MovieAssembler.MovieAssemblerProbe = MovieAssembler.MovieAssemblerProbe { file ->
    try {
        val ffprobeKit = Class.forName("com.arthenica.ffmpegkit.FFprobeKit")
        val sessionClass = Class.forName("com.arthenica.ffmpegkit.MediaInformationSession")
        val session = ffprobeKit.getMethod("getMediaInformation", String::class.java)
            .invoke(null, file.absolutePath) ?: return@MovieAssemblerProbe null
        val mediaInfo = sessionClass.getMethod("getMediaInformation").invoke(session)
            ?: return@MovieAssemblerProbe null
        val miClass = mediaInfo.javaClass
        val durationMs = (miClass.getMethod("getDuration").invoke(mediaInfo) as? String)
            ?.toDoubleOrNull()?.times(1000.0)?.toLong() ?: 0L
        @Suppress("UNCHECKED_CAST")
        val streams = miClass.getMethod("getStreams").invoke(mediaInfo) as? List<Any?> ?: emptyList()
        var hasAudio = false
        var width = 0
        var height = 0
        var fps = 0.0
        for (stream in streams) {
            if (stream == null) continue
            val streamClass = stream.javaClass
            val type = streamClass.getMethod("getType").invoke(stream) as? String ?: continue
            if (type == "audio") hasAudio = true
            if (type == "video") {
                width = (streamClass.getMethod("getWidth").invoke(stream) as? Long)?.toInt() ?: width
                height = (streamClass.getMethod("getHeight").invoke(stream) as? Long)?.toInt() ?: height
                val avgFps = streamClass.getMethod("getAverageFrameRate").invoke(stream) as? String
                fps = parseFrameRate(avgFps) ?: fps
            }
        }
        MovieAssembler.ClipInfo(
            durationMs = durationMs,
            hasAudio = hasAudio,
            width = width,
            height = height,
            fps = fps,
        )
    } catch (t: Throwable) {
        null
    }
}

/** 解析 ffprobe 的帧率字符串（如 "24/1"、"30000/1001"、"24"）；非法返回 null。 */
private fun parseFrameRate(raw: String?): Double? {
    val value = raw?.trim()?.takeIf { it.isNotEmpty() && it != "0/0" } ?: return null
    return if (value.contains('/')) {
        val num = value.substringBefore('/').toDoubleOrNull()
        val den = value.substringAfter('/').toDoubleOrNull()
        if (num != null && den != null && den > 0.0) num / den else null
    } else {
        value.toDoubleOrNull()
    }
}
