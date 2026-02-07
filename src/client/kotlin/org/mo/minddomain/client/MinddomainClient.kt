package org.mo.minddomain.client

import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import org.mo.minddomain.client.keybinding.ModKeyBindings
import org.mo.minddomain.client.render.WhiteSkyRenderer
import org.mo.minddomain.network.ModNetworking

/**
 * MindDomain 客户端初始化器
 *
 * 负责注册客户端专属内容：
 * - 快捷键绑定（I 进入 / O 离开）
 * - 快捷键按下时发送网络包
 * - 心灵空间维度的天空渲染效果（纯白天空）
 *
 * 1.21.11 变更：
 * - DimensionRenderingRegistry 和 DimensionEffects 已移除
 * - 天空渲染改为通过 WorldRenderEvents + 直接 OpenGL 调用实现
 */
class MinddomainClient : ClientModInitializer {

    override fun onInitializeClient() {
        // 1. 注册快捷键
        ModKeyBindings.register()

        // 2. 注册白色天空渲染（通过 WorldRenderEvents.START_MAIN）
        WhiteSkyRenderer.register()

        // 3. 注册客户端 tick 事件（处理按键输入）
        registerClientTick()
    }

    /**
     * 注册客户端 tick 事件处理
     *
     * 每个客户端 tick 检测快捷键按下并发送对应网络包。
     */
    private fun registerClientTick() {
        ClientTickEvents.END_CLIENT_TICK.register { _ ->
            handleKeyBindings()
        }
    }

    /**
     * 处理快捷键输入
     *
     * 检测 I 键和 O 键是否被按下，发送对应的 C2S 网络包到服务端。
     * wasPressed() 会消费按键事件，避免重复触发。
     */
    private fun handleKeyBindings() {
        // I 键：进入空间
        while (ModKeyBindings.ENTER_SPACE.wasPressed()) {
            ClientPlayNetworking.send(ModNetworking.EnterSpaceC2SPayload())
        }

        // O 键：离开空间
        while (ModKeyBindings.LEAVE_SPACE.wasPressed()) {
            ClientPlayNetworking.send(ModNetworking.LeaveSpaceC2SPayload())
        }
    }
}
