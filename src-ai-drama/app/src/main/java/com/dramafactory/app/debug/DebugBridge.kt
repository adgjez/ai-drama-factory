package com.dramafactory.app.debug

import android.util.Log
import com.dramafactory.app.AppGraph
import com.dramafactory.app.BuildConfig
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder

/**
 * 调试桥（仅 debug 包启用）：在本机 127.0.0.1 上暴露只读的 JSON 查询接口，
 * 让开发者在 proot/终端里直接读 App 的 Room 数据库状态（资产审核态、分镜字段、
 * 渲染任务失败原因），不必靠截图猜。
 *
 * 设计约束：
 * - 只在 BuildConfig.DEBUG 构建里启动；release 包不会跑这段代码；
 * - 只绑 127.0.0.1，不对外网/局域网开放；
 * - 只读：不提供任何写库或触发付费渲染的接口；
 * - 单线程 accept + 每连接一线程，DAO 调用走 runBlocking（IO 线程）。
 *
 * 端点（GET）：
 *   /health
 *   /projects
 *   /episodes?projectId=...
 *   /assets?projectId=...
 *   /shots?episodeId=...
 *   /tasks?episodeId=...
 *   /summary?projectId=...
 */
object DebugBridge {

    const val PORT = 18765
    private const val TAG = "DebugBridge"

    @Volatile private var started = false

    fun start() {
        if (!BuildConfig.DEBUG) return
        if (started) return
        started = true
        Thread({ runServer() }, "drama-debug-bridge").apply { isDaemon = true }.start()
    }

    private fun runServer() {
        try {
            ServerSocket(PORT, 16, InetAddress.getByName("127.0.0.1")).use { server ->
                Log.i(TAG, "debug bridge listening on http://127.0.0.1:" + PORT)
                while (true) {
                    val client = try {
                        server.accept()
                    } catch (t: Throwable) {
                        Log.w(TAG, "accept failed", t)
                        break
                    }
                    Thread({ handle(client) }, "drama-debug-conn").apply { isDaemon = true }.start()
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "debug bridge failed", t)
        }
    }

    private fun handle(client: Socket) {
        try {
            client.use { socket ->
                socket.soTimeout = 5000
                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
                val requestLine = reader.readLine() ?: return
                // 读掉请求头（不处理 body：本桥只读）
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                }
                val parts = requestLine.split(" ")
                val method = parts.getOrElse(0) { "" }
                val target = parts.getOrElse(1) { "/" }
                val path = target.substringBefore('?')
                val query = parseQuery(target.substringAfter('?', ""))
                val out = socket.getOutputStream()
                if (method != "GET") {
                    respond(out, 405, error("method_not_allowed", "只读调试桥，只支持 GET"))
                    return
                }
                val result = try {
                    runBlocking { route(path, query) }
                } catch (t: Throwable) {
                    Log.w(TAG, "route " + path + " failed", t)
                    500 to error("internal_error", t.message ?: t.javaClass.simpleName)
                }
                respond(out, result.first, result.second)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "connection failed", t)
        }
    }

