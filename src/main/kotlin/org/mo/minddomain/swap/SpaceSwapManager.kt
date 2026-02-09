package org.mo.minddomain.swap

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.minecraft.block.Block
import net.minecraft.block.BlockState
import net.minecraft.entity.Entity
import net.minecraft.entity.LivingEntity
import net.minecraft.entity.player.PlayerEntity
import net.minecraft.inventory.Inventory
import net.minecraft.nbt.NbtCompound
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.storage.NbtReadView
import net.minecraft.util.ErrorReporter
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Box
import net.minecraft.util.math.Vec3d
import net.minecraft.world.TeleportTarget
import org.mo.minddomain.dimension.DynamicWorldManager
import org.mo.minddomain.level.SpaceLevelConfig
import org.slf4j.LoggerFactory

/**
 * 空间替换引擎
 *
 * 负责在现实世界和心灵空间维度之间执行方块和实体的双向交换。
 * 交换将以玩家为中心、与空间大小匹配的立方体区域内的所有方块和非玩家实体
 * 与空间维度的可用区域进行一对一映射交换。
 *
 * 核心流程：
 * 1. 预加载阶段：强制加载空间维度区块，等待实体从磁盘异步加载完成
 * 2. 快照阶段：同时读取两侧所有方块状态和方块实体 NBT 到内存
 * 3. 写入阶段：将空间快照写入现实世界，将现实快照写入空间
 * 4. 方块实体恢复：写入后恢复方块实体的 NBT 数据（如箱子内容物）
 * 5. 实体交换：将两侧的非玩家实体通过 teleportTo 跨维度移动到对应位置
 *
 * 延迟执行机制：
 * 空间维度在无玩家时区块会卸载，实体被序列化到磁盘。setChunkForced 添加票据后，
 * 区块和实体的加载是异步的（由工作线程完成），无法在同一 tick 内完成。
 * 因此交换操作分为两步：先强制加载区块，等待若干 tick 后再执行实际交换。
 *
 * 坐标映射：
 * - 空间可用区域 X: [-halfSize, halfSize-1], Z: [-halfSize, halfSize-1], Y: [PLATFORM_Y+1, PLATFORM_Y+size]
 * - 现实交换区域以玩家脚下为基准，X/Z 以玩家为中心，Y 从脚下方块开始向上 size 格
 * - 映射公式: spaceX = realX - centerX, spaceZ = realZ - centerZ, spaceY = (realY - baseY) + PLATFORM_Y + 1
 */
object SpaceSwapManager {

    private val logger = LoggerFactory.getLogger("MindDomain/SpaceSwapManager")

    /**
     * 区块和实体加载所需的等待 tick 数
     *
     * setChunkForced 后，区块管理器需要在后续 tick 中处理票据更新、加载区块、
     * 触发实体追踪状态变更，然后实体管理器在下一次 tick 中完成异步 IO 并注册实体。
     * 3 tick（约 150ms）足够完成这些异步操作。
     */
    private const val ENTITY_LOAD_DELAY_TICKS = 3

    /**
     * setBlockState 使用的标志位组合
     *
     * 空间交换需要"原封不动"地搬运方块，不触发任何游戏逻辑副作用：
     * - NOTIFY_LISTENERS (2): 通知客户端渲染更新（必须，否则客户端看不到变化）
     * - FORCE_STATE (16): 跳过 canPlaceAt 检查（火把等方块在支撑方块尚未写入时不会放置失败）
     * - SKIP_DROPS (32): 禁止物品掉落（防止 updateNeighborStates 导致的级联掉落物复制）
     * - SKIP_BLOCK_ADDED_CALLBACK (512): 跳过 onBlockAdded 回调（防止方块自检有效性后自毁）
     */
    private const val SWAP_SET_BLOCK_FLAGS =
        Block.NOTIFY_LISTENERS or Block.FORCE_STATE or Block.SKIP_DROPS or Block.SKIP_BLOCK_ADDED_CALLBACK

    /**
     * 待执行的延迟任务队列
     *
     * 每个任务有剩余等待 tick 数，每 tick 递减 1，到达 0 时执行并移除。
     * 使用 synchronized 访问保证线程安全（虽然所有操作都在服务端主线程）。
     */
    private val pendingTasks = mutableListOf<PendingTask>()

