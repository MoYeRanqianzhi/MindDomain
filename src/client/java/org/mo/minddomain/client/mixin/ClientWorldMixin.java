package org.mo.minddomain.client.mixin;

import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.Direction;
import org.mo.minddomain.dimension.ModDimensions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * ClientWorld 混入 —— 消除心灵空间维度中方块面的方向性着色
 *
 * <p>Minecraft 对方块的不同面施加不同的亮度系数以模拟环境光方向感：
 * <ul>
 *   <li>顶面 (UP): 1.0（全亮）</li>
 *   <li>底面 (DOWN): 0.5（最暗）</li>
 *   <li>南北面 (NORTH/SOUTH): 0.8</li>
 *   <li>东西面 (EAST/WEST): 0.6</li>
 * </ul>
 * 这些系数在 {@link ClientWorld#getBrightness(Direction, boolean)} 中计算，
 * 独立于光照贴图（lightmap），因此夜视效果无法改善。</p>
 *
 * <p>在心灵空间维度中，空间整体应呈现纯白色外观。
 * 此 Mixin 将所有面的亮度系数统一为 1.0，
 * 使得方块在任何角度观察都是均匀亮度，不再因面朝向不同而产生灰色阴影。</p>
 */
@Mixin(ClientWorld.class)
public abstract class ClientWorldMixin {

    /**
     * 在 getBrightness 方法入口处拦截，心灵空间维度内一律返回 1.0f。
     *
     * <p>方法签名：{@code getBrightness(Direction direction, boolean shaded) -> float}
     * <br>参数 {@code shaded} 控制是否应用面着色——当 {@code false} 时原版已返回 1.0，
     * 当 {@code true} 时按方向返回 0.5~1.0。此处无论 shaded 为何值，
     * 只要在心灵空间维度中就统一返回 1.0。</p>
     *
     * @param direction 方块面朝向
     * @param shaded    是否启用方向性着色
     * @param cir       回调信息（用于替换返回值）
     */
    @Inject(method = "getBrightness", at = @At("HEAD"), cancellable = true)
    private void minddomain$disableDirectionalShading(
            Direction direction,
            boolean shaded,
            CallbackInfoReturnable<Float> cir
    ) {
        ClientWorld self = (ClientWorld) (Object) this;
        if (ModDimensions.INSTANCE.isMindDomainWorld(self.getRegistryKey())) {
            cir.setReturnValue(1.0f);
        }
    }
}
