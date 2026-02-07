package org.mo.minddomain.dimension

import net.minecraft.block.Blocks
import net.minecraft.network.packet.s2c.play.WorldBorderInitializeS2CPacket
import net.minecraft.registry.RegistryKey
import net.minecraft.registry.RegistryKeys
import net.minecraft.server.MinecraftServer
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.util.Identifier
import net.minecraft.util.math.BlockPos
import net.minecraft.world.World
import net.minecraft.world.biome.source.FixedBiomeSource
import net.minecraft.world.dimension.DimensionOptions
import net.minecraft.world.level.ServerWorldProperties
import net.minecraft.world.level.UnmodifiableLevelProperties
import org.mo.minddomain.block.ModBlocks
import org.mo.minddomain.data.MindDomainState
import org.mo.minddomain.level.SpaceLevelConfig
import org.mo.minddomain.mixin.MinecraftServerAccessor
import org.slf4j.LoggerFactory

/**
 * 动态维度管理器
 *
 * 负责在运行时创建、获取和配置心灵空间的独立维度实例。
 * 每个玩家的空间对应一个独立的 ServerWorld，通过 Mixin Accessor 将其注册到服务器的维度映射中。
 *
 * 核心职责：
 * 1. 创建新的 ServerWorld 实例（含自定义区块生成器和维度类型）
 * 2. 配置空间边界（白色屏障方块构成地面、天花板和墙壁，不可破坏）
 * 3. 配置 WorldBorder 作为最终安全边界（设在屏障墙壁之外，不可见）
 * 4. 服务器启动时重建所有已注册的维度
 *
 * 边界设计：
 * - 地面（bottomY）：白色屏障方块，不可破坏的白色地面
 * - 天花板（topY）：白色屏障方块，不可破坏，融入白色天空
 * - XZ 四周墙壁：白色屏障方块，不可破坏，白色外观与天空融为一体
 * - WorldBorder：设在屏障墙壁之外，防止任何手段穿越，但不可见
 */
object DynamicWorldManager {

    private val logger = LoggerFactory.getLogger("MindDomain/DynamicWorldManager")

    /** 地面的 Y 坐标（空间从此处向上延伸） */
    const val PLATFORM_Y = 64

    /** 玩家初始出生点 Y 坐标（地面上方一格） */
    const val SPAWN_Y = 65

    /**
     * WorldBorder 相对于屏障墙壁向外扩展的距离
     *
     * WorldBorder 紧贴屏障墙壁外侧，作为最终安全边界。
     */
    private const val BORDER_PADDING = 1

    /**
     * 获取或创建指定空间 ID 对应的 ServerWorld
     *
     * 如果维度已存在则直接返回，否则创建新维度并完成初始配置。
     *
     * @param server MinecraftServer 实例
     * @param spaceId 空间 ID
     * @param size 空间边长（仅在创建新维度时使用）
     * @return 对应的 ServerWorld 实例，创建失败时返回 null
     */
    fun getOrCreateWorld(server: MinecraftServer, spaceId: Int, size: Int): ServerWorld? {
        val worldKey = ModDimensions.getWorldKey(spaceId)

        // 检查维度是否已存在
        server.getWorld(worldKey)?.let { return it }

        // 创建新维度
        return try {
            val world = createWorld(server, worldKey)
            if (world != null) {
                // 配置边界
                setupWorldBorder(world, size)
                setupBoundaries(world, size)
                logger.info("成功创建心灵空间维度: {} (大小: {})", worldKey.value, size)
            }
            world
        } catch (e: Exception) {
            logger.error("创建心灵空间维度失败: {}", worldKey.value, e)
            null
        }
    }

