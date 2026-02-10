package org.mo.minddomain.client.render

import net.minecraft.client.model.ModelData
import net.minecraft.client.model.ModelPartBuilder
import net.minecraft.client.model.ModelTransform
import net.minecraft.client.model.TexturedModelData
import net.minecraft.client.render.OverlayTexture
import net.minecraft.client.render.RenderLayers
import net.minecraft.client.render.VertexConsumer
import net.minecraft.client.render.command.OrderedRenderCommandQueue
import net.minecraft.client.render.entity.EntityRenderer
import net.minecraft.client.render.entity.EntityRendererFactory
import net.minecraft.client.render.entity.model.EntityModel
import net.minecraft.client.render.entity.state.EntityRenderState
import net.minecraft.client.render.state.CameraRenderState
import net.minecraft.client.util.math.MatrixStack
import net.minecraft.util.Identifier
import net.minecraft.util.math.RotationAxis
import org.mo.minddomain.entity.SpaceBallEntity
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * 空间球实体渲染器
 *
 * 使用编程式 UV 球体网格渲染 [SpaceBallEntity]，替代传统的 ModelPart 方块堆叠方式。
 * 通过三角函数生成高精度球面网格（32 扇区 × 16 层叠 = 512 个四边面），
 * 确保球体在任意角度观察都呈现平滑圆润的外观。
 *
 * 渲染由两层球体叠加实现：
 * - **内层核心**：较小的不透明球体，使用实体固体渲染层，呈现微蓝白色调
 * - **外层光晕**：稍大的半透明自发光球体，营造能量外壳的视觉效果
 *
 * 动画效果：
 * - Y 轴缓慢旋转：约 12 秒一圈
 * - 正弦浮动：以 3 秒为周期上下浮动 0.15 格
 *
 * 使用 MC 1.21.11 的 [OrderedRenderCommandQueue.submitCustom] API
 * 提交自定义顶点数据，绕过传统的 ModelPart 限制。
 *
 * **发光轮廓（Outline）支持**：
 * `submitCustom` 的 `CustomCommand` 不包含 `outlineColor` 字段，
 * 无法参与 MC 的 `OutlineVertexConsumerProvider` 轮廓渲染。
 * 因此额外通过 `submitModel` 提交一组近似球形的 `ModelPart`，
 * 由 5 个不同朝向的立方体联合构成，使轮廓从任意角度观察都近似圆形。
 * `ModelCommandRenderer`（先于 `CustomCommandRenderer` 执行）将此模型写入深度缓冲，
 * 随后球体 `submitCustom` 渲染时因深度更浅而覆盖大部分立方体区域。
 * `ModelCommand` 的 `outlineColor` 字段触发独立的轮廓帧缓冲写入，
 * 使实体在被方块遮挡时仍可看到近似球形的发光边框。
 */
