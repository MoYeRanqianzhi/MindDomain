package org.mo.minddomain.data

import com.mojang.serialization.Codec
import net.minecraft.nbt.NbtCompound
import net.minecraft.registry.RegistryKey
import net.minecraft.registry.RegistryKeys
import net.minecraft.server.MinecraftServer
import net.minecraft.util.Identifier
import net.minecraft.util.math.BlockPos
import net.minecraft.world.PersistentState
import net.minecraft.world.PersistentStateType
import net.minecraft.world.World
import org.mo.minddomain.level.SpaceLevelConfig
import java.util.UUID

/**
 * 心灵空间的世界级持久化数据
 *
 * 存储在主世界（Overworld）的存档文件夹中，服务器重启后自动恢复。
 * 管理以下核心数据：
 * - 空间 ID 分配计数器（确保全局唯一）
 * - 所有已创建的空间信息（尺寸、等级、经验）
 * - 玩家与空间的绑定关系
 * - 玩家在空间内的记忆坐标（下次进入时恢复）
 * - 玩家进入空间前的返回位置（离开时恢复）
 */
class MindDomainState : PersistentState() {

    /** 下一个可用的空间 ID，递增分配 */
    var nextSpaceId: Int = 0

    /** 空间 ID → 空间信息映射 */
    val spaces: MutableMap<Int, SpaceInfo> = mutableMapOf()

    /** 玩家 UUID → 绑定的空间 ID */
    val playerSpaceMap: MutableMap<UUID, Int> = mutableMapOf()

    /** 玩家 UUID → 空间内最后坐标（下次进入时传送到此位置） */
    val playerSpacePositions: MutableMap<UUID, BlockPos> = mutableMapOf()

    /** 玩家 UUID → 进入空间前的返回位置（离开时传送回此位置） */
    val playerReturnPositions: MutableMap<UUID, ReturnPosition> = mutableMapOf()

    /**
     * 分配一个新的空间 ID 并创建空间信息
     *
     * @param size 初始空间边长（由空间球的大小决定）
     * @return 新分配的空间 ID
     */
    fun allocateSpace(size: Int): Int {
        val id = nextSpaceId++
        spaces[id] = SpaceInfo(size = size, level = 1, experience = 0)
        markDirty()
        return id
    }

    /**
     * 将空间绑定到玩家
     *
     * @param playerUuid 玩家的 UUID
     * @param spaceId 要绑定的空间 ID
     */
    fun bindSpace(playerUuid: UUID, spaceId: Int) {
        playerSpaceMap[playerUuid] = spaceId
        markDirty()
    }

    /**
     * 解除玩家的空间绑定
     *
     * 仅移除绑定关系，不删除空间数据（空间可能被空间球继承）。
     *
     * @param playerUuid 玩家的 UUID
     * @return 被解绑的空间 ID，如果没有绑定则返回 null
     */
    fun unbindSpace(playerUuid: UUID): Int? {
        val spaceId = playerSpaceMap.remove(playerUuid)
        if (spaceId != null) {
            // 同时清除位置记忆数据
            playerSpacePositions.remove(playerUuid)
            playerReturnPositions.remove(playerUuid)
            markDirty()
        }
        return spaceId
    }

    /**
     * 获取玩家绑定的空间 ID
     *
     * @return 空间 ID，如果没有绑定则返回 null
     */
    fun getPlayerSpaceId(playerUuid: UUID): Int? = playerSpaceMap[playerUuid]

    /**
     * 获取指定空间的信息
     *
     * @return 空间信息，如果不存在则返回 null
     */
    fun getSpaceInfo(spaceId: Int): SpaceInfo? = spaces[spaceId]

    /**
     * 为指定空间添加经验值，并处理升级逻辑
     *
     * 仅处理经验累积和等级提升，不修改空间大小。
     * 空间大小的变更由 DynamicWorldManager.expandSpace() 统一处理，
     * 确保持久化数据和物理边界始终同步。
     *
     * @param spaceId 空间 ID
     * @param amount 获取的经验量（必须为正值）
     * @return 升级结果，如果未升级则返回 null；如果空间不存在也返回 null
     */
    fun addSpaceExperience(spaceId: Int, amount: Int): LevelUpResult? {
        val info = spaces[spaceId] ?: return null

        info.experience += amount
        val oldLevel = info.level

        // 循环处理升级（支持一次跳多级），仅更新等级和经验
        var expNeeded = SpaceLevelConfig.getExpForNextLevel(info.level)
        while (info.experience >= expNeeded) {
            info.experience -= expNeeded
            info.level++
            expNeeded = SpaceLevelConfig.getExpForNextLevel(info.level)
        }

        markDirty()

        // 如果等级发生了变化，返回升级结果
        return if (info.level > oldLevel) {
            LevelUpResult(
                newLevel = info.level,
                levelsGained = info.level - oldLevel
            )
        } else {
            null
        }
    }

