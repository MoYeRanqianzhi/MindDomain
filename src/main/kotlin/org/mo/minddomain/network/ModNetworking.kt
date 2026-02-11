package org.mo.minddomain.network

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.network.RegistryByteBuf
import net.minecraft.network.codec.PacketCodec
import net.minecraft.network.packet.CustomPayload
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import net.minecraft.util.Identifier
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Vec3d
import net.minecraft.world.TeleportTarget
import org.mo.minddomain.advancement.ModAdvancements
import org.mo.minddomain.data.MindDomainState
import org.mo.minddomain.data.ReturnPosition
import org.mo.minddomain.dimension.DynamicWorldManager
import org.mo.minddomain.dimension.ModDimensions
import org.mo.minddomain.swap.SpaceSwapManager

/**
 * 网络通信模块
 *
 * 定义客户端到服务端的网络包（C2S Payload），用于处理快捷键触发的空间进出请求。
 * 所有游戏逻辑均在服务端执行，客户端仅发送请求信号。
 *
 * 包类型：
 * - EnterSpaceC2SPayload：请求进入个人空间（I 键）
 * - LeaveSpaceC2SPayload：请求离开空间（O 键）
 * - SwapSpaceC2SPayload：请求空间替换（V 键）
 */
object ModNetworking {

    // ==================== Payload 定义 ====================

    /**
     * 进入空间请求包（C2S）
     *
     * 客户端按 I 键时发送，无额外数据字段。
     * 服务端接收后检查玩家是否拥有空间，并执行传送。
     */
    class EnterSpaceC2SPayload : CustomPayload {
        companion object {
            val ID = CustomPayload.Id<EnterSpaceC2SPayload>(
                Identifier.of("minddomain", "enter_space")
            )
            /** 无数据的单元 Codec：编码/解码均为空操作 */
            val CODEC: PacketCodec<RegistryByteBuf, EnterSpaceC2SPayload> = PacketCodec.of(
                { _, _ -> }, // encode: 无数据需要写入
                { _ -> EnterSpaceC2SPayload() } // decode: 直接创建实例
            )
        }

        override fun getId() = ID
    }

    /**
     * 离开空间请求包（C2S）
     *
     * 客户端按 O 键时发送，无额外数据字段。
     * 服务端接收后检查玩家是否在空间内，并传送回之前的位置。
     */
    class LeaveSpaceC2SPayload : CustomPayload {
        companion object {
            val ID = CustomPayload.Id<LeaveSpaceC2SPayload>(
                Identifier.of("minddomain", "leave_space")
            )
            val CODEC: PacketCodec<RegistryByteBuf, LeaveSpaceC2SPayload> = PacketCodec.of(
                { _, _ -> },
                { _ -> LeaveSpaceC2SPayload() }
            )
        }

        override fun getId() = ID
    }

    /**
     * 空间替换请求包（C2S）
     *
     * 客户端按 V 键时发送，无额外数据字段。
     * 服务端接收后执行现实世界与空间维度之间的方块和实体双向交换。
     * 玩家留在现实世界，位置移动到空间记录位置对应的现实坐标。
     */
    class SwapSpaceC2SPayload : CustomPayload {
        companion object {
            val ID = CustomPayload.Id<SwapSpaceC2SPayload>(
                Identifier.of("minddomain", "swap_space")
            )
            /** 无数据的单元 Codec：编码/解码均为空操作 */
            val CODEC: PacketCodec<RegistryByteBuf, SwapSpaceC2SPayload> = PacketCodec.of(
                { _, _ -> }, // encode: 无数据需要写入
                { _ -> SwapSpaceC2SPayload() } // decode: 直接创建实例
            )
        }

        override fun getId() = ID
    }

    // ==================== 注册与处理 ====================

