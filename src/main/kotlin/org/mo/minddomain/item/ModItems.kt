package org.mo.minddomain.item

import net.minecraft.item.Item
import net.minecraft.registry.Registries
import net.minecraft.registry.Registry
import net.minecraft.registry.RegistryKey
import net.minecraft.registry.RegistryKeys
import net.minecraft.util.Identifier
import net.minecraft.util.Rarity

/**
 * 物品注册模块
 *
 * 集中管理所有自定义物品的注册。
 * 物品注册在 ModInitializer.onInitialize() 中完成，
 * 确保在服务端和客户端都能正确识别自定义物品。
 */
object ModItems {

    /**
     * 空间球物品的注册 Key
     *
     * 1.21.11 要求在 Item.Settings 上预设 registryKey，
     * 以便 Item 构造函数能够推导翻译键等信息。
     */
    private val SPACE_BALL_KEY: RegistryKey<Item> = RegistryKey.of(
        RegistryKeys.ITEM,
        Identifier.of("minddomain", "space_ball")
    )

    /**
     * 空间球物品实例
     *
     * 右键使用可开辟或继承个人维度空间。
     * 物品属性：
     * - 最大堆叠数 1（每个球独立携带数据）
     * - 稀有度 UNCOMMON（绿色名称）
     */
    val SPACE_BALL: SpaceBallItem = Registry.register(
        Registries.ITEM,
        SPACE_BALL_KEY,
        SpaceBallItem(
            Item.Settings()
                .registryKey(SPACE_BALL_KEY)
                .maxCount(1)
                .rarity(Rarity.UNCOMMON)
        )
    )

    /**
     * 触发物品注册
     *
     * 通过访问静态字段触发类加载和注册。
     * 应在 ModInitializer.onInitialize() 中调用。
     */
    fun register() {
        // 类加载时静态字段即完成注册
    }
}
