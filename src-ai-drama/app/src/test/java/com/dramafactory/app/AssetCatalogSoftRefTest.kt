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

    // ---- 第七批：软引用短名 / token 匹配 ----

    @Test
    fun softRefIds_短名与造型token命中() {
        val rows = listOf(
            row("a_sec", "女秘书·职业装女性"),
            row("a_office", "办公室·现代办公区", kind = "scene"),
            row("a_phone", "手机·黑色屏幕亮起", kind = "prop"),
        )
        // 镜头文本只出现短名，资产名是「短名·造型描述」的长名
        val ids = AssetCatalog.softRefIds(rows, listOf("女秘书在办公室把手机递给他"))
        assertEquals(listOf("a_sec", "a_office", "a_phone"), ids,
            "短名 token（女秘书/办公室/手机）应命中全名未出现的长名资产，且仍按 角色→场景→道具 排序")
    }

    @Test
    fun softRefIds_通用词token不参与匹配() {
        val rows = listOf(
            row("a_people", "人物·女性"),
            row("a_male", "人物·男性"),
            row("a_office", "办公室", kind = "scene"),
        )
        // 纯通用词（人物/女性/男性）不得把同 kind 资产全拉进来
        assertTrue(AssetCatalog.softRefIds(rows, listOf("画面中出现很多人物，女性居多")).isEmpty(),
            "通用词不是可识别信息，不能命中任何资产")
        // 具体短名仍可命中
        assertEquals(listOf("a_office"),
            AssetCatalog.softRefIds(rows, listOf("人物在办公室交谈")))
    }

    @Test
    fun softRefIds_同kind内全名命中优先于token命中() {
        val rows = listOf(
            row("a_token", "女秘书·职业装女性"),   // 只能靠 token「女秘书」命中
            row("a_full", "女秘书"),               // 全名命中
        )
        val ids = AssetCatalog.softRefIds(rows, listOf("女秘书递上文件"))
        assertEquals(listOf("a_full", "a_token"), ids,
            "同一 kind 内全名命中必须排 token 命中之前")
    }

    @Test
    fun softRefIds_token命中仍遵守limit() {
        val rows = (1..8).map { row("a_$it", "角色$it·职业装女性") }
        val ids = AssetCatalog.softRefIds(rows, listOf("角色1、角色3、角色5 依次入场"), limit = 2)
        assertEquals(listOf("a_1", "a_3"), ids, "token 命中后仍按 limit 截断，不超额注入")
    }

    @Test
    fun softNameTokens_切分与通用词过滤() {
        assertEquals(listOf("女秘书", "职业装女性"),
            AssetCatalog.softNameTokens("女秘书·职业装女性"))
        assertEquals(listOf("办公室", "现代办公区", "白天"),
            AssetCatalog.softNameTokens("办公室：现代办公区（白天）"))
        assertTrue(AssetCatalog.softNameTokens("人物·女性").isEmpty(),
            "通用词 token 必须全部过滤")
        assertTrue(AssetCatalog.softNameTokens("张").isEmpty(),
            "单字 token 不参与匹配")
    }
}
