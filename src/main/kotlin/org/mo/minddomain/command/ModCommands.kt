package org.mo.minddomain.command

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.context.CommandContext
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.minecraft.command.CommandRegistryAccess
import net.minecraft.command.argument.EntityArgumentType
import net.minecraft.server.command.CommandManager
import net.minecraft.server.command.CommandManager.RegistrationEnvironment
import net.minecraft.server.command.ServerCommandSource
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import net.minecraft.util.math.Vec3d
import net.minecraft.world.TeleportTarget
import org.mo.minddomain.data.MindDomainState
import org.mo.minddomain.dimension.DynamicWorldManager
import org.mo.minddomain.level.SpaceLevelConfig
import org.mo.minddomain.network.ModNetworking

/**
 * 指令注册模块
 *
 * 注册 /md 主指令及其所有子指令。
 * 使用 Brigadier API 实现，支持 Tab 补全和目标选择器（@a, @p 等）。
 *
 * 指令列表：
 * - /md enter       — 进入自己的空间（所有玩家）
 * - /md leave       — 离开空间（所有玩家）
 * - /md info        — 查看自己的空间信息（所有玩家）
 * - /md info <玩家> — 查看指定玩家的空间信息（OP 2级）
 * - /md visit <玩家> — 传送到指定玩家的空间（OP 2级）
 */
object ModCommands {

    /**
     * 注册指令回调
     *
     * 应在 ModInitializer.onInitialize() 中调用。
     */
    fun register() {
        CommandRegistrationCallback.EVENT.register(
            CommandRegistrationCallback { dispatcher, registryAccess, environment ->
                registerCommands(dispatcher, registryAccess, environment)
            }
        )
    }

    /**
     * 注册所有子指令到 /md 主指令
     */
    private fun registerCommands(
        dispatcher: CommandDispatcher<ServerCommandSource>,
        registryAccess: CommandRegistryAccess,
        environment: RegistrationEnvironment
    ) {
        dispatcher.register(
            CommandManager.literal("md")
                // /md enter — 进入空间
                .then(
                    CommandManager.literal("enter")
                        .executes(::executeEnter)
                )
                // /md leave — 离开空间
                .then(
                    CommandManager.literal("leave")
                        .executes(::executeLeave)
                )
                // /md info — 查看自己的空间信息
                // /md info <player> — 查看指定玩家的信息（需要 OP 2级）
                .then(
                    CommandManager.literal("info")
                        .executes(::executeInfoSelf)
                        .then(
                            CommandManager.argument("player", EntityArgumentType.player())
                                .requires(CommandManager.requirePermissionLevel(CommandManager.GAMEMASTERS_CHECK))
                                .executes(::executeInfoOther)
                        )
                )
                // /md visit <player> — 传送到指定玩家的空间（需要 OP 2级）
                .then(
                    CommandManager.literal("visit")
                        .requires(CommandManager.requirePermissionLevel(CommandManager.GAMEMASTERS_CHECK))
                        .then(
                            CommandManager.argument("player", EntityArgumentType.player())
                                .executes(::executeVisit)
                        )
                )
        )
    }

    // ==================== 指令执行器 ====================

    /**
     * /md enter — 进入自己的空间
     *
     * 复用 ModNetworking 中的进入空间逻辑。
     */
    private fun executeEnter(context: CommandContext<ServerCommandSource>): Int {
        val player = context.source.playerOrThrow
        ModNetworking.handleEnterSpace(player)
        return 1
    }

    /**
     * /md leave — 离开空间
     *
     * 复用 ModNetworking 中的离开空间逻辑。
     */
    private fun executeLeave(context: CommandContext<ServerCommandSource>): Int {
        val player = context.source.playerOrThrow
        ModNetworking.handleLeaveSpace(player)
        return 1
    }

    /**
     * /md info — 查看自己的空间信息
     */
    private fun executeInfoSelf(context: CommandContext<ServerCommandSource>): Int {
        val player = context.source.playerOrThrow
        return showSpaceInfo(context.source, player)
    }

