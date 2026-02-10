package org.mo.minddomain.entity

import net.minecraft.entity.Entity
import net.minecraft.entity.EntityType
import net.minecraft.entity.MovementType
import net.minecraft.entity.data.DataTracker
import net.minecraft.entity.data.TrackedData
import net.minecraft.entity.data.TrackedDataHandlerRegistry
import net.minecraft.entity.player.PlayerEntity
import net.minecraft.item.ItemStack
import net.minecraft.particle.ParticleTypes
import net.minecraft.server.world.ServerWorld
import net.minecraft.sound.SoundEvents
import net.minecraft.storage.ReadView
import net.minecraft.storage.WriteView
import net.minecraft.util.ActionResult
import net.minecraft.util.Hand
import net.minecraft.world.World
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * 空间球自定义实体
 *
 * 玩家死亡后在死亡位置生成的特殊实体，替代普通掉落物（ItemEntity）。
 * 外观表现为较大的浮动球体，具有以下特性：
 * - 无敌：不受任何伤害
 * - 发光描边：始终带有高亮轮廓（透视可见），便于玩家寻找
 * - 重力：生成后自然落地
 * - 上下浮动：落地后由渲染器执行视觉浮动（不改变实际位置，避免同步问题）
 * - 粒子效果：多层次特效（双螺旋、能量扩散、脉冲波、星尘上升）
 * - 右键交互：玩家右键点击可拾取空间球物品
 * - 持久化：支持 NBT 序列化，服务器重启后实体仍存在
 *
 * 渲染由客户端的 SpaceBallEntityRenderer 处理，
 * 显示为放大 2.5 倍的 3D 空间球物品模型，带缓慢旋转和正弦浮动。
 */
class SpaceBallEntity(type: EntityType<out SpaceBallEntity>, world: World) : Entity(type, world) {

    companion object {
        /**
         * 同步到客户端的 ItemStack 数据（用于渲染器获取物品模型）
         */
        private val ITEM_STACK: TrackedData<ItemStack> = DataTracker.registerData(
            SpaceBallEntity::class.java,
            TrackedDataHandlerRegistry.ITEM_STACK
        )

        // ==================== 重力常量 ====================

        /** 每 tick 向下加速度（与普通物品掉落一致） */
        private const val GRAVITY = 0.04

        // ==================== 粒子常量 ====================

        /** 三轴轨道环半径（格） */
        private const val ORBIT_RADIUS = 0.55

        /** 轨道 A 角速度（弧度/tick），约 13 秒一圈 */
        private const val ORBIT_A_SPEED = 0.08

        /** 轨道 B 角速度（弧度/tick），约 16 秒一圈 */
        private const val ORBIT_B_SPEED = 0.065

        /** 轨道 C 角速度（弧度/tick），约 21 秒一圈 */
        private const val ORBIT_C_SPEED = 0.05

        /** 能量汇聚脉冲间隔 tick 数（约 4 秒） */
        private const val CONVERGENCE_INTERVAL = 80

        /** 能量汇聚脉冲持续 tick 数 */
        private const val CONVERGENCE_DURATION = 12

        /** 汇聚粒子生成半径 */
        private const val CONVERGENCE_SPAWN_RADIUS = 1.2

        /** 符文光环顶点数（六边形） */
        private const val RUNE_SIDES = 6

        /** 符文光环半径 */
        private const val RUNE_RADIUS = 0.65

        /**
         * 在指定位置生成空间球实体
         *
         * 由 [org.mo.minddomain.event.ModEvents] 在玩家死亡时调用。
         *
         * @param world 生成实体的世界（必须是服务端世界）
         * @param x 生成位置 X 坐标
         * @param y 生成位置 Y 坐标
         * @param z 生成位置 Z 坐标
         * @param stack 携带空间数据的空间球物品
         */
        fun spawn(world: ServerWorld, x: Double, y: Double, z: Double, stack: ItemStack) {
            val entity = SpaceBallEntity(ModEntities.SPACE_BALL_ENTITY, world)
            entity.setPosition(x, y, z)
            entity.setStack(stack)
            world.spawnEntity(entity)
        }
    }

    /**
     * 标记实体是否已落地（落地后停止重力计算）
     */
    private var landed: Boolean = false

    init {
        // 无敌：不受任何伤害源的伤害
        isInvulnerable = true
        // 发光描边：始终带有高亮轮廓，便于玩家在复杂地形中透视找到
        setGlowing(true)
    }

    // ==================== 数据同步 ====================

    override fun initDataTracker(builder: DataTracker.Builder) {
        builder.add(ITEM_STACK, ItemStack.EMPTY)
    }

    /**
     * 获取实体携带的空间球物品
     */
    fun getStack(): ItemStack = dataTracker.get(ITEM_STACK)

