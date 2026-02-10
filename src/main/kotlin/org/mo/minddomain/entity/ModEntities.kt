package org.mo.minddomain.entity

import net.minecraft.entity.EntityType
import net.minecraft.entity.SpawnGroup
import net.minecraft.registry.Registries
import net.minecraft.registry.Registry
import net.minecraft.registry.RegistryKey
import net.minecraft.registry.RegistryKeys
import net.minecraft.util.Identifier

/**
 * 实体类型注册模块
 *
 * 集中管理所有自定义实体类型的注册。
 * 当前注册的实体：
 * - SpaceBallEntity：玩家死亡后掉落的空间球实体，带浮动动画和粒子效果
 *
 * 实体注册在 ModInitializer.onInitialize() 中完成，
 * 确保在服务端和客户端都能正确识别自定义实体。
 */
object ModEntities {

    /**
     * 空间球实体的注册 Key
     *
     * 1.21.11 要求在 EntityType.Builder.build() 时传入 registryKey。
     */
    private val SPACE_BALL_ENTITY_KEY: RegistryKey<EntityType<*>> = RegistryKey.of(
        RegistryKeys.ENTITY_TYPE,
        Identifier.of("minddomain", "space_ball_entity")
    )

    /**
     * 空间球实体类型
     *
     * 玩家死亡后在死亡位置生成的自定义实体，替代普通掉落物。
     * 实体属性：
     * - 碰撞箱 0.5×0.5（较小，不阻挡移动）
     * - SpawnGroup.MISC（杂项类实体，不计入生物上限）
     * - 每秒同步一次位置（trackingTickInterval=20），因为实体基本静止
     * - 最大追踪范围 16 区块
     */
    val SPACE_BALL_ENTITY: EntityType<SpaceBallEntity> = Registry.register(
        Registries.ENTITY_TYPE,
        SPACE_BALL_ENTITY_KEY,
        EntityType.Builder.create(::SpaceBallEntity, SpawnGroup.MISC)
            .dimensions(0.5f, 0.5f)
            .maxTrackingRange(16)
            .trackingTickInterval(20)
            .build(SPACE_BALL_ENTITY_KEY)
    )

    /**
     * 触发实体类型注册
     *
     * 通过访问静态字段触发类加载和注册。
     * 应在 ModInitializer.onInitialize() 中、物品注册之后调用。
     */
    fun register() {
        // 类加载时静态字段即完成注册
    }
}
