package org.mo.minddomain.client.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.world.ClientWorld;
import org.mo.minddomain.dimension.ModDimensions;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * LightmapTextureManager 混入 —— 强制心灵空间维度使用全白光照贴图
 *
 * <p>问题根因：1.21.11 的光照贴图在 GPU 端通过 {@code core/lightmap} 着色器计算，
 * 其中包含 {@code mix(color, vec3(0.75), 0.04)} 操作（出现两次），
 * 始终将光照颜色拉向灰色 (0.75)，导致即使 ambient_light=1.0 且天空光照满级，
 * 最终光照贴图输出也无法达到纯白 (1.0)，方块表面始终带有灰色调。</p>
 *
 * <p>解决方案：在心灵空间维度中，跳过 GPU 着色器计算，
 * 直接将 16×16 光照贴图纹理清除为纯白（0xFFFFFFFF）。
 * 这使得所有方块光照级别×天空光照级别组合都输出最大亮度，
 * 确保空间内的方块表面不会因光照计算而变灰。</p>
 *
 * <p>注意：光照贴图仅影响亮度计算，不影响方块纹理本身的颜色。
 * 草方块等使用灰度纹理 + 生物群系染色的方块，
 * 其灰色来源于纹理本身，需配合其他方案解决。</p>
 */
@Mixin(LightmapTextureManager.class)
public abstract class LightmapTextureManagerMixin {

    /**
     * 光照贴图 GPU 纹理（16×16，RGBA8 格式）
     */
    @Shadow
    @Final
    private GpuTexture glTexture;

    /**
     * 脏标记：为 true 时 update() 才会重新计算光照贴图。
     * 每次 tick() 调用时设为 true。
     */
    @Shadow
    private boolean dirty;

    /**
     * 在 update() 方法入口处拦截光照贴图计算。
     *
     * <p>当玩家处于心灵空间维度时：
     * <ol>
     *   <li>重置脏标记，避免后续帧重复执行</li>
     *   <li>将光照贴图纹理清除为纯白（所有光照级别 = 最大亮度）</li>
     *   <li>取消原始方法执行，跳过 GPU 着色器计算</li>
     * </ol>
     * 非心灵空间维度不受影响，继续使用原版光照计算。</p>
     *
     * @param tickProgress 当前 tick 进度（用于插值，此处未使用）
     * @param ci           回调信息（用于取消方法执行）
     */
    @Inject(method = "update", at = @At("HEAD"), cancellable = true)
    private void minddomain$forceWhiteLightmap(float tickProgress, CallbackInfo ci) {
        if (!this.dirty) {
            return;
        }

        ClientWorld world = MinecraftClient.getInstance().world;
        if (world != null && ModDimensions.INSTANCE.isMindDomainWorld(world.getRegistryKey())) {
            this.dirty = false;

            // 将光照贴图清除为纯白 (0xFFFFFFFF = RGBA 全 255)
            // -1 的 int 表示为 0xFFFFFFFF，即所有颜色通道均为最大值
            RenderSystem.getDevice().createCommandEncoder()
                    .clearColorTexture(this.glTexture, -1);

            ci.cancel();
        }
    }
}