    /**
     * 记录玩家在空间内的坐标
     */
    fun setSpacePosition(playerUuid: UUID, pos: BlockPos) {
        playerSpacePositions[playerUuid] = pos
        markDirty()
    }

    /**
     * 获取玩家在空间内的记忆坐标
     *
     * @return 上次离开时的坐标，如果没有记录则返回 null（应使用空间中心）
     */
    fun getSpacePosition(playerUuid: UUID): BlockPos? = playerSpacePositions[playerUuid]

    /**
     * 记录玩家进入空间前的返回位置
     */
    fun setReturnPosition(playerUuid: UUID, returnPos: ReturnPosition) {
        playerReturnPositions[playerUuid] = returnPos
        markDirty()
    }

    /**
     * 获取玩家的返回位置
     */
    fun getReturnPosition(playerUuid: UUID): ReturnPosition? = playerReturnPositions[playerUuid]

    /**
     * 将所有数据序列化为 NBT
     *
     * 1.21.11 的 PersistentState 使用 Codec 进行序列化，
     * 此方法由 CODEC 的 xmap 调用，不再是 PersistentState 的 override。
     */
    fun toNbt(): NbtCompound {
        val nbt = NbtCompound()
        nbt.putInt("nextSpaceId", nextSpaceId)

        // 序列化空间信息
        val spacesNbt = NbtCompound()
        spaces.forEach { (id, info) ->
            val spaceNbt = NbtCompound()
            spaceNbt.putInt("size", info.size)
            spaceNbt.putInt("level", info.level)
            spaceNbt.putInt("experience", info.experience)
            spacesNbt.put(id.toString(), spaceNbt)
        }
        nbt.put("spaces", spacesNbt)

        // 序列化玩家空间绑定
        val bindingsNbt = NbtCompound()
        playerSpaceMap.forEach { (uuid, spaceId) ->
            bindingsNbt.putInt(uuid.toString(), spaceId)
        }
        nbt.put("playerSpaceMap", bindingsNbt)

        // 序列化玩家空间内坐标
        val positionsNbt = NbtCompound()
        playerSpacePositions.forEach { (uuid, pos) ->
            val posNbt = NbtCompound()
            posNbt.putInt("x", pos.x)
            posNbt.putInt("y", pos.y)
            posNbt.putInt("z", pos.z)
            positionsNbt.put(uuid.toString(), posNbt)
        }
        nbt.put("playerSpacePositions", positionsNbt)

        // 序列化返回位置
        val returnNbt = NbtCompound()
        playerReturnPositions.forEach { (uuid, returnPos) ->
            val rNbt = NbtCompound()
            rNbt.putString("world", returnPos.worldKey.value.toString())
            rNbt.putDouble("x", returnPos.x)
            rNbt.putDouble("y", returnPos.y)
            rNbt.putDouble("z", returnPos.z)
            rNbt.putFloat("yaw", returnPos.yaw)
            rNbt.putFloat("pitch", returnPos.pitch)
            returnNbt.put(uuid.toString(), rNbt)
        }
        nbt.put("playerReturnPositions", returnNbt)

        return nbt
    }

