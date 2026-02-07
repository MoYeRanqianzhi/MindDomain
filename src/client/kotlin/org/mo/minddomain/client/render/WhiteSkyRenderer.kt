package org.mo.minddomain.client.render

import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents
import net.minecraft.client.MinecraftClient
import org.lwjgl.opengl.GL11
import org.mo.minddomain.dimension.ModDimensions

/**
 * 纯白天空渲染器
 *
 * 将心灵空间维度的天空渲染为纯白色（RGB 255, 255, 255）。
 *
 * 1.21.11 变更：
 * - DimensionRenderingRegistry 和 DimensionEffects 已被移除
 * - 改用 Fabric WorldRenderEvents.START_MAIN 事件 + 直接 OpenGL 调用实现
 *
 * 渲染原理：
 * 在主渲染阶段开始时（天空已渲染完毕、地形尚未渲染之前），
 * 使用 glClear 将颜色缓冲区清除为纯白色。
 * 地形、实体和 UI 在之后渲染时会通过深度测试正确覆盖白色背景。
 */
object WhiteSkyRenderer {

    /**
     * 注册白色天空渲染事件处理器
     *
     * 应在 ClientModInitializer.onInitializeClient() 中调用。
     */
    fun register() {
        WorldRenderEvents.START_MAIN.register { _ ->
            val client = MinecraftClient.getInstance()
            val worldKey = client.world?.registryKey ?: return@register
            if (ModDimensions.isMindDomainWorld(worldKey)) {
                // 设置清除颜色为纯白
                GL11.glClearColor(1.0f, 1.0f, 1.0f, 1.0f)
                // 仅清除颜色缓冲区（保留深度缓冲区，确保后续渲染的深度测试正常）
                GL11.glClear(GL11.GL_COLOR_BUFFER_BIT)
            }
        }
    }
}
