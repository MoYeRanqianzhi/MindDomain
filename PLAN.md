# MindDomain 基础功能实现计划

## 一、架构概述

采用**动态独立维度**方案：每个玩家的空间是完全独立的维度实例。
通过 Mixin 向 MinecraftServer 注入动态创建维度的能力，在玩家激活空间球时创建
`minddomain:space_<id>` 维度，服务器重启时自动重建已注册的维度。

**核心优势：**
- 维度级别的完全隔离，不可能通过任何游戏内手段越界
- 末影珍珠、爆炸、指令传送等均无法跨维度
- 管理员只能通过专用指令 `/md visit <玩家>` 访问他人空间

## 二、空间环境设计

### 初始空间
- **全白虚空**：整个维度默认无方块，四周和天空均渲染为纯白色
- **白色地面**：空间底部为白色屏障方块层，不可破坏且外观纯白
- **无怪物生成**，固定正午光照

### 空间边界（白色屏障 + WorldBorder）
- **自定义白色屏障方块** (`white_barrier`)：
  - 纯白色纹理，与白色天空无缝融合
  - 硬度 -1（生存模式不可破坏）
  - 通过 `PlayerBlockBreakEvents.BEFORE` 事件阻止创造模式破坏
  - 不可被活塞推动，不允许生物生成
- **地面层**：白色屏障方块，不可破坏的白色地面
- **天花板层**：白色屏障方块，不可破坏，融入白色天空
- **四周墙壁**：白色屏障方块，不可破坏，白色外观与天空融为一体
- **WorldBorder**：设在屏障墙壁之外（+10 格），作为最终安全边界
  - 距离足够远，玩家不会看到 WorldBorder 的动画效果
- 空间为**正方体**：宽度 = 高度 = 当前尺寸
- 初始尺寸 = 空间球标注的大小（如 4×4×4 空间球 → 初始 4×4×4）
- 等级提升时宽度和高度同步增长，**无上限**

### 天空渲染
- 客户端通过 `WorldRenderEvents.START_MAIN` 事件注册自定义天空渲染器
- 使用 OpenGL `glClear` 将颜色缓冲区清除为纯白色
- 地形、实体和 UI 在之后渲染时会通过深度测试正确覆盖白色背景
- 不渲染太阳、月亮、星星、云层

## 三、文件结构

```
src/main/kotlin/org/mo/minddomain/
├── Minddomain.kt                          # 主初始化器（统一注册入口）
├── block/
│   └── ModBlocks.kt                       # 方块注册（白色屏障方块）
├── item/
│   ├── ModItems.kt                        # 物品注册
│   └── SpaceBallItem.kt                   # 空间球物品（右键使用开辟空间）
├── component/
│   └── ModComponents.kt                   # 自定义 DataComponent 注册
├── dimension/
│   ├── ModDimensions.kt                   # 维度常量、区块生成器 Codec 注册
│   ├── MindDomainChunkGenerator.kt        # 自定义区块生成器（纯虚空）
│   └── DynamicWorldManager.kt             # 动态维度创建/加载/边界管理
├── data/
│   ├── MindDomainState.kt                 # 世界级持久化数据（PersistentState）
│   └── SpaceBallData.kt                   # 空间球携带的数据结构
├── network/
│   └── ModNetworking.kt                   # 网络包定义与处理
├── command/
│   └── ModCommands.kt                     # 指令注册（/md visit, /md info 等）
├── event/
│   └── ModEvents.kt                       # 事件处理（死亡、方块保护、掉落物管理、经验获取）
├── level/
│   └── SpaceLevelConfig.kt                # 空间等级系统配置（升级公式、扩展参数）
└── mixin/
    ├── MinecraftServerAccessor.java       # Mixin Accessor：访问 Server 内部字段
    ├── ItemEntityAccessor.java            # Mixin Accessor：访问 ItemEntity.itemAge
    └── PlayerEntityMixin.java             # Mixin Inject：捕获经验获取事件

src/client/kotlin/org/mo/minddomain/client/
├── MinddomainClient.kt                    # 客户端初始化（按键、天空渲染、网络）
├── keybinding/
│   └── ModKeyBindings.kt                  # 快捷键绑定（I 进入 / O 离开）
└── render/
    └── WhiteSkyRenderer.kt               # 纯白天空渲染器

src/main/resources/
├── data/minddomain/
│   ├── dimension_type/mind_domain.json    # 共享维度类型
│   └── worldgen/biome/white_void.json     # 白色虚空生物群系
└── assets/minddomain/
    ├── blockstates/white_barrier.json     # 白色屏障方块状态
    ├── models/
    │   ├── block/white_barrier.json       # 白色屏障方块模型
    │   └── item/space_ball.json           # 空间球物品模型
    ├── textures/
    │   ├── block/white_barrier.png        # 白色屏障纹理（纯白）
    │   └── item/space_ball.png            # 空间球纹理（半透明水晶球体）
    └── lang/
        ├── en_us.json                     # 英文
        └── zh_cn.json                     # 中文
```