    companion object {
        /**
         * 序列化 Codec
         *
         * 使用 NbtCompound.CODEC.xmap 包装手动序列化逻辑，
         * 将 toNbt()/fromNbt() 桥接到 Codec 接口。
         */
        val CODEC: Codec<MindDomainState> = NbtCompound.CODEC.xmap(
            { nbt -> fromNbt(nbt) },
            { state -> state.toNbt() }
        )

        /**
         * PersistentState 的类型定义
         *
         * 1.21.11 使用 PersistentStateType 记录替代了旧的 PersistentState.Type，
         * 包含 ID、构造器、Codec 和可选的 DataFixTypes。
         */
        val TYPE = PersistentStateType<MindDomainState>(
            "minddomain_state",
            { MindDomainState() },
            CODEC,
            null
        )

        /**
         * 从 NBT 数据反序列化为 MindDomainState 实例
         *
         * 1.21.11 的 NbtCompound getter 返回 Optional 或需要提供默认值：
         * - getInt(key, default) 替代旧的 getInt(key)
         * - getCompoundOrEmpty(key) 替代旧的 getCompound(key)
         * - getString(key, default) 替代旧的 getString(key)
         * - getDouble(key, default) 替代旧的 getDouble(key)
         * - getFloat(key, default) 替代旧的 getFloat(key)
         */
        private fun fromNbt(nbt: NbtCompound): MindDomainState {
            val state = MindDomainState()
            state.nextSpaceId = nbt.getInt("nextSpaceId", 0)

            // 反序列化空间信息
            val spacesNbt = nbt.getCompoundOrEmpty("spaces")
            for (key in spacesNbt.keys) {
                val spaceNbt = spacesNbt.getCompoundOrEmpty(key)
                state.spaces[key.toInt()] = SpaceInfo(
                    size = spaceNbt.getInt("size", 16),
                    level = spaceNbt.getInt("level", 1),
                    experience = spaceNbt.getInt("experience", 0)
                )
            }

            // 反序列化玩家空间绑定
            val bindingsNbt = nbt.getCompoundOrEmpty("playerSpaceMap")
            for (key in bindingsNbt.keys) {
                state.playerSpaceMap[UUID.fromString(key)] = bindingsNbt.getInt(key, 0)
            }

            // 反序列化玩家空间内坐标
            val positionsNbt = nbt.getCompoundOrEmpty("playerSpacePositions")
            for (key in positionsNbt.keys) {
                val posNbt = positionsNbt.getCompoundOrEmpty(key)
                state.playerSpacePositions[UUID.fromString(key)] = BlockPos(
                    posNbt.getInt("x", 0),
                    posNbt.getInt("y", 0),
                    posNbt.getInt("z", 0)
                )
            }

            // 反序列化返回位置
            val returnNbt = nbt.getCompoundOrEmpty("playerReturnPositions")
            for (key in returnNbt.keys) {
                val rNbt = returnNbt.getCompoundOrEmpty(key)
                state.playerReturnPositions[UUID.fromString(key)] = ReturnPosition(
                    worldKey = RegistryKey.of(
                        RegistryKeys.WORLD,
                        Identifier.of(rNbt.getString("world", "minecraft:overworld"))
                    ),
                    x = rNbt.getDouble("x", 0.0),
                    y = rNbt.getDouble("y", 0.0),
                    z = rNbt.getDouble("z", 0.0),
                    yaw = rNbt.getFloat("yaw", 0f),
                    pitch = rNbt.getFloat("pitch", 0f)
                )
            }

            return state
        }

        /**
         * 获取当前服务器的 MindDomainState 实例
         *
         * 从主世界的 PersistentStateManager 中获取或创建状态实例。
         * 此方法应仅在服务端调用。
         *
         * @param server 当前的 MinecraftServer 实例
         * @return MindDomainState 实例
         */
        fun get(server: MinecraftServer): MindDomainState {
            val persistentStateManager = server.overworld.persistentStateManager
            return persistentStateManager.getOrCreate(TYPE)
        }
    }
}

/**
 * 空间信息数据类
 *
 * @param size 当前空间边长（宽度 = 高度 = size）
 * @param level 空间等级（影响可扩展大小）
 * @param experience 空间经验值（累积到阈值时升级）
 */
data class SpaceInfo(
    var size: Int,
    var level: Int,
    var experience: Int
)

/**
 * 返回位置数据类
 *
 * 记录玩家进入空间前所在的维度和精确坐标，
 * 以便离开空间时能准确传送回原位。
 *
 * @param worldKey 所在维度的 RegistryKey
 * @param x X 坐标
 * @param y Y 坐标
 * @param z Z 坐标
 * @param yaw 水平视角角度
 * @param pitch 垂直视角角度
 */
data class ReturnPosition(
    val worldKey: RegistryKey<World>,
    val x: Double,
    val y: Double,
    val z: Double,
    val yaw: Float,
    val pitch: Float
)

/**
 * 等级提升结果数据类
 *
 * 当空间经验达到升级阈值时返回此结果，
 * 仅包含等级变化信息。空间大小的实际变更
 * 由 DynamicWorldManager.expandSpace() 统一处理。
 *
 * @param newLevel 升级后的等级
 * @param levelsGained 本次升级提升的等级数（支持一次跳多级）
 */
data class LevelUpResult(
    val newLevel: Int,
    val levelsGained: Int
)