    /**
     * 延迟任务
     *
     * @param ticksRemaining 剩余等待 tick 数
     * @param action 到期执行的操作
     */
    private class PendingTask(
        var ticksRemaining: Int,
        val action: () -> Unit
    )

    /**
     * 快照中单个方块位置的数据
     *
     * @param blockState 方块状态（包含方块类型和属性）
     * @param nbt 方块实体的 NBT 数据（如果该方块有方块实体，例如箱子、漏斗等），无则为 null
     */
    private data class BlockSnapshot(
        val blockState: BlockState,
        val nbt: NbtCompound?
    )

    // ==================== 初始化 ====================

    /**
     * 注册服务端 tick 事件
     *
     * 应在 ModInitializer.onInitialize() 中调用。
     * 每 tick 末尾检查并执行到期的延迟任务。
     */
    fun registerEvents() {
        ServerTickEvents.END_SERVER_TICK.register { _ ->
            processPendingTasks()
        }
    }

    /**
     * 处理到期的延迟任务
     *
     * 每 tick 调用一次，将所有任务的剩余 tick 数减 1，
     * 到达 0 的任务立即执行并从队列移除。
     */
    private fun processPendingTasks() {
        if (pendingTasks.isEmpty()) return

        val iterator = pendingTasks.iterator()
        while (iterator.hasNext()) {
            val task = iterator.next()
            task.ticksRemaining--
            if (task.ticksRemaining <= 0) {
                try {
                    task.action()
                } catch (e: Exception) {
                    logger.error("延迟任务执行失败", e)
                }
                iterator.remove()
            }
        }
    }

    // ==================== 公开 API ====================

    /**
     * 强制加载空间维度中交换区域覆盖的所有区块
     *
     * 当空间维度没有玩家在场时，区块会被卸载，实体会被序列化到磁盘。
     * 通过 setChunkForced 添加强制加载票据，使区块在后续 tick 中被加载到实体追踪级别，
     * 触发实体管理器从磁盘异步加载实体数据。
     *
     * 调用此方法后，应等待 [ENTITY_LOAD_DELAY_TICKS] tick 再执行实际交换操作。
     *
     * @param spaceWorld 空间维度
     * @param halfSize 空间半径 (size / 2)
     * @param size 空间边长
     * @return 被强制加载的区块坐标列表，用于后续释放
     */
    fun forceLoadSpaceChunks(
        spaceWorld: ServerWorld,
        halfSize: Int,
        size: Int
    ): List<Pair<Int, Int>> {
        // 计算交换区域覆盖的区块范围
        // 空间可用区域 X: [-halfSize, -halfSize+size-1], Z 同理
        val minChunkX = (-halfSize) shr 4
        val maxChunkX = (-halfSize + size - 1) shr 4
        val minChunkZ = (-halfSize) shr 4
        val maxChunkZ = (-halfSize + size - 1) shr 4

        val forcedChunks = mutableListOf<Pair<Int, Int>>()

        // 添加强制加载票据（ChunkTicketType.FORCED）
        // 这使区块达到实体追踪级别（≤31），从而触发实体加载
        for (cx in minChunkX..maxChunkX) {
            for (cz in minChunkZ..maxChunkZ) {
                spaceWorld.setChunkForced(cx, cz, true)
                forcedChunks.add(cx to cz)
            }
        }

        logger.debug(
            "空间维度区块强制加载已请求: 区块范围=[({},{})-({},{})], 共 {} 个区块",
            minChunkX, minChunkZ, maxChunkX, maxChunkZ, forcedChunks.size
        )

        return forcedChunks
    }

    /**
     * 释放之前强制加载的空间维度区块
     *
     * 移除 setChunkForced 添加的强制加载票据，允许区块在无人时自然卸载。
     * 应在交换操作完成后调用（包括异常情况）。
     *
     * @param spaceWorld 空间维度
     * @param chunks 之前强制加载的区块坐标列表
     */
    fun releaseForceLoadedChunks(spaceWorld: ServerWorld, chunks: List<Pair<Int, Int>>) {
        for ((cx, cz) in chunks) {
            spaceWorld.setChunkForced(cx, cz, false)
        }
    }