    private suspend fun route(path: String, q: Map<String, String>): Pair<Int, String> {
        // /health 不依赖数据库，AppGraph 未初始化时也要能回话
        if (path == "/" || path == "/health") {
            return 200 to JSONObject()
                .put("ok", true)
                .put("bridge", "drama-debug-bridge")
                .put("version", BuildConfig.VERSION_NAME)
                .put("port", PORT)
                .put("endpoints", JSONArray(listOf("/projects", "/episodes?projectId=", "/assets?projectId=", "/shots?episodeId=", "/tasks?episodeId=", "/summary?projectId=")))
                .toString()
        }
        val dao = daoOrNull() ?: return 503 to error("db_not_ready", "AppGraph.dao 尚未初始化")
        return when (path) {
            "/projects" -> 200 to JSONObject()
                .put("ok", true)
                .put("projects", JSONArray(dao.listProjects().map { projectJson(it) }))
                .toString()

            "/episodes" -> {
                val projectId = q["projectId"].orEmpty()
                if (projectId.isEmpty()) return 400 to error("missing_param", "projectId 必填")
                200 to JSONObject()
                    .put("ok", true)
                    .put("episodes", JSONArray(dao.episodesOf(projectId).map { episodeJson(it) }))
                    .toString()
            }

            "/assets" -> {
                val projectId = q["projectId"].orEmpty()
                if (projectId.isEmpty()) return 400 to error("missing_param", "projectId 必填")
                val rows = dao.assetsAllOf(projectId)
                200 to JSONObject()
                    .put("ok", true)
                    .put("count", rows.size)
                    .put("assets", JSONArray(rows.map { assetJson(it) }))
                    .toString()
            }

            "/shots" -> {
                val episodeId = q["episodeId"].orEmpty()
                if (episodeId.isEmpty()) return 400 to error("missing_param", "episodeId 必填")
                val rows = dao.shotsOf(episodeId)
                200 to JSONObject()
                    .put("ok", true)
                    .put("count", rows.size)
                    .put("shots", JSONArray(rows.map { shotJson(it) }))
                    .toString()
            }

            "/tasks" -> {
                val episodeId = q["episodeId"].orEmpty()
                if (episodeId.isEmpty()) return 400 to error("missing_param", "episodeId 必填")
                val rows = dao.renderTasksOf(episodeId)
                200 to JSONObject()
                    .put("ok", true)
                    .put("count", rows.size)
                    .put("tasks", JSONArray(rows.map { taskJson(it) }))
                    .toString()
            }

            "/summary" -> {
                val projectId = q["projectId"].orEmpty()
                if (projectId.isEmpty()) return 400 to error("missing_param", "projectId 必填")
                summary(projectId)
            }

            else -> 404 to error("not_found", "未知端点：" + path)
        }
    }

    /** 项目级体检：资产审核分布 + 每集分镜/渲染状态分布，用于快速定位「为什么渲染不动」。 */
    private suspend fun summary(projectId: String): Pair<Int, String> {
        val dao = daoOrNull() ?: return 503 to error("db_not_ready", "AppGraph.dao 尚未初始化")
        val project = dao.project(projectId)
        val assets = dao.assetsAllOf(projectId)
        val auditStates = linkedMapOf<String, Int>()
        for (a in assets) auditStates[a.audit_state] = (auditStates[a.audit_state] ?: 0) + 1
        val episodes = dao.episodesOf(projectId)
        val epRows = mutableListOf<JSONObject>()
        for (ep in episodes) {
            val shots = dao.shotsOf(ep.episode_id)
            val tasks = dao.renderTasksOf(ep.episode_id)
            val sbError = shots.count { it.sb_check.startsWith("error") }
            val sbPass = shots.count { it.sb_check == "pass" }
            val taskStates = linkedMapOf<String, Int>()
            for (t in tasks) taskStates[t.state] = (taskStates[t.state] ?: 0) + 1
            epRows.add(
                JSONObject()
                    .put("episodeId", ep.episode_id)
                    .put("epNo", ep.ep_no)
                    .put("shots", shots.size)
                    .put("sbPass", sbPass)
                    .put("sbError", sbError)
                    .put("taskStates", countsJson(taskStates))
                    .put("lastFailReason", str(tasks.lastOrNull { it.fail_reason != null }?.fail_reason, 500))
            )
        }
        return 200 to JSONObject()
            .put("ok", true)
            .put("project", project?.let { projectJson(it) })
            .put("assetCount", assets.size)
            .put("assetAuditStates", countsJson(auditStates))
            .put("assetApproved", assets.count { it.audit_state == "approved" })
            .put("assetPending", assets.count { it.audit_state == "pending" })
            .put("assetRejected", assets.count { it.audit_state == "rejected" })
            .put("episodes", JSONArray(epRows))
            .toString()
    }

    private fun countsJson(counts: Map<String, Int>): JSONObject {
        val o = JSONObject()
        for ((k, v) in counts) o.put(k, v)
        return o
    }

    private fun daoOrNull(): com.dramafactory.app.data.DramaDao? =
        try {
            AppGraph.dao
        } catch (t: Throwable) {
            null
        }

    private fun projectJson(p: com.dramafactory.app.data.ProjectEntity): JSONObject = JSONObject()
        .put("projectId", p.project_id)
        .put("name", p.name)
        .put("stylePreset", p.style_preset)
        .put("episodePlan", p.episode_plan)
        .put("budgetShots", p.budget_shots)
        .put("createdAt", p.created_at)
        .put("bgmPath", p.bgm_path)
        .put("bgmVolume", p.bgm_volume)