## 四、维度方案详细设计

### 4.1 共享维度类型（JSON）
所有玩家空间复用同一个 `DimensionType`：
- `fixed_time: 6000`（永远正午）
- `ambient_light: 1.0`（全亮）
- `has_skylight: true`, `has_ceiling: false`
- `monster_spawn_light_level: 0`（禁止怪物生成）
- `effects: "minddomain:mind_domain"`（自定义天空效果标识）

### 4.2 动态维度创建（DynamicWorldManager）
- 通过 `MinecraftServerAccessor` Mixin 访问 `MinecraftServer.worlds` 私有字段
- 创建 `ServerWorld` 实例，使用共享维度类型 + 自定义区块生成器
- 维度 Key 格式：`minddomain:space_<spaceId>`（spaceId 为递增整数）
- 服务器启动时（`SERVER_STARTED` 事件）根据 `MindDomainState` 重建所有已注册维度
- 创建后立即配置 WorldBorder 和 Y 轴 barrier 层

### 4.3 区块生成器（MindDomainChunkGenerator）
- **全部为空气**（纯虚空维度），不生成任何方块
- 落脚平台和 Y 轴边界由 `DynamicWorldManager` 在维度创建后放置 barrier 方块实现
- 注册自定义 Codec 到 `Registries.CHUNK_GENERATOR`

### 4.4 白色天空渲染（客户端）
- 注册 `WorldRenderEvents.START_MAIN` 事件处理器
- `WhiteSkyRenderer`：使用 `GL11.glClearColor` + `GL11.glClear` 将颜色缓冲区清除为纯白
- 仅在心灵空间维度中激活（通过 `ModDimensions.isMindDomainWorld()` 判断）
- 不渲染太阳、月亮、星星、云层

## 五、空间球设计

### 命名与品质
- 品质直接以空间大小表示：`4×4×4 空间球`、`16×16×16 空间球`
- DataComponent 存储 `size: Int`（边长），物品名和 tooltip 动态生成
- 玩家死亡掉落的空间球：
  - 物品名：`<玩家名>的空间`（如 `Steve的空间`）
  - tooltip 显示：`大小: 32×32×32`（当前实际尺寸，含升级后的大小）
  - DataComponent 额外存储 `spaceId: Int` 用于关联已有维度

### 使用逻辑
- 右键使用：若玩家无空间 → 消耗物品，创建/继承维度，绑定空间
- 若玩家已有空间 → 提示无法使用，不消耗

## 六、位置记忆

- **空间内坐标**：MindDomainState 记录每个玩家在空间内的最后坐标
  - 初始位置：空间中心 (0, 65, 0)（barrier 落脚点上方）
  - 每次离开空间时更新记录
  - 下次进入时传送到上次离开的坐标
- **空间外坐标**：进入空间前记录玩家在主世界的位置
  - 离开空间时恢复到该位置

## 七、指令系统

| 指令 | 权限 | 说明 |
|------|------|------|
| `/md enter` | 所有玩家 | 进入自己的空间（等同于按 I 键） |
| `/md leave` | 所有玩家 | 离开空间（等同于按 O 键） |
| `/md info` | 所有玩家 | 查看自己空间的信息（等级、大小等） |
| `/md visit <玩家>` | OP 2级 | 管理员传送到指定玩家的空间 |
| `/md info <玩家>` | OP 2级 | 管理员查看指定玩家空间信息 |

指令中的 `<玩家>` 参数使用 `EntityArgumentType.player()`，支持 `@a` `@p` 等选择器。

## 八、实现步骤

### 步骤1：Mixin 基础设施
- `MinecraftServerAccessor`：暴露 `worlds` 和 `session` 字段
- 更新 `minddomain.mixins.json`

### 步骤2：维度系统
- `dimension_type/mind_domain.json`
- `MindDomainChunkGenerator`：纯虚空区块生成器
- `ModDimensions`：常量和 Codec 注册
- `DynamicWorldManager`：动态创建/获取 ServerWorld + WorldBorder + barrier 层 + 落脚点