class SpaceBallEntityRenderer(
    context: EntityRendererFactory.Context
) : EntityRenderer<SpaceBallEntity, SpaceBallEntityRenderState>(context) {

    companion object {
        // ==================== 球体网格参数 ====================

        /** 水平细分数（经线方向），值越大球越圆 */
        private const val SECTORS = 32

        /** 垂直细分数（纬线方向），值越大球越圆 */
        private const val STACKS = 16

        /** 内层核心球体半径（格） */
        private const val INNER_RADIUS = 0.56f

        /** 外层光晕球体半径（格） */
        private const val OUTER_RADIUS = 0.70f

        // ==================== 动画参数 ====================

        /**
         * 旋转速度：每 tick 旋转的角度（度）
         * 12 秒一圈 = 360° / (12 × 20 tick) = 1.5°/tick
         */
        private const val ROTATION_SPEED = 1.5f

        /** 浮动振幅（格） */
        private const val FLOAT_AMPLITUDE = 0.15f

        /**
         * 浮动角频率（弧度/tick）
         * 周期 3 秒 = 60 tick → ω = 2π / 60 ≈ 0.10472
         */
        private const val FLOAT_ANGULAR_VELOCITY = 0.10472f

        // ==================== 渲染参数 ====================

        /** 球体表面纹理（纯白纹理，实际颜色通过顶点着色控制） */
        private val SPHERE_TEXTURE = Identifier.of("minddomain", "textures/entity/space_ball_sphere.png")

        /** 完全亮度光照值（自发光层忽略环境光照） */
        private const val FULL_BRIGHT_LIGHT = 0x00F000F0

        // ==================== 轮廓模型参数 ====================

        /**
         * 轮廓球体近似半径（格）
         *
         * 轮廓模型由 5 个不同朝向的立方体组合而成，近似球形轮廓。
         * 每个立方体的 8 个顶点落在此半径的球面上。
         * 该值略大于 [OUTER_RADIUS] 以确保轮廓尺寸接近球体视觉大小。
         * 超出 [OUTER_RADIUS] 的立方体顶点区域（约 0.20 格）在主渲染中
         * 会产生微小的白色瑕疵，因与球体颜色相近而不易察觉。
         */
        private const val OUTLINE_RADIUS = 1.80f

        /**
         * 轮廓立方体半边长 = [OUTLINE_RADIUS] / √3
         *
         * 5 个此尺寸的立方体在不同朝向上联合，
         * 从任意角度观察的轮廓都近似圆形。
         */
        private const val OUTLINE_CUBE_HALF_SIDE = 1.04f
    }

    /**
     * 球面网格的单个顶点数据
     *
     * @property x 顶点位置 X
     * @property y 顶点位置 Y
     * @property z 顶点位置 Z
     * @property nx 法线 X（球体上法线 = 归一化位置向量）
     * @property ny 法线 Y
     * @property nz 法线 Z
     * @property u 纹理坐标 U（0.0 ~ 1.0，沿经线方向）
     * @property v 纹理坐标 V（0.0 ~ 1.0，沿纬线方向）
     */
    private class SphereVertex(
        val x: Float, val y: Float, val z: Float,
        val nx: Float, val ny: Float, val nz: Float,
        val u: Float, val v: Float
    )

    /** 预计算的内层球体四边面列表（初始化时生成一次，每帧复用） */
    private val innerSphereQuads: List<Array<SphereVertex>> = buildSphereQuads(INNER_RADIUS)

    /** 预计算的外层球体四边面列表 */
    private val outerSphereQuads: List<Array<SphereVertex>> = buildSphereQuads(OUTER_RADIUS)

    /**
     * 轮廓用球形近似模型
     *
     * 由 5 个不同朝向的立方体组成，联合轮廓从任意角度近似球形。
     * 通过 [OrderedRenderCommandQueue.submitModel] 提交时携带 `outlineColor`，
     * 使 [net.minecraft.client.render.command.ModelCommandRenderer] 将此模型
     * 渲染到轮廓帧缓冲（[net.minecraft.client.render.OutlineVertexConsumerProvider]），
     * 从而实现透视方块时的近似球形发光边框效果。
     */
    private val outlineModel: EntityModel<SpaceBallEntityRenderState>

    init {
        val h = OUTLINE_CUBE_HALF_SIDE
        val modelData = ModelData()
        val root = modelData.getRoot()
        val rot45 = (PI / 4).toFloat()

        // 每个子部件使用独立的 ModelPartBuilder，避免共享可变状态
        fun cuboid() = ModelPartBuilder.create()
            .uv(0, 0)
            .cuboid(-h, -h, -h, h * 2, h * 2, h * 2)

        // 5 个不同朝向的立方体，联合轮廓从任意角度近似圆形：
        // - base：轴对齐基础立方体
        // - y45/x45/z45：分别绕 Y/X/Z 轴旋转 45°，消除基础立方体的棱角
        // - xy45：同时绕 X、Y 轴旋转 45°，填补对角线方向的间隙
        root.addChild("cube_base", cuboid(), ModelTransform.NONE)
        root.addChild("cube_y45", cuboid(), ModelTransform.rotation(0f, rot45, 0f))
        root.addChild("cube_x45", cuboid(), ModelTransform.rotation(rot45, 0f, 0f))
        root.addChild("cube_z45", cuboid(), ModelTransform.rotation(0f, 0f, rot45))
        root.addChild("cube_xy45", cuboid(), ModelTransform.rotation(rot45, rot45, 0f))

        outlineModel = object : EntityModel<SpaceBallEntityRenderState>(
            TexturedModelData.of(modelData, 64, 64).createModel()
        ) {}
    }

    /**
     * 生成 UV 球体的四边面网格
     *
     * 使用标准球坐标参数化：
     * - φ (phi)：极角，从北极 (0) 到南极 (π)
     * - θ (theta)：方位角，从 0 到 2π
     *
     * 每对相邻 (φ, θ) 格子构成一个四边面。
     * 在极点处四边面退化为零面积（两个顶点重合），GPU 会自动跳过。
     *
     * 顶点按逆时针顺序排列（从外部观察），使法线朝外：
     * ```
     * v0 (φ1,θ1) ---- v1 (φ1,θ2)
     *      |                |
     * v3 (φ2,θ1) ---- v2 (φ2,θ2)
     * ```
     *
     * @param radius 球体半径
     * @return 四边面列表，每个元素包含 4 个顶点
     */
    private fun buildSphereQuads(radius: Float): List<Array<SphereVertex>> {
        val quads = ArrayList<Array<SphereVertex>>(SECTORS * STACKS)
        val pi = PI.toFloat()
        val twoPi = (2.0 * PI).toFloat()

        for (stack in 0 until STACKS) {
            val phi1 = pi * stack / STACKS
            val phi2 = pi * (stack + 1) / STACKS

            for (sector in 0 until SECTORS) {
                val theta1 = twoPi * sector / SECTORS
                val theta2 = twoPi * (sector + 1) / SECTORS

                // 逆时针顶点顺序（从外部观察），确保法线朝外
                val v0 = sphereVertex(radius, phi1, theta1)
                val v1 = sphereVertex(radius, phi1, theta2)
                val v2 = sphereVertex(radius, phi2, theta2)
                val v3 = sphereVertex(radius, phi2, theta1)

                quads.add(arrayOf(v0, v1, v2, v3))
            }
        }

        return quads
    }

    /**
     * 计算球面上单个顶点的位置、法线和纹理坐标
     *
     * @param radius 球体半径
     * @param phi 极角 (0 ~ π)
     * @param theta 方位角 (0 ~ 2π)
     */
    private fun sphereVertex(radius: Float, phi: Float, theta: Float): SphereVertex {
        val sinPhi = sin(phi)
        val cosPhi = cos(phi)
        val sinTheta = sin(theta)
        val cosTheta = cos(theta)

        // 单位球面上的法线（同时也是归一化位置向量）
        val nx = sinPhi * cosTheta
        val ny = cosPhi
        val nz = sinPhi * sinTheta

        return SphereVertex(
            x = radius * nx, y = radius * ny, z = radius * nz,
            nx = nx, ny = ny, nz = nz,
            u = theta / (2f * PI.toFloat()),
            v = phi / PI.toFloat()
        )
    }

    override fun createRenderState(): SpaceBallEntityRenderState {
        return SpaceBallEntityRenderState()
    }

    /**
     * 将实体数据同步到渲染状态
     *
     * 仅传递动画所需的插值年龄，球体网格已在初始化时预计算。
     */
    override fun updateRenderState(entity: SpaceBallEntity, state: SpaceBallEntityRenderState, tickDelta: Float) {
        super.updateRenderState(entity, state, tickDelta)
        state.interpolatedAge = entity.age + tickDelta
    }

    /**
     * 渲染空间球实体
     *
     * 分三步提交渲染命令：
     * 1. 内层核心球体（不透明固体 [submitCustom]）
     * 2. 外层光晕球体（半透明自发光 [submitCustom]）
     * 3. 球形近似轮廓模型（[submitModel]，携带 outlineColor 触发轮廓渲染）
     *
     * 渲染顺序：MC 的 [net.minecraft.client.render.command.RenderDispatcher] 先执行
     * [net.minecraft.client.render.command.ModelCommandRenderer]（步骤 3），
     * 后执行 [net.minecraft.client.render.command.CustomCommandRenderer]（步骤 1、2）。
     * 轮廓模型由 5 个旋转立方体构成，球体表面在大部分区域通过深度测试覆盖立方体。
     * 轮廓帧缓冲的写入独立于主深度缓冲，不受渲染顺序影响。
     */
    override fun render(
        state: SpaceBallEntityRenderState,
        matrices: MatrixStack,
        queue: OrderedRenderCommandQueue,
        camera: CameraRenderState
    ) {
        matrices.push()

        // 正弦浮动偏移（纯视觉效果，不影响实体位置和碰撞箱）
        val floatOffset = FLOAT_AMPLITUDE * sin(FLOAT_ANGULAR_VELOCITY * state.interpolatedAge)

        // 向上偏移使球体中心对齐实体视觉位置 + 浮动（球体增大后适当提高中心点）
        matrices.translate(0.0f, 0.75f + floatOffset, 0.0f)

        // Y 轴旋转动画
        matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(state.interpolatedAge * ROTATION_SPEED))

        // --- 第一层：内层核心球体（实体固体渲染，不透明） ---
        val solidLayer = RenderLayers.entitySolid(SPHERE_TEXTURE)
        queue.submitCustom(matrices, solidLayer) { entry, vc ->
            emitSphereVertices(vc, entry, innerSphereQuads, 235, 240, 255, 255, state.light)
        }

        // --- 第二层：外层光晕球体（自发光半透明渲染） ---
        val emissiveLayer = RenderLayers.entityTranslucentEmissive(SPHERE_TEXTURE)
        queue.submitCustom(matrices, emissiveLayer) { entry, vc ->
            emitSphereVertices(vc, entry, outerSphereQuads, 245, 248, 255, 100, FULL_BRIGHT_LIGHT)
        }

        // --- 第三层：轮廓用球形近似模型（通过 submitModel 触发轮廓帧缓冲写入） ---
        // 5 个旋转立方体的联合轮廓近似球形，使透视轮廓呈现圆形边框。
        // ModelCommandRenderer 先于 CustomCommandRenderer 执行，模型先渲染到主缓冲，
        // 随后球体渲染时深度测试通过，覆盖大部分立方体区域。
        // 轮廓帧缓冲独立运作，保留球形轮廓供后处理边缘检测。
        val outlineColor = state.outlineColor
        if (outlineColor != 0) {
            queue.submitModel(
                outlineModel,
                state,
                matrices,
                solidLayer,
                state.light,
                OverlayTexture.DEFAULT_UV,
                -1,            // tintColor：白色（无色调偏移），与 LivingEntityRenderer 一致
                null,          // sprite：无图集精灵（使用渲染层直接绑定纹理）
                outlineColor,  // outlineColor：触发 OutlineVertexConsumerProvider 轮廓写入
                null           // crumblingOverlay：无破坏覆盖层
            )
        }

        matrices.pop()

        super.render(state, matrices, queue, camera)
    }

    /**
     * 向 VertexConsumer 提交球体网格的全部顶点数据
     *
     * 每个四边面由 4 个顶点组成，按 QUADS 模式提交。
     * 顶点数据格式：位置 → 颜色 → 纹理坐标 → 覆盖层 → 光照 → 法线。
     *
     * @param vc 顶点消费者（绑定到对应的渲染层）
     * @param entry 当前矩阵栈条目（用于变换位置和法线）
     * @param quads 预计算的四边面列表
     * @param r 顶点颜色 R 分量 (0-255)
     * @param g 顶点颜色 G 分量 (0-255)
     * @param b 顶点颜色 B 分量 (0-255)
     * @param a 顶点颜色 Alpha 分量 (0-255)
     * @param light 打包的光照贴图坐标
     */
    private fun emitSphereVertices(
        vc: VertexConsumer,
        entry: MatrixStack.Entry,
        quads: List<Array<SphereVertex>>,
        r: Int, g: Int, b: Int, a: Int,
        light: Int
    ) {
        for (quad in quads) {
            for (v in quad) {
                vc.vertex(entry, v.x, v.y, v.z)
                    .color(r, g, b, a)
                    .texture(v.u, v.v)
                    .overlay(OverlayTexture.DEFAULT_UV)
                    .light(light)
                    .normal(entry, v.nx, v.ny, v.nz)
            }
        }
    }
}

/**
 * 空间球实体的渲染状态
 *
 * 在 updateRenderState 阶段填充数据，在 render 阶段消费数据。
 * 球体网格已预计算，渲染状态仅需传递动画时间。
 */
class SpaceBallEntityRenderState : EntityRenderState() {

    /** 插值后的实体年龄（entity.age + tickDelta），用于平滑动画计算 */
    var interpolatedAge: Float = 0f
}
