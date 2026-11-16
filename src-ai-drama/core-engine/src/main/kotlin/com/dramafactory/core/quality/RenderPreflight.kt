package com.dramafactory.core.quality

/**
 * 渲染前预检（零付费、零网络）：在把分镜送进渲染队列之前，先判断哪些镜一定会被
 * 队列/闸门拦下、哪些镜带可接受的降级风险，把结论提前给用户看。
 *
 * 设计原则：
 * - 只做纯函数判断，不读盘、不联网、不花钱；App 侧从 Room 取分镜/资产后喂进来。
 * - BLOCK = 这一镜必然失败或会被硬闸拦下（提交前就该修，否则白排队/白点开始）；
 *   WARN  = 可以渲但会降级（无接缝帧走纯文生视频、时长缺失按默认帧数等）。
 * - 与 DefaultRenderQueue 的实际闸门保持同一口径：空 prompt、分镜 error、已拒绝资产、
 *   编造资产 ID 都会在队列里硬拦，预检提前暴露。
 */
object RenderPreflight {

    enum class Severity { BLOCK, WARN }

    data class ShotInput(
        val shotNo: Int,
        val shotId: String,
        val action: String = "",
        val dialogue: String? = null,
        val narration: String? = null,
        val durationSeconds: Double = 0.0,
        val firstImageUri: String? = null,
        val lastImageUri: String? = null,
        val boundAssetIds: List<String> = emptyList(),
        /** 分镜校验结果原文，如 "pass" / "warn:axis_jump" / "error:scene_context_missing" / "pending" */
        val sbCheck: String? = null,
        val sceneNo: Int = 0,
    )

    data class Issue(
        val shotNo: Int,
        val code: String,
        val message: String,
        val severity: Severity,
    )

    data class Report(
        val total: Int,
        val renderable: Int,
        val blockedShots: Int,
        val warningShots: Int,
        val issues: List<Issue>,
    ) {
        val ok: Boolean get() = blockedShots == 0
        val blockers: List<Issue> get() = issues.filter { it.severity == Severity.BLOCK }
        /** 只列不会被阻断的镜：被拦下的镜再报「将降级」没有行动价值，且会把 warningShots 的口径搞乱 */
        val warnings: List<Issue>
            get() {
                val blocked = blockers.map { it.shotNo }.toSet()
                return issues.filter { it.severity == Severity.WARN && it.shotNo !in blocked }
            }

        /** 一行摘要，直接可放队列页顶部 */
        fun summary(): String {
            if (total == 0) return "预检：本集没有分镜"
            val tail = buildString {
                if (blockedShots > 0) append("，$blockedShots 镜会被拦下")
                if (warningShots > 0) append("，$warningShots 镜将降级")
            }
            return "预检：可渲 $renderable/$total 镜$tail"
        }
    }

    /**
     * @param knownAssetIds 本项目全部资产 ID；传 null 表示跳过「编造资产」检查（拿不到资产表时）
     * @param rejectedAssetIds 已被质检拒绝的资产 ID（进渲染会被硬拦）
     * @param budgetRemaining 本集剩余可提交镜数；null 表示不做预算提示
     */
    fun check(
        shots: List<ShotInput>,
        knownAssetIds: Set<String>? = null,
        rejectedAssetIds: Set<String> = emptySet(),
        budgetRemaining: Int? = null,
    ): Report {
        val issues = mutableListOf<Issue>()
        val ordered = shots.sortedBy { it.shotNo }
        for (shot in ordered) {
            if (shot.shotId.isBlank()) {
                issues += Issue(shot.shotNo, "invalid_shot", "分镜缺少 shot_id，无法入队", Severity.BLOCK)
                continue
            }
            val silent = shot.action.isBlank() &&
                shot.dialogue.isNullOrBlank() &&
                shot.narration.isNullOrBlank()
            if (silent) {
                issues += Issue(shot.shotNo, "empty_prompt", "动作/台词/旁白全为空，队列会拒提（避免浪费额度）", Severity.BLOCK)
            }
            val check = shot.sbCheck.orEmpty().trim()
            if (check.startsWith("error", ignoreCase = true)) {
                val detail = check.substringAfter(':', "").trim()
                issues += Issue(
                    shot.shotNo, "storyboard_blocked",
                    "分镜校验未通过" + if (detail.isNotBlank()) "（$detail）" else "，需先在分镜页编辑或重新生成",
                    Severity.BLOCK,
                )
            }
            for (aid in shot.boundAssetIds) {
                if (aid in rejectedAssetIds) {
                    issues += Issue(shot.shotNo, "asset_rejected", "绑定的资产 $aid 已被质检拒绝，需换图或解绑", Severity.BLOCK)
                } else if (knownAssetIds != null && aid !in knownAssetIds) {
                    issues += Issue(shot.shotNo, "asset_unknown", "绑定的资产 $aid 不在本项目资产库（会被判编造 ID）", Severity.BLOCK)
                }
            }
            if (shot.durationSeconds <= 0.0) {
                issues += Issue(shot.shotNo, "duration_missing", "时长缺失，将按默认帧数（约 5 秒）渲染", Severity.WARN)
            }
            if (shot.firstImageUri.isNullOrBlank() && shot.lastImageUri.isNullOrBlank()) {
                issues += Issue(shot.shotNo, "no_keyframe", "没有接缝帧，将走纯文生视频（画面一致性较弱）", Severity.WARN)
            }
        }
        val blockedSet = issues.filter { it.severity == Severity.BLOCK }.map { it.shotNo }.toSet()
        val warnSet = issues.filter { it.severity == Severity.WARN }.map { it.shotNo }.toSet() - blockedSet
        val renderable = ordered.count { it.shotNo !in blockedSet }
        if (budgetRemaining != null && renderable > budgetRemaining) {
            issues += Issue(
                0, "budget_shortfall",
                "本次可渲 $renderable 镜，但预算只剩 $budgetRemaining 条，超出部分会暂停等确认",
                Severity.WARN,
            )
        }
        return Report(
            total = ordered.size,
            renderable = renderable,
            blockedShots = blockedSet.size,
            warningShots = warnSet.size,
            issues = issues,
        )
    }
}
