package com.dramafactory.app

import com.dramafactory.app.data.AssetEntity
import com.dramafactory.app.ui.AssetCatalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 第六批：分镜不再硬绑资产后，渲染期按名字软解析（softRefIds）。
 *
 * 约束：
 * - 只认母卡，不认参考图子卡（否则又会出现引用侧脸导致跨镜长相漂移）；
 * - rejected / review_state=regen 的卡不参与；
 * - 角色优先于场景、场景优先于道具（锁脸 > 背景 > 道具）；
 * - 文本里没出现任何资产名 → 返回空（渲染端不注入参考图，而不是乱兜底）。
 */
class AssetCatalogSoftRefTest {

    private fun row(
        id: String,
        name: String,
        kind: String = "character",
        parentId: String? = null,
        poseRole: String? = null,
        remoteUrl: String? = "https://img/$id.png",
        audit: String = "pending",
        review: String = "none",
    ) = AssetEntity(
        asset_id = id, project_id = "p1", kind = kind, parent_id = parentId, pose_role = poseRole,
        prompt = "$name：用于测试的资产描述", remote_url = remoteUrl,
        audit_state = audit, review_state = review, updated_at = 0L)

    @Test
    fun softRefIds_按角色名匹配母卡() {
        val rows = listOf(
            row("a_lin", "林见夕"),
            row("a_gu", "顾沉"),
            row("a_office", "办公室", kind = "scene"),
        )
        val ids = AssetCatalog.softRefIds(rows, listOf("林见夕坐在电脑前，顾沉推门而入"))
        assertEquals(listOf("a_lin", "a_gu"), ids, "两个角色都出现 → 都解析出来，且角色优先")
    }

    @Test
    fun softRefIds_忽略参考图子卡() {
        val rows = listOf(
            row("a_mother", "林见夕"),
            row("a_face", "林见夕，正面立姿", parentId = "a_mother", poseRole = "front_bust"),
        )
        val ids = AssetCatalog.softRefIds(rows, listOf("林见夕抬头"))
        assertEquals(listOf("a_mother"), ids)
    }

    @Test
    fun softRefIds_排除被拒与判重生成的卡() {
        val rows = listOf(
            row("a_ok", "林见夕"),
            row("a_rejected", "顾沉", audit = "rejected"),
            row("a_regen", "赵启明", review = "regen"),
        )
        val ids = AssetCatalog.softRefIds(rows, listOf("林见夕、顾沉、赵启明同场"))
        assertEquals(listOf("a_ok"), ids)
    }

    @Test
    fun softRefIds_角色优先于场景再优先于道具() {
        val rows = listOf(
            row("a_prop", "手机", kind = "prop"),
            row("a_scene", "办公室", kind = "scene"),
            row("a_char", "林见夕"),
        )
        val ids = AssetCatalog.softRefIds(rows, listOf("林见夕在办公室拿起手机"))
        assertEquals(listOf("a_char", "a_scene", "a_prop"), ids)
    }

    @Test
    fun softRefIds_文本无匹配时返回空() {
        val rows = listOf(row("a_lin", "林见夕"))
        assertTrue(AssetCatalog.softRefIds(rows, listOf("一个陌生人走过走廊")).isEmpty(),
            "匹配不到资产时宁可不注入参考图，也不能乱兜底串脸")
        assertTrue(AssetCatalog.softRefIds(rows, emptyList()).isEmpty())
        assertTrue(AssetCatalog.softRefIds(emptyList(), listOf("林见夕")).isEmpty())
    }

    @Test
    fun softRefIds_遵守上限() {
        val rows = (1..8).map { row("a_$it", "角色$it") }
        val ids = AssetCatalog.softRefIds(rows, listOf((1..8).joinToString("、") { "角色$it" }), limit = 3)
        assertEquals(3, ids.size)
    }
}