    /**
     * 创建 ServerWorld 实例并注册到服务器
     *
     * 通过 Mixin Accessor 访问服务器内部字段，手动构建 ServerWorld 并添加到维度映射中。
     *
     * @param server MinecraftServer 实例
     * @param worldKey 维度的 RegistryKey
     * @return 新创建的 ServerWorld，失败返回 null
     */
    private fun createWorld(server: MinecraftServer, worldKey: RegistryKey<World>): ServerWorld? {
        val accessor = server as MinecraftServerAccessor
        val overworld = server.overworld

        // 获取维度类型（通过 Identifier 查找，1.21.11 移除了 RegistryKey 重载）
        val dimensionTypeRegistry = server.registryManager.getOrThrow(RegistryKeys.DIMENSION_TYPE)
        val dimensionTypeEntry = dimensionTypeRegistry.getEntry(ModDimensions.DIMENSION_TYPE_KEY.value)
        if (dimensionTypeEntry.isEmpty) {
            logger.error("找不到维度类型: {}", ModDimensions.DIMENSION_TYPE_KEY.value)
            return null
        }

        // 创建使用白色虚空生物群系的区块生成器
        val biomeRegistry = server.registryManager.getOrThrow(RegistryKeys.BIOME)
        val whiteVoidBiome = biomeRegistry.getEntry(
            Identifier.of("minddomain", "white_void")
        )

        // 如果自定义生物群系不存在，回退到平原生物群系
        val biomeEntry = if (whiteVoidBiome.isPresent) {
            whiteVoidBiome.get()
        } else {
            logger.warn("白色虚空生物群系未找到，回退到平原生物群系")
            biomeRegistry.getEntry(net.minecraft.world.biome.BiomeKeys.PLAINS.value).orElseThrow()
        }

        val biomeSource = FixedBiomeSource(biomeEntry)
        val chunkGenerator = MindDomainChunkGenerator(biomeSource)
        val dimensionOptions = DimensionOptions(dimensionTypeEntry.get(), chunkGenerator)

        // 构造 ServerWorld 所需的世界属性
        // 使用 UnmodifiableLevelProperties 包装主世界属性，这是非主世界维度的标准做法
        val worldProperties = UnmodifiableLevelProperties(
            server.saveProperties,
            server.saveProperties.mainWorldProperties
        )

        // 创建 ServerWorld 实例（1.21.11 移除了 WorldGenerationProgressListener 参数）
        val world = ServerWorld(
            server,
            net.minecraft.util.Util.getMainWorkerExecutor(),
            accessor.getSession(),
            worldProperties as ServerWorldProperties,
            worldKey,
            dimensionOptions,
            false, // debugWorld
            overworld.seed,
            emptyList(), // spawners: 无生物生成器
            false, // shouldTickTime: 不进行时间流逝
            null   // randomSequencesState
        )

        // 将新维度注册到服务器的维度映射中
        @Suppress("UNCHECKED_CAST")
        val worlds = accessor.getWorlds() as MutableMap<RegistryKey<World>, ServerWorld>
        worlds[worldKey] = world

        return world
    }

    /**
     * 配置维度的 WorldBorder
     *
     * WorldBorder 设在屏障墙壁之外（加上 BORDER_PADDING），
     * 作为最终安全边界防止任何手段穿越（末影珍珠、爆炸推力等）。
     * 同时禁用红色警告效果（warningBlocks = 0），避免玩家靠近边界时视野变红。
     *
     * @param world 目标维度
     * @param size 空间边长
     */
    fun setupWorldBorder(world: ServerWorld, size: Int) {
        val border = world.worldBorder
        border.setCenter(0.0, 0.0)
        border.size = (size + BORDER_PADDING * 2).toDouble()
        // 禁用靠近边界时的红色警告渲染效果
        border.warningBlocks = 0
    }

