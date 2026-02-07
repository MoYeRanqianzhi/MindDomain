package org.mo.minddomain

import net.fabricmc.api.ModInitializer
import org.mo.minddomain.block.ModBlocks
import org.mo.minddomain.command.ModCommands
import org.mo.minddomain.component.ModComponents
import org.mo.minddomain.dimension.ModDimensions
import org.mo.minddomain.event.ModEvents
import org.mo.minddomain.item.ModItems
import org.mo.minddomain.network.ModNetworking
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * MindDomain（心灵空间）Mod 主初始化器
 *
 * 作为 Fabric Mod 的服务端/通用入口点，在游戏启动时按依赖顺序注册所有模块。
 *
 * 注册顺序（存在依赖关系，顺序不可随意调整）：
 * 1. 维度系统（区块生成器 Codec）—— 其他模块引用维度常量
 * 2. 方块注册 —— 维度管理器中需要引用方块实例
 * 3. 数据组件（DataComponent）—— 物品依赖组件类型
 * 4. 物品注册 —— 事件处理中需要引用物品实例
 * 5. 网络通信 —— 客户端快捷键和指令系统依赖网络包
 * 6. 指令系统 —— 复用网络模块的逻辑
 * 7. 事件处理 —— 监听死亡、服务器生命周期和方块保护事件
 */
class Minddomain : ModInitializer {

    companion object {
        const val MOD_ID = "minddomain"
        val logger: Logger = LoggerFactory.getLogger("MindDomain")
    }

    override fun onInitialize() {
        logger.info("MindDomain (心灵空间) 正在初始化...")

        // 1. 注册维度系统（区块生成器 Codec）
        ModDimensions.register()

        // 2. 注册方块
        ModBlocks.register()

        // 3. 注册自定义数据组件
        ModComponents.register()

        // 4. 注册物品
        ModItems.register()

        // 5. 注册网络通信
        ModNetworking.register()

        // 6. 注册指令系统
        ModCommands.register()

        // 7. 注册事件处理
        ModEvents.register()

        logger.info("MindDomain (心灵空间) 初始化完成")
    }
}