    /**
     * /md info <player> — 查看指定玩家的空间信息（管理员）
     */
    private fun executeInfoOther(context: CommandContext<ServerCommandSource>): Int {
        val target = EntityArgumentType.getPlayer(context, "player")
        return showSpaceInfo(context.source, target)
    }

    /**
     * 显示玩家的空间信息
     *
     * @param source 指令来源
     * @param target 目标玩家
     * @return 指令结果（1 = 成功，0 = 失败）
     */
    private fun showSpaceInfo(source: ServerCommandSource, target: ServerPlayerEntity): Int {
        val state = MindDomainState.get(source.server)
        val spaceId = state.getPlayerSpaceId(target.uuid)

        if (spaceId == null) {
            source.sendFeedback(
                { Text.translatable("command.minddomain.info.no_space", target.name)
                    .formatted(Formatting.YELLOW) },
                false
            )
            return 0
        }

        val spaceInfo = state.getSpaceInfo(spaceId)
        if (spaceInfo == null) {
            source.sendFeedback(
                { Text.translatable("command.minddomain.info.data_error")
                    .formatted(Formatting.RED) },
                false
            )
            return 0
        }

        // 显示空间信息
        source.sendFeedback(
            { Text.translatable("command.minddomain.info.header", target.name)
                .formatted(Formatting.GOLD) },
            false
        )
        source.sendFeedback(
            { Text.translatable(
                "command.minddomain.info.size",
                spaceInfo.size, spaceInfo.size, spaceInfo.size
            ).formatted(Formatting.WHITE) },
            false
        )
        source.sendFeedback(
            { Text.translatable("command.minddomain.info.level", spaceInfo.level)
                .formatted(Formatting.WHITE) },
            false
        )
        // 显示经验进度：当前经验 / 升级所需经验
        val expNeeded = SpaceLevelConfig.getExpForNextLevel(spaceInfo.level)
        source.sendFeedback(
            { Text.translatable("command.minddomain.info.experience", spaceInfo.experience, expNeeded)
                .formatted(Formatting.WHITE) },
            false
        )
        source.sendFeedback(
            { Text.translatable("command.minddomain.info.space_id", spaceId)
                .formatted(Formatting.GRAY) },
            false
        )

        return 1
    }

    /**
     * /md visit <player> — 管理员传送到指定玩家的空间
     *
     * 将执行者传送到目标玩家空间的中心落脚点上方。
     */
    private fun executeVisit(context: CommandContext<ServerCommandSource>): Int {
        val source = context.source
        val executor = source.playerOrThrow
        val target = EntityArgumentType.getPlayer(context, "player")
        val state = MindDomainState.get(source.server)

        // 检查目标玩家是否有空间
        val spaceId = state.getPlayerSpaceId(target.uuid)
        if (spaceId == null) {
            source.sendFeedback(
                { Text.translatable("command.minddomain.visit.no_space", target.name)
                    .formatted(Formatting.RED) },
                false
            )
            return 0
        }

        // 获取空间信息并创建/获取维度
        val spaceInfo = state.getSpaceInfo(spaceId) ?: return 0
        val spaceWorld = DynamicWorldManager.getOrCreateWorld(source.server, spaceId, spaceInfo.size)
        if (spaceWorld == null) {
            source.sendFeedback(
                { Text.translatable("command.minddomain.visit.failed")
                    .formatted(Formatting.RED) },
                false
            )
            return 0
        }

        // 传送管理员到空间中心
        executor.teleportTo(
            TeleportTarget(
                spaceWorld,
                Vec3d(0.5, DynamicWorldManager.SPAWN_Y.toDouble(), 0.5),
                Vec3d.ZERO,
                executor.yaw,
                executor.pitch,
                TeleportTarget.NO_OP
            )
        )

        source.sendFeedback(
            { Text.translatable("command.minddomain.visit.success", target.name)
                .formatted(Formatting.GREEN) },
            true // 广播给其他管理员
        )

        return 1
    }
}
