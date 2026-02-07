package org.mo.minddomain.dimension

import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.block.BlockState
import net.minecraft.registry.entry.RegistryEntry
import net.minecraft.util.math.BlockPos
import net.minecraft.world.ChunkRegion
import net.minecraft.world.HeightLimitView
import net.minecraft.world.Heightmap
import net.minecraft.world.biome.Biome
import net.minecraft.world.biome.source.BiomeAccess
import net.minecraft.world.biome.source.BiomeSource
import net.minecraft.world.chunk.Chunk
import net.minecraft.world.gen.StructureAccessor
import net.minecraft.world.gen.chunk.Blender
import net.minecraft.world.gen.chunk.ChunkGenerator
import net.minecraft.world.gen.chunk.VerticalBlockSample
import net.minecraft.world.gen.noise.NoiseConfig
import java.util.concurrent.CompletableFuture

/**
 * 心灵空间自定义区块生成器
 *
 * 生成完全的虚空世界——所有区块均为空气，不包含任何地形、矿物或结构。
 * 落脚平台（barrier 方块）和 Y 轴边界由 DynamicWorldManager 在维度创建后单独放置，
 * 而非在区块生成阶段处理，这样可以根据空间大小动态调整边界位置。
 *
 * 使用 FixedBiomeSource 固定为自定义的白色虚空生物群系（white_void），
 * 该生物群系的天空颜色和雾气颜色均为纯白，配合客户端天空渲染器实现全白空间效果。
 *
 * @param biomeSource 生物群系来源，应为 FixedBiomeSource（白色虚空生物群系）
 */
class MindDomainChunkGenerator(
    biomeSource: BiomeSource
) : ChunkGenerator(biomeSource) {

    companion object {
        /**
         * 序列化 Codec
         *
         * 用于将区块生成器实例编码为 JSON（保存到维度配置）
         * 和从 JSON 解码回实例（加载维度时恢复）。
         * 仅需编码 biomeSource 字段，因为该生成器没有其他可配置参数。
         */
        val CODEC: MapCodec<MindDomainChunkGenerator> = RecordCodecBuilder.mapCodec { instance ->
            instance.group(
                BiomeSource.CODEC.fieldOf("biome_source").forGetter { it.biomeSource }
            ).apply(instance, ::MindDomainChunkGenerator)
        }
    }

    /**
     * 返回此区块生成器的序列化 Codec
     */
    override fun getCodec(): MapCodec<out ChunkGenerator> = CODEC

    /**
     * 构建地表 —— 虚空世界无需任何地表处理
     */
    override fun buildSurface(
        region: ChunkRegion,
        structures: StructureAccessor,
        noiseConfig: NoiseConfig,
        chunk: Chunk
    ) {
        // 虚空世界：不生成任何地表方块
    }

    /**
     * 填充噪声（主要地形生成） —— 直接返回空区块
     *
     * 虚空世界不需要任何地形，所有方块保持默认的空气状态。
     */
    override fun populateNoise(
        blender: Blender,
        noiseConfig: NoiseConfig,
        structures: StructureAccessor,
        chunk: Chunk
    ): CompletableFuture<Chunk> {
        return CompletableFuture.completedFuture(chunk)
    }

    /**
     * 洞穴雕刻 —— 虚空世界无需洞穴
     */
    override fun carve(
        chunkRegion: ChunkRegion,
        seed: Long,
        noiseConfig: NoiseConfig,
        biomeAccess: BiomeAccess,
        structures: StructureAccessor,
        chunk: Chunk
    ) {
        // 虚空世界：不生成洞穴
    }

    /**
     * 生成生物 —— 虚空世界无生物生成
     */
    override fun populateEntities(region: ChunkRegion) {
        // 虚空世界：不生成任何生物
    }

    /**
     * 获取指定坐标的地形高度
     *
     * 虚空世界没有地形，始终返回最低 Y 坐标。
     * 此方法被结构生成和世界访问接口调用。
     */
    override fun getHeight(
        x: Int,
        z: Int,
        heightmap: Heightmap.Type,
        world: HeightLimitView,
        noiseConfig: NoiseConfig
    ): Int {
        return world.bottomY
    }

    /**
     * 获取指定列的方块样本
     *
     * 返回一个全空气的垂直方块样本，用于结构放置检测等。
     */
    override fun getColumnSample(
        x: Int,
        z: Int,
        world: HeightLimitView,
        noiseConfig: NoiseConfig
    ): VerticalBlockSample {
        return VerticalBlockSample(world.bottomY, arrayOf<BlockState>())
    }

    /**
     * 获取实体生成列表 —— 虚空世界无生物生成
     */
    override fun getEntitySpawnList(
        biome: RegistryEntry<Biome>,
        structures: StructureAccessor,
        group: net.minecraft.entity.SpawnGroup,
        pos: BlockPos
    ): net.minecraft.util.collection.Pool<net.minecraft.world.biome.SpawnSettings.SpawnEntry> {
        return net.minecraft.util.collection.Pool.empty()
    }

    /**
     * 世界总高度（从 bottomY 到 topY）
     *
     * 返回 384（标准主世界高度），虚空世界不限制实际生成高度。
     */
    override fun getWorldHeight(): Int = 384

    /**
     * 海平面高度 —— 虚空世界无海平面
     */
    override fun getSeaLevel(): Int = -63

    /**
     * 世界最低 Y 坐标
     */
    override fun getMinimumY(): Int = -64

    /**
     * 调试 HUD 文本 —— 虚空世界无额外调试信息
     */
    override fun appendDebugHudText(text: MutableList<String>, noiseConfig: NoiseConfig, pos: BlockPos) {
        // 虚空世界：无额外调试信息
    }
}