    /**
     * 注册所有网络包类型和服务端处理器
     *
     * 应在 ModInitializer.onInitialize() 中调用。
     */
    fun register() {
        // 注册 C2S 包类型
        PayloadTypeRegistry.playC2S().register(EnterSpaceC2SPayload.ID, EnterSpaceC2SPayload.CODEC)
        PayloadTypeRegistry.playC2S().register(LeaveSpaceC2SPayload.ID, LeaveSpaceC2SPayload.CODEC)
        PayloadTypeRegistry.playC2S().register(SwapSpaceC2SPayload.ID, SwapSpaceC2SPayload.CODEC)

        // 注册服务端接收处理器
        ServerPlayNetworking.registerGlobalReceiver(EnterSpaceC2SPayload.ID) { _, context ->
            // 在服务端主线程执行
            val player = context.player()
            val server = (player.entityWorld as ServerWorld).server!!
            server.execute {
                handleEnterSpace(player)
            }
        }

        ServerPlayNetworking.registerGlobalReceiver(LeaveSpaceC2SPayload.ID) { _, context ->
            val player = context.player()
            val server = (player.entityWorld as ServerWorld).server!!
            server.execute {
                handleLeaveSpace(player)
            }
        }

        ServerPlayNetworking.registerGlobalReceiver(SwapSpaceC2SPayload.ID) { _, context ->
            val player = context.player()
            val server = (player.entityWorld as ServerWorld).server!!
            server.execute {
                handleSwapSpace(player)
            }
        }
    }

    // ==================== 进入空间逻辑 ====================

    /**
     * 处理玩家进入空间的请求
     *
     * 流程：
     * 1. 检查玩家是否拥有空间
     * 2. 检查玩家是否已在空间中（防止重复进入）
     * 3. 记录当前位置为返回位置
     * 4. 获取或创建目标维度
     * 5. 传送到空间内记忆坐标（或空间中心）
     */
    fun handleEnterSpace(player: ServerPlayerEntity) {
        val serverWorld = player.entityWorld as ServerWorld
        val server = serverWorld.server!!
        val state = MindDomainState.get(server)

        // 检查是否拥有空间
        val spaceId = state.getPlayerSpaceId(player.uuid)
        if (spaceId == null) {
            player.sendMessage(
                Text.translatable("message.minddomain.no_space")
                    .formatted(Formatting.RED),
                true
            )
            return
        }

        // 检查是否已在心灵空间中
        if (ModDimensions.isMindDomainWorld(player.entityWorld.registryKey)) {
            player.sendMessage(
                Text.translatable("message.minddomain.already_in_space")
                    .formatted(Formatting.YELLOW),
                true
            )
            return
        }

        // 获取空间信息
        val spaceInfo = state.getSpaceInfo(spaceId) ?: return

        // 获取或创建维度
        val spaceWorld = DynamicWorldManager.getOrCreateWorld(server, spaceId, spaceInfo.size)
        if (spaceWorld == null) {
            player.sendMessage(
                Text.translatable("message.minddomain.enter_failed")
                    .formatted(Formatting.RED),
                true
            )
            return
        }

        // 记录当前位置为返回位置
        state.setReturnPosition(
            player.uuid,
            ReturnPosition(
                worldKey = player.entityWorld.registryKey,
                x = player.x,
                y = player.y,
                z = player.z,
                yaw = player.yaw,
                pitch = player.pitch
            )
        )

        // 获取空间内记忆坐标（首次进入使用中心点）
        val spacePos = state.getSpacePosition(player.uuid)
        val targetX = spacePos?.x?.toDouble()?.plus(0.5) ?: 0.5
        val targetY = spacePos?.y?.toDouble() ?: DynamicWorldManager.SPAWN_Y.toDouble()
        val targetZ = spacePos?.z?.toDouble()?.plus(0.5) ?: 0.5

        // 传送到空间
        player.teleportTo(
            TeleportTarget(
                spaceWorld,
                Vec3d(targetX, targetY, targetZ),
                Vec3d.ZERO,
                player.yaw,
                player.pitch,
                TeleportTarget.NO_OP
            )
        )

        player.sendMessage(
            Text.translatable("message.minddomain.entered_space")
                .formatted(Formatting.GREEN),
            true
        )

        // 授予"袖里有乾坤"成就
        ModAdvancements.grant(player, ModAdvancements.UNIVERSE_IN_SLEEVE)
    }

    // ==================== 离开空间逻辑 ====================