    /**
     * 调度延迟执行的任务
     *
     * 任务将在指定 tick 数后在服务端主线程执行。
     *
     * @param delayTicks 延迟的 tick 数
     * @param action 到期执行的操作
     */
    fun scheduleDelayed(delayTicks: Int, action: () -> Unit) {
        pendingTasks.add(PendingTask(delayTicks, action))
    }

    /**
     * 执行空间替换操作
     *
     * 将现实世界以 (centerX, baseY, centerZ) 为基准的区域
     * 与空间维度的可用区域进行双向方块和实体交换。
     * 水平方向为 size×size，垂直方向为 verticalSize（受 [SpaceLevelConfig.MAX_VERTICAL_SIZE] 上限限制）。
     *
     * 调用此方法前，应已通过 [forceLoadSpaceChunks] 强制加载空间维度区块，
     * 并等待足够的 tick 让区块和实体完成异步加载。
     *
     * @param player 执行交换的玩家（用于排除实体交换时不移动玩家自身）
     * @param realWorld 现实世界维度
     * @param spaceWorld 心灵空间维度
     * @param size 空间水平边长（交换区域水平为 size×size）
     * @param centerX 现实世界交换中心 X 坐标（玩家 blockPos.x）
     * @param centerZ 现实世界交换中心 Z 坐标（玩家 blockPos.z）
     * @param baseY 现实世界交换起始 Y 坐标（玩家脚下方块 Y，即 blockPos.y - 1）
     * @return true 如果交换成功完成
     */
    fun executeSwap(
        player: ServerPlayerEntity,
        realWorld: ServerWorld,
        spaceWorld: ServerWorld,
        size: Int,
        centerX: Int,
        centerZ: Int,
        baseY: Int
    ): Boolean {
        val halfSize = size / 2
        // 垂直方向受上限限制，可能小于水平方向的 size
        val verticalSize = SpaceLevelConfig.getVerticalSize(size)

        // 交换区域范围：
        // 水平: size × size, 垂直: verticalSize
        // 现实世界: X [centerX-halfSize, centerX+halfSize-1], Z 同理, Y [baseY, baseY+verticalSize-1]
        // 空间:     X [-halfSize, halfSize-1], Z 同理, Y [PLATFORM_Y+1, PLATFORM_Y+verticalSize]

        try {
            // ==================== 预处理阶段：清理状态绑定 ====================
            // 唤醒两侧区域内所有睡眠实体，防止方块替换导致状态损坏：
            // - 睡眠实体与床方块通过 occupied 属性双向绑定
            // - 方块替换时床消失会导致睡眠实体进入无效状态而被引擎移除
            // - wakeUp() 同时清除实体睡眠状态和床的 occupied=true 属性
            wakeUpSleepingEntities(
                realWorld,
                centerX - halfSize, baseY, centerZ - halfSize,
                size, verticalSize
            )
            wakeUpSleepingEntities(
                spaceWorld,
                -halfSize, DynamicWorldManager.PLATFORM_Y + 1, -halfSize,
                size, verticalSize
            )

            // ==================== 第一阶段：快照 ====================
            // 将两侧所有方块状态和方块实体 NBT 读取到内存数组中，
            // 避免写入过程中读取到已被修改的数据
            // 数组维度: [size (X)] × [verticalSize (Y)] × [size (Z)]

            val realSnapshots = Array(size) { Array(verticalSize) { arrayOfNulls<BlockSnapshot>(size) } }
            val spaceSnapshots = Array(size) { Array(verticalSize) { arrayOfNulls<BlockSnapshot>(size) } }

            for (dx in 0 until size) {
                for (dy in 0 until verticalSize) {
                    for (dz in 0 until size) {
                        // 现实世界坐标
                        val realPos = BlockPos(
                            centerX - halfSize + dx,
                            baseY + dy,
                            centerZ - halfSize + dz
                        )
                        // 空间坐标
                        val spacePos = BlockPos(
                            -halfSize + dx,
                            DynamicWorldManager.PLATFORM_Y + 1 + dy,
                            -halfSize + dz
                        )

                        // 读取现实世界方块快照
                        realSnapshots[dx][dy][dz] = takeBlockSnapshot(realWorld, realPos)
                        // 读取空间方块快照
                        spaceSnapshots[dx][dy][dz] = takeBlockSnapshot(spaceWorld, spacePos)
                    }
                }
            }

            // ==================== 第二阶段：写入方块 ====================
            // 将空间快照写入现实世界，将现实快照写入空间

            for (dx in 0 until size) {
                for (dy in 0 until verticalSize) {
                    for (dz in 0 until size) {
                        val realPos = BlockPos(
                            centerX - halfSize + dx,
                            baseY + dy,
                            centerZ - halfSize + dz
                        )
                        val spacePos = BlockPos(
                            -halfSize + dx,
                            DynamicWorldManager.PLATFORM_Y + 1 + dy,
                            -halfSize + dz
                        )

                        // 空间 → 现实世界
                        val spaceSnapshot = spaceSnapshots[dx][dy][dz]!!
                        writeBlockSnapshot(realWorld, realPos, spaceSnapshot)

                        // 现实世界 → 空间
                        val realSnapshot = realSnapshots[dx][dy][dz]!!
                        writeBlockSnapshot(spaceWorld, spacePos, realSnapshot)
                    }
                }
            }

            // ==================== 第三阶段：实体交换 ====================
            // 收集两侧区域内的非玩家实体，按坐标映射公式跨维度移动

            swapEntities(
                player, realWorld, spaceWorld,
                centerX, centerZ, baseY, halfSize, size, verticalSize
            )

            logger.info(
                "空间替换成功: 玩家={}, 区域中心=({}, {}, {}), 大小={}, 垂直={}",
                player.name.string, centerX, baseY, centerZ, size, verticalSize
            )
            return true

        } catch (e: Exception) {
            logger.error("空间替换失败: 玩家={}", player.name.string, e)
            return false
        }
    }

