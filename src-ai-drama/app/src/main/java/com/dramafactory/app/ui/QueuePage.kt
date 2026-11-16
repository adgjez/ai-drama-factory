package com.dramafactory.app.ui

import android.net.Uri
import android.widget.VideoView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.res.painterResource
import com.dramafactory.app.AppGraph
import com.dramafactory.app.R
import com.dramafactory.app.data.RenderTaskEntity
import com.dramafactory.app.ui.components.DramaCard
import com.dramafactory.app.ui.components.EmptyState
import androidx.compose.ui.Alignment
import com.dramafactory.app.ui.components.PageHeader
import com.dramafactory.core.quality.RenderPreflight
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.runtime.CompositionLocalProvider
import java.io.File
import kotlinx.coroutines.launch

/**
 * 渲染队列页（S6）：每集渲染进度列表（镜状态机实时刷新）、暂停/恢复/取消、
 * 预算确认弹窗（budgetConfirmed放行位对齐）、RECONCILE人工处置对话框。
 * 第六轮新增：每镜「设参考图」（图生视频，复用本地上传选图）、
 * 「上传参考视频」（仅当前视频模型标记 supportsVideoReference 时显示）。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun QueuePage(
    episodeId: String = "default",
    vm: QueueViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
        key = "queue_$episodeId",
        factory = object : androidx.lifecycle.ViewModelProvider.Factory {
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return QueueViewModel(episodeId) as T
            }
        },
    ),
) {
    val st by vm.state.collectAsState()
    // 视频模型是否支持参考视频：原来是在列表 item 里同步查库，这里提升为一次性订阅
    val videoRefSupported by vm.videoRefSupported.collectAsState()

    // 第四批：渲染任务明细（local_file_uri / file_size / 时间戳）——逐镜预览 + 原始失败原因
    val renderTasks by vm.renderTasks.collectAsState()
    LaunchedEffect(st.shotStates) { vm.refreshRenderTasks() }
    // 第七批：进入页面先跑一次零付费预检（不阻塞首帧）
    LaunchedEffect(Unit) { vm.refreshPreflight() }
    var previewShotId by remember { mutableStateOf<String?>(null) }
    var reasonShotId by remember { mutableStateOf<String?>(null) }
    // 第七批：预检明细弹窗 / 有阻断镜时的入队二次确认
    var showPreflightDetail by remember { mutableStateOf(false) }
    var showStartBlockedConfirm by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // 第五批：预算上限可调（工程 ID 与 RenderRuntime.projectIdOf 对齐：截到最后一个 _ep 之前）
    val projectId = remember(episodeId) { episodeId.substringBeforeLast("_ep") }
    var budgetLimit by remember(projectId) { mutableStateOf(st.usage.limit) }
    var showBudgetLimitDialog by remember { mutableStateOf(false) }
    var budgetLimitInput by remember { mutableStateOf("") }
    var budgetLimitError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(projectId, st.usage.limit) {
        budgetLimit = runCatching { AppGraph.budgetLimit(projectId) }.getOrDefault(0)
            .takeIf { it > 0 } ?: st.usage.limit
    }

    // 第六轮：图生视频/视频参考 入口状态
    var pendingShotId by remember { mutableStateOf<String?>(null) }
    var showRefImagePicker by remember { mutableStateOf(false) }
    var showRefVideoPicker by remember { mutableStateOf(false) }

    // 相册图片 → 作为该镜首帧（图生视频 keyframes 起点；复用本地上传选图）
    val refImageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()) { uri: Uri? ->
        val shot = pendingShotId
        if (uri != null && shot != null) vm.setShotKeyframe(shot, first = uri.toString(), last = null)
        pendingShotId = null
    }
    // 相册视频 → 作为该镜视频参考（仅模型支持时）
    val refVideoLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()) { uri: Uri? ->
        val shot = pendingShotId
        if (uri != null && shot != null) vm.setShotReferenceVideo(shot, uri.toString())
        pendingShotId = null
    }

    if (showRefImagePicker) {
        LaunchedPickEffect {
            refImageLauncher.launch("image/*")
            showRefImagePicker = false
        }
    }
    if (showRefVideoPicker) {
        LaunchedPickEffect {
            refVideoLauncher.launch("video/*")
            showRefVideoPicker = false
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PageHeader(title = "渲染队列", subtitle = "镜头状态机实时刷新 · 可暂停/恢复/取消")

        // ---- 总进度卡 ----
        DramaCard(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val snap = st.snapshot
                val pauseText: String? = snap.pausedReason
                val epDisplay = snap.episodeId?.let { id ->
                    // v1.9.23：工程内部 ID 如 p_1788428301309_ep1 → 显示 ep1，避免「第 p_..._ep1集」
                    id.substringAfterLast("_").takeIf { it.startsWith("ep", ignoreCase = true) } ?: id
                } ?: "本集"
                val epLabel = "第${epDisplay}集"
                // 第五批：统计只认本页逐镜事实 shotStates（不再回退 snapshot 的 0/0，见 batch5 P0-2）
                val statTotal = st.shotStates.size
                val statCompleted = st.shotStates.values.count { it == "COMPLETED" }
                val statFailed = st.shotStates.values.count { it == "FAILED" || it == "BLOCKED" }
                val statPending = (statTotal - statCompleted - statFailed).coerceAtLeast(0)
                // ★v1.10：头部进度改用本页逐镜事实（历史失败任务存在时 snapshot 可能为 0/0）
                Text("$epLabel · $statCompleted/$statTotal 镜完成",
                    style = MaterialTheme.typography.titleMedium)
                LinearProgressIndicator(
                    progress = { if (statTotal > 0) statCompleted.toFloat() / statTotal else 0f },
                    modifier = Modifier.fillMaxWidth())
                Text("已完成 $statCompleted / 失败 $statFailed / 待处理 $statPending / 共 $statTotal",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                // 第五批：暂停原因统一走中文映射（QueueLogic.pausedReasonText，纯 Kotlin 可单测）
                val statusLine = if (pauseText == null) {
                    if (snap.running) "渲染中…" else "空闲 · 点击「开始渲染」入队"
                } else {
                    "已暂停：" + (pausedReasonText(pauseText) ?: pauseText)
                }
                val isPaused = pauseText != null
                Text(statusLine, style = MaterialTheme.typography.bodySmall,
                    color = if (isPaused) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline)
                // 第七批：渲染前预检摘要（阻断>0 红色）；点击查看逐条原因
                st.preflight?.let { rep ->
                    Text(
                        "预检：可渲 ${rep.renderable}/${rep.total} 镜 · 阻断 ${rep.blockedShots} · 警告 ${rep.warningShots}",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (rep.blockedShots > 0) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.fillMaxWidth().clickable { showPreflightDetail = true },
                    )
                }
                // 预算用量条（条数型，决议Q2）；第五批：上限可调，保存后立即生效
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("预算：${st.usage.used}/$budgetLimit 条" +
                            (if (st.usage.priceEstimateYuan > 0) " · 约¥%.2f".format(st.usage.priceEstimateYuan) else ""),
                        style = MaterialTheme.typography.bodySmall)
                    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 32.dp) {
                        OutlinedButton(
                            onClick = {
                                budgetLimitError = null
                                budgetLimitInput = budgetLimit.toString()
                                showBudgetLimitDialog = true
                            },
                            enabled = !snap.running,
                            modifier = Modifier.height(32.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                        ) { Text("调整上限", style = MaterialTheme.typography.labelMedium) }
                    }
                }

                // ---- 暂停/恢复/开始按钮组（FlowRow：窄屏自动换行，不再挤压放大）----
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { vm.pause() }, enabled = snap.running) { Text("暂停") }
                    Button(onClick = { vm.resume() },
                        enabled = !snap.running && snap.pausedReason != "budget_exceeded") { Text("恢复") }
                    Button(onClick = {
                        // 第七批：先刷新预检；有阻断镜先二次确认，阻断=0 才直接入队
                        scope.launch {
                            vm.refreshPreflight()
                            val report = vm.state.value.preflight
                            if (report != null && report.blockedShots > 0) {
                                showStartBlockedConfirm = true
                            } else {
                                // 第五批：从本集分镜表读真实镜头构造 ShotMeta 入队（原先传空列表恒为 0 镜）
                                vm.enqueueCurrentEpisode()
                            }
                        }
                    }, enabled = !snap.running) {
                        Text(if (snap.running) "渲染中" else "开始渲染")
                    }
                }
                // v1.9.19：清空队列独立一行右置，避免与主控按钮挤在一行导致文字竖排
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    OutlinedButton(onClick = { vm.requestClearQueue() },
                        enabled = !snap.running && st.shotStates.isNotEmpty()) {
                        Text("清空队列")
                    }
                }
            }
        }

        // ---- v1.9.20：storyboard_gate 详情直显（哪几镜、为什么），省去抓 logcat ----
        if (st.storyboardBlockDetail.isNotEmpty()) {
            DramaCard(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("分镜六铁律未过审 · 整集中止", style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.error)
                    Text("以下镜头分镜检查报 error，需回分镜页修复 / 重生成后再点「开始渲染」：",
                        style = MaterialTheme.typography.bodySmall)
                    for ((shotId, raw) in st.storyboardBlockDetail) {
                        val human = raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                            .joinToString("；") { sbCheckLabel(it) }
                        Text("• $shotId：$human", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }

        // ---- 镜状态列表（状态机六态实时刷新）----
        // weight(1f)：外层是 fillMaxSize 的 Column，不给高度约束的话 LazyColumn 会以
        // 「无限最大高度」测量 —— foundation 1.4+ 不再抛异常，但会退化为一次性全量排版
        // （丢掉复用）且超出屏幕部分滚不到。给它剩余空间才能正常懒加载 + 滚动。
        // v1.9.22：LazyColumn 底部预留 88dp，避免最后一镜被底部导航栏 + FAB 遮挡
        LazyColumn(
            Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = PaddingValues(bottom = 88.dp),
        ) {
            if (st.shotStates.isEmpty()) {
                item {
                    EmptyState(
                        icon = { Icon(painterResource(R.drawable.ic_videocam), contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp)) },
                        title = "队列是空的",
                        subtitle = "先到分镜页生成镜头，再点「开始渲染」把它们送进渲染队列。",
                    )
                }
            }
            for ((shotId, stateName) in st.shotStates) {
                item(key = shotId) {
                    DramaCard(Modifier.fillMaxWidth()) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            // 镜信息（镜号 + 状态 + 失败原因）：左对齐自然宽度
                            Column(Modifier) {
                                Text(shotId, style = MaterialTheme.typography.bodyMedium)
                                Row(verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Icon(shotStateIcon(stateName), contentDescription = null,
                                        tint = shotStateColor(stateName), modifier = Modifier.size(16.dp))
                                    Text(shotStateLabel(stateName), style = MaterialTheme.typography.bodySmall,
                                        color = shotStateColor(stateName))
                                }
                                // v1.9.13：FAILED/BLOCKED/RECONCILE 直接展示原因，省去抓 logcat
                                val reason = st.shotReasons[shotId]
                                if (!reason.isNullOrBlank() && stateName in listOf("FAILED", "BLOCKED", "RECONCILE")) {
                                    Text(renderErrorLabel(reason), style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.padding(top = 2.dp))
                                }
                            }
                            // 第四批：本镜渲染任务明细（预览需要文件路径/大小；原因弹窗需要原始 fail_reason）
                            val task = renderTasks[shotId]
                            // v1.9.21：操作按钮改用 FlowRow 全宽换行，窄屏不再横向溢出被放大
                            FlowRow(Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                // 第四批：已完成/已落文件的镜头可逐镜预览（本地文件或 http(s) 源）
                                if (task != null && isPreviewable(task, stateName)) {
                                    QueueActionButton("预览") { previewShotId = shotId }
                                }
                                // 第四批：失败/放弃/待对账 → 查看完整原始原因（可滚动、可长按复制）
                                if (stateName in listOf("FAILED", "BLOCKED", "RECONCILE") &&
                                    taskReason(task, st.shotReasons[shotId]) != null) {
                                    QueueActionButton("查看原因") { reasonShotId = shotId }
                                }
                                // 第六轮：图生视频「设参考图」入口（复用本地上传选图）
                                QueueActionButton("设参考图") { pendingShotId = shotId; showRefImagePicker = true }
                                // 第六轮：视频参考入口——仅当前视频模型标记支持时显示
                                if (videoRefSupported) {
                                    QueueActionButton("上传参考视频") { pendingShotId = shotId; showRefVideoPicker = true }
                                }
                                if (stateName == "RECONCILE") {
                                    // 待对账镜 → 人工处置对话框（复审N-2条件项）
                                    QueueActionButton("处置") {
                                        vm.openReconcileDialog(shotId, "提交结果未知，需人工核实")
                                    }
                                } else if (stateName in listOf("PENDING", "SUBMITTING", "SUBMITTED")) {
                                    QueueActionButton("取消") { vm.cancelShot(shotId) }
                                }
                                // v1.9.18：失败/已放弃/待对账的镜可重试（重置 PENDING 并清失败原因）
                                if (stateName in listOf("FAILED", "BLOCKED", "RECONCILE")) {
                                    QueueActionButton("重试") { vm.retryShot(shotId) }
                                }
                                // v1.9.18：删除该镜（二次确认，彻底移除 shots + render_tasks）
                                QueueActionButton("删除", danger = true) { vm.requestDeleteShot(shotId) }
                            }
                        }
                    }
                }
            }
        }
    }

    // ---- 预算确认弹窗（resume(confirmedByUser=true)放行位语义）----
    if (st.showBudgetConfirm) {
        AlertDialog(
            onDismissRequest = { vm.dismissBudgetConfirm() },
            title = { Text("预算已达上限") },
            text = { Text("已用 ${st.usage.used}/$budgetLimit 条。继续渲染将超出预设上限并产生额外费用。\n确认继续吗？") },
            confirmButton = { Button(onClick = { vm.confirmBudget() }) { Text("继续渲染（超限放行）") } },
            dismissButton = { OutlinedButton(onClick = { vm.dismissBudgetConfirm() }) { Text("暂不渲染") } },
        )
    }

    // ---- 第五批：调整预算上限（1–200；保存同步 AppGraph/SharedPreferences 与 budgetGuard）----
    if (showBudgetLimitDialog) {
        val parsed = budgetLimitInput.toIntOrNull()
        val valid = parsed != null && parsed in 1..200
        AlertDialog(
            onDismissRequest = { showBudgetLimitDialog = false },
            title = { Text("调整预算上限") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("每集最多提交渲染的镜头数（1–200）。保存后立即生效，已用条数不受影响。",
                        style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        value = budgetLimitInput,
                        onValueChange = { raw ->
                            budgetLimitInput = raw.filter { it.isDigit() }.take(3)
                            budgetLimitError = null
                        },
                        singleLine = true,
                        isError = budgetLimitError != null || (budgetLimitInput.isNotEmpty() && !valid),
                        label = { Text("上限（条）") },
                        supportingText = {
                            Text(budgetLimitError ?: if (valid) "当前已用 ${st.usage.used} 条" else "请输入 1–200 的整数")
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    val n = budgetLimitInput.toIntOrNull()
                    if (n != null && n in 1..200) {
                        runCatching { AppGraph.setBudgetLimit(projectId, n) }
                            .onSuccess {
                                budgetLimit = n
                                showBudgetLimitDialog = false
                            }
                            .onFailure { budgetLimitError = it.message ?: "保存失败，请重试" }
                    }
                }, enabled = valid) { Text("保存") }
            },
            dismissButton = {
                OutlinedButton(onClick = { showBudgetLimitDialog = false }) { Text("取消") }
            },
        )
    }

    // ---- RECONCILE人工处置对话框：重试 / 放弃 ----
    st.reconcileShot?.let { (shotId, reason) ->
        AlertDialog(
            onDismissRequest = { vm.dismissReconcileDialog() },
            title = { Text("镜头待对账（RECONCILE）") },
            text = { Text("镜头 $shotId 提交结果未知，可能已在服务端创建任务并计费。\n原因：$reason\n\n「重试」将重置为待处理并续跑队列；「放弃」将其标记为终止态（不再自动处理）。") },
            confirmButton = { Button(onClick = { vm.resolveReconcile(retry = true) }) { Text("重试") } },
            dismissButton = { OutlinedButton(onClick = { vm.resolveReconcile(retry = false) }) { Text("放弃") } },
        )
    }

    // ---- v1.9.18：删除单镜二次确认（不可逆）----
    st.pendingDelete?.let { shotId ->
        AlertDialog(
            onDismissRequest = { vm.dismissDeleteShot() },
            title = { Text("删除镜头 $shotId？") },
            text = { Text("将彻底删除该镜：分镜数据（含台词/关键帧/资产引用）与渲染任务记录一并移除，且无法恢复。\n\n如果只是不想让它继续渲染，用「取消」即可。") },
            confirmButton = { Button(onClick = { vm.confirmDeleteShot() }) { Text("确认删除") } },
            dismissButton = { OutlinedButton(onClick = { vm.dismissDeleteShot() }) { Text("再想想") } },
        )
    }

    // ---- v1.9.18：清空本集队列二次确认 ----
    if (st.showClearConfirm) {
        AlertDialog(
            onDismissRequest = { vm.dismissClearQueue() },
            title = { Text("清空本集渲染队列？") },
            text = { Text("将清空本集全部渲染任务记录（含已完成/失败/待处理）。\n\n分镜数据（shots）会保留，之后仍可点「开始渲染」重新入队。") },
            confirmButton = { Button(onClick = { vm.confirmClearQueue() }) { Text("清空") } },
            dismissButton = { OutlinedButton(onClick = { vm.dismissClearQueue() }) { Text("取消") } },
        )
    }

    // ---- 第七批：渲染前预检明细（逐条原因，阻断红色）----
    val preflightReport = st.preflight
    if (showPreflightDetail && preflightReport != null) {
        AlertDialog(
            onDismissRequest = { showPreflightDetail = false },
            title = { Text("渲染前预检") },
            text = {
                Column(
                    Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        "共 ${preflightReport.total} 镜：可渲 ${preflightReport.renderable} · " +
                            "阻断 ${preflightReport.blockedShots} · 警告 ${preflightReport.warningShots}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (preflightReport.issues.isEmpty()) {
                        Text("没有发现阻断项或警告。", style = MaterialTheme.typography.bodySmall)
                    } else {
                        for (issue in preflightReport.issues) {
                            val isBlock = issue.severity == RenderPreflight.Severity.BLOCK
                            Text(
                                text = (if (issue.shotNo > 0) "镜头 #${issue.shotNo} · " else "") +
                                    (if (isBlock) "阻断" else "警告") + "：" + issue.message,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (isBlock) MaterialTheme.colorScheme.error
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = { showPreflightDetail = false }) { Text("知道了") }
            },
        )
    }

    // ---- 第七批：有阻断镜时的「开始渲染」二次确认（确认后才入队）----
    if (showStartBlockedConfirm) {
        val blockers = preflightReport?.blockers.orEmpty()
        AlertDialog(
            onDismissRequest = { showStartBlockedConfirm = false },
            title = { Text("有 ${blockers.size} 镜会被渲染闸门拦下") },
            text = {
                Column(
                    Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("以下镜头会被队列闸门拦下，整集渲染可能中止：",
                        style = MaterialTheme.typography.bodySmall)
                    for (issue in blockers) {
                        Text(
                            (if (issue.shotNo > 0) "镜头 #${issue.shotNo}：" else "") + issue.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Text("建议先回分镜页修复这些镜头，再点「开始渲染」。",
                        style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                Button(onClick = {
                    showStartBlockedConfirm = false
                    vm.enqueueCurrentEpisode()
                }) { Text("仍要提交") }
            },
            dismissButton = {
                OutlinedButton(onClick = { showStartBlockedConfirm = false }) { Text("先去修") }
            },
        )
    }

    // ---- 第四批：逐镜视频预览（本地文件 / http(s) URL；关闭弹窗即停止播放并释放）----
    previewShotId?.let { sid ->
        QueuePreviewDialog(shotId = sid, task = renderTasks[sid], onClose = { previewShotId = null })
    }

    // ---- 第四批：失败原因完整展示（原始 fail_reason / blocked_reason，可滚动 + 长按复制）----
    reasonShotId?.let { sid ->
        QueueReasonDialog(
            shotId = sid,
            rawReason = taskReason(renderTasks[sid], st.shotReasons[sid]),
            onClose = { reasonShotId = null },
        )
    }

    // ---- 入队错误横幅 ----
    st.enqueueError?.let { err ->
        AlertDialog(onDismissRequest = { vm.clearEnqueueError() },
            title = { Text("入队失败") }, text = { Text(err) },
            confirmButton = { Button(onClick = { vm.clearEnqueueError() }) { Text("知道了") } })
    }
}

/** 仅用于触发一次图片/视频选择器的副作用封装（避免Compose重组重复launch） */
@Composable
private fun LaunchedPickEffect(block: () -> Unit) {
    androidx.compose.runtime.LaunchedEffect(Unit) { block() }
}

