package org.mo.minddomain.dimension

import net.minecraft.registry.Registries
import net.minecraft.registry.Registry
import net.minecraft.registry.RegistryKey
import net.minecraft.registry.RegistryKeys
import net.minecraft.util.Identifier
import net.minecraft.world.World
import net.minecraft.world.dimension.DimensionType

/**
 * 维度系统注册模块
 *
 * 负责注册心灵空间所需的维度相关内容：
 * - 自定义区块生成器的 Codec（用于序列化/反序列化）
 * - 维度类型的 RegistryKey（引用 JSON 定义的维度类型）
 *
 * 维度类型定义在 data/minddomain/dimension_type/mind_domain.json，
 * 所有玩家的独立空间维度共享同一个维度类型配置。
 */
object ModDimensions {

    /** Mod 的命名空间标识符 */
    const val MOD_ID = "minddomain"

    /**
     * 维度类型的 RegistryKey
     *
     * 指向 data/minddomain/dimension_type/mind_domain.json 中定义的维度类型。
     * 该维度类型配置了固定时间（正午）、最大环境光、禁止怪物生成等属性。
     */
    val DIMENSION_TYPE_KEY: RegistryKey<DimensionType> = RegistryKey.of(
        RegistryKeys.DIMENSION_TYPE,
        Identifier.of(MOD_ID, "mind_domain")
    )

    /**
     * 根据空间 ID 生成对应的维度 RegistryKey
     *
     * 每个玩家的空间对应一个独立维度，维度标识符格式为 minddomain:space_<id>。
     * 空间 ID 由 MindDomainState 递增分配，确保全局唯一。
     *
     * @param spaceId 空间的唯一整数标识符
     * @return 对应维度的 RegistryKey
     */
    fun getWorldKey(spaceId: Int): RegistryKey<World> {
        return RegistryKey.of(
            RegistryKeys.WORLD,
            Identifier.of(MOD_ID, "space_$spaceId")
        )
    }

    /**
     * 判断一个维度 Key 是否属于心灵空间
     *
     * 通过检查命名空间是否为 "minddomain" 且路径以 "space_" 开头来判断。
     * 用于客户端天空渲染、事件过滤等场景。
     *
     * @param worldKey 要检查的维度 RegistryKey
     * @return 是否为心灵空间维度
     */
    fun isMindDomainWorld(worldKey: RegistryKey<World>): Boolean {
        val id = worldKey.value
        return id.namespace == MOD_ID && id.path.startsWith("space_")
    }

    /**
     * 注册自定义区块生成器的 Codec
     *
     * 区块生成器的 Codec 必须在服务器启动前注册到 Registries.CHUNK_GENERATOR，
     * 这样维度系统才能正确地序列化和反序列化我们的自定义区块生成器。
     */
    fun register() {
        Registry.register(
            Registries.CHUNK_GENERATOR,
            Identifier.of(MOD_ID, "void"),
            MindDomainChunkGenerator.CODEC
        )
    }
}