    // ==================== 内部方法 ====================

    /**
     * 读取指定位置的方块快照（BlockState + 方块实体 NBT）
     *
     * @param world 目标维度
     * @param pos 方块位置
     * @return 包含方块状态和可选 NBT 的快照数据
     */
    private fun takeBlockSnapshot(world: ServerWorld, pos: BlockPos): BlockSnapshot {
        val blockState = world.getBlockState(pos)
        val blockEntity = world.getBlockEntity(pos)

        // 如果方块有方块实体（如箱子、熔炉等），序列化其完整 NBT 数据
        val nbt = if (blockEntity != null) {
            blockEntity.createNbtWithIdentifyingData(world.registryManager)
        } else {
            null
        }

        return BlockSnapshot(blockState, nbt)
    }

    /**
     * 将方块快照写入目标位置
     *
     * 使用 [SWAP_SET_BLOCK_FLAGS] 组合标志确保"原封不动"搬运：
     * - 跳过放置条件检查（FORCE_STATE），避免依赖邻居方块的方块放置失败
     * - 禁止物品掉落（SKIP_DROPS），防止 updateNeighborStates 级联导致掉落物复制
     * - 跳过 onBlockAdded 回调（SKIP_BLOCK_ADDED_CALLBACK），防止方块自检后自毁
     *
     * 写入后如果快照包含方块实体 NBT 数据，将其恢复到新创建的方块实体中。
     *
     * @param world 目标维度
     * @param pos 目标位置
     * @param snapshot 要写入的方块快照
     */
    private fun writeBlockSnapshot(world: ServerWorld, pos: BlockPos, snapshot: BlockSnapshot) {
        // 清空现有方块实体的物品栏，防止 setBlockState 触发
        // onStateReplaced → ItemScatterer.scatter() 将物品以掉落物形式散落。
        // 快照已完整保存方块实体的 NBT 数据，散落的掉落物是多余副本，会导致物品复制。
        val existingBlockEntity = world.getBlockEntity(pos)
        if (existingBlockEntity is Inventory) {
            (existingBlockEntity as Inventory).clear()
        }

        // 使用精心选择的标志组合：无掉落、无自检、强制放置、客户端同步
        world.setBlockState(pos, snapshot.blockState, SWAP_SET_BLOCK_FLAGS)

        // 如果快照包含方块实体 NBT 数据，恢复到目标位置的方块实体
        if (snapshot.nbt != null) {
            val newBlockEntity = world.getBlockEntity(pos)
            if (newBlockEntity != null) {
                // 更新 NBT 中的坐标字段为目标位置
                val nbtCopy = snapshot.nbt.copy()
                nbtCopy.putInt("x", pos.x)
                nbtCopy.putInt("y", pos.y)
                nbtCopy.putInt("z", pos.z)

                // 1.21.11: 通过 NbtReadView.create() 将 NbtCompound 转换为 ReadView，
                // 再传给 BlockEntity.read(ReadView) 恢复数据
                val readView = NbtReadView.create(
                    ErrorReporter.EMPTY,
                    world.registryManager,
                    nbtCopy
                )
                newBlockEntity.read(readView)
                newBlockEntity.markDirty()
            }
        }
    }

