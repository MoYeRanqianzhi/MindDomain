package org.mo.minddomain.event

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents
import net.minecraft.item.ItemStack
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import org.mo.minddomain.block.ModBlocks
import org.mo.minddomain.component.ModComponents
import org.mo.minddomain.component.SpaceBallData
import org.mo.minddomain.data.MindDomainState
import org.mo.minddomain.dimension.DynamicWorldManager
import org.mo.minddomain.entity.SpaceBallEntity
import org.mo.minddomain.item.ModItems

/**
 * 事件处理模块
 *
 * 监听并处理与心灵空间相关的游戏事件：
 * - 服务器启动：重建所有已注册的动态维度
 * - 玩家死亡：解绑空间并掉落携带空间数据的空间球
 * - 方块破坏保护：阻止任何模式下破坏白色屏障方块
 * - 经验获取：将玩家获得的经验同步计入空间等级系统
 *
 * 注意：光照亮度处理（全亮效果）在客户端通过 LightmapTextureManagerMixin 实现，
 * 不经过服务端事件系统。
 */
object ModEvents {

    /**
     * 注册所有事件监听器
     *
     * 应在 ModInitializer.onInitialize() 中调用。
     */
    fun register() {
        registerServerLifecycleEvents()
        registerDeathEvents()
        registerBlockBreakProtection()
    }

    // ==================== 服务器生命周期事件 ====================

    /**
     * 注册服务器启动事件
     *
     * 在服务器完全启动后重建所有动态维度，
     * 确保之前创建的空间在服务器重启后仍然可用。
     */
    private fun registerServerLifecycleEvents() {
        ServerLifecycleEvents.SERVER_STARTED.register { server ->
            DynamicWorldManager.rebuildAllWorlds(server)
        }
    }

    // ==================== 方块破坏保护 ====================

    /**
     * 注册白色屏障方块的破坏保护
     *
     * 通过 PlayerBlockBreakEvents.BEFORE 事件拦截所有对白色屏障方块的破坏尝试，
     * 包括创造模式。硬度 -1 仅能阻止生存模式的破坏，创造模式会绕过硬度检查，
     * 因此必须通过事件处理来实现完全不可破坏。
     *
     * 返回 false 会取消方块破坏操作。
     */
    private fun registerBlockBreakProtection() {
        PlayerBlockBreakEvents.BEFORE.register { world, player, pos, state, _ ->
            // 如果方块是白色屏障，阻止破坏
            state.block != ModBlocks.WHITE_BARRIER
        }
    }

    // ==================== 玩家死亡事件 ====================

    /**
     * 注册玩家死亡事件
     *
     * 当拥有空间的玩家死亡时：
     * 1. 在死亡位置掉落携带空间数据的空间球
     * 2. 解除玩家与空间的绑定关系
     * 3. 空间本身不被删除（等待其他玩家通过空间球继承）
     */
    private fun registerDeathEvents() {
        ServerLivingEntityEvents.AFTER_DEATH.register { entity, damageSource ->
            if (entity is ServerPlayerEntity) {
                handlePlayerDeath(entity)
            }
        }
    }

    /**
     * 处理玩家死亡时的空间掉落逻辑
     *
     * 在死亡位置生成自定义空间球实体（SpaceBallEntity），
     * 替代普通掉落物，具有浮动动画、粒子效果和右键拾取功能。
     * 实体无敌且永久存在，不会因时间流逝而消失。
     *
     * @param player 死亡的玩家
     */
    private fun handlePlayerDeath(player: ServerPlayerEntity) {
        val serverWorld = player.entityWorld as ServerWorld
        val server = serverWorld.server!!
        val state = MindDomainState.get(server)
        val spaceId = state.getPlayerSpaceId(player.uuid) ?: return

        // 获取空间信息
        val spaceInfo = state.getSpaceInfo(spaceId) ?: return

        // 创建携带空间数据的空间球物品
        val stack = ItemStack(ModItems.SPACE_BALL)
        stack.set(
            ModComponents.SPACE_BALL_DATA,
            SpaceBallData(
                size = spaceInfo.size,
                spaceId = spaceId,
                ownerName = player.name.string
            )
        )

        // 设置物品自定义名称为 "<玩家名>的空间"
        stack.set(
            net.minecraft.component.DataComponentTypes.CUSTOM_NAME,
            Text.translatable("item.minddomain.space_ball.named", player.name.string)
        )

        // 在死亡位置生成空间球实体（替代普通 ItemEntity 掉落）
        SpaceBallEntity.spawn(serverWorld, player.x, player.y, player.z, stack)

        // 解除玩家与空间的绑定
        state.unbindSpace(player.uuid)
    }

    // ==================== 经验获取处理 ====================

    /**
     * 处理玩家获取经验事件
     *
     * 由 PlayerEntityMixin 在 PlayerEntity.addExperience() 被调用时触发。
     * 将获取的经验同步计入玩家绑定空间的等级系统，处理流程：
     * 1. 查找玩家绑定的空间
     * 2. 将经验添加到空间数据中
     * 3. 如果触发升级，通过 expandSpace() 同步更新数据和物理边界，并发送通知
     *
     * @param player 获取经验的玩家（必须是服务端玩家）
     * @param amount 获取的经验量（正值）
     * 
     */
    fun onPlayerGainExperience(player: ServerPlayerEntity, amount: Int) {
        val server = (player.entityWorld as ServerWorld).server ?: return
        val state = MindDomainState.get(server)
        val spaceId = state.getPlayerSpaceId(player.uuid) ?: return

        // 添加经验并检查是否升级（仅更新经验和等级，不改变空间大小）
        val levelUpResult = state.addSpaceExperience(spaceId, amount) ?: return

        // 触发了升级，同步扩展空间大小和物理边界
        val newSize = DynamicWorldManager.expandSpace(server, spaceId, levelUpResult.levelsGained) ?: return

        // 发送升级通知给玩家
        player.sendMessage(
            Text.translatable(
                "message.minddomain.level_up",
                levelUpResult.newLevel,
                newSize, newSize, newSize
            ).formatted(Formatting.GREEN),
            false
        )
    }
}
