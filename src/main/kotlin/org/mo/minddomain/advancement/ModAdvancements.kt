package org.mo.minddomain.advancement

import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.util.Identifier
import org.mo.minddomain.Minddomain

/**
 * 成就系统模块
 *
 * 管理心灵空间模组的所有成就（Advancements），使用 MC 原生成就框架。
 * 所有成就的触发器均为 `minecraft:impossible`，通过代码程序化授予。
 *
 * 成就树结构：
 * ```
 * 开窍 (root) ─── 使用空间球
 * └── 袖里有乾坤 ─── 首次进入空间
 *     ├── 空间系初学者 ─── 首次空间替换
 *     └── 壶中有日月 ─── 首次空间升级
 *         └── 万里山河图 ─── 空间 ≥ 128
 *             └── 至尊仙窍 ─── 空间 ≥ 512
 * ```
 *
 * 授予机制：
 * - 调用 `grant()` 传入玩家和成就 ID
 * - 内部通过 `PlayerAdvancementTracker.grantCriterion()` 授予
 * - 已完成的成就不会重复触发 toast 通知（幂等操作）
 */
object ModAdvancements {

    // ==================== 成就 ID 常量 ====================

    /** 开窍 — 使用空间球开辟个人空间（根成就） */
    val AWAKENING: Identifier = Identifier.of(Minddomain.MOD_ID, "awakening")

    /** 袖里有乾坤 — 首次进入个人空间 */
    val UNIVERSE_IN_SLEEVE: Identifier = Identifier.of(Minddomain.MOD_ID, "universe_in_sleeve")

    /** 空间系初学者 — 首次执行空间替换 */
    val SPACE_BEGINNER: Identifier = Identifier.of(Minddomain.MOD_ID, "space_beginner")

    /** 壶中有日月 — 首次空间升级 */
    val SUN_MOON_IN_GOURD: Identifier = Identifier.of(Minddomain.MOD_ID, "sun_moon_in_gourd")

    /** 万里山河图 — 空间大小达到 128 */
    val VAST_LANDSCAPE: Identifier = Identifier.of(Minddomain.MOD_ID, "vast_landscape")

    /** 至尊仙窍 — 空间大小达到 512 */
    val SUPREME_IMMORTAL_SPACE: Identifier = Identifier.of(Minddomain.MOD_ID, "supreme_immortal_space")

    /** 成就 JSON 中 criterion 的统一 key 名 */
    private const val CRITERION_KEY = "requirement"

    // ==================== 公开方法 ====================

    /**
     * 注册成就系统模块
     *
     * 当前为空实现，保持与其他模块一致的 `register()` 注册模式。
     * 成就定义通过 `data/minddomain/advancement/` 下的 JSON 文件加载。
     */
    fun register() {
        Minddomain.logger.info("成就系统已加载")
    }

    /**
     * 程序化授予玩家成就
     *
     * 通过 MC 原生 `PlayerAdvancementTracker.grantCriterion()` 机制授予。
     * 如果玩家已完成该成就，`grantCriterion()` 返回 false，不会重复触发 toast。
     *
     * @param player 目标玩家（服务端实例）
     * @param advancementId 成就的 Identifier（如 `minddomain:awakening`）
     */
    fun grant(player: ServerPlayerEntity, advancementId: Identifier) {
        val server = (player.entityWorld as ServerWorld).server!!
        // 从服务端成就管理器中查找对应的成就定义
        val advancement = server.advancementLoader.get(advancementId)
        if (advancement == null) {
            Minddomain.logger.warn("成就未找到: {}", advancementId)
            return
        }

        // 通过玩家的成就追踪器授予 criterion，key 与 JSON 中定义的一致
        player.advancementTracker.grantCriterion(advancement, CRITERION_KEY)
    }

    /**
     * 检查空间大小里程碑并授予对应成就
     *
     * 统一检查 128 和 512 两个大小里程碑。
     * 用于空间升级和继承大空间两种场景：
     * - 空间升级时：每次升级后调用，检查新大小是否达到里程碑
     * - 继承空间球时：使用空间球后调用，处理继承 size ≥ 128/512 的情况
     *
     * @param player 目标玩家
     * @param currentSize 当前空间大小
     */
    fun checkSizeMilestones(player: ServerPlayerEntity, currentSize: Int) {
        if (currentSize >= 128) {
            grant(player, VAST_LANDSCAPE)
        }
        if (currentSize >= 512) {
            grant(player, SUPREME_IMMORTAL_SPACE)
        }
    }
}
