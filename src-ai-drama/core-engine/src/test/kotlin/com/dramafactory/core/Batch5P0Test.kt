// ★第五批 P0 回归：幽灵任务剪枝 / 条件提交意图 / 预算持久化 / 空白分镜止损 / clip 自愈 / 已提交不重提
package com.dramafactory.core

import com.dramafactory.core.model.ConnectionInfo
import com.dramafactory.core.model.ModelSpec
import com.dramafactory.core.model.PollResult
import com.dramafactory.core.model.ShotMeta
import com.dramafactory.core.model.ShotState
import com.dramafactory.core.model.VideoSubmitRequest
import com.dramafactory.core.pipeline.DefaultBudgetGuard
import com.dramafactory.core.pipeline.DefaultRenderQueue
import com.dramafactory.core.provider.VideoProvider
import com.dramafactory.core.storage.InMemoryCheckpointStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Batch5P0Test {

    private class CountingVideo : VideoProvider {
        override val id = "fake"
        val submitted = mutableListOf<String>()
        private val polls = mutableMapOf<String, Int>()
        override suspend fun validateKey(key: String) = Result.success(ConnectionInfo(true))
        override fun listModels(): List<ModelSpec> = emptyList()
        override suspend fun submitVideo(req: VideoSubmitRequest): String {
            submitted += req.shotId
            return "vid-" + req.shotId
        }
        override suspend fun pollResult(providerTaskId: String): PollResult {
            val n = polls[providerTaskId] ?: 0
            polls[providerTaskId] = n + 1
            return if (n >= 1) PollResult.Completed("http://x/" + providerTaskId + ".mp4")
            else PollResult.InProgress(30)
        }
    }

    private fun newQueue(
        scope: CoroutineScope,
        video: VideoProvider,
        store: InMemoryCheckpointStore,
        guard: DefaultBudgetGuard = DefaultBudgetGuard(mapOf("p1" to 50)),
        downloader: suspend (String, String) -> Pair<String, Long> = { _, s -> "file://" + s + ".mp4" to 2048L },
    ): DefaultRenderQueue = DefaultRenderQueue(
        scope = scope,
        videoProvider = video,
        checkpointStore = store,
        budgetGuard = guard,
        downloader = downloader,
        pollIntervalMs = { 0 },
        projectIdOf = { "p1" },
    )

    @Test
    fun pruneToShots_removesGhostRows() = runBlocking {
        val store = InMemoryCheckpointStore()
        store.loadOrMerge("ep1", listOf(ShotMeta("s1", "ep1", ""), ShotMeta("s2", "ep1", "")))
        assertEquals(1, store.pruneToShots("ep1", setOf("s1")), "只删不在分镜里的行")
        assertEquals(listOf("s1"), store.getEpisode("ep1")!!.shots.map { it.shotId })
        assertEquals(0, store.pruneToShots("ep1", setOf("s1")), "幂等")
    }

    @Test
    fun tryMarkSubmitting_rejectsAlreadyPaidShots() = runBlocking {
        val store = InMemoryCheckpointStore()
        store.loadOrMerge("ep1", listOf(ShotMeta("s1", "ep1", "")))
        assertTrue(store.tryMarkSubmitting("s1"))
        store.markSubmitted("s1", "vid-1")
        assertFalse(store.tryMarkSubmitting("s1"), "已提交（已付费）的镜不得再次转 SUBMITTING")
        assertEquals(ShotState.SUBMITTED, store.getEpisode("ep1")!!.shots.single().state)
    }

    @Test
    fun budgetGuard_restoresUsedAndNotifiesPersistence() {
        val changes = mutableListOf<Pair<String, Int>>()
        val g = DefaultBudgetGuard(initialUsed = mapOf("p1" to 49))
        g.onUsageChanged = { pid, used -> changes += pid to used }
        assertTrue(g.canSubmit("p1"))
        g.consumeSubmitted("p1")
        assertFalse(g.canSubmit("p1"))
        assertEquals(50, g.used("p1"))
        assertEquals(listOf("p1" to 50), changes, "扣减必须回调落盘")
        val restored = DefaultBudgetGuard(initialUsed = mapOf("p1" to 50))
        assertFalse(restored.canSubmit("p1"), "重启后已用条数不归零")
        restored.setLimit("p1", 60)
        assertTrue(restored.canSubmit("p1"))
    }

    @Test
    fun queue_blankPromptNeverSubmitted() = runBlocking {
        withTimeout(10_000) {
            val scope = CoroutineScope(Dispatchers.Default)
            val video = CountingVideo()
            val store = InMemoryCheckpointStore()
            val q = newQueue(scope, video, store)
            q.blockEmptyPrompt = true
            q.shotPromptResolver = { Triple("", "", "") }
            q.enqueueEpisode("ep1", listOf(ShotMeta("s1", "ep1", "")))
            var n = 0
            while (n++ < 400) {
                val cp = store.getEpisode("ep1")
                if (cp != null && cp.shots.all { it.state == ShotState.FAILED }) break
                delay(25)
            }
            assertTrue(video.submitted.isEmpty(), "空白分镜不得提交付费任务")
            val entry = store.getEpisode("ep1")!!.shots.single()
            assertEquals(ShotState.FAILED, entry.state)
            assertTrue((entry.failReason ?: "").contains("empty_prompt"))
            scope.cancel()
        }
    }

    @Test
    fun queue_healsMissingClipFromRecordedRemoteUrl() = runBlocking {
        withTimeout(10_000) {
            val scope = CoroutineScope(Dispatchers.Default)
            val video = CountingVideo()
            val store = InMemoryCheckpointStore()
            store.loadOrMerge("ep1", listOf(ShotMeta("s1", "ep1", "")))
            store.markSubmitting("s1")
            store.markSubmitted("s1", "vid-1")
            store.markCompleted("s1", "file:///tmp/old.mp4", 100L)
            var downloads = 0
            val q = newQueue(scope, video, store, downloader = { _, _ ->
                downloads++
                "file:///tmp/new.mp4" to 200L
            })
            q.clipFileChecker = { _, _ -> false }
            q.recordedRemoteUrlOf = { "http://remote/vid-1.mp4" }
            q.enqueueEpisode("ep1", listOf(ShotMeta("s1", "ep1", "")))
            assertEquals(1, downloads, "clip 丢失只用落库的远端 URL 重取一次")
            assertTrue(video.submitted.isEmpty(), "绝不因本地 clip 丢失重新提交付费任务")
            val entry = store.getEpisode("ep1")!!.shots.single()
            assertEquals(ShotState.COMPLETED, entry.state)
            assertEquals("file:///tmp/new.mp4", entry.localFileUri)
            scope.cancel()
        }
    }

    @Test
    fun queue_enqueuePrunesGhostCheckpointRows() = runBlocking {
        withTimeout(10_000) {
            val scope = CoroutineScope(Dispatchers.Default)
            val video = CountingVideo()
            val store = InMemoryCheckpointStore()
            store.loadOrMerge("ep1", listOf(ShotMeta("s1", "ep1", ""), ShotMeta("s2", "ep1", "")))
            store.markSubmitting("s2")
            store.markFailed("s2", "old")
            val q = newQueue(scope, video, store)
            q.shotPromptResolver = { Triple("你来了", "", "他起身") }
            q.enqueueEpisode("ep1", listOf(ShotMeta("s1", "ep1", "")))
            var n = 0
            while (n++ < 400 && q.state.value.completedShots < 1) delay(25)
            assertEquals(listOf("s1"), store.getEpisode("ep1")!!.shots.map { it.shotId }, "幽灵行入队即剪掉")
            assertEquals(listOf("s1"), video.submitted)
            scope.cancel()
        }
    }

    @Test
    fun queue_repollsSubmittedShotWithoutResubmit() = runBlocking {
        withTimeout(10_000) {
            val scope = CoroutineScope(Dispatchers.Default)
            val video = CountingVideo()
            val store = InMemoryCheckpointStore()
            store.loadOrMerge("ep1", listOf(ShotMeta("s1", "ep1", "")))
            store.markSubmitting("s1")
            store.markSubmitted("s1", "vid-1")
            val q = newQueue(scope, video, store)
            q.shotPromptResolver = { Triple("你来了", "", "他起身") }
            q.enqueueEpisode("ep1", listOf(ShotMeta("s1", "ep1", "")))
            var n = 0
            while (n++ < 400 && q.state.value.completedShots < 1) delay(25)
            assertTrue(video.submitted.isEmpty(), "已提交的镜只轮询取回，绝不重复提交")
            assertEquals(ShotState.COMPLETED, store.getEpisode("ep1")!!.shots.single().state)
            scope.cancel()
        }
    }
}