    /**
     * 交换两侧区域内的非玩家实体
     *
     * 收集现实世界交换区域和空间可用区域内的所有非玩家实体，
     * 按坐标映射公式计算目标位置后通过 teleportTo 跨维度传送。
     *
     * 坐标映射：
     * - 现实 → 空间: spaceX = realX - centerX, spaceZ = realZ - centerZ,
     *                 spaceY = (realY - baseY) + PLATFORM_Y + 1
     * - 空间 → 现实: realX = spaceX + centerX, realZ = spaceZ + centerZ,
     *                 realY = (spaceY - PLATFORM_Y - 1) + baseY
     *
     * @param player 执行交换的玩家（不会被移动）
     * @param realWorld 现实世界
     * @param spaceWorld 空间维度
     * @param centerX 现实交换中心 X
     * @param centerZ 现实交换中心 Z
     * @param baseY 现实交换起始 Y
     * @param halfSize 空间半径（水平方向）
     * @param size 空间水平边长
     * @param verticalSize 空间垂直尺寸（受 MAX_VERTICAL_SIZE 上限限制）
     */
    private fun swapEntities(
        player: ServerPlayerEntity,
        realWorld: ServerWorld,
        spaceWorld: ServerWorld,
        centerX: Int,
        centerZ: Int,
        baseY: Int,
        halfSize: Int,
        size: Int,
        verticalSize: Int
    ) {
        // 现实世界交换区域的碰撞箱（水平: size, 垂直: verticalSize）
        val realBox = Box(
            (centerX - halfSize).toDouble(),
            baseY.toDouble(),
            (centerZ - halfSize).toDouble(),
            (centerX - halfSize + size).toDouble(),
            (baseY + verticalSize).toDouble(),
            (centerZ - halfSize + size).toDouble()
        )

        // 空间可用区域的碰撞箱（垂直使用 verticalSize）
        val spaceBox = Box(
            (-halfSize).toDouble(),
            (DynamicWorldManager.PLATFORM_Y + 1).toDouble(),
            (-halfSize).toDouble(),
            (-halfSize + size).toDouble(),
            (DynamicWorldManager.PLATFORM_Y + 1 + verticalSize).toDouble(),
            (-halfSize + size).toDouble()
        )

        // 收集现实世界中的非玩家实体
        // 通过 filterOutSubParts 排除多部件实体的子部件（如末影龙的碰撞箱部件），
        // 避免主体传送后子部件单独传送导致实体重复创建
        val realEntities = filterOutSubParts(
            realWorld.getOtherEntities(null, realBox) { entity ->
                entity !is PlayerEntity
            }
        )

        // 收集空间中的非玩家实体
        val spaceEntities = filterOutSubParts(
            spaceWorld.getOtherEntities(null, spaceBox) { entity ->
                entity !is PlayerEntity
            }
        )

        logger.debug(
            "实体交换: 现实世界 {} 个实体, 空间维度 {} 个实体",
            realEntities.size, spaceEntities.size
        )

        // 现实世界实体 → 空间
        for (entity in realEntities) {
            val targetX = entity.x - centerX
            val targetZ = entity.z - centerZ
            val targetY = (entity.y - baseY) + DynamicWorldManager.PLATFORM_Y + 1

            teleportEntity(entity, spaceWorld, targetX, targetY, targetZ)
        }

        // 空间实体 → 现实世界
        for (entity in spaceEntities) {
            val targetX = entity.x + centerX
            val targetZ = entity.z + centerZ
            val targetY = (entity.y - DynamicWorldManager.PLATFORM_Y - 1) + baseY

            teleportEntity(entity, realWorld, targetX, targetY, targetZ)
        }
    }