/**
 * 队列页每镜操作按钮（v1.9.21）：局部把最小可交互尺寸降到 36dp + 紧凑 padding + 小字号，
 * 让一镜最多 5 个按钮（设参考图/上传参考视频/取消或处置/重试/删除）在手机窄屏下也能 FlowRow 换行，
 * 不再横向溢出被强制压缩放大。danger=true 时按钮文字着 error 色，提示不可逆操作。
 */
@Composable
private fun QueueActionButton(
    text: String,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 36.dp) {
        OutlinedButton(
            onClick = onClick,
            modifier = Modifier.height(36.dp),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
        ) {
            Text(
                text,
                style = MaterialTheme.typography.labelMedium,
                color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/** 只提示、不阻断渲染的软校验码：场景/光线/节奏/站位视线/场次结构差异属于画面设计问题，不该挡住整集渲染。 */
internal val SOFT_SB_CHECK_CODES = setOf(
    "scene_context_conflict",
    "rhythm_monotone",
    "rhythm_flat",
    "establishing_missing",
    "dialogue_pace",
    "axis_jump",
    "eyeline_mismatch",
    "scene_single_shot",
    "hook_weak",
    "ending_weak",
    "action_camera",
    "action_vague",
    "action_thin",
    "visual_missing",
    "visual_thin",
    "visual_no_size",
    "visual_no_light",
    "visual_no_composition",
    "scene_thin",
    "carry_intent",
)

/** gateErrors → sb_check 持久化值：硬错误写 error:（渲染闸拦截），软提示写 warn:（界面黄色提示）。 */
internal fun sbCheckFromResult(codes: List<String>?): String {
    if (codes.isNullOrEmpty()) return "pass"
    val hard = codes.filter { it !in SOFT_SB_CHECK_CODES }
    val soft = codes.filter { it in SOFT_SB_CHECK_CODES }
    return when {
        hard.isNotEmpty() -> "error:" + hard.joinToString(",")
        soft.isNotEmpty() -> "warn:" + soft.joinToString(",")
        else -> "pass"
    }
}

/** 旧数据兼容：历史版本把视觉指令拼进 action（"动作；［画面描述］"），渲染/显示前剥掉，避免运镜重复。 */
internal fun cleanShotAction(action: String?, visual: String?): String {
    val raw = action.orEmpty()
    val v = visual?.takeIf { it.isNotBlank() } ?: return raw
    return raw.replace("；［$v］", "").replace("［$v］", "").trim().ifBlank { raw }
}

/** 分镜 sb_check 错误码 → 中文可读（对齐 AiStoryboardDirector 六铁律忠实性校验） */
internal fun sbCheckLabel(code: String) = when (code) {
    "dialogue_not_verbatim" -> "台词未逐字出现在剧本原文"
    "carry_over_missing" -> "缺少前后镜头承接：请补充角色、场景、道具状态或转场动作"
    "shot_order_gap" -> "镜号不连续：前后镜头存在顺序断点"
    "asset_unbound", "missing_asset" -> "引用了未批准资产：请重新选择资产库中的素材"
    "rhythm_duration" -> "镜头时长不在4–10秒建议范围"
    "rhythm_monotone" -> "连续多镜同一景别（建议拉开景别层次，仅提示）"
    "rhythm_flat" -> "连续多镜同一时长（建议做节奏变化，仅提示）"
    "establishing_missing" -> "场次切换后缺少建立镜头（建议先给全景/中景，仅提示）"
    "dialogue_pace" -> "台词/旁白朗读时长超过镜头（建议加长或拆镜，仅提示）"
    "axis_jump" -> "站位越轴待确认"
    "eyeline_mismatch" -> "视线同向待确认"
    "scene_single_shot" -> "单镜成场"
    "hook_weak" -> "开场钩子弱"
    "ending_weak" -> "结尾泄气"
    "scene_context_conflict" -> "画面描述与上一镜场景不连续（仅提示，不阻断渲染）"
    "scene_context_missing" -> "缺少场景上下文：请补充时间、空间、天气"
    "beat_ref_missing" -> "缺少剧本节拍来源"
    "beat_index_invalid" -> "剧本节拍顺序无效"
    "beat_out_of_order" -> "剧本节拍顺序回退"
    "action_empty" -> "动作描述为空"
    "action_camera" -> "动作混入镜头语言：景别/机位/运镜应写进画面描述"
    "action_vague" -> "动作空泛：情绪需外化成可见行为"
    "action_thin" -> "动作太短：补一个道具/光影/环境细节"
    "visual_missing" -> "缺少画面描述：需补景别+机位+构图+光线"
    "visual_thin" -> "画面描述过短：按五项写满 70–130 字"
    "visual_no_size" -> "画面缺景别"
    "visual_no_light" -> "画面缺光线"
    "visual_no_composition" -> "画面缺构图与主体调度"
    "scene_thin" -> "场景信息太薄：补时间/空间布局/主光源"
    "carry_intent" -> "承接写成了剧情意图：应写最后一帧状态"
    else -> code
}

/** 状态机中文标签 */
internal fun shotStateLabel(s: String) = when (s) {
    "PENDING" -> "待处理"
    "SUBMITTING" -> "提交中"
    "SUBMITTED" -> "已提交·生成中"
    "COMPLETED" -> "已完成"
    "FAILED" -> "失败"
    "BLOCKED" -> "已放弃"
    "RECONCILE" -> "待对账"
    else -> s
}

/** 将服务端/供应商错误翻译成用户可执行的提示，避免暴露内部重试堆栈。 */
internal fun renderErrorLabel(raw: String): String {
    val s = raw.trim()
    if (s.contains("model_not_found", ignoreCase = true) ||
        s.contains("No available channel", ignoreCase = true)) {
        val model = Regex("model\\s+([A-Za-z0-9_.-]+)", RegexOption.IGNORE_CASE)
            .find(s)?.groupValues?.getOrNull(1)
        return if (model != null) "视频模型不可用（$model）：请到设置更换模型或渠道"
        else "视频模型不可用：请到设置更换模型或渠道"
    }
    if (s.contains("giving up", ignoreCase = true) && s.contains("503")) {
        return "视频服务暂时繁忙：请稍后重试"
    }
    return s
}

/** 状态机图标（与 shotStateColor 同一套语义色，替代 emoji 前缀） */
internal fun shotStateIcon(s: String): ImageVector = when (s) {
    "PENDING" -> Icons.Default.DateRange
    "SUBMITTING" -> Icons.Default.ArrowForward
    "SUBMITTED" -> Icons.Default.Refresh
    "COMPLETED" -> Icons.Default.CheckCircle
    "FAILED" -> Icons.Default.Warning
    "BLOCKED" -> Icons.Default.Clear
    "RECONCILE" -> Icons.Default.Warning
    else -> Icons.Default.Info
}

@Composable
private fun shotStateColor(s: String) = when (s) {
    "COMPLETED" -> MaterialTheme.colorScheme.primary
    "FAILED", "BLOCKED", "RECONCILE" -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.outline
}

/** 第四批：失败/放弃/待对账的原始原因——优先 render_tasks.fail_reason/blocked_reason，回落队列状态映射。 */
private fun taskReason(task: RenderTaskEntity?, fallback: String?): String? =
    task?.fail_reason?.takeIf { it.isNotBlank() }
        ?: task?.blocked_reason?.takeIf { it.isNotBlank() }
        ?: fallback?.takeIf { it.isNotBlank() }

/** 第四批：是否展示「预览」入口——COMPLETED（即使文件被清理也给出提示）或已落文件（file_size>0 且 URI 非空）。 */
private fun isPreviewable(task: RenderTaskEntity, stateName: String): Boolean {
    if (stateName == "COMPLETED") return true
    return task.file_size > 0L && !task.local_file_uri.isNullOrBlank()
}

/** 第四批：本地文件/远程 URL → VideoView 可播放的 Uri（兼容绝对路径、file://、content://、http(s)）。 */
private fun previewUriOf(raw: String): Uri = when {
    raw.startsWith("http://", ignoreCase = true) ||
        raw.startsWith("https://", ignoreCase = true) ||
        raw.startsWith("content://", ignoreCase = true) ||
        raw.startsWith("file://", ignoreCase = true) -> Uri.parse(raw)
    raw.startsWith("/") -> Uri.fromFile(File(raw))
    else -> Uri.parse(raw)
}

/** 文件大小（MB，保留 1 位小数）。 */
private fun formatFileSizeMb(bytes: Long): String {
    if (bytes <= 0L) return "未知"
    return "%.1f MB".format(java.util.Locale.CHINA, bytes / 1024.0 / 1024.0)
}

/** epoch 毫秒 → 本地时间 MM-dd HH:mm；空值显示「—」。 */
private fun formatEpochTime(ms: Long?): String {
    if (ms == null || ms <= 0L) return "—"
    return java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA).format(java.util.Date(ms))
}

/**
 * 第四批：逐镜视频预览弹窗。
 * - 本地文件（绝对路径 / file:// / content://）与 http(s) 源都走 VideoView；
 * - DisposableEffect 在弹窗离开组合时 stopPlayback + 解除 URI，避免后台继续播放/占用解码器；
 * - update 用 tag 去重，避免每次重组都 setVideoURI 导致从头重播。
 */
@Composable
private fun QueuePreviewDialog(shotId: String, task: RenderTaskEntity?, onClose: () -> Unit) {
    val rawUri = task?.local_file_uri
    if (task == null || rawUri.isNullOrBlank()) {
        AlertDialog(
            onDismissRequest = onClose,
            title = { Text("镜头 " + shotId + " 预览") },
            text = { Text("该镜没有可播放的视频文件（可能尚未渲染完成或被清理）。") },
            confirmButton = { Button(onClick = onClose) { Text("关闭") } },
        )
        return
    }
    val uri = remember(rawUri) { previewUriOf(rawUri) }
    val vvHolder = remember(uri) { arrayOfNulls<VideoView>(1) }
    DisposableEffect(uri) {
        onDispose {
            vvHolder[0]?.let { vv ->
                runCatching { vv.stopPlayback() }
                runCatching { vv.setVideoURI(null) }
            }
            vvHolder[0] = null
        }
    }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("镜头 " + shotId + " 预览") },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.fillMaxWidth().height(320.dp)) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            VideoView(ctx).apply {
                                vvHolder[0] = this
                                tag = rawUri
                                setVideoURI(uri)
                                setOnPreparedListener { mp -> mp.isLooping = false; start() }
                                setOnErrorListener { _, _, _ -> true }
                            }
                        },
                        update = { vv ->
                            // tag 相同说明播放源未变：不要重复 setVideoURI（会从头重播）
                            if (vv.tag != rawUri) {
                                vv.tag = rawUri
                                vv.setVideoURI(uri)
                                vv.start()
                            }
                        },
                    )
                }
                Text("文件大小：" + formatFileSizeMb(task.file_size),
                    style = MaterialTheme.typography.bodySmall)
                Text("完成时间：" + formatEpochTime(task.completed_at),
                    style = MaterialTheme.typography.bodySmall)
                Text("提交时间：" + formatEpochTime(task.submitted_at),
                    style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { Button(onClick = onClose) { Text("关闭") } },
    )
}