    /**
     * 设置实体携带的空间球物品（同步到客户端供渲染器使用）
     */
    fun setStack(stack: ItemStack) {
        dataTracker.set(ITEM_STACK, stack)
    }

    // ==================== 交互 ====================

    /**
     * 允许实体被玩家十字准星选中（canHit = true 使实体可被交互）
     */
    override fun canHit(): Boolean = true

    /**
     * 实体不参与碰撞推挤（不阻挡玩家和其他实体的移动）
     */
    override fun isCollidable(entity: Entity?): Boolean = false

    /**
     * 处理玩家右键交互
     *
     * 将空间球物品放入玩家背包（或掉落到玩家脚下），
     * 播放拾取音效后移除实体。
     * 仅在服务端执行逻辑，客户端返回 SUCCESS 以播放手部动画。
     *
     * @param player 交互的玩家
     * @param hand 使用的手
     * @return SUCCESS 表示交互成功
     */
    override fun interact(player: PlayerEntity, hand: Hand): ActionResult {
        if (entityWorld.isClient) {
            return ActionResult.SUCCESS
        }

        val stack = getStack()
        if (!stack.isEmpty) {
            // 将物品放入玩家背包，如果背包已满则掉落在玩家脚下
            if (!player.giveItemStack(stack.copy())) {
                player.dropItem(stack.copy(), false)
            }
            // 播放拾取音效
            player.playSound(SoundEvents.ENTITY_ITEM_PICKUP, 1.0f, 1.0f)
        }

        // 移除实体
        discard()
        return ActionResult.SUCCESS
    }

    // ==================== Tick 逻辑 ====================

    /**
     * 每 tick 更新逻辑
     *
     * 服务端：处理重力下落直到落地（落地后位置不再改变，浮动由渲染器视觉实现）
     * 客户端：生成多层粒子特效
     *
     * 浮动动画不在此处修改位置，避免 trackingTickInterval(20) 导致的
     * 客户端-服务端位置同步延迟，影响 hitbox 和视觉的对齐。
     */
    override fun tick() {
        super.tick()

        if (!entityWorld.isClient) {
            // === 服务端：重力下落 ===
            if (!landed) {
                velocity = velocity.add(0.0, -GRAVITY, 0.0)
                move(MovementType.SELF, velocity)

                if (isOnGround) {
                    landed = true
                    velocity = velocity.multiply(0.0, 0.0, 0.0)
                }
            }
        } else {
            // === 客户端：粒子效果 ===
            spawnParticles()
        }
    }

    // ==================== 粒子效果 ====================

