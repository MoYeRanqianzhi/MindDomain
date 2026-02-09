package org.mo.minddomain.client.mixin;

import net.minecraft.client.render.Camera;
import net.minecraft.client.render.fog.FogRenderer;
import net.minecraft.client.world.ClientWorld;
import org.joml.Vector4f;
import org.mo.minddomain.dimension.ModDimensions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * FogRenderer 混入 —— 强制心灵空间维度的雾气颜色为纯白
 *
 * <p>Minecraft 1.21.11 的 FogRenderer.getFogColor() 方法根据
 * 生物群系 fog_color、时间、天气等因素计算雾气颜色并返回 Vector4f。
 * 即使生物群系 fog_color 已设为白色，渲染管线的混合计算仍可能导致结果偏灰。</p>
 *
 * <p>此 Mixin 在 getFogColor() 返回时，检测到心灵空间维度时
 * 将返回值覆盖为纯白 (1.0, 1.0, 1.0, 1.0)，确保雪白效果。
 * 由于 getFogColor 的返回值同时用于背景清除和 GPU 雾气缓冲区写入，
 * 在此处拦截可以同时确保天空背景和方块远景都呈现纯白色。</p>
 */
@Mixin(FogRenderer.class)
public abstract class FogRendererMixin {

    /**
     * 在雾气颜色计算完毕后，如果当前处于心灵空间维度，强制返回纯白色。
     *
     * <p>getFogColor 的第三个参数为 ClientWorld，可直接用于判断维度，
     * 无需额外获取 MinecraftClient 实例。</p>
     *
     * @param camera       相机对象
     * @param tickDelta    帧间时间增量
     * @param world        客户端世界实例
     * @param viewDistance 渲染距离
     * @param skyDarkness  天空暗度因子
     * @param cir          回调信息（可取消/替换返回值）
     */
    @Inject(method = "getFogColor", at = @At("RETURN"), cancellable = true)
    private void minddomain$forceWhiteFogColor(
            Camera camera,
            float tickDelta,
            ClientWorld world,
            int viewDistance,
            float skyDarkness,
            CallbackInfoReturnable<Vector4f> cir
    ) {
        if (ModDimensions.INSTANCE.isMindDomainWorld(world.getRegistryKey())) {
            cir.setReturnValue(new Vector4f(1.0f, 1.0f, 1.0f, 1.0f));
        }
    }
}
