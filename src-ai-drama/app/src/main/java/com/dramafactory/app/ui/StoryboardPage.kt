package com.dramafactory.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.painterResource
import com.dramafactory.app.R
import com.dramafactory.app.ui.components.DramaCard
import com.dramafactory.app.ui.components.EmptyState
import com.dramafactory.app.ui.components.LoadingRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.dramafactory.app.ui.components.HeroButton
import com.dramafactory.app.ui.components.PrimaryButton

/** 分镜校验错误码 → 用户可理解的中文提示。 */
private fun storyboardCheckLabel(code: String): String = when (code) {
    "carry_over_missing" -> "缺少前后镜头承接：请补充角色、场景、道具状态或转场动作"
    "shot_order_gap" -> "镜号不连续：前后镜头存在顺序断点"
    "asset_unbound" -> "引用了未批准资产：请重新选择资产库中的素材"
    "rhythm_duration" -> "镜头时长不在4–10秒建议范围"
    "rhythm_monotone" -> "连续多镜同一景别（建议拉开景别层次，仅提示）"
    "rhythm_flat" -> "连续多镜同一时长（建议做节奏变化，仅提示）"
    "establishing_missing" -> "场次切换后缺少建立镜头（建议先给全景/中景，仅提示）"
    "dialogue_pace" -> "台词/旁白朗读时长超过镜头（建议加长或拆镜，仅提示）"
    "dialogue_not_verbatim" -> "台词与剧本原文不一致"
    "action_empty" -> "动作描述为空"
    "scene_context_conflict" -> "画面描述与上一镜场景不连续（仅提示，不阻断渲染）"
    "scene_context_missing" -> "缺少场景上下文：请补充时间、空间、天气"
    "beat_ref_missing" -> "缺少剧本节拍来源"
    "beat_index_invalid" -> "剧本节拍顺序无效"
    "beat_out_of_order" -> "剧本节拍顺序回退"
    "missing_asset" -> "引用了未批准资产：请重新选择资产库中的素材"
    "axis_jump" -> "站位越轴待确认"
    "eyeline_mismatch" -> "视线同向待确认"
    "scene_single_shot" -> "单镜成场"
    "hook_weak" -> "开场钩子弱"
    "ending_weak" -> "结尾泄气"
    else -> code
}

/** 兼容旧数据：历史版本把视觉指令拼进了 action（"动作；［视觉指令］"），显示/编辑时剥掉，避免重复。 */
private fun displayAction(shot: com.dramafactory.app.data.ShotEntity): String =
    cleanShotAction(shot.action, shot.visual_prompt)

/** 从视觉指令/动作解析景别（顺序与 core 的 ShotDesigner.SIZE_RULES 一致），用于分镜页节奏摘要。 */
internal fun storyboardSizeLabelOf(text: String?): String? {
    val s = text?.trim().orEmpty()
    if (s.isEmpty()) return null
    return when {
        s.contains("大特写") || s.contains("极特写") -> "大特写"
        s.contains("中近景") || s.contains("中景") -> "中景"
        s.contains("全景") || s.contains("远景") -> "全景"
        s.contains("近景") -> "近景"
        s.contains("特写") -> "特写"
        else -> null
    }
}

/** 将 error:code,code 渲染成自然语言，避免把内部错误码直接暴露给用户。 */
private fun storyboardCheckSummary(raw: String): String = raw.removePrefix("error:").removePrefix("warn:")
    .split(",").map { it.trim() }.filter { it.isNotEmpty() }
    .joinToString("；") { storyboardCheckLabel(it) }

/** 第三批：站位/视线编辑选项（空串=清除）。 */
private val SCREEN_SIDE_OPTIONS = listOf("", "左", "右", "中")
private val EYELINE_OPTIONS = listOf("", "左", "右", "上", "下", "对方")

/** 第三批：按 scene_no 把相邻同场镜头聚组；scene_no=0 的镜头归「未分场」。 */
private fun groupShotsByScene(
    shots: List<com.dramafactory.app.data.ShotEntity>,
): List<Pair<Int, List<com.dramafactory.app.data.ShotEntity>>> {
    val groups = mutableListOf<Pair<Int, MutableList<com.dramafactory.app.data.ShotEntity>>>()
    for (shot in shots) {
        val last = groups.lastOrNull()
        if (last != null && last.first == shot.scene_no) last.second.add(shot)
        else groups.add(shot.scene_no to mutableListOf(shot))
    }
    return groups.map { (sceneNo, rows) -> sceneNo to rows.toList() }
}

