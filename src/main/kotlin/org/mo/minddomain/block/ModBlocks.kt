package org.mo.minddomain.block

import net.minecraft.block.AbstractBlock
import net.minecraft.block.Block
import net.minecraft.block.piston.PistonBehavior
import net.minecraft.registry.Registries
import net.minecraft.registry.Registry
import net.minecraft.registry.RegistryKey
import net.minecraft.registry.RegistryKeys
import net.minecraft.util.Identifier

/**
 * 方块注册模块
 *
 * 集中管理所有自定义方块的注册。
 * 方块注册在 ModInitializer.onInitialize() 中完成。
 */
object ModBlocks {

    /**
     * 白色屏障方块的注册 Key
     *
     * 1.21.11 要求在 AbstractBlock.Settings 上预设 registryKey。
     */
    private val WHITE_BARRIER_KEY: RegistryKey<Block> = RegistryKey.of(
        RegistryKeys.BLOCK,
        Identifier.of("minddomain", "white_barrier")
    )

    /**
     * 白色屏障方块实例
     *
     * 用于心灵空间的所有边界（地面、天花板、墙壁）。
     * 外观为纯白色，与白色天空和白色虚空生物群系无缝融合。
     *
     * 方块属性：
     * - 硬度 -1.0（生存模式不可破坏，与基岩相同）
     * - 爆炸抗性 3600000（不可被爆炸破坏）
     * - 无掉落物
     * - 不可被活塞推动
     * - 不允许生物生成
     *
     * 注意：创造模式的破坏保护通过 PlayerBlockBreakEvents.BEFORE 事件实现，
     * 详见 ModEvents 中的 registerBlockBreakProtection()。
     */
    val WHITE_BARRIER: Block = Registry.register(
        Registries.BLOCK,
        WHITE_BARRIER_KEY,
        Block(
            AbstractBlock.Settings.create()
                .registryKey(WHITE_BARRIER_KEY)
                .strength(-1.0F, 3600000.0F)
                .dropsNothing()
                .pistonBehavior(PistonBehavior.BLOCK)
                .allowsSpawning { _, _, _, _ -> false }
        )
    )

    /**
     * 触发方块注册
     *
     * 通过访问静态字段触发类加载和注册。
     * 应在 ModInitializer.onInitialize() 中调用，
     * 且必须在物品注册之前调用（若方块需要对应的 BlockItem）。
     */
    fun register() {
        // 类加载时静态字段即完成注册
    }
}
