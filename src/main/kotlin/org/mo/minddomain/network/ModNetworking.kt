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
import org.mo.minddomain.data.MindDomainState
import org.mo.minddomain.data.ReturnPosition
import org.mo.minddomain.dimension.DynamicWorldManager
import org.mo.minddomain.dimension.ModDimensions

/**
 * 网络通信模块
 *
 * 定义客户端到服务端的网络包（C2S Payload），用于处理快捷键触发的空间进出请求。
 * 所有游戏逻辑均在服务端执行，客户端仅发送请求信号。
 *
 * 包类型：
 * - EnterSpaceC2SPayload：请求进入个人空间（I 键）
 * - LeaveSpaceC2SPayload：请求离开空间（O 键）
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
}