    private fun episodeJson(e: com.dramafactory.app.data.EpisodeEntity): JSONObject = JSONObject()
        .put("episodeId", e.episode_id)
        .put("projectId", e.project_id)
        .put("epNo", e.ep_no)
        .put("reviewPassed", e.review_passed)
        .put("scriptChars", e.script_json?.length ?: 0)

    private fun assetJson(a: com.dramafactory.app.data.AssetEntity): JSONObject = JSONObject()
        .put("assetId", a.asset_id)
        .put("kind", a.kind)
        .put("poseRole", a.pose_role)
        .put("prompt", str(a.prompt, 300))
        .put("enrichedPrompt", str(a.enriched_prompt, 300))
        .put("remoteUrl", a.remote_url)
        .put("fileUri", a.file_uri)
        .put("auditState", a.audit_state)
        .put("g1State", a.g1_state)
        .put("qualityScore", a.quality_score)
        .put("qRejectReason", a.q_reject_reason)
        .put("defectsJson", str(a.defects_json, 300))
        .put("g1ErrorCode", a.g1_error_code)
        .put("source", a.source)
        .put("reviewState", a.review_state)
        .put("updatedAt", a.updated_at)

    private fun shotJson(s: com.dramafactory.app.data.ShotEntity): JSONObject = JSONObject()
        .put("shotId", s.shot_id)
        .put("shotNo", s.shot_no)
        .put("sceneNo", s.scene_no)
        .put("sceneTitle", s.scene_title)
        .put("durationSeconds", s.duration_seconds)
        .put("action", str(s.action, 600))
        .put("dialogue", str(s.dialogue, 600))
        .put("narration", str(s.narration, 600))
        .put("visualPrompt", str(s.visual_prompt, 400))
        .put("sceneContext", str(s.scene_context, 300))
        .put("carryOver", str(s.carry_over, 300))
        .put("screenSide", s.screen_side)
        .put("eyeline", s.eyeline)
        .put("sbCheck", str(s.sb_check, 800))
        .put("firstAssetIds", s.first_asset_ids)
        .put("lastAssetIds", s.last_asset_ids)
        .put("firstImageUri", s.first_image_uri)
        .put("lastImageUri", s.last_image_uri)

    private fun taskJson(t: com.dramafactory.app.data.RenderTaskEntity): JSONObject = JSONObject()
        .put("shotId", t.shot_id)
        .put("state", t.state)
        .put("attempt", t.attempt)
        .put("providerTaskId", t.provider_task_id)
        .put("blockedReason", str(t.blocked_reason, 800))
        .put("failReason", str(t.fail_reason, 1200))
        .put("fileSize", t.file_size)
        .put("submittedAt", t.submitted_at)
        .put("completedAt", t.completed_at)

    private fun str(v: String?, max: Int): String? =
        if (v == null || v.length <= max) v else v.take(max) + "…"

    private fun error(code: String, message: String): String =
        JSONObject().put("ok", false).put("error", code).put("message", message).toString()

    private fun parseQuery(raw: String): Map<String, String> {
        if (raw.isEmpty()) return emptyMap()
        val map = linkedMapOf<String, String>()
        for (pair in raw.split("&")) {
            if (pair.isEmpty()) continue
            val key = pair.substringBefore('=')
            val value = pair.substringAfter('=', "")
            map[URLDecoder.decode(key, "UTF-8")] = URLDecoder.decode(value, "UTF-8")
        }
        return map
    }

    private fun respond(out: OutputStream, code: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val head = "HTTP/1.1 " + code + " " + reason(code) + "\r\n" +
            "Content-Type: application/json; charset=utf-8\r\n" +
            "Content-Length: " + bytes.size + "\r\n" +
            "Connection: close\r\n" +
            "Access-Control-Allow-Origin: *\r\n" +
            "\r\n"
        try {
            out.write(head.toByteArray(Charsets.UTF_8))
            out.write(bytes)
            out.flush()
        } catch (t: Throwable) {
            Log.w(TAG, "write failed", t)
        }
    }

    private fun reason(code: Int): String = when (code) {
        200 -> "OK"
        400 -> "Bad Request"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        503 -> "Service Unavailable"
        else -> "Internal Server Error"
    }
}