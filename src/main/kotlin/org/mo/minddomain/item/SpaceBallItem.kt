package org.mo.minddomain.item

import net.minecraft.component.type.TooltipDisplayComponent
import net.minecraft.entity.player.PlayerEntity
import net.minecraft.item.Item
import net.minecraft.item.ItemStack
import net.minecraft.item.tooltip.TooltipType
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.text.Text
import net.minecraft.util.ActionResult
import net.minecraft.util.Formatting
import net.minecraft.util.Hand
import net.minecraft.world.World
import org.mo.minddomain.advancement.ModAdvancements
import org.mo.minddomain.component.ModComponents
import org.mo.minddomain.component.SpaceBallData
import org.mo.minddomain.data.MindDomainState
import org.mo.minddomain.dimension.DynamicWorldManager
import java.util.function.Consumer

/**
 * 空间球物品
 *
 * 玩家的核心道具，右键使用可开辟或继承个人维度空间。
 *
 * 使用规则：
 * - 玩家当前没有绑定空间时，右键使用消耗空间球并创建/继承空间
 * - 玩家已有绑定空间时，提示无法使用，不消耗物品
 * - 空间球品质直接以大小表示（如 "16×16×16 空间球"）
 * - 死亡掉落的空间球附带原拥有者名称和已有空间数据
 *
 * @param settings 物品属性配置
 */
class SpaceBallItem(settings: Settings) : Item(settings) {

    /**
     * 右键使用空间球
     *
     * 仅在服务端处理逻辑：
     * 1. 读取空间球的 DataComponent 获取大小和关联空间信息
     * 2. 检查玩家是否已有空间
     * 3. 创建新空间或继承已有空间
     * 4. 绑定空间到玩家，消耗物品
     */
    override fun use(world: World, user: PlayerEntity, hand: Hand): ActionResult {
        val stack = user.getStackInHand(hand)

        // 仅在服务端处理
        if (world.isClient) {
            return ActionResult.SUCCESS
        }

        val player = user as ServerPlayerEntity
        val server = (player.entityWorld as ServerWorld).server!!
        val state = MindDomainState.get(server)

        // 检查玩家是否已拥有空间
        if (state.getPlayerSpaceId(player.uuid) != null) {
            player.sendMessage(
                Text.translatable("message.minddomain.already_has_space")
                    .formatted(Formatting.RED),
                true // actionbar 显示
            )
            return ActionResult.FAIL
        }

        // 读取空间球数据
        val data = stack.get(ModComponents.SPACE_BALL_DATA) ?: SpaceBallData(size = 16)
        val size = data.size

        val spaceId: Int
        if (data.hasExistingSpace) {
            // 继承已有空间：使用空间球中记录的 spaceId
            spaceId = data.spaceId
            // 确保空间信息存在（可能是从其他存档继承的情况）
            if (state.getSpaceInfo(spaceId) == null) {
                player.sendMessage(
                    Text.translatable("message.minddomain.invalid_space_ball")
                        .formatted(Formatting.RED),
                    true
                )
                return ActionResult.FAIL
            }
        } else {
            // 创建全新空间
            spaceId = state.allocateSpace(size)
        }

        // 创建或获取对应的维度
        val spaceInfo = state.getSpaceInfo(spaceId)!!
        val spaceWorld = DynamicWorldManager.getOrCreateWorld(server, spaceId, spaceInfo.size)
        if (spaceWorld == null) {
            player.sendMessage(
                Text.translatable("message.minddomain.create_failed")
                    .formatted(Formatting.RED),
                true
            )
            return ActionResult.FAIL
        }

        // 绑定空间到玩家
        state.bindSpace(player.uuid, spaceId)

        // 消耗物品（非创造模式）
        if (!player.isCreative) {
            stack.decrement(1)
        }

        // 提示成功
        player.sendMessage(
            Text.translatable(
                "message.minddomain.space_created",
                spaceInfo.size, spaceInfo.size, spaceInfo.size
            ).formatted(Formatting.GREEN),
            true
        )

        // 授予"开窍"成就
        ModAdvancements.grant(player, ModAdvancements.AWAKENING)
        // 继承大空间时检查大小里程碑
        ModAdvancements.checkSizeMilestones(player, spaceInfo.size)

        return ActionResult.SUCCESS
    }

    /**
     * 附加物品 Tooltip 信息
     *
     * 显示空间球的大小信息，对于死亡掉落的空间球还显示原拥有者名称。
     */
    @Deprecated("Overrides deprecated member in Item")
    override fun appendTooltip(
        stack: ItemStack,
        context: TooltipContext,
        displayComponent: TooltipDisplayComponent,
        textConsumer: Consumer<Text>,
        type: TooltipType
    ) {
        val data = stack.get(ModComponents.SPACE_BALL_DATA) ?: return

        // 显示空间大小
        textConsumer.accept(
            Text.translatable("tooltip.minddomain.space_size", data.size, data.size, data.size)
                .formatted(Formatting.GRAY)
        )

        // 如果是死亡掉落的球，显示原拥有者
        if (data.isDroppedBall) {
            textConsumer.accept(
                Text.translatable("tooltip.minddomain.original_owner", data.ownerName)
                    .formatted(Formatting.AQUA)
            )
            textConsumer.accept(
                Text.translatable("tooltip.minddomain.has_existing_space")
                    .formatted(Formatting.YELLOW)
            )
        }
    }

    /**
     * 获取物品的显示名称
     *
     * 死亡掉落的空间球显示为 "<玩家名>的空间"，
     * 普通空间球显示为 "<大小>×<大小>×<大小> 空间球"。
     */
    override fun getName(stack: ItemStack): Text {
        val data = stack.get(ModComponents.SPACE_BALL_DATA)

        return if (data != null && data.isDroppedBall) {
            // 死亡掉落的球：显示 "xx的空间"
            Text.translatable("item.minddomain.space_ball.named", data.ownerName)
        } else if (data != null) {
            // 普通球：显示 "NxNxN 空间球"
            Text.translatable("item.minddomain.space_ball.sized", data.size, data.size, data.size)
        } else {
            // 无数据的默认名称
            Text.translatable("item.minddomain.space_ball")
        }
    }
}