    /**
     * 放置空间的所有边界方块
     *
     * 包括三种边界：
     * - 地面层（bottomY）：白色屏障方块，不可破坏的白色地面
     * - 天花板层（topY）：白色屏障方块，不可破坏，与白色天空融为一体
     * - XZ 四周墙壁：白色屏障方块，不可破坏，白色外观与天空融为一体
     *
     * 所有边界使用自定义的 WHITE_BARRIER 方块，
     * 该方块外观为纯白色且在任何游戏模式下都不可破坏。
     *
     * @param world 目标维度
     * @param size 空间边长（等于高度）
     */
    fun setupBoundaries(world: ServerWorld, size: Int) {
        val bounds = calculateBounds(size)
        val whiteBarrier = ModBlocks.WHITE_BARRIER.defaultState

        // 放置地面层（白色屏障——不可破坏的白色地面）
        for (x in bounds.minX..bounds.maxX) {
            for (z in bounds.minZ..bounds.maxZ) {
                world.setBlockState(BlockPos(x, bounds.bottomY, z), whiteBarrier)
            }
        }

        // 放置天花板层（白色屏障——不可破坏，与白色天空融为一体）
        for (x in bounds.minX..bounds.maxX) {
            for (z in bounds.minZ..bounds.maxZ) {
                world.setBlockState(BlockPos(x, bounds.topY, z), whiteBarrier)
            }
        }

        // 放置 XZ 四周白色屏障墙壁（不可破坏的白色硬边界）
        for (y in bounds.bottomY..bounds.topY) {
            for (x in bounds.minX..bounds.maxX) {
                world.setBlockState(BlockPos(x, y, bounds.minZ - 1), whiteBarrier)
                world.setBlockState(BlockPos(x, y, bounds.maxZ + 1), whiteBarrier)
            }
            for (z in (bounds.minZ - 1)..(bounds.maxZ + 1)) {
                world.setBlockState(BlockPos(bounds.minX - 1, y, z), whiteBarrier)
                world.setBlockState(BlockPos(bounds.maxX + 1, y, z), whiteBarrier)
            }
        }
    }

    /**
     * 更新空间边界（用于等级提升扩大空间时）
     *
     * 移除旧的边界方块并放置新的，同时更新 WorldBorder 大小。
     *
     * @param world 目标维度
     * @param oldSize 旧的空间边长
     * @param newSize 新的空间边长
     */
    private fun updateBoundaries(world: ServerWorld, oldSize: Int, newSize: Int) {
        // 移除旧的边界方块
        removeBoundaries(world, oldSize)

        // 设置新的边界
        setupWorldBorder(world, newSize)
        setupBoundaries(world, newSize)
    }

    /**
     * 扩展空间大小（数据与物理边界同步更新）
     *
     * 在一个函数中同时完成：
     * 1. 更新 SpaceInfo.size 持久化数据
     * 2. 更新物理世界的屏障方块和 WorldBorder
     *
     * 确保数据和实际边界始终一致，不会出现大小变了但边界没更新的情况。
     *
     * @param server MinecraftServer 实例
     * @param spaceId 空间 ID
     * @param levelsGained 本次升级提升的等级数
     * @return 扩展后的新空间边长，失败返回 null
     */
    fun expandSpace(server: MinecraftServer, spaceId: Int, levelsGained: Int): Int? {
        val state = MindDomainState.get(server)
        val info = state.getSpaceInfo(spaceId) ?: return null

        val oldSize = info.size
        val newSize = oldSize + levelsGained * SpaceLevelConfig.EXPANSION_PER_LEVEL

        // 1. 更新持久化数据中的空间大小
        info.size = newSize
        state.markDirty()

        // 2. 更新物理世界边界（屏障方块 + WorldBorder）
        val world = getOrCreateWorld(server, spaceId, newSize) ?: return null
        updateBoundaries(world, oldSize, newSize)

        // 3. 向空间内的玩家同步 WorldBorder 变化
        syncWorldBorderToPlayers(world)

        logger.info("空间 {} 扩展: {} -> {} (升级 {} 级)", spaceId, oldSize, newSize, levelsGained)
        return newSize
    }

