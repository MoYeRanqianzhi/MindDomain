package org.mo.minddomain.client.keybinding

import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper
import net.minecraft.client.option.KeyBinding
import net.minecraft.client.util.InputUtil
import net.minecraft.util.Identifier
import org.lwjgl.glfw.GLFW

/**
 * 快捷键绑定模块
 *
 * 注册心灵空间的快捷键，均可在游戏设置 → 按键绑定中自定义修改。
 *
 * 默认按键：
 * - I 键：进入个人空间
 * - O 键：离开个人空间
 *
 * 所有按键归属于 "MindDomain" 分类，在按键绑定界面中统一显示。
 *
 * 1.21.11 变更：KeyBinding 构造函数的分类参数从 String 改为 KeyBinding.Category 记录类型，
 * 通过 Identifier 创建自定义分类。
 */
object ModKeyBindings {

    /** 按键分类（1.21.11 使用 KeyBinding.Category 记录替代了旧的字符串分类） */
    private val CATEGORY = KeyBinding.Category(Identifier.of("minddomain", "minddomain"))

    /**
     * 进入空间快捷键（默认 I 键）
     *
     * 按下后向服务端发送 EnterSpaceC2SPayload 网络包，
     * 请求传送至个人空间。
     */
    val ENTER_SPACE: KeyBinding = KeyBindingHelper.registerKeyBinding(
        KeyBinding(
            "key.minddomain.enter_space",
            InputUtil.Type.KEYSYM,
            GLFW.GLFW_KEY_I,
            CATEGORY
        )
    )

    /**
     * 离开空间快捷键（默认 O 键）
     *
     * 按下后向服务端发送 LeaveSpaceC2SPayload 网络包，
     * 请求离开空间并返回之前的位置。
     */
    val LEAVE_SPACE: KeyBinding = KeyBindingHelper.registerKeyBinding(
        KeyBinding(
            "key.minddomain.leave_space",
            InputUtil.Type.KEYSYM,
            GLFW.GLFW_KEY_O,
            CATEGORY
        )
    )

    /**
     * 触发按键注册
     *
     * 通过访问静态字段触发类加载和注册。
     * 应在 ClientModInitializer.onInitializeClient() 中调用。
     */
    fun register() {
        // 类加载时静态字段即完成注册
    }
}
