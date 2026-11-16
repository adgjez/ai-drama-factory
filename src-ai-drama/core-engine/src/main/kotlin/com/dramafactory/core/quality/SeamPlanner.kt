package com.dramafactory.core.quality

/**
 * 第六批：接缝帧规划器（声明式接缝帧方案）。
 *
 * 背景（已用 4s keyframe 实测验证）：Agnes 2.5-flash 的 keyframe 模式会把输出视频的
 * 首帧/尾帧精确落回声明的 first_frame / last_frame（几何图 MSE≈2/38，人物图 MSE≈12/136），
 * 因此**同一场戏的相邻两镜可以共享同一张接缝帧**：
 *
 *   镜 i 的尾帧 == 镜 i+1 的首帧 == 同一张图
 *
 * 这样两镜衔接处像素级连续（不是抽帧对齐，而是直接声明同一张图），
 * 且每镜单独渲染、单独重渲都不会让下游接缝失效——接缝帧本身由渲染前的
 * 生成阶段统一产出并写回 shots.first_image_uri / last_image_uri。
 *
 * 规则：
 * - 同场相邻（两侧 sceneNo>0 且相等）→ 链在一起，镜 i 的 lastToShotNo = 镜 i+1；
 * - 跨场 / sceneNo 缺失（<=0）/ 集尾 → 硬切，lastToShotNo = null，绝不共享帧；
 * - 输入按 shotNo 排序处理（调用方传 DB 读出的顺序，可能未排序）。
 */
object SeamPlanner {

    /** 规划输入的最小镜头信息（只依赖镜号与场次号）。 */
    data class ShotRef(
        val shotNo: Int,
        val sceneNo: Int,
    )

    /**
     * 单个镜头的接缝规划结果。
     *
     * @param lastToShotNo 本镜尾帧要复用哪一镜的首帧：同场下一镜的镜号；null = 本镜场尾硬切。
     * @param hardCutAfter 本镜之后是否硬切（跨场 / 集尾 / 场次号缺失）。硬切镜不写尾帧。
     */
    data class Link(
        val shotNo: Int,
        val sceneNo: Int,
        val lastToShotNo: Int?,
        val hardCutAfter: Boolean,
    ) {
        /** 本镜与下一镜共享接缝帧。 */
        val chainedToNext: Boolean get() = lastToShotNo != null
    }

    fun plan(shots: List<ShotRef>): List<Link> {
        if (shots.isEmpty()) return emptyList()
        val ordered = shots.sortedBy { it.shotNo }
        return ordered.mapIndexed { index, current ->
            val next = ordered.getOrNull(index + 1)
            val sameScene = next != null && current.sceneNo > 0 && next.sceneNo == current.sceneNo
            Link(
                shotNo = current.shotNo,
                sceneNo = current.sceneNo,
                lastToShotNo = if (sameScene) next?.shotNo else null,
                hardCutAfter = !sameScene,
            )
        }
    }

    /** 便捷方法：直接按「镜号 to 场次号」返回尾帧去向表（shotNo -> 下一镜 shotNo）。 */
    fun tailTargets(shots: List<ShotRef>): Map<Int, Int> =
        plan(shots).mapNotNull { link -> link.lastToShotNo?.let { link.shotNo to it } }.toMap()
}