/** 第四批：失败原因完整展示弹窗——可滚动 + SelectionContainer 长按复制原始错误。 */
@Composable
private fun QueueReasonDialog(shotId: String, rawReason: String?, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("镜头 " + shotId + " 失败原因") },
        text = {
            SelectionContainer {
                Column(
                    Modifier.fillMaxWidth()
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("概要：" + (rawReason?.let { renderErrorLabel(it) } ?: "无原因记录"),
                        style = MaterialTheme.typography.bodyMedium)
                    if (!rawReason.isNullOrBlank()) {
                        Text("原始错误（长按可复制）：",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(rawReason, style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace)
                    }
                }
            }
        },
        confirmButton = { Button(onClick = onClose) { Text("关闭") } },
    )
}

// ---------- Compose预览 ----------

@Preview(showBackground = true, locale = "zh")
@Composable
private fun PreviewQueuePage() {
    MaterialTheme {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("渲染队列", style = MaterialTheme.typography.headlineSmall)
            DramaCard(Modifier.fillMaxWidth()) {
                Column(Modifier) {
                    Text("第p1_ep1集 · 12/24 镜完成", style = MaterialTheme.typography.titleMedium)
                    LinearProgressIndicator(progress = { 0.5f }, modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {}) { Text("暂停") }
                        Button(onClick = {}) { Text("恢复") }
                    }
                }
            }
            for ((sid, label) in mapOf(
                "s001" to shotStateLabelStatic("SUBMITTED"),
                "s002" to shotStateLabelStatic("COMPLETED"),
                "s003" to shotStateLabelStatic("RECONCILE"))) {
                DramaCard(Modifier.fillMaxWidth()) {
                    Row(Modifier, horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(sid); Text(label, color = MaterialTheme.colorScheme.outline)
                    }
                }
            }
        }
    }
}

private fun shotStateLabelStatic(s: String) = when (s) {
    "SUBMITTED" -> "已提交·生成中"; "COMPLETED" -> "已完成"; "RECONCILE" -> "待对账"; else -> s
}