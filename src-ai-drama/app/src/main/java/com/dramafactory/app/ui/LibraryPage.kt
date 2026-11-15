package com.dramafactory.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import com.dramafactory.app.ui.components.PageHeader
import androidx.compose.material3.Icon
import androidx.compose.ui.res.painterResource
import com.dramafactory.app.R
import com.dramafactory.app.ui.components.DramaCard
import com.dramafactory.app.ui.components.EmptyState
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import android.widget.VideoView
import android.net.Uri
import com.dramafactory.app.data.RenderTaskEntity
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.unit.dp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import android.media.MediaPlayer
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.saveable.rememberSaveable
import kotlin.math.roundToInt

/**
 * 成片库页（S7）：已完成集数列表 + 导出/分享（Android ACTION_SEND分享mp4）。
 * 数据源：render_tasks中该集COMPLETED镜全部齐备 → FfmpegAssembler合成成片后可分享。
 */
@Composable
fun LibraryPage() {
    val context = LocalContext.current
    var shareMsg by remember { mutableStateOf<String?>(null) }
    var composingEp by remember { mutableStateOf<String?>(null) }
    // v1.7.2：成片直接预览——点预览后弹出本地播放器播放已合成成片
    var previewUri by remember { mutableStateOf<Uri?>(null) }
    // ★第三批：预览时锁定预览的集号（字幕时间轴按该集 shots 构建）
    var previewEp by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    // ★第三批：BGM 选曲——页面级 launcher，bgmTargetEp 记录本次要设置 BGM 的集
    var bgmTargetEp by remember { mutableStateOf<String?>(null) }
    // epId -> 当前 BGM 文件名（null=未选择）；BGM 是项目级配置，同项目各集同步回填
    val bgmNames = remember { mutableStateMapOf<String, String?>() }
    // episodes 必须先声明：bgmPicker 回调里要按项目刷新各集 BGM 回显
    val episodes = remember { mutableStateOf<List<Triple<String, Int, Int>>>(emptyList()) }
    // ★第四批：BGM 音量（项目级共享 projects.bgm_volume，0..1）与已落盘 BGM 绝对路径（试听/音量落库用）
    val bgmVolumes = remember { mutableStateMapOf<String, Double>() }
    val bgmPaths = remember { mutableStateMapOf<String, String?>() }
    // ★第四批：BGM 试听——MediaPlayer 用数组槽位持有（同 vvHolder 思路，不把 Android 对象放进 Compose state）
    val bgmPlayerHolder = remember { arrayOfNulls<MediaPlayer>(1) }
    var playingBgmEp by remember { mutableStateOf<String?>(null) }
    // ★第四批：音量防抖落库——拖动只改本地 state，停顿 350ms 后写一次数据库
    var bgmVolSaveReq by remember { mutableStateOf(0) }
    var bgmVolSaveTarget by remember { mutableStateOf<Triple<String, String?, Double>?>(null) }

    // 停止试听并释放 MediaPlayer（可能尚未初始化/已释放，统一 runCatching 兜底）
    fun stopBgmPreview() {
        playingBgmEp = null
        val mp = bgmPlayerHolder[0] ?: return
        bgmPlayerHolder[0] = null
        runCatching { mp.release() }
    }

    // 试听：本地 BGM 文件同步 prepare（本地小文件，主线程可接受）；任何失败都提示，不静默
    fun startBgmPreview(epId: String) {
        stopBgmPreview()
        val file = bgmPaths[epId]?.takeIf { it.isNotBlank() }?.let { java.io.File(it) }
        if (file == null || !file.exists() || file.length() <= 0L) {
            shareMsg = "BGM 试听失败：本地 BGM 文件不存在，请重新选择 BGM"
            return
        }
        val mp = MediaPlayer()
        bgmPlayerHolder[0] = mp
        val result = runCatching {
            mp.setAudioAttributes(
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            mp.setDataSource(file.absolutePath)
            mp.setOnCompletionListener { stopBgmPreview() }
            mp.setOnErrorListener { _, _, _ ->
                stopBgmPreview()
                shareMsg = "BGM 试听失败：无法播放该音频文件"
                true
            }
            mp.prepare()
            mp.start()
        }
        if (result.isSuccess) {
            playingBgmEp = epId
        } else {
            stopBgmPreview()
            shareMsg = "BGM 试听失败：" + (result.exceptionOrNull()?.message ?: "无法播放该音频文件")
        }
    }

    // 离开成片库页面时释放试听播放器，避免音频泄漏/后台继续播放
    DisposableEffect(Unit) {
        onDispose {
            bgmPlayerHolder[0]?.let { mp -> runCatching { mp.release() } }
            bgmPlayerHolder[0] = null
        }
    }

    val bgmPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        val epId = bgmTargetEp
        bgmTargetEp = null
        if (uri == null || epId == null) return@rememberLauncherForActivityResult
        scope.launch {
            // content:// 只有临时读权限：立即拷进 filesDir/bgm/<epId>.<ext>（同名覆盖，可长期合成用）
            val local: java.io.File? = runCatching {
                withContext(Dispatchers.IO) {
                    val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull()
                    val ext = when {
                        mime?.contains("mpeg", ignoreCase = true) == true -> "mp3"
                        mime?.contains("mp4", ignoreCase = true) == true -> "m4a"
                        mime?.contains("aac", ignoreCase = true) == true -> "m4a"
                        mime?.contains("ogg", ignoreCase = true) == true -> "ogg"
                        mime?.contains("wav", ignoreCase = true) == true -> "wav"
                        mime?.contains("flac", ignoreCase = true) == true -> "flac"
                        else -> "mp3"
                    }
                    val dir = java.io.File(context.filesDir, "bgm").apply { mkdirs() }
                    val target = java.io.File(dir, "$epId.$ext")
                    val ok = context.contentResolver.openInputStream(uri)?.use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                        target.length() > 0L   // 空文件视为失败（选到被移除/不可读的媒体）
                    } ?: false
                    if (!ok) throw java.io.IOException("所选音频无法读取（可能已损坏或被移除）")
                    target
                }
            }.getOrElse { e ->
                shareMsg = "BGM 设置失败：" + (e.message ?: "读取音频失败")
                null
            }
            if (local == null) return@launch
            val projectId = epId.substringBefore("_ep")
            runCatching {
                com.dramafactory.app.AppGraph.dao.setProjectBgm(projectId, local.absolutePath, 0.22)
            }.onSuccess {
                // 同项目各集共用一条 BGM，回显/路径/音量一起刷新（新选 BGM 音量回到默认 0.22）
                stopBgmPreview()
                episodes.value.filter { it.first.substringBefore("_ep") == projectId }
                    .forEach {
                        bgmNames[it.first] = local.name
                        bgmPaths[it.first] = local.absolutePath
                        bgmVolumes[it.first] = 0.22
                    }
                shareMsg = "BGM 已设置：${local.name}（重新合成后生效）"
            }.onFailure { e -> shareMsg = "BGM 落库失败：" + (e.message ?: "数据库写入失败") }
        }
    }

    // MVP：从Room读全部episode及其完成度（简单同步读取在VM更佳，此处直接组合DAO）
    androidx.compose.runtime.LaunchedEffect(Unit) {
        episodes.value = runCatching {
            com.dramafactory.app.AppGraph.dao.allEpisodeIds().map { epId ->
                val tasks = com.dramafactory.app.AppGraph.dao.renderTasksOf(epId)
                Triple(epId, tasks.count { it.state == "COMPLETED" }, tasks.size)
            }
        }.getOrDefault(emptyList())
        // ★第三批：回填每集当前 BGM 文件名（项目级配置，来自 projects.bgm_path）
        // ★第四批：同时回填 BGM 绝对路径与音量（音量滑杆初值读 projects.bgm_volume，默认 0.22）
        bgmNames.clear()
        bgmPaths.clear()
        bgmVolumes.clear()
        episodes.value.forEach { (epId, _, _) ->
            val bgm = runCatching {
                com.dramafactory.app.AppGraph.bgmForEpisode(epId)
            }.getOrNull()
            bgmNames[epId] = bgm?.first?.name
            bgmPaths[epId] = bgm?.first?.absolutePath
            bgmVolumes[epId] = bgm?.second ?: 0.22
        }
    }

    // ★第四批：BGM 音量防抖落库——拖动滑杆期间只更新本地 state，停顿 350ms 后写一次数据库
    LaunchedEffect(bgmVolSaveReq) {
        if (bgmVolSaveReq <= 0) return@LaunchedEffect
        kotlinx.coroutines.delay(350L)
        val target = bgmVolSaveTarget ?: return@LaunchedEffect
        val (epId, bgmPath, volume) = target
        val projectId = epId.substringBefore("_ep")
        runCatching {
            com.dramafactory.app.AppGraph.dao.setProjectBgm(projectId, bgmPath, volume)
        }.onFailure { e -> shareMsg = "BGM 音量保存失败：" + (e.message ?: "数据库写入失败") }
    }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PageHeader(title = "成片库", subtitle = "已合成剧集 · 可播放与分享")

        if (episodes.value.isEmpty()) {
            EmptyState(
                icon = { Icon(painterResource(R.drawable.ic_video_library), contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp)) },
                title = "还没有成片",
                subtitle = "渲染并合成完成后，成片会出现在这里，可直接播放或分享。",
            )
            return@Column
        }

        // weight(1f)：同 QueuePage —— 外层是 fillMaxSize 的 Column，不给高度约束会让
        // LazyColumn 以无限高度测量，退化为全量排版且超出屏幕的部分滚不到
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((epId, done, total) in episodes.value) {
                item(key = epId) {
                    DramaCard(Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier,
                            verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("第$epId 集", style = MaterialTheme.typography.titleMedium)
                            val doneAll = done == total && total > 0
                            Text(if (doneAll) "已完成 · 可合成" else "$done/$total 镜完成",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (doneAll) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.outline)
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                // v1.7.2：直接预览——播放已合成成片(cacheDir/$epId.mp4)，无则提示先合成
                                OutlinedButton(onClick = {
                                    // ★第二批 P0-D2：统一从 filesDir/movies 找片（兼容旧 cacheDir 位置）
                                    val f = com.dramafactory.app.FilmPaths.resolveExisting(context, epId)
                                    if (f != null) {
                                        // 预览成片前停掉 BGM 试听，避免两路音频同时响
                                        stopBgmPreview()
                                        previewEp = epId
                                        previewUri = Uri.fromFile(f)
                                    } else shareMsg = "成片尚未合成，请先点「合成」"
                                }, modifier = Modifier.weight(1f)) { Text("预览") }
                                Button(onClick = {
                                    // 第十三轮 P0-3：点合成，触发后台合成
                                    composingEp = epId
                                }, enabled = doneAll, modifier = Modifier.weight(1f)) { Text("合成") }
                                Button(onClick = {
                                    // ★第二批 P0-D2：成片在 filesDir（FileProvider 白名单只含 cache），
                                    // 分享前先复制到 cacheDir/share 再走 FileProvider；IO 不占主线程。
                                    scope.launch {
                                        runCatching {
                                            val shareFile = withContext(Dispatchers.IO) {
                                                val src = com.dramafactory.app.FilmPaths
                                                    .resolveExisting(context, epId)
                                                    ?: return@withContext null
                                                val shareDir = java.io.File(context.cacheDir, "share")
                                                if (!shareDir.exists()) shareDir.mkdirs()
                                                val dst = java.io.File(shareDir, "$epId.mp4")
                                                if (!dst.exists() || dst.length() != src.length()) {
                                                    src.copyTo(dst, overwrite = true)
                                                }
                                                dst
                                            } ?: run {
                                                shareMsg = "成片尚未合成到本地"
                                                null
                                            }
                                            if (shareFile != null) {
                                                val uri = androidx.core.content.FileProvider.getUriForFile(
                                                    context, context.packageName + ".fileprovider", shareFile)
                                                val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                                    type = "video/mp4"
                                                    putExtra(android.content.Intent.EXTRA_STREAM, uri)
                                                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                                }
                                                context.startActivity(android.content.Intent.createChooser(intent, "分享成片"))
                                            }
                                        }.onFailure { shareMsg = it.message ?: "分享失败" }
                                    }
                                }, enabled = doneAll, modifier = Modifier.weight(1f)) { Text("分享") }
                            }
                            // ★第三批：BGM 选曲（项目级：同项目各集共用一条 BGM，合成时混音）
                            Row(verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("BGM：${bgmNames[epId] ?: "未选择"}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.outline,
                                    maxLines = 1,
                                    modifier = Modifier.weight(1f))
                                OutlinedButton(onClick = {
                                    bgmTargetEp = epId
                                    bgmPicker.launch(arrayOf("audio/*"))
                                }) { Text("选择 BGM") }
                                // ★第四批：BGM 试听/停止（MediaPlayer 播放已落盘本地 BGM，未选 BGM 时禁用）
                                OutlinedButton(
                                    enabled = bgmNames[epId] != null,
                                    onClick = {
                                        if (playingBgmEp == epId) stopBgmPreview() else startBgmPreview(epId)
                                    },
                                ) { Text(if (playingBgmEp == epId) "停止" else "试听") }
                                if (bgmNames[epId] != null) {
                                    OutlinedButton(onClick = {
                                        // 清除前先停掉试听，避免停在一个已删除的文件上继续响
                                        stopBgmPreview()
                                        val projectId = epId.substringBefore("_ep")
                                        scope.launch {
                                            runCatching {
                                                com.dramafactory.app.AppGraph.dao.setProjectBgm(projectId, null, 0.22)
                                            }.onSuccess {
                                                episodes.value
                                                    .filter { it.first.substringBefore("_ep") == projectId }
                                                    .forEach {
                                                        bgmNames[it.first] = null
                                                        bgmPaths[it.first] = null
                                                        bgmVolumes[it.first] = 0.22
                                                    }
                                                shareMsg = "已清除 BGM（重新合成后生效）"
                                            }.onFailure { e ->
                                                shareMsg = "清除 BGM 失败：" + (e.message ?: "数据库写入失败")
                                            }
                                        }
                                    }) { Text("清除") }
                                }
                            }
                            // ★第四批：BGM 音量滑杆（项目级 projects.bgm_volume，0..100%；未选 BGM 时禁用）
                            val bgmVolume = bgmVolumes[epId] ?: 0.22
                            Row(verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("音量", style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.outline)
                                Slider(
                                    value = bgmVolume.coerceIn(0.0, 1.0).toFloat(),
                                    onValueChange = { v ->
                                        // 拖动时只改本地 state（同项目各集实时同步回显），防抖后再落库
                                        val volume = v.toDouble().coerceIn(0.0, 1.0)
                                        val projectId = epId.substringBefore("_ep")
                                        episodes.value
                                            .filter { it.first.substringBefore("_ep") == projectId }
                                            .forEach { bgmVolumes[it.first] = volume }
                                        bgmVolSaveTarget = Triple(epId, bgmPaths[epId], volume)
                                        bgmVolSaveReq += 1
                                    },
                                    valueRange = 0f..1f,
                                    enabled = bgmNames[epId] != null,
                                    modifier = Modifier.weight(1f),
                                )
                                Text("${(bgmVolume * 100).roundToInt()}%",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.outline)
                            }
                        }
                    }
                }
            }
        }
    }

    shareMsg?.let {
        androidx.compose.material3.AlertDialog(onDismissRequest = { shareMsg = null },
            title = { Text("提示") }, text = { Text(it) },
            confirmButton = { Button(onClick = { shareMsg = null }) { Text("知道了") } })
    }

    // v1.7.2：成片库直接预览——本地 VideoView 弹窗播放已合成成片
    previewUri?.let { uri ->
        // ★第三批：预览字幕叠加——底部居中的半透明字幕层（数据来自该集 shots 的台词/旁白）
        var subtitleLine by remember(uri) { mutableStateOf<String?>(null) }
        // ★第四批：字幕开关 + 小/中/大三档字号（弹窗内临时状态，rememberSaveable 即可，不落库）
        var subtitleEnabled by rememberSaveable(uri) { mutableStateOf(true) }
        var subtitleSizeIdx by rememberSaveable(uri) { mutableStateOf(1) }
        // 字幕字号随档位变：小 12sp / 中 14sp / 大 16sp
        val subtitleFontSize = when (subtitleSizeIdx) {
            0 -> 12.sp
            2 -> 16.sp
            else -> 14.sp
        }
        // VideoView 实例用普通数组槽位持有：AndroidView factory 里不写 Compose state，避免额外重组
        val vvHolder = remember(uri) { arrayOfNulls<VideoView>(1) }
        LaunchedEffect(uri, previewEp, subtitleEnabled) {
            // 字幕关闭时立刻清空当前字幕并停止时间轴轮询（重新打开会重新构建时间轴）
            if (!subtitleEnabled) {
                subtitleLine = null
                return@LaunchedEffect
            }
            val epId = previewEp ?: return@LaunchedEffect
            // 时间轴：按 shot_no 排序后简单累计 duration_seconds（转场重叠忽略，与成片字幕轨同口径）
            val timeline = runCatching {
                var cursorMs = 0L
                com.dramafactory.app.AppGraph.dao.shotsOf(epId).sortedBy { it.shot_no }.mapNotNull { shot ->
                    val durMs = ((shot.duration_seconds.takeIf { it > 0.0 } ?: 5.0) * 1000).toLong()
                    val startMs = cursorMs
                    cursorMs += durMs
                    val line = shot.dialogue?.takeIf { it.isNotBlank() }
                        ?: shot.narration?.takeIf { it.isNotBlank() }
                    line?.let { text -> Triple(startMs, cursorMs, text) }
                }
            }.getOrDefault(emptyList())
            if (timeline.isEmpty()) {
                subtitleLine = null
                return@LaunchedEffect
            }
            // 每 250ms 读播放位置映射到当前镜头字幕；弹窗关闭（离开组合）时 effect 取消，轮询即停止
            while (true) {
                val pos = vvHolder[0]?.currentPosition?.toLong() ?: 0L
                subtitleLine = timeline.firstOrNull { pos >= it.first && pos < it.second }?.third
                kotlinx.coroutines.delay(250L)
            }
        }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { previewUri = null; previewEp = null },
            confirmButton = { Button(onClick = { previewUri = null; previewEp = null }) { Text("关闭") } },
            title = {
                // ★第四批：预览工具栏——字幕开关 + 小/中/大三档字号（仅本次预览生效，不落库）
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("字幕", style = MaterialTheme.typography.bodyMedium)
                        Switch(checked = subtitleEnabled, onCheckedChange = { subtitleEnabled = it })
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("字号", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline)
                        listOf("小", "中", "大").forEachIndexed { idx, label ->
                            if (subtitleSizeIdx == idx) {
                                Button(onClick = { subtitleSizeIdx = idx }, enabled = subtitleEnabled,
                                    modifier = Modifier.weight(1f)) { Text(label) }
                            } else {
                                OutlinedButton(onClick = { subtitleSizeIdx = idx }, enabled = subtitleEnabled,
                                    modifier = Modifier.weight(1f)) { Text(label) }
                            }
                        }
                    }
                }
            },
            text = {
                Box(modifier = Modifier.fillMaxWidth().height(360.dp)) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            VideoView(ctx).apply {
                                vvHolder[0] = this
                                setVideoURI(uri)
                                setOnPreparedListener { it.isLooping = false; it.start() }
                                setOnErrorListener { _, _, _ -> true }
                            }
                        },
                        update = { vv -> vv.setVideoURI(uri); vv.start() }
                    )
                    // ★第四批：关闭字幕时完全不渲染字幕层；字号随小/中/大档位（12/14/16sp）
                    if (subtitleEnabled) {
                        subtitleLine?.let { line ->
                            Text(
                                text = line,
                                color = Color.White,
                                fontSize = subtitleFontSize,
                                textAlign = TextAlign.Center,
                                maxLines = 3,
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .padding(bottom = 16.dp, start = 12.dp, end = 12.dp)
                                    .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(6.dp))
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                            )
                        }
                    }
                }
            }
        )
    }

    // 第十三轮 P0-3：合成后台协程（LaunchedEffect 触发，UI 显示进度对话框）
    composingEp?.let { targetEpId ->
        LaunchedEffect(targetEpId) {
            // ★第二批 P0-D6：composer 在 effect 内现读，避免 remember 捕获到旧的 EmptyMovieAssembler
            val composer = com.dramafactory.app.AppGraph.movieAssembler
            val result = try {
                // ★第二批 P0-D1：按数字 shot_no 排序（字典序会把 shot10 排到 shot2 前）
                val shotNoById = kotlin.runCatching {
                    com.dramafactory.app.AppGraph.dao.shotsOf(targetEpId)
                        .associate { it.shot_id to it.shot_no }
                }.getOrDefault(emptyMap())
                val tasks = com.dramafactory.app.AppGraph.dao.renderTasksOf(targetEpId)
                    .filter { it.state == "COMPLETED" && !it.local_file_uri.isNullOrBlank() }
                    .sortedWith(
                        compareBy<RenderTaskEntity> { shotNoById[it.shot_id] ?: Int.MAX_VALUE }
                            .thenBy { it.shot_id })
                val entries = tasks.mapNotNull { t ->
                    val f = java.io.File(t.local_file_uri ?: "")
                    if (f.exists() && f.length() > 0L) t to f else null
                }
                if (entries.isEmpty()) {
                    kotlin.Result.failure(com.dramafactory.core.assemble.MovieAssembler.NotAvailableException(
                        "本集没有可合成的镜片段"))
                } else {
                    val clips = entries.map { it.second }
                    // ★第二批 P0-D2：成片统一落 filesDir/movies
                    val out = com.dramafactory.app.FilmPaths.film(context, targetEpId)
                    // ★第二批 P0：手动合成也带字幕轨、响度归一化与转场（与 AI 自动成片同口径）
                    val shotsById = kotlin.runCatching {
                        com.dramafactory.app.AppGraph.dao.shotsOf(targetEpId).associateBy { it.shot_id }
                    }.getOrDefault(emptyMap())
                    val transitionSeconds = 0.30
                    var cursorMs = 0L
                    val cues = mutableListOf<com.dramafactory.core.assemble.MovieAssembler.SubtitleCue>()
                    entries.forEachIndexed { index, (task, clip) ->
                        val shot = shotsById[task.shot_id]
                        // 真实镜长优先，DB duration_seconds 兜底（字幕不再按台词时长凭空累加）
                        val durMs = com.dramafactory.app.videoDurationMs(clip)
                            ?: (((shot?.duration_seconds?.takeIf { it > 0.0 } ?: 5.0) * 1000).toLong())
                        val text = listOfNotNull(
                            shot?.dialogue?.takeIf { it.isNotBlank() },
                            shot?.narration?.takeIf { it.isNotBlank() },
                        ).joinToString(" / ")
                        if (text.isNotBlank() && durMs > 250L) {
                            val start = (cursorMs + 150L).coerceAtLeast(0L)
                            val end = (cursorMs + durMs - 100L -
                                (if (index > 0) (transitionSeconds * 1000).toLong() else 0L))
                                .coerceAtLeast(start + 1L)
                            cues += com.dramafactory.core.assemble.MovieAssembler.SubtitleCue(
                                text = text, startMs = start, endMs = end)
                        }
                        cursorMs += durMs
                    }
                    // ★第三批：手动合成也混入项目 BGM（成片页选曲；文件被删/未选则自动跳过）
                    val bgmPair: Pair<java.io.File?, Double>? = kotlin.runCatching {
                        com.dramafactory.app.AppGraph.bgmForEpisode(targetEpId)
                    }.getOrNull()
                    val bgmFile = bgmPair?.first
                    val bgmVolume = bgmPair?.second ?: 0.22
                    // ★第二批 P0-D6：ffmpeg 内部 CountDownLatch 阻塞，必须在 IO 线程合成，否则主线程 ANR
                    val res = withContext(Dispatchers.IO) {
                        composer.assemble(
                            clips, out,
                            com.dramafactory.core.assemble.MovieAssembler.AssembleOptions(
                                subtitles = cues,
                                bgm = bgmFile,
                                bgmVolume = bgmVolume,
                                loudnessNormalize = true,
                                transition = com.dramafactory.core.assemble.MovieAssembler.Transition.FADE,
                                transitionSeconds = transitionSeconds,
                            ),
                        )
                    }
                    if (res is com.dramafactory.core.assemble.MovieAssembler.AssembleResult.Success) {
                        // ★第二批 P0-D3/D2：Success.output 才是真实成片（可能是 _norm.mp4），统一落盘
                        val finalFilm = com.dramafactory.app.FilmPaths
                            .persistFilm(context, targetEpId, res.output)
                        res.subtitleFile?.takeIf { it.exists() }?.let { srt ->
                            kotlin.runCatching {
                                val dst = com.dramafactory.app.FilmPaths.subtitle(context, targetEpId)
                                if (srt.absolutePath != dst.absolutePath) srt.copyTo(dst, overwrite = true)
                            }
                        }
                        if (res.warnings.isNotEmpty()) {
                            android.util.Log.w("LibraryPage", "compose warnings: " + res.warnings.joinToString("; "))
                        }
                        if (finalFilm != null && finalFilm.exists() && finalFilm.length() > 0L) {
                            // 落库：filePath/duration 都取真实成片路径与真实时长
                            kotlin.runCatching {
                                com.dramafactory.app.AppGraph.movieLibraryDao.upsertFilmOf(
                                    com.dramafactory.app.data.FinishedFilmEntity(
                                        film_id = targetEpId,
                                        episode_id = targetEpId,
                                        project_id = targetEpId.substringBefore("_ep"),
                                        filePath = finalFilm.absolutePath,
                                        fileSize = finalFilm.length(),
                                        durationMs = (res.durationSeconds * 1000).toLong(),
                                        createdAt = System.currentTimeMillis(),
                                    ))
                            }
                            kotlin.Result.success(finalFilm)
                        } else {
                            kotlin.Result.failure(com.dramafactory.core.assemble.MovieAssembler.NotAvailableException(
                                "合成完成但成片文件缺失"))
                        }
                    } else if (res is com.dramafactory.core.assemble.MovieAssembler.AssembleResult.Segmented) {
                        kotlin.Result.failure(com.dramafactory.core.assemble.MovieAssembler.NotAvailableException(
                            "合成降级为分段导出（${res.parts.size} 段），暂未支持拼装"))
                    } else {
                        kotlin.Result.failure(com.dramafactory.core.assemble.MovieAssembler.NotAvailableException(
                            (res as? com.dramafactory.core.assemble.MovieAssembler.AssembleResult.Failure)?.message
                                ?: "合成失败"))
                    }
                }
            } catch (e: com.dramafactory.core.assemble.MovieAssembler.NotAvailableException) {
                kotlin.Result.failure(e)
            } catch (e: Throwable) {
                kotlin.Result.failure(e)
            }
            result.onSuccess { shareMsg = "合成完成，可点击「预览」或「分享」" }
            result.onFailure { shareMsg = "合成失败：" + it.message }
            composingEp = null
        }
    }
}

// ---------- Compose预览 ----------

@Preview(showBackground = true, locale = "zh")
@Composable
private fun PreviewLibrary() {
    MaterialTheme {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("成片库", style = MaterialTheme.typography.headlineSmall)
            for ((name, sub) in listOf("第p1_ep1 集" to "已完成 · 可导出", "第p2_ep1 集" to "12/24 镜完成")) {
                DramaCard(Modifier.fillMaxWidth()) {
                    Row(Modifier, horizontalArrangement = Arrangement.SpaceBetween) {
                        Column { Text(name); Text(sub, style = MaterialTheme.typography.bodySmall) }
                        OutlinedButton(onClick = {}) { Text("导出分享") }
                    }
                }
            }
        }
    }
}