/** 第三批：场次组头「第N场 · 标题 · N镜 · 共X秒」（scene_no=0 显示「未分场」）。 */
@Composable
private fun SceneGroupHeader(sceneNo: Int, rows: List<com.dramafactory.app.data.ShotEntity>) {
    val title = rows.firstOrNull()?.scene_title?.takeIf { it.isNotBlank() }
    val head = if (sceneNo > 0) "第${sceneNo}场" else "未分场"
    val label = (if (title != null) "$head · $title" else head) +
        " · ${rows.size}镜 · 共${rows.sumOf { it.duration_seconds }.toInt()}秒"
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(Icons.Default.List, contentDescription = "场次",
            tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
        Text(label, style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary)
    }
}

/** 第三批：只读信息 chip（场次/站位/视线速览）。 */
@Composable
private fun InfoChip(text: String, tint: Color = MaterialTheme.colorScheme.secondary) {
    Box(
        modifier = Modifier
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(50))
            .background(tint.copy(alpha = 0.12f))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = tint)
    }
}

/** 第三批：镜头卡首帧缩略图（16:9 · 128×72dp）；未出首帧时显示占位。 */
@Composable
private fun ShotFirstFrame(shot: com.dramafactory.app.data.ShotEntity) {
    val uri = shot.first_image_uri
    val shape = MaterialTheme.shapes.small
    if (!uri.isNullOrBlank()) {
        AsyncImage(
            model = uri,
            contentDescription = "镜头#${shot.shot_no}首帧",
            modifier = Modifier.width(128.dp).height(72.dp).clip(shape),
            contentScale = ContentScale.Crop,
        )
    } else {
        Box(
            modifier = Modifier.width(128.dp).height(72.dp).clip(shape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = androidx.compose.ui.Alignment.Center,
        ) {
            Icon(
                painterResource(R.drawable.ic_image),
                contentDescription = "暂无首帧",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}


@Composable
private fun StatusChip(icon: ImageVector, text: String, tint: Color) {
    Row(
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(icon, contentDescription = text, tint = tint, modifier = Modifier.size(16.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = tint)
    }
}

/** v1.9.2：详情弹窗的「标签 + 内容」行（只读展示） */
@Composable
private fun DetailLine(label: String, value: String, tint: Color? = null) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline)
        Text(value, style = MaterialTheme.typography.bodyMedium,
            color = tint ?: MaterialTheme.colorScheme.onSurface)
    }
}

/**
 * 分镜编辑页（第十二轮：可操作化）：
 * - AI编剧+导演一键生成分镜；每镜展示序号/时长/校验位/动作/视觉指令/台词/旁白/承接
 * - 点卡片或「✏ 编辑」进入单镜编辑（动作/台词/旁白/视觉指令/时长）
 * - 「🗑 删除」二次确认删单镜；「🎬 渲染本集」一键入队渲染队列
 */
@Composable
fun StoryboardPage(
    episodeId: String,
    vm: StoryboardViewModel = viewModel(
        key = "sb_$episodeId",
        factory = object : androidx.lifecycle.ViewModelProvider.Factory {
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return StoryboardViewModel(episodeId) as T
            }
        },
    ),
) {
    val st by vm.state.collectAsState()
    // v1.7.1 实时联动：进入分镜页/切项目(episodeId 变化)时强制重拉，
    // 让 AI 生成的分镜立刻可见（否则只在 VM 首次构造时拉一次，切走再切回是陈旧数据）。
    androidx.compose.runtime.LaunchedEffect(episodeId) { vm.refresh() }
    // v1.7.3：项目内查看剧本——进入分镜页即加载本集剧本原文，折叠卡展示
    var scriptText by remember { mutableStateOf<String?>(null) }
    var showScript by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(episodeId) {
        scriptText = runCatching {
            withContext(Dispatchers.IO) { com.dramafactory.app.AppGraph.dao.episode(episodeId)?.script_json }
        }.getOrNull()
    }
    // v1.9.2：分镜详情（点击卡片查看全部内容 + 引用资产）；编辑仍走「编辑」按钮
    var viewingShotId by remember { mutableStateOf<String?>(null) }
    var editingShotId by remember { mutableStateOf<String?>(null) }
    var confirmDeleteShotId by remember { mutableStateOf<String?>(null) }
    var queuedCount by remember { mutableStateOf<Int?>(null) }
    // 第十三轮：分镜视频预览（非空=正在播放该本地路径）
    var previewUri by remember { mutableStateOf<String?>(null) }
    // 第三批：按 scene_no 连续分组渲染；scene_no=0 归「未分场」。
    val sceneGroups = groupShotsByScene(st.shots)

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Text("分镜 · $episodeId", style = MaterialTheme.typography.headlineSmall) }

        // v1.7.3：项目内查看剧本——折叠卡，点开看本集剧本原文（AI 提取资产/分镜的依据）
        scriptText?.let { script ->
            if (script.isNotBlank()) {
                item {
                    DramaCard(
                        onClick = { showScript = !showScript },
                        modifier = Modifier.fillMaxWidth(),
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ) {
                        Column(Modifier) {
                            Row(Modifier.fillMaxWidth(),
                                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween) {
                                Row(
                                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Icon(Icons.Default.List, contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(18.dp))
                                    Text("剧本原文（${script.length}字）",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Row(
                                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Text(if (showScript) "收起" else "展开",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary)
                                    Icon(
                                        if (showScript) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                        contentDescription = if (showScript) "收起" else "展开",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                            if (showScript) {
                                Text(script, Modifier.padding(top = 8.dp)
                                    .heightIn(max = 360.dp)
                                    .verticalScroll(androidx.compose.foundation.rememberScrollState()),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }

        item {
            HeroButton(
                text = if (st.generating) "AI 生成中…" else "AI 生成分镜（编剧+导演）",
                onClick = { vm.generateWithAi() },
                enabled = !st.generating,
                modifier = Modifier.fillMaxWidth(),
            )
            if (st.generating) {
                LoadingRow("正在拆解镜头并生成视觉指令，通常需要十几秒…")
            }
        }
        if (st.shots.isNotEmpty()) {
            item {
                // 节奏摘要：让用户在渲染前一眼看到「总时长 / 平均镜长 / 景别分布」是否失衡。
                val totalSeconds = st.shots.sumOf { it.duration_seconds }
                val average = if (st.shots.isEmpty()) 0.0 else totalSeconds / st.shots.size
                val sizeCounts = st.shots
                    .mapNotNull { storyboardSizeLabelOf(it.visual_prompt ?: it.action) }
                    .groupingBy { it }.eachCount()
                    .entries.sortedByDescending { it.value }
                    .joinToString(" · ") { "${it.key}×${it.value}" }
                DramaCard(Modifier.fillMaxWidth()) {
                    Column(Modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            "分镜设计：${st.shots.size} 镜 · 总 ${totalSeconds.toInt()} 秒 · 平均 ${((average * 10).toInt() / 10.0)} 秒",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            if (sizeCounts.isBlank()) "景别：视觉指令可补充景别/机位，让节奏更有层次"
                            else "景别分布：${sizeCounts}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary,
                        )
                    }
                }
            }
            item {
                // 第三批：节奏条——每镜一根竖条，柱高∝时长（clamp 4–10 秒），同场用底色分组。
                DramaCard(Modifier.fillMaxWidth()) {
                    Column(Modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            "镜头节奏：柱高=时长（4–10 秒封顶）· 底色=同场分镜",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = androidx.compose.ui.Alignment.Bottom,
                        ) {
                            sceneGroups.forEachIndexed { groupIndex, group ->
                                val groupShots = group.second
                                Row(
                                    modifier = Modifier
                                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
                                        .background(
                                            if (groupIndex % 2 == 0) {
                                                MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.35f)
                                            } else {
                                                MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.35f)
                                            }
                                        )
                                        .padding(4.dp),
                                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                                    verticalAlignment = androidx.compose.ui.Alignment.Bottom,
                                ) {
                                    groupShots.forEach { shot ->
                                        val seconds = shot.duration_seconds.coerceIn(4.0, 10.0)
                                        val barHeight = (10.0 + (seconds - 4.0) / 6.0 * 30.0).dp
                                        val barColor = when {
                                            shot.sb_check == "pass" -> MaterialTheme.colorScheme.primary
                                            shot.sb_check.startsWith("warn:") -> MaterialTheme.colorScheme.tertiary
                                            else -> MaterialTheme.colorScheme.error
                                        }
                                        Box(
                                            Modifier.width(6.dp).height(barHeight)
                                                .clip(androidx.compose.foundation.shape.RoundedCornerShape(2.dp))
                                                .background(barColor),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            item {
                PrimaryButton(
                    text = "渲染本集全部 ${st.shots.size} 镜",
                    onClick = { vm.enqueueRender { n -> queuedCount = n } },
                    enabled = !st.generating,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        queuedCount?.let { n ->
            item {
                DramaCard(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier,
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (n > 0) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                        } else {
                            Icon(Icons.Default.Info, contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                        }
                        Text(if (n > 0) "已入队 $n 镜，进度见「渲染」标签页" else "没有可渲染的镜头",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
        st.message?.let { msg ->
            item {
                DramaCard(Modifier.fillMaxWidth()) {
                    Text(msg, style = MaterialTheme.typography.bodySmall,
                        color = if (msg.startsWith("已生成") || msg.contains("✓")) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.error)
                }
            }
        }
        if (!st.generating && st.shots.isEmpty()) {
            item {
                EmptyState(
                    icon = { Icon(painterResource(R.drawable.ic_movie), contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp)) },
                    title = "还没有分镜",
                    subtitle = "导入剧本后点上方「AI 生成分镜」，大模型会自动拆解镜头并生成视觉指令。",
                )
            }
        }
        sceneGroups.forEach { (sceneNo, groupShots) ->
            item(key = "scene_h_${groupShots.first().shot_id}") {
                SceneGroupHeader(sceneNo, groupShots)
            }
            items(groupShots, key = { it.shot_id }) { shot ->
                DramaCard(
                    onClick = { viewingShotId = shot.shot_id },   // v1.9.2：点卡=查看详情
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("#${shot.shot_no} · ${shot.duration_seconds.toInt()}秒",
                                style = MaterialTheme.typography.titleSmall)
                            when {
                                shot.sb_check == "pass" -> StatusChip(
                                    icon = Icons.Default.CheckCircle, text = "校验通过",
                                    tint = MaterialTheme.colorScheme.primary)
                                shot.sb_check == "pending" -> StatusChip(
                                    icon = Icons.Default.DateRange, text = "待生成",
                                    tint = MaterialTheme.colorScheme.outline)
                                shot.sb_check.startsWith("warn:") -> StatusChip(
                                    icon = Icons.Default.Info, text = storyboardCheckSummary(shot.sb_check),
                                    tint = MaterialTheme.colorScheme.tertiary)
                                else -> StatusChip(
                                    icon = Icons.Default.Warning,
                                    text = storyboardCheckSummary(shot.sb_check),
                                    tint = MaterialTheme.colorScheme.error)
                            }
                        }
                        // 第三批：首帧缩略图 + 场次/站位/视线速览
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        ) {
                            ShotFirstFrame(shot)
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                shot.scene_title?.takeIf { it.isNotBlank() }?.let {
                                    InfoChip(if (shot.scene_no > 0) "第${shot.scene_no}场 · $it" else "未分场 · $it")
                                }
                                shot.screen_side?.takeIf { it.isNotBlank() }?.let { InfoChip("站位：$it") }
                                shot.eyeline?.takeIf { it.isNotBlank() }?.let { InfoChip("视线：$it") }
                            }
                        }
                        shot.action?.let { Text("动作：${displayAction(shot)}", style = MaterialTheme.typography.bodyMedium) }
                        shot.visual_prompt?.let {
                            Row(
                                verticalAlignment = androidx.compose.ui.Alignment.Top,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Icon(painterResource(R.drawable.ic_movie), contentDescription = "视觉指令",
                                    tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(16.dp))
                                Text(it, style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.weight(1f),
                                    color = MaterialTheme.colorScheme.secondary)
                            }
                        }
                        shot.scene_context?.takeIf { it.isNotBlank() }?.let {
                            Row(
                                verticalAlignment = androidx.compose.ui.Alignment.Top,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Icon(painterResource(R.drawable.ic_image), contentDescription = "场景上下文",
                                    tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(16.dp))
                                Text("场景：$it", style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.weight(1f),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        // v1.7.17：把「本镜引用了哪些资产」显式摆出来。
                        // 此前这层完全不可见——LLM 引错（或引了还没生图的卡）用户无从察觉，
                        // 只会在成片里看到角色换脸。引用为空即代表渲染时回退项目级前4张，锁不住脸。
                        val refs = AssetCatalog.parseRefIds(shot.first_asset_ids)
                        if (refs.isNotEmpty()) {
                            Row(
                                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Icon(painterResource(R.drawable.ic_link), contentDescription = "引用资产",
                                    tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(16.dp))
                                Text("引用：${refs.joinToString("、") { st.assetNames[it] ?: it }}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.tertiary)
                            }
                        } else {
                            Row(
                                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Icon(Icons.Default.Warning, contentDescription = "未引用资产",
                                    tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                                Text("未引用资产：渲染将回退项目级前4张",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error)
                            }
                        }
                        shot.dialogue?.let { Text("台词：「$it」", style = MaterialTheme.typography.bodySmall) }
                        shot.narration?.let { Text("旁白：$it", style = MaterialTheme.typography.bodySmall) }
                        shot.carry_over?.let { Text("承接：$it", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline) }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            // 第十三轮：已出产视频 → 预览入口
                            st.videoUris[shot.shot_id]?.let { uri ->
                                OutlinedButton(onClick = { previewUri = uri }) {
                                    Icon(Icons.Default.PlayArrow, contentDescription = "预览",
                                        modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("预览")
                                }
                            }
                            if (shot.sb_check != "pass") {
                                OutlinedButton(onClick = { vm.regenerateShot(shot.shot_id) }) {
                                    Icon(Icons.Default.Refresh, contentDescription = "重新生成",
                                        modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("重新生成")
                                }
                            }
                            OutlinedButton(onClick = { editingShotId = shot.shot_id }) {
                                Icon(Icons.Default.Edit, contentDescription = "编辑",
                                    modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("编辑")
                            }
                            OutlinedButton(onClick = { confirmDeleteShotId = shot.shot_id }) {
                                Icon(Icons.Default.Delete, contentDescription = "删除",
                                    modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("删除")
                            }
                        }
                    }
                }
            }
        }
    }

    val viewingShot = viewingShotId?.let { id -> st.shots.firstOrNull { it.shot_id == id } }
    if (viewingShot != null) {
        AlertDialog(
            onDismissRequest = { viewingShotId = null },
            title = { Text("镜头 #${viewingShot.shot_no} · ${viewingShot.duration_seconds.toInt()}秒") },
            text = {
                Column(
                    Modifier.fillMaxWidth().heightIn(max = 440.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    when {
                        viewingShot.sb_check == "pass" -> StatusChip(
                            Icons.Default.CheckCircle, "校验通过", MaterialTheme.colorScheme.primary)
                        viewingShot.sb_check == "pending" -> StatusChip(
                            Icons.Default.DateRange, "待生成", MaterialTheme.colorScheme.outline)
                        viewingShot.sb_check.startsWith("warn:") -> StatusChip(
                            Icons.Default.Info, storyboardCheckSummary(viewingShot.sb_check),
                            MaterialTheme.colorScheme.tertiary)
                        else -> StatusChip(
                            Icons.Default.Warning,
                            storyboardCheckSummary(viewingShot.sb_check),
                            MaterialTheme.colorScheme.error)
                    }
                    viewingShot.action?.let { DetailLine("动作", displayAction(viewingShot)) }
                    viewingShot.visual_prompt?.let {
                        DetailLine("画面描述", it, MaterialTheme.colorScheme.secondary) }
                    viewingShot.scene_context?.takeIf { it.isNotBlank() }?.let {
                        DetailLine("场景上下文", it, MaterialTheme.colorScheme.onSurfaceVariant) }
                    viewingShot.dialogue?.let { DetailLine("台词", "「$it」") }
                    viewingShot.narration?.let { DetailLine("旁白", it) }
                    viewingShot.carry_over?.let {
                        DetailLine("承接", it, MaterialTheme.colorScheme.outline) }
                    // 第三批：场次/站位/视线
                    if (viewingShot.scene_no > 0 || !viewingShot.scene_title.isNullOrBlank()) {
                        val sceneTitle = viewingShot.scene_title?.takeIf { it.isNotBlank() }
                        DetailLine("场次",
                            (if (viewingShot.scene_no > 0) "第${viewingShot.scene_no}场" else "未分场") +
                                (sceneTitle?.let { " · $it" } ?: ""))
                    }
                    viewingShot.screen_side?.takeIf { it.isNotBlank() }?.let { DetailLine("站位", it) }
                    viewingShot.eyeline?.takeIf { it.isNotBlank() }?.let { DetailLine("视线", it) }

                    Text("引用资产", style = MaterialTheme.typography.titleSmall)
                    val refs = AssetCatalog.parseRefIds(viewingShot.first_asset_ids)
                    if (refs.isEmpty()) {
                        Row(
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Icon(Icons.Default.Warning, contentDescription = null,
                                tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                            Text("未引用资产：渲染将回退项目级前4张",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error)
                        }
                    } else {
                        for (refId in refs) {
                            val name = st.assetNames[refId] ?: refId
                            val thumb = st.assetThumbs[refId]
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                if (thumb != null) {
                                    AsyncImage(
                                        model = thumb, contentDescription = name,
                                        modifier = Modifier.size(46.dp)
                                            .clip(MaterialTheme.shapes.extraSmall),
                                        contentScale = ContentScale.Crop)
                                } else {
                                    Box(
                                        Modifier.size(46.dp)
                                            .clip(MaterialTheme.shapes.extraSmall)
                                            .background(MaterialTheme.colorScheme.surfaceVariant),
                                        contentAlignment = androidx.compose.ui.Alignment.Center,
                                    ) {
                                        Icon(painterResource(R.drawable.ic_image), contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(22.dp))
                                    }
                                }
                                Column(Modifier.weight(1f)) {
                                    Text(name, style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        if (thumb != null) "已生图 · 渲染时注入锁脸"
                                        else "尚无图 · 渲染时可能失锁",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (thumb != null) MaterialTheme.colorScheme.outline
                                                else MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    editingShotId = viewingShot.shot_id
                    viewingShotId = null
                }) { Text("编辑") }
            },
            dismissButton = { OutlinedButton(onClick = { viewingShotId = null }) { Text("关闭") } },
        )
    }

    // ---- 单镜编辑对话框（LazyColumn 外，composable 上下文）----
    val editingShot = editingShotId?.let { id -> st.shots.firstOrNull { it.shot_id == id } }
    if (editingShot != null) {
        var eAction by remember(editingShot.shot_id) { mutableStateOf(displayAction(editingShot)) }
        var eDialogue by remember(editingShot.shot_id) { mutableStateOf(editingShot.dialogue ?: "") }
        var eNarration by remember(editingShot.shot_id) { mutableStateOf(editingShot.narration ?: "") }
        var eVisual by remember(editingShot.shot_id) { mutableStateOf(editingShot.visual_prompt ?: "") }
        var eScene by remember(editingShot.shot_id) { mutableStateOf(editingShot.scene_context ?: "") }
        var eCarry by remember(editingShot.shot_id) { mutableStateOf(editingShot.carry_over ?: "") }
        var eDur by remember(editingShot.shot_id) {
            mutableStateOf(editingShot.duration_seconds.toInt().toString())
        }
        // 第三批：场次 / 站位 / 视线编辑态
        var eSceneNo by remember(editingShot.shot_id) {
            mutableStateOf(if (editingShot.scene_no > 0) editingShot.scene_no.toString() else "")
        }
        var eSceneTitle by remember(editingShot.shot_id) {
            mutableStateOf(editingShot.scene_title ?: "")
        }
        var eSide by remember(editingShot.shot_id) { mutableStateOf(editingShot.screen_side ?: "") }
        var eEye by remember(editingShot.shot_id) { mutableStateOf(editingShot.eyeline ?: "") }
        var sideMenu by remember(editingShot.shot_id) { mutableStateOf(false) }
        var eyeMenu by remember(editingShot.shot_id) { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { editingShotId = null },
            title = { Text("编辑镜头 #${editingShot.shot_no}") },
            text = {
                Column(
                    Modifier.fillMaxWidth().heightIn(max = 440.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(value = eAction, onValueChange = { eAction = it },
                        label = { Text("动作描述") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                    // 第三批：场次号（0=未分场）+ 场次标题
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(value = eSceneNo,
                            onValueChange = { txt -> eSceneNo = txt.filter(Char::isDigit) },
                            label = { Text("场次号（0=未分场）") }, singleLine = true,
                            modifier = Modifier.weight(1f))
                        OutlinedTextField(value = eSceneTitle, onValueChange = { eSceneTitle = it },
                            label = { Text("场次标题") }, singleLine = true,
                            modifier = Modifier.weight(1f))
                    }
                    // 第三批：站位 / 视线 下拉选择
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.weight(1f)) {
                            OutlinedButton(onClick = { sideMenu = true },
                                modifier = Modifier.fillMaxWidth()) {
                                Text("站位：" + eSide.ifBlank { "未设置" })
                            }
                            DropdownMenu(expanded = sideMenu, onDismissRequest = { sideMenu = false }) {
                                SCREEN_SIDE_OPTIONS.forEach { option ->
                                    DropdownMenuItem(text = { Text(option.ifBlank { "未设置" }) },
                                        onClick = { eSide = option; sideMenu = false })
                                }
                            }
                        }
                        Box(Modifier.weight(1f)) {
                            OutlinedButton(onClick = { eyeMenu = true },
                                modifier = Modifier.fillMaxWidth()) {
                                Text("视线：" + eEye.ifBlank { "未设置" })
                            }
                            DropdownMenu(expanded = eyeMenu, onDismissRequest = { eyeMenu = false }) {
                                EYELINE_OPTIONS.forEach { option ->
                                    DropdownMenuItem(text = { Text(option.ifBlank { "未设置" }) },
                                        onClick = { eEye = option; eyeMenu = false })
                                }
                            }
                        }
                    }
                    OutlinedTextField(value = eDialogue, onValueChange = { eDialogue = it },
                        label = { Text("台词（留空删除）") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = eNarration, onValueChange = { eNarration = it },
                        label = { Text("旁白（留空删除）") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = eVisual, onValueChange = { eVisual = it },
                        label = { Text("画面描述（景别/运镜/光线/影调）") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                    OutlinedTextField(value = eScene, onValueChange = { eScene = it },
                        label = { Text("场景上下文（时间/空间/天气）") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                    OutlinedTextField(value = eCarry, onValueChange = { eCarry = it },
                        label = { Text("承接（上一镜结束状态/转场）") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                    OutlinedTextField(value = eDur,
                        onValueChange = { txt -> eDur = txt.filter(Char::isDigit) },
                        label = { Text("时长（秒，1-60）") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                Button(onClick = {
                    vm.updateShot(editingShot.shot_id, eAction,
                        eDialogue.ifBlank { null }, eNarration.ifBlank { null },
                        eVisual.ifBlank { null }, (eDur.toIntOrNull() ?: 6).toDouble(),
                        eScene.ifBlank { null }, eCarry.ifBlank { null })
                    // 第三批：场次/站位/视线单独走局部 UPDATE，避开 upsert 整行覆盖
                    vm.updateShotSceneInfo(editingShot.shot_id,
                        eSceneNo.toIntOrNull()?.coerceAtLeast(0) ?: 0,
                        eSceneTitle.ifBlank { null }, eSide.ifBlank { null },
                        eEye.ifBlank { null })
                    editingShotId = null
                }) { Text("保存") }
            },
            dismissButton = { OutlinedButton(onClick = { editingShotId = null }) { Text("取消") } },
        )
    }

    // ---- 第十三轮：分镜视频预览（原生VideoView，零新增依赖）----
    previewUri?.let { uri ->
        AlertDialog(
            onDismissRequest = { previewUri = null },
            title = { Text("镜头预览") },
            text = {
                androidx.compose.ui.viewinterop.AndroidView(
                    factory = { ctx ->
                        android.widget.VideoView(ctx).apply {
                            setVideoPath(uri)
                            setOnPreparedListener { it.isLooping = true; it.start() }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = { Button(onClick = { previewUri = null }) { Text("关闭") } },
        )
    }

    // ---- 删除镜二次确认 ----
    confirmDeleteShotId?.let { delId ->
        AlertDialog(
            onDismissRequest = { confirmDeleteShotId = null },
            title = { Text("删除该镜头？") },
            text = { Text("镜头将被移除，不可恢复。") },
            confirmButton = {
                Button(onClick = { vm.deleteShot(delId); confirmDeleteShotId = null }) { Text("删除") }
            },
            dismissButton = { OutlinedButton(onClick = { confirmDeleteShotId = null }) { Text("取消") } },
        )
    }
}