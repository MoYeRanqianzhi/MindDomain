package org.mo.minddomain.level

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
 * 空间扩展：每升一级，空间边长增加 EXPANSION_PER_LEVEL
 */
object SpaceLevelConfig {

    /** 每升一级空间边长增加的格数 */
    const val EXPANSION_PER_LEVEL = 2

    /**
     * 计算从当前等级升至下一级所需的经验点数
     *
     * @param level 当前等级（必须 >= 1）
     * @return 升至 level+1 所需的经验点数
     */
    fun getExpForNextLevel(level: Int): Int = 100 * level
}