    /**
     * 处理玩家离开空间的请求
     *
     * 流程：
     * 1. 检查玩家是否在心灵空间中
     * 2. 记录当前空间内坐标
     * 3. 获取返回位置
     * 4. 传送回之前的位置
     */
    fun handleLeaveSpace(player: ServerPlayerEntity) {
        val serverWorld = player.entityWorld as ServerWorld
        val server = serverWorld.server!!
        val state = MindDomainState.get(server)

        // 检查是否在心灵空间中
        if (!ModDimensions.isMindDomainWorld(player.entityWorld.registryKey)) {
            player.sendMessage(
                Text.translatable("message.minddomain.not_in_space")
                    .formatted(Formatting.YELLOW),
                true
            )
            return
        }

        // 记录空间内坐标
        state.setSpacePosition(
            player.uuid,
            BlockPos(player.blockX, player.blockY, player.blockZ)
        )

        // 获取返回位置
        val returnPos = state.getReturnPosition(player.uuid)
        if (returnPos == null) {
            // 没有返回位置记录，传送到主世界出生点
            val overworld = server.overworld
            val spawnPoint = overworld.spawnPoint
            val spawnPos = spawnPoint.pos
            player.teleportTo(
                TeleportTarget(
                    overworld,
                    Vec3d(spawnPos.x + 0.5, spawnPos.y.toDouble(), spawnPos.z + 0.5),
                    Vec3d.ZERO,
                    player.yaw,
                    player.pitch,
                    TeleportTarget.NO_OP
                )
            )
        } else {
            // 传送回记录的位置
            val targetWorld = server.getWorld(returnPos.worldKey) ?: server.overworld
            player.teleportTo(
                TeleportTarget(
                    targetWorld,
                    Vec3d(returnPos.x, returnPos.y, returnPos.z),
                    Vec3d.ZERO,
                    returnPos.yaw,
                    returnPos.pitch,
                    TeleportTarget.NO_OP
                )
            )
        }

        player.sendMessage(
            Text.translatable("message.minddomain.left_space")
                .formatted(Formatting.GREEN),
            true
        )
    }

    // ==================== 空间替换逻辑 ====================