    /**
     * 向空间内的所有玩家发送 WorldBorder 完整状态
     *
     * 动态维度的 WorldBorder listener 可能未正确注册到维度内的玩家，
     * 导致通过 worldBorder.size 修改边界后客户端无法收到自动同步。
     * 此方法手动发送 WorldBorderInitializeS2CPacket，
     * 将完整的 WorldBorder 状态（中心、大小、警告等）推送到客户端。
     *
     * @param world 空间维度
     */
    private fun syncWorldBorderToPlayers(world: ServerWorld) {
        val players = world.players.filterIsInstance<ServerPlayerEntity>()
        if (players.isEmpty()) return

        val packet = WorldBorderInitializeS2CPacket(world.worldBorder)
        for (player in players) {
            player.networkHandler.sendPacket(packet)
        }
    }

    /**
     * 移除指定大小的所有边界方块
     */
    private fun removeBoundaries(world: ServerWorld, size: Int) {
        val bounds = calculateBounds(size)
        val air = Blocks.AIR.defaultState

        // 移除地面层和天花板层
        for (x in bounds.minX..bounds.maxX) {
            for (z in bounds.minZ..bounds.maxZ) {
                world.setBlockState(BlockPos(x, bounds.bottomY, z), air)
                world.setBlockState(BlockPos(x, bounds.topY, z), air)
            }
        }

        // 移除 XZ 墙壁
        for (y in bounds.bottomY..bounds.topY) {
            for (x in bounds.minX..bounds.maxX) {
                world.setBlockState(BlockPos(x, y, bounds.minZ - 1), air)
                world.setBlockState(BlockPos(x, y, bounds.maxZ + 1), air)
            }
            for (z in (bounds.minZ - 1)..(bounds.maxZ + 1)) {
                world.setBlockState(BlockPos(bounds.minX - 1, y, z), air)
                world.setBlockState(BlockPos(bounds.maxX + 1, y, z), air)
            }
        }
    }

    /**
     * 服务器启动时重建所有已注册的维度
     *
     * 从 MindDomainState 中读取所有空间数据，
     * 为每个空间重新创建 ServerWorld 实例并配置边界。
     *
     * @param server MinecraftServer 实例
     */
    fun rebuildAllWorlds(server: MinecraftServer) {
        val state = MindDomainState.get(server)
        var count = 0

        state.spaces.forEach { (spaceId, info) ->
            val worldKey = ModDimensions.getWorldKey(spaceId)
            if (server.getWorld(worldKey) == null) {
                val world = createWorld(server, worldKey)
                if (world != null) {
                    setupWorldBorder(world, info.size)
                    setupBoundaries(world, info.size)
                    count++
                }
            }
        }

        if (count > 0) {
            logger.info("服务器启动：重建了 {} 个心灵空间维度", count)
        }
    }

    /**
     * 根据空间边长计算边界坐标范围
     *
     * 空间从 PLATFORM_Y 向上延伸 size 格可用高度：
     * - 地面在 PLATFORM_Y（屏障），玩家站在 PLATFORM_Y + 1
     * - 天花板在 PLATFORM_Y + size + 1（屏障）
     * - 可用高度：PLATFORM_Y+1 到 PLATFORM_Y+size，共 size 格，与 XZ 一致
     * - XZ 以原点为中心，范围为 [-halfSize, halfSize-1]
     *
     * @param size 空间边长
     * @return 包含 X/Z 范围和顶底 Y 坐标的边界数据
     */
    private fun calculateBounds(size: Int): SpaceBounds {
        val halfSize = size / 2
        return SpaceBounds(
            minX = -halfSize,
            maxX = halfSize - 1,
            minZ = -halfSize,
            maxZ = halfSize - 1,
            bottomY = PLATFORM_Y,
            topY = PLATFORM_Y + size + 1
        )
    }

}

/**
 * 空间边界坐标数据
 *
 * @param minX X 轴最小坐标
 * @param maxX X 轴最大坐标
 * @param minZ Z 轴最小坐标
 * @param maxZ Z 轴最大坐标
 * @param bottomY 地面层 Y 坐标（白色屏障方块）
 * @param topY 天花板层 Y 坐标（白色屏障方块）
 */
private data class SpaceBounds(
    val minX: Int,
    val maxX: Int,
    val minZ: Int,
    val maxZ: Int,
    val bottomY: Int,
    val topY: Int
)