### 步骤3：数据持久化
- `MindDomainState`（PersistentState）：
  - `nextSpaceId: Int` — 空间ID递增分配
  - `spaces: Map<Int, SpaceInfo>` — 空间信息（等级、经验、当前尺寸）
  - `playerSpaceMap: Map<UUID, Int>` — 玩家 → 空间绑定
  - `playerSpacePositions: Map<UUID, BlockPos>` — 玩家在空间内的最后坐标
  - `playerReturnPositions: Map<UUID, ReturnPos>` — 玩家进入前的位置（含维度）

### 步骤4：DataComponent 与空间球物品
- `ModComponents`：注册 DataComponentType（size、spaceId、ownerName）
- `SpaceBallItem`：右键使用逻辑 + 动态物品名/tooltip
- `ModItems`：物品注册

### 步骤5：网络通信
- `EnterSpaceC2SPayload`：请求进入空间
- `LeaveSpaceC2SPayload`：请求离开空间
- 服务端处理：校验 + 传送 + 位置记忆

### 步骤6：快捷键（客户端）
- I 键：进入空间（`key.minddomain.enter_space`）
- O 键：离开空间（`key.minddomain.leave_space`）
- 均可在游戏设置中自定义修改
- 按键 → 发对应网络包 → 服务端处理

### 步骤7：白色天空渲染（客户端）
- `WhiteSkyRenderer`：纯白天空（GL11 直接调用）
- 注册到 `WorldRenderEvents.START_MAIN` 事件

### 步骤8：指令系统
- `ModCommands`：Brigadier API 注册所有子指令

### 步骤9：死亡处理与空间球掉落
- 玩家死亡且拥有空间 → 生成空间球掉落物
  - 物品名设为 `<玩家名>的空间`
  - DataComponent 写入当前空间尺寸和 spaceId
- 掉落的空间球特殊属性：
  - **永不消失**：通过 `ItemEntityAccessor` Mixin 将 `itemAge` 设为 `Integer.MIN_VALUE`
  - **发光效果**：`isGlowing = true`，便于在地面上发现
- 解除玩家与空间的绑定

### 步骤10：语言文件与资源
- 语言文件、物品模型、贴图

### 步骤11：空间等级系统
- `SpaceLevelConfig`：等级公式和扩展参数
- `PlayerEntityMixin`：注入 `addExperience` 捕获经验获取
- `MindDomainState.addSpaceExperience()`：经验累积和升级逻辑
- `ModEvents.onPlayerGainExperience()`：处理经验获取 → 升级 → 边界更新 → 通知

### 步骤12：文档
- `README.md` + `docs/DEVELOPMENT.md`

## 九、交互设计

| 操作 | 方式 | 效果 |
|------|------|------|
| 开辟空间 | 右键使用空间球 | 消耗空间球，创建独立维度，绑定空间 |
| 进入空间 | 按 I 键 或 `/md enter` | 传送至空间内记忆坐标（初始为中心） |
| 离开空间 | 按 O 键 或 `/md leave` | 记录空间内坐标，传送回之前位置 |
| 管理员访问 | `/md visit <玩家>` | 传送至目标玩家空间中心 |
| 死亡 | 自动 | 空间解绑，掉落命名空间球（含大小信息） |
| 继承空间 | 使用拾取的空间球 | 绑定已有维度（保留建设内容） |

## 十、技术要点

- **完全隔离**：每个空间是独立维度，无法通过任何游戏内手段越界
- **白色不可破坏边界**：自定义 `white_barrier` 方块，纯白纹理 + 硬度 -1 + 事件拦截创造模式破坏
- **真实边界**：X/Z 使用原生 `WorldBorder`（不可跨越） + 白色屏障墙壁，Y 轴使用白色屏障方块层
- **纯白环境**：客户端天空渲染器（GL11 glClear）+ 白色虚空生物群系 + 白色屏障边界
- **正方体空间**：宽度 = 高度，随等级同步增长，无上限
- **位置记忆**：记录空间内/外坐标，进出时恢复
- **空间球品质 = 大小**：如 `16×16×16 空间球`，死亡掉落球命名为 `xx的空间`
- **永不消失掉落**：死亡掉落的空间球通过 Mixin 设置 itemAge 为 Integer.MIN_VALUE，永不自然消失
- **发光掉落物**：死亡掉落的空间球带有 Glowing 效果，便于寻找
- **持久化**：PersistentState + Codec 存入主世界存档，重启自动重建
- **传送机制**：`FabricDimensions.teleport()` 跨维度传送
- **数据组件**：1.21 DataComponent 系统存储空间球数据
- **空间等级**：独立于原版等级，通过 Mixin 注入 `addExperience` 同步经验，`addExperienceLevels` 消耗不触发
- **升级公式**：等级 N → N+1 需要 100×N 经验，每级空间边长 +2，溢出经验保留