    /**
     * 生成多层次粒子特效（仅客户端）
     *
     * 五层效果叠加营造"次元结晶体"视觉：
     * 1. 三轴轨道环：三条不同平面的 END_ROD 粒子轨道，形成原子模型般的动态光环
     * 2. 符文光环：旋转六边形 ENCHANTED_HIT 粒子阵列，赋予神秘感
     * 3. 能量汇聚脉冲：周期性从远处向球心汇聚的 END_ROD 粒子流
     * 4. 顶部能量喷射：球体顶部持续释放的上升微光
     * 5. 底部逆门涟漪：球体底部的 REVERSE_PORTAL 紫色能量扩散
     */
    private fun spawnParticles() {
        val world = entityWorld
        val random = world.random
        val cx = x
        val cy = y + 0.75  // 球体视觉中心（与渲染器的 translate 偏移对齐）
        val cz = z
        val tick = age
        val t = tick.toDouble()

        // === 三轴轨道环 (Triple-Axis Orbital Rings) ===
        // 三条不同平面的圆形轨道，每条轨道上有 2 个对称 END_ROD 粒子，
        // 角速度各不相同，产生陀螺仪般的复杂运动视觉
        run {
            val r = ORBIT_RADIUS

            // 轨道 A：XZ 水平平面
            val a1 = t * ORBIT_A_SPEED
            world.addParticleClient(
                ParticleTypes.END_ROD,
                cx + r * cos(a1), cy, cz + r * sin(a1),
                0.0, 0.003, 0.0
            )
            world.addParticleClient(
                ParticleTypes.END_ROD,
                cx - r * cos(a1), cy, cz - r * sin(a1),
                0.0, 0.003, 0.0
            )

            // 轨道 B：XY 垂直平面（面向 Z 方向）
            val a2 = t * ORBIT_B_SPEED + 2.094  // 相位偏移 2π/3
            world.addParticleClient(
                ParticleTypes.END_ROD,
                cx + r * cos(a2), cy + r * sin(a2), cz,
                0.0, 0.0, 0.0
            )
            world.addParticleClient(
                ParticleTypes.END_ROD,
                cx - r * cos(a2), cy - r * sin(a2), cz,
                0.0, 0.0, 0.0
            )

            // 轨道 C：YZ 垂直平面（面向 X 方向）
            val a3 = t * ORBIT_C_SPEED + 4.189  // 相位偏移 4π/3
            world.addParticleClient(
                ParticleTypes.END_ROD,
                cx, cy + r * cos(a3), cz + r * sin(a3),
                0.0, 0.0, 0.0
            )
            world.addParticleClient(
                ParticleTypes.END_ROD,
                cx, cy - r * cos(a3), cz - r * sin(a3),
                0.0, 0.0, 0.0
            )
        }

        // === 符文光环 (Rune Halo) ===
        // 每 2 tick 在球体赤道平面生成一个旋转六边形顶点处的 ENCHANTED_HIT 粒子，
        // 六个顶点依次亮起，形成缓慢旋转的神秘符文阵列
        if (tick % 2 == 0) {
            val runeAngle = t * 0.03  // 缓慢旋转
            val vertexIndex = (tick / 2) % RUNE_SIDES
            val angle = runeAngle + vertexIndex * (2.0 * PI / RUNE_SIDES)
            world.addParticleClient(
                ParticleTypes.ENCHANTED_HIT,
                cx + RUNE_RADIUS * cos(angle), cy, cz + RUNE_RADIUS * sin(angle),
                0.0, 0.01, 0.0
            )
        }

        // === 能量汇聚脉冲 (Energy Convergence Pulse) ===
        // 每 80 tick（4 秒）触发一次汇聚效果，持续 12 tick，
        // 从远处向球心飞射 END_ROD 粒子，模拟球体吸收周围能量
        val pulseCycle = tick % CONVERGENCE_INTERVAL
        if (pulseCycle < CONVERGENCE_DURATION && pulseCycle % 2 == 0) {
            for (i in 0 until 6) {
                val angle = random.nextDouble() * 2.0 * PI
                val dist = CONVERGENCE_SPAWN_RADIUS + random.nextDouble() * 0.5
                val px = cx + dist * cos(angle)
                val pz = cz + dist * sin(angle)
                val py = cy + (random.nextDouble() - 0.5) * 0.6

                // 速度方向指向球心，模拟能量被吸入
                val speed = 0.04 + random.nextDouble() * 0.02
                world.addParticleClient(
                    ParticleTypes.END_ROD,
                    px, py, pz,
                    (cx - px) * speed / dist,
                    (cy - py) * speed / dist,
                    (cz - pz) * speed / dist
                )
            }
        }

        // === 顶部能量喷射 (Top Energy Jet) ===
        // 每 5 tick 从球体顶部释放一个缓慢上升的微光粒子，
        // 形成持续的能量柱状上升流
        if (tick % 5 == 0) {
            val spread = 0.08
            world.addParticleClient(
                ParticleTypes.END_ROD,
                cx + (random.nextDouble() - 0.5) * spread,
                cy + 0.35,
                cz + (random.nextDouble() - 0.5) * spread,
                0.0, 0.04 + random.nextDouble() * 0.02, 0.0
            )
        }

        // === 底部逆门涟漪 (Bottom Portal Ripple) ===
        // 每 3 tick 在球体底部生成向下扩散的 REVERSE_PORTAL 紫色粒子，
        // 营造次元能量从球体底部泄漏的视觉效果
        if (tick % 3 == 0) {
            val angle = random.nextDouble() * 2.0 * PI
            val dist = 0.2 + random.nextDouble() * 0.3
            world.addParticleClient(
                ParticleTypes.REVERSE_PORTAL,
                cx + dist * cos(angle),
                cy - 0.35,
                cz + dist * sin(angle),
                0.0, -0.02, 0.0
            )
        }
    }

    // ==================== 持久化 ====================

    override fun writeCustomData(nbt: WriteView) {
        // 保存携带的物品（通过 Codec 序列化）
        val stack = getStack()
        if (!stack.isEmpty) {
            nbt.put("Item", ItemStack.CODEC, stack)
        }

        // 保存是否已落地
        nbt.putBoolean("Landed", landed)

        // 保存实体年龄（恢复粒子动画相位）
        nbt.putInt("EntityAge", age)
    }

    override fun readCustomData(nbt: ReadView) {
        // 恢复携带的物品
        val stack = nbt.read("Item", ItemStack.CODEC)
        stack.ifPresent { setStack(it) }

        // 恢复落地状态
        landed = nbt.getBoolean("Landed", false)

        // 恢复实体年龄
        age = nbt.getInt("EntityAge", 0)
    }

    // ==================== 伤害 ====================

    /**
     * 实体不受任何伤害（无敌）
     */
    override fun damage(world: ServerWorld, source: net.minecraft.entity.damage.DamageSource, amount: Float): Boolean {
        return false
    }
}