    /**
     * 将实体跨维度传送到目标位置
     *
     * 使用 TeleportTarget 实现跨维度传送，保持实体的朝向和速度归零。
     *
     * @param entity 要传送的实体
     * @param targetWorld 目标维度
     * @param x 目标 X 坐标
     * @param y 目标 Y 坐标
     * @param z 目标 Z 坐标
     */
    private fun teleportEntity(entity: Entity, targetWorld: ServerWorld, x: Double, y: Double, z: Double) {
        entity.teleportTo(
            TeleportTarget(
                targetWorld,
                Vec3d(x, y, z),
                Vec3d.ZERO,
                entity.yaw,
                entity.pitch,
                TeleportTarget.NO_OP
            )
        )
    }

    /**
     * 从实体列表中过滤掉多部件实体的子部件
     *
     * 多部件实体（如末影龙）由一个主体和多个子部件实体组成。
     * 子部件的 [Entity.isPartOf] 方法对其主体实体返回 true（默认实现仅对自身返回 true）。
     * 传送主体时引擎会自动处理所有子部件，因此子部件不应被单独传送，
     * 否则会导致实体重复创建。
     *
     * 此方法通过交叉检查每个实体的 isPartOf 关系通用地识别并排除子部件，
     * 适用于原版和模组添加的任何多部件实体。
     *
     * @param entities 候选实体列表
     * @return 过滤掉子部件后的实体列表
     */
    private fun filterOutSubParts(entities: List<Entity>): List<Entity> {
        if (entities.size <= 1) return entities

        val subParts = HashSet<Entity>()
        for (entity in entities) {
            for (other in entities) {
                // isPartOf 默认仅对 self 返回 true；
                // 多部件子部件重写此方法，对其主体也返回 true
                if (entity !== other && entity.isPartOf(other)) {
                    subParts.add(entity)
                    break
                }
            }
        }

        if (subParts.isNotEmpty()) {
            logger.debug("过滤了 {} 个多部件实体子部件", subParts.size)
        }

        return if (subParts.isEmpty()) entities else entities.filter { it !in subParts }
    }

    /**
     * 唤醒指定区域内所有睡眠中的实体
     *
     * 睡眠实体（如床上的村民）与床方块通过 occupied 属性双向绑定。
     * 在方块替换之前唤醒它们可同时清除实体的睡眠状态和床的 occupied 属性，
     * 防止方块替换后实体因失去关联床方块而进入无效状态被引擎移除。
     *
     * @param world 目标维度
     * @param minX 区域最小 X 坐标
     * @param minY 区域最小 Y 坐标
     * @param minZ 区域最小 Z 坐标
     * @param horizontalSize 区域水平边长
     * @param verticalSize 区域垂直高度
     */
    private fun wakeUpSleepingEntities(
        world: ServerWorld,
        minX: Int, minY: Int, minZ: Int,
        horizontalSize: Int, verticalSize: Int
    ) {
        val box = Box(
            minX.toDouble(), minY.toDouble(), minZ.toDouble(),
            (minX + horizontalSize).toDouble(),
            (minY + verticalSize).toDouble(),
            (minZ + horizontalSize).toDouble()
        )
        val sleepingEntities = world.getOtherEntities(null, box) {
            it is LivingEntity && it.isSleeping
        }
        for (entity in sleepingEntities) {
            (entity as LivingEntity).wakeUp()
        }
        if (sleepingEntities.isNotEmpty()) {
            logger.debug("唤醒了 {} 个睡眠中的实体", sleepingEntities.size)
        }
    }
}