    /**
     * 处理玩家空间替换的请求
     *
     * 将现实世界以玩家为中心的立方体区域与空间维度的可用区域进行双向交换。
     * 交换后玩家留在现实世界，位置移动到空间记录位置对应的现实坐标。
     *
     * 流程：
     * 1. 检查玩家不在心灵空间维度内（只能在现实世界使用）
     * 2. 检查玩家拥有空间
     * 3. 获取/创建空间维度
     * 4. 记录玩家当前位置 preSwapPos
     * 5. 读取已存储的空间位置 oldSpacePos
     * 6. 调用 SpaceSwapManager.executeSwap() 执行方块和实体交换
     * 7. 将 oldSpacePos 转换为现实坐标，传送玩家到该位置（留在现实世界）
     * 8. 将 preSwapPos 转换为空间坐标，设为新的存储空间位置
     * 9. 发送成功/失败消息
     */
    fun handleSwapSpace(player: ServerPlayerEntity) {
        val serverWorld = player.entityWorld as ServerWorld
        val server = serverWorld.server!!
        val state = MindDomainState.get(server)

        // 1. 检查玩家不在心灵空间中（只能在现实世界使用空间替换）
        if (ModDimensions.isMindDomainWorld(player.entityWorld.registryKey)) {
            player.sendMessage(
                Text.translatable("message.minddomain.swap_not_from_space")
                    .formatted(Formatting.RED),
                true
            )
            return
        }

        // 2. 检查是否拥有空间
        val spaceId = state.getPlayerSpaceId(player.uuid)
        if (spaceId == null) {
            player.sendMessage(
                Text.translatable("message.minddomain.no_space")
                    .formatted(Formatting.RED),
                true
            )
            return
        }

        // 3. 获取空间信息和维度
        val spaceInfo = state.getSpaceInfo(spaceId) ?: return
        val spaceWorld = DynamicWorldManager.getOrCreateWorld(server, spaceId, spaceInfo.size)
        if (spaceWorld == null) {
            player.sendMessage(
                Text.translatable("message.minddomain.swap_failed")
                    .formatted(Formatting.RED),
                true
            )
            return
        }

        val size = spaceInfo.size
        val halfSize = size / 2

        // 4. 记录玩家当前位置（交换前），在延迟回调中使用
        val preSwapBlockPos = player.blockPos
        val centerX = preSwapBlockPos.x
        val centerZ = preSwapBlockPos.z
        val baseY = preSwapBlockPos.y - 1 // 玩家脚下方块 Y

        // 5. 读取已存储的空间位置（若无默认 (0, PLATFORM_Y+2, 0)）
        val oldSpacePos = state.getSpacePosition(player.uuid)
            ?: BlockPos(0, DynamicWorldManager.PLATFORM_Y + 2, 0)

        // 6. 强制加载空间维度区块，确保实体从磁盘异步加载完成
        // 空间维度在无玩家时区块会卸载，实体被序列化到磁盘。
        // setChunkForced 添加票据后，需要等待若干 tick 让区块和实体的异步加载完成。
        val forcedChunks = SpaceSwapManager.forceLoadSpaceChunks(spaceWorld, halfSize, size)

        // 记录玩家 UUID，用于延迟回调中重新获取玩家实例（防止引用过期）
        val playerUuid = player.uuid

        // 7. 延迟执行实际交换操作，等待空间维度区块和实体加载完成
        SpaceSwapManager.scheduleDelayed(3) {
            // 重新获取玩家实例（延迟期间玩家可能已下线）
            val delayedPlayer = server.playerManager.getPlayer(playerUuid)
            if (delayedPlayer == null) {
                // 玩家已下线，释放强制加载的区块后返回
                SpaceSwapManager.releaseForceLoadedChunks(spaceWorld, forcedChunks)
                return@scheduleDelayed
            }

            try {
                // 执行方块和实体交换
                val success = SpaceSwapManager.executeSwap(
                    delayedPlayer, serverWorld, spaceWorld,
                    size, centerX, centerZ, baseY
                )

                if (!success) {
                    delayedPlayer.sendMessage(
                        Text.translatable("message.minddomain.swap_failed")
                            .formatted(Formatting.RED),
                        true
                    )
                    return@scheduleDelayed
                }

                // 8. 将 oldSpacePos 转换为现实坐标，传送玩家到该位置（留在现实世界）
                // 映射公式（空间 → 现实）：
                //   realX = spaceX + centerX, realZ = spaceZ + centerZ
                //   realY = (spaceY - PLATFORM_Y - 1) + baseY
                val targetRealX = oldSpacePos.x.toDouble() + centerX + 0.5
                val targetRealZ = oldSpacePos.z.toDouble() + centerZ + 0.5
                val targetRealY = (oldSpacePos.y.toDouble() - DynamicWorldManager.PLATFORM_Y - 1) + baseY

                delayedPlayer.teleportTo(
                    TeleportTarget(
                        serverWorld,
                        Vec3d(targetRealX, targetRealY, targetRealZ),
                        Vec3d.ZERO,
                        delayedPlayer.yaw,
                        delayedPlayer.pitch,
                        TeleportTarget.NO_OP
                    )
                )

                // 9. 将 preSwapPos 转换为空间坐标，设为新的存储空间位置
                // 映射公式（现实 → 空间）：
                //   spaceX = realX - centerX, spaceZ = realZ - centerZ
                //   spaceY = (realY - baseY) + PLATFORM_Y + 1
                val newSpaceX = preSwapBlockPos.x - centerX
                val newSpaceZ = preSwapBlockPos.z - centerZ
                val newSpaceY = (preSwapBlockPos.y - baseY) + DynamicWorldManager.PLATFORM_Y + 1

                state.setSpacePosition(
                    playerUuid,
                    BlockPos(newSpaceX, newSpaceY, newSpaceZ)
                )

                delayedPlayer.sendMessage(
                    Text.translatable("message.minddomain.swap_success")
                        .formatted(Formatting.GREEN),
                    true
                )

                // 授予"空间系初学者"成就
                ModAdvancements.grant(delayedPlayer, ModAdvancements.SPACE_BEGINNER)
            } finally {
                // 无论成功失败，都释放强制加载的区块
                SpaceSwapManager.releaseForceLoadedChunks(spaceWorld, forcedChunks)
            }
        }
    }
}
