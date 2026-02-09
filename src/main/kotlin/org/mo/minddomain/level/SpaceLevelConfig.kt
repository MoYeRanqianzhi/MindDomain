package org.mo.minddomain.level

import kotlin.math.min

/**
 * 空间等级系统配置
 *
 * 定义空间等级的升级公式和扩展参数。
 * 空间等级独立于原版游戏等级，只增不减，不受附魔消耗等影响。
 *
 * 升级公式：从等级 N 升至 N+1 所需经验点数 = 100 * N
 *   - 1 -> 2: 100 XP
 *   - 2 -> 3: 200 XP
 *   - 3 -> 4: 300 XP
 *   - ...
 *
 * 空间扩展：每升一级，空间边长增加 [EXPANSION_PER_LEVEL]。
 * 当空间 size 超过 [MAX_VERTICAL_SIZE] 后，垂直方向不再增长，
 * 只在水平方向扩展，且每级增量从 [EXPANSION_PER_LEVEL] 提升到
 * [EXPANSION_PER_LEVEL_AFTER_CAP] 作为不增加高度的补偿。
 *
 * 垂直上限推导：
 * - 天花板 Y = PLATFORM_Y(64) + verticalSize + 1
 * - MAX_VERTICAL_SIZE = 1024 → 天花板 Y = 1089
 * - 维度高度 ≥ 1089 - (-64) = 1153 → 取 16 倍数 = 1168
 * - 最高 Y = -64 + 1168 = 1104，满足 MC 约束 ≤ 2032
 */
object SpaceLevelConfig {

    /** 每升一级空间边长增加的格数（垂直方向未达上限时） */
    const val EXPANSION_PER_LEVEL = 2

    /**
     * 垂直方向的最大尺寸上限
     *
     * 当空间的 size 超过此值后，垂直方向固定为此值，
     * 只在水平方向继续扩展。这是为了防止天花板 Y 坐标
     * 超出维度最大建筑高度导致屏障方块无法放置。
     */
    const val MAX_VERTICAL_SIZE = 1024

    /**
     * 超过垂直上限后每升一级空间水平边长增加的格数
     *
     * 作为不再增加垂直高度的补偿，水平方向增量从 2 提升到 4。
     */
    const val EXPANSION_PER_LEVEL_AFTER_CAP = 4

    /**
     * 计算从当前等级升至下一级所需的经验点数
     *
     * @param level 当前等级（必须 >= 1）
     * @return 升至 level+1 所需的经验点数
     */
    fun getExpForNextLevel(level: Int): Int = 100 * level

    /**
     * 根据空间总 size 计算实际的垂直尺寸
     *
     * 垂直方向上限为 [MAX_VERTICAL_SIZE]，超过后不再增长。
     * 天花板 Y = PLATFORM_Y + verticalSize + 1，确保不会超出维度高度限制。
     *
     * @param size 空间的逻辑 size（水平方向的边长）
     * @return 实际垂直方向尺寸，最大为 [MAX_VERTICAL_SIZE]
     */
    fun getVerticalSize(size: Int): Int = min(size, MAX_VERTICAL_SIZE)

    /**
     * 根据当前空间 size 计算每级扩展增量
     *
     * - 当 size < [MAX_VERTICAL_SIZE] 时，返回 [EXPANSION_PER_LEVEL]（水平和垂直同步扩展）
     * - 当 size >= [MAX_VERTICAL_SIZE] 时，返回 [EXPANSION_PER_LEVEL_AFTER_CAP]（仅水平扩展，增量加大补偿）
     *
     * @param currentSize 当前空间的 size
     * @return 每升一级空间边长应增加的格数
     */
    fun getExpansionPerLevel(currentSize: Int): Int {
        return if (currentSize >= MAX_VERTICAL_SIZE) {
            EXPANSION_PER_LEVEL_AFTER_CAP
        } else {
            EXPANSION_PER_LEVEL
        }
    }
}
