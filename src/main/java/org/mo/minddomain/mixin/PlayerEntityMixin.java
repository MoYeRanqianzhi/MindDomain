package org.mo.minddomain.mixin;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import org.mo.minddomain.event.ModEvents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * PlayerEntity Mixin - 经验获取拦截
 *
 * <p>注入 {@link PlayerEntity#addExperience(int)} 方法，捕获玩家通过正常游戏获取的经验
 * （杀怪、挖矿、熔炼等），同时将这些经验计入空间等级系统。</p>
 *
 * <p>关键设计：
 * <ul>
 *   <li>{@code addExperience(int)} 仅在玩家获得经验时调用（正值参数）</li>
 *   <li>附魔消耗走 {@code addExperienceLevels(-n)} 路径，不会触发此注入</li>
 *   <li>因此空间等级的经验只增不减，与原版等级完全隔离</li>
 *   <li>仅在服务端处理（检查 {@link ServerPlayerEntity}），客户端调用被忽略</li>
 * </ul>
 * </p>
 *
 * @see ModEvents#onPlayerGainExperience(ServerPlayerEntity, int)
 */
@Mixin(PlayerEntity.class)
public abstract class PlayerEntityMixin {

    /**
     * 注入到 addExperience 方法头部，捕获经验获取事件
     *
     * <p>在原版经验处理逻辑执行之前（HEAD），将经验量转发给空间等级系统。
     * 这确保无论原版经验如何分配，空间等级都能获得完整的经验量。</p>
     *
     * @param experience 获取的经验量（由调用方保证为正值）
     * @param ci 回调信息（不取消原版逻辑）
     */
    @Inject(method = "addExperience", at = @At("HEAD"))
    private void onAddExperience(int experience, CallbackInfo ci) {
        // 仅在服务端处理，避免客户端重复计算
        //noinspection ConstantValue
        if ((Object) this instanceof ServerPlayerEntity serverPlayer) {
            // 仅处理正值经验（防御性检查）
            if (experience > 0) {
                ModEvents.INSTANCE.onPlayerGainExperience(serverPlayer, experience);
            }
        }
    }
}
