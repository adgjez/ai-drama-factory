package com.dramafactory.app.debug

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.dramafactory.app.AppGraph
import com.dramafactory.app.BuildConfig
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * 调试桥（仅 debug 包启用）：在本机 127.0.0.1 上暴露只读的 JSON 查询接口，
 * 让开发者在 proot/终端里直接读 App 的 Room 数据库状态（资产审核态、分镜字段、
 * 渲染任务失败原因），不必靠截图猜。
 *
 * 设计约束：
 * - 只在 BuildConfig.DEBUG 构建里启动；release 包不会跑这段代码；
 * - 可通过 SharedPreferences("dsh_debug" / debug_bridge_enabled) 关闭（默认开）；
 * - 只绑 127.0.0.1，不对外网/局域网开放；
 * - 所有请求必须带 X-Debug-Token 头或 ?token=（SecureRandom 生成并持久化）；
 *   鉴权失败返回 401，且不读取 DAO、不生成任何数据；
 * - 不带 CORS 头：浏览器跨域读取被同源策略阻断；
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
 *
 * 鉴权：所有端点都需带 X-Debug-Token 头或 ?token=；桥启动时把 baseUrl 与 token
 * 写入 getExternalFilesDir(null)/debug-bridge.txt（一行 JSON），也可在设置页
 * 「开发者选项」查看/复制。
 */
object DebugBridge {

    const val PORT = 18765
    private const val TAG = "DebugBridge"

    private const val PREFS = "dsh_debug"
    private const val KEY_ENABLED = "debug_bridge_enabled"
    private const val KEY_TOKEN = "debug_bridge_token"

    @Volatile private var started = false

    /** 当前监听 socket（stop() 时 close，以唤醒阻塞在 accept 的线程） */
    @Volatile private var serverSocket: ServerSocket? = null

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 调试桥开关（默认 true；仅在 debug 包生效） */
    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    /** 已生成的 token（未生成过为 null），供设置页「开发者选项」展示 */
    fun tokenOrNull(context: Context): String? =
        prefs(context).getString(KEY_TOKEN, null)?.takeIf { it.isNotBlank() }

    fun isRunning(): Boolean = started

    /**
     * 启动调试桥（需 Context 读开关 / 生成并落盘 token）。
     * 幂等：已在监听或关闭状态下重复调用直接返回。
     */
    fun start(context: Context) {
        if (!BuildConfig.DEBUG) return
        val app = context.applicationContext
        if (!isEnabled(app)) {
            Log.i(TAG, "debug bridge disabled by " + PREFS + "/" + KEY_ENABLED)
            return
        }
        synchronized(this) {
            if (started || serverSocket != null) return
            val token = ensureToken(app)
            writeAccessInfo(app, token)
            started = true
            Thread({ runServer(token) }, "drama-debug-bridge").apply { isDaemon = true }.start()
        }
    }

    /** 关闭监听（设置页开关用）；再次 start() 会重新监听，token 不变 */
    fun stop() {
        synchronized(this) {
            started = false
            runCatching { serverSocket?.close() }
            serverSocket = null
        }
    }

    /**
     * token 首次启动时生成：SecureRandom 16 字节 → 32 位小写 hex。
     * 持久化在 SharedPreferences("dsh_debug")，重启 App / 重开桥都不变。
     */
    private fun ensureToken(context: Context): String {
        val p = prefs(context)
        p.getString(KEY_TOKEN, null)?.takeIf { it.isNotBlank() }?.let { return it }
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        val token = bytes.joinToString("") { "%02x".format(it) }
        p.edit().putString(KEY_TOKEN, token).apply()
        return token
    }

    /**
     * baseUrl + token 写入 getExternalFilesDir(null)/debug-bridge.txt（一行 JSON），
     * 并 Log.i 一次；getExternalFilesDir 可能为 null（外置存储未挂载），此时仅 log。
     */
    private fun writeAccessInfo(context: Context, token: String) {
        val baseUrl = "http://127.0.0.1:" + PORT
        runCatching {
            val dir = context.getExternalFilesDir(null)
            if (dir == null) {
                Log.w(TAG, "getExternalFilesDir(null) == null，跳过 debug-bridge.txt")
                return@runCatching
            }
            val file = File(dir, "debug-bridge.txt")
            file.writeText(JSONObject().put("baseUrl", baseUrl).put("token", token).toString() + "\n")
            Log.i(TAG, "debug bridge listening on " + baseUrl + " ; token/access info -> " + file.absolutePath)
        }.onFailure { Log.w(TAG, "写入 debug-bridge.txt 失败", it) }
    }

    private fun runServer(expectedToken: String) {
        var server: ServerSocket? = null
        try {
            val s = ServerSocket(PORT, 16, InetAddress.getByName("127.0.0.1"))
            server = s
            serverSocket = s
            Log.i(TAG, "debug bridge listening on http://127.0.0.1:" + PORT + " (token required)")
            s.use {
                while (true) {
                    val client = try {
                        it.accept()
                    } catch (t: Throwable) {
                        if (started) Log.w(TAG, "accept failed", t) else Log.i(TAG, "debug bridge stopped")
                        break
                    }
                    Thread({ handle(client, expectedToken) }, "drama-debug-conn")
                        .apply { isDaemon = true }.start()
                }
            }
        } catch (t: Throwable) {
            if (started) Log.e(TAG, "debug bridge failed", t)
        } finally {
            synchronized(this) {
                // 只清自己这次的 socket：避免 stop() 后立刻 start() 的新实例被误清
                if (serverSocket === server) {
                    serverSocket = null
                    started = false
                }
            }
        }
    }

    private fun handle(client: Socket, expectedToken: String) {
        try {
            client.use { socket ->
                socket.soTimeout = 5000
                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
                val requestLine = reader.readLine() ?: return
                // 读掉请求头（不处理 body：本桥只读），顺带取出 X-Debug-Token
                var headerToken: String? = null
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                    if (line.startsWith("X-Debug-Token:", ignoreCase = true)) {
                        headerToken = line.substringAfter(':').trim()
                    }
                }
                val parts = requestLine.split(" ")
                val method = parts.getOrElse(0) { "" }
                val target = parts.getOrElse(1) { "/" }
                val path = target.substringBefore('?')
                val query = parseQuery(target.substringAfter('?', ""))
                val out = socket.getOutputStream()
                // 鉴权必须早于任何 DAO 读取 / JSON 生成：未授权只回 401，绝不泄露数据
                if (!tokenMatches(headerToken ?: query["token"], expectedToken)) {
                    respond(out, 401, error("unauthorized", "缺少或无效的 X-Debug-Token / ?token="))
                    return
                }
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

    /** 定长比较，避免按字节短路泄露 token 前缀 */
    private fun tokenMatches(provided: String?, expected: String): Boolean {
        if (provided.isNullOrEmpty()) return false
        return MessageDigest.isEqual(
            provided.toByteArray(Charsets.UTF_8),
            expected.toByteArray(Charsets.UTF_8),
        )
    }

    private fun respond(out: OutputStream, code: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val head = "HTTP/1.1 " + code + " " + reason(code) + "\r\n" +
            "Content-Type: application/json; charset=utf-8\r\n" +
            "Content-Length: " + bytes.size + "\r\n" +
            "Connection: close\r\n" +
            "Cache-Control: no-store\r\n" +
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
        401 -> "Unauthorized"
        405 -> "Method Not Allowed"
        503 -> "Service Unavailable"
        else -> "Internal Server Error"
    }
}