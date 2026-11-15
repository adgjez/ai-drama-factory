package com.dramafactory.core.quality

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * v1.10 资产闸策略：拒绝态硬拦 / 未审核放行（warn）/ 编造 ID 硬拦 / 旧默认行为不变。
 *
 * 背景：第二批把 A.1 改成「只认 approved」fail-closed 后，历史资产的 audit_state 多为
 * pending，导致整集 15 镜全部 fidelity_blocked（gate_asset_not_real），APP 卡死无法渲染。
 * 现按审计结论分级：rejected 继续硬拦，pending 放行但告警，不存在的 ID 仍硬拦。
 */
class FidelityGateAssetPolicyTest {

    private fun entry(assetIds: List<String> = listOf("a1")): StoryboardGate.Entry =
        StoryboardGate.Entry(
            "s1", 1, beatRef = "beat_01", beatIndex = 1,
            panel = StoryboardGate.Panel(action = "推门而入", duration = 5.0),
            associateAssetIds = assetIds,
        )

    private fun run(
        assetIds: List<String> = listOf("a1"),
        approved: Set<String> = emptySet(),
        rejected: Set<String> = emptySet(),
        known: Set<String>? = null,
        allowUnreviewed: Boolean = false,
    ) = FidelityGate.gateShot(
        entry = entry(assetIds),
        motionPrompt = "推门而入",
        catalogApprovedIds = approved,
        submittedDuration = 5.0,
        expectedIndex = 1,
        requireCatalog = true,
        catalogRejectedIds = rejected,
        catalogKnownIds = known,
        allowUnreviewedAssets = allowUnreviewed,
    )

    @Test
    fun 已拒绝资产硬拦() {
        val r = run(rejected = setOf("a1"), known = setOf("a1"), allowUnreviewed = true)
        assertTrue(r.blocked, "被质检拒绝的资产必须阻断渲染")
        assertTrue(r.errors.any { it.code == "gate_asset_rejected" }, "应报 gate_asset_rejected：${r.issues}")
    }

    @Test
    fun 未审核资产放行并告警() {
        val r = run(known = setOf("a1"), allowUnreviewed = true)
        assertFalse(r.blocked, "未审核资产不应阻断渲染：${r.issues}")
        assertTrue(r.issues.any { it.code == "gate_asset_unreviewed" && it.severity == "warn" },
            "应给未审核告警：${r.issues}")
    }

    @Test
    fun 编造资产仍硬拦() {
        val r = run(assetIds = listOf("fake_1"), known = setOf("a1"), allowUnreviewed = true)
        assertTrue(r.blocked, "不存在的资产 ID 仍应阻断")
        assertTrue(r.errors.any { it.code == "gate_asset_not_real" }, "应报 gate_asset_not_real：${r.issues}")
    }

    @Test
    fun 旧默认行为未审核不放行() {
        val r = run(approved = emptySet(), known = null, allowUnreviewed = false)
        assertTrue(r.blocked)
        assertTrue(r.errors.any { it.code == "gate_asset_not_real" })
    }
}
