package org.mo.minddomain.component

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.component.ComponentType
import net.minecraft.network.codec.PacketCodec
import net.minecraft.registry.Registries
import net.minecraft.registry.Registry
import net.minecraft.util.Identifier

/**
 * 自定义数据组件注册模块
 *
 * 1.21 版本使用 DataComponent 系统替代了旧版 NBT 标签来存储物品附加数据。
 * 本模块注册空间球物品所需的自定义数据组件类型。
 */
object ModComponents {

    /**
     * 空间球数据组件类型
     *
     * 存储空间球的核心属性：
     * - size: 空间边长（即空间球品质，如 16 表示 16×16×16）
     * - spaceId: 关联的空间 ID（-1 表示全新空间球，>= 0 表示关联已有空间）
     * - ownerName: 原始拥有者名称（空字符串表示全新球，死亡掉落时写入玩家名）
     */
    val SPACE_BALL_DATA: ComponentType<SpaceBallData> = Registry.register(
        Registries.DATA_COMPONENT_TYPE,
        Identifier.of("minddomain", "space_ball_data"),
        ComponentType.builder<SpaceBallData>()
            .codec(SpaceBallData.CODEC)
            .packetCodec(SpaceBallData.PACKET_CODEC)
            .build()
    )

    /**
     * 触发组件注册
     *
     * 通过访问 SPACE_BALL_DATA 字段触发其静态初始化。
     * 应在 ModInitializer.onInitialize() 中调用。
     */
    fun register() {
        // 类加载时静态字段即完成注册，此方法确保类被加载
    }
}

/**
 * 空间球数据
 *
 * 作为 DataComponent 附加在空间球物品上，存储空间相关信息。
 *
 * @param size 空间边长，直接作为物品品质显示（如 "16×16×16 空间球"）
 * @param spaceId 关联的已有空间 ID，-1 表示全新空间球（使用时创建新空间）
 * @param ownerName 原始拥有者名称，空字符串表示全新球
 */
data class SpaceBallData(
    val size: Int,
    val spaceId: Int = -1,
    val ownerName: String = ""
) {
    /** 是否关联了已有空间（用于继承机制） */
    val hasExistingSpace: Boolean get() = spaceId >= 0

    /** 是否是死亡掉落的空间球（拥有者名称非空） */
    val isDroppedBall: Boolean get() = ownerName.isNotEmpty()

    companion object {
        /**
         * NBT/JSON 序列化 Codec
         *
         * 用于将空间球数据保存到存档和从存档加载。
         */
        val CODEC: Codec<SpaceBallData> = RecordCodecBuilder.create { instance ->
            instance.group(
                Codec.INT.fieldOf("size").forGetter { it.size },
                Codec.INT.optionalFieldOf("space_id", -1).forGetter { it.spaceId },
                Codec.STRING.optionalFieldOf("owner_name", "").forGetter { it.ownerName }
            ).apply(instance, ::SpaceBallData)
        }

        /**
         * 网络传输 Codec
         *
         * 用于在客户端和服务端之间同步空间球数据。
         */
        val PACKET_CODEC: PacketCodec<net.minecraft.network.RegistryByteBuf, SpaceBallData> =
            PacketCodec.of(
                { data, buf ->
                    buf.writeInt(data.size)
                    buf.writeInt(data.spaceId)
                    buf.writeString(data.ownerName)
                },
                { buf ->
                    SpaceBallData(
                        size = buf.readInt(),
                        spaceId = buf.readInt(),
                        ownerName = buf.readString()
                    )
                }
            )
    }
}
