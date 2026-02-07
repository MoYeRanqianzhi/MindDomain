# MindDomain 开发指南

本文档面向开发者，介绍项目架构、核心模块、API 接口和开发规范。

## 项目结构

```
src/main/kotlin/org/mo/minddomain/
├── Minddomain.kt                      # 主初始化器（注册入口）
├── block/
│   └── ModBlocks.kt                   # 方块注册（白色屏障方块）
├── item/
│   ├── ModItems.kt                    # 物品注册
│   └── SpaceBallItem.kt              # 空间球物品逻辑
├── component/
│   └── ModComponents.kt              # DataComponent 注册 + SpaceBallData
├── dimension/
│   ├── ModDimensions.kt              # 维度常量、Codec 注册
│   ├── MindDomainChunkGenerator.kt   # 虚空区块生成器
│   └── DynamicWorldManager.kt        # 动态维度创建/管理/边界扩展
├── data/
│   └── MindDomainState.kt            # PersistentState 持久化数据
├── network/
│   └── ModNetworking.kt              # C2S 网络包 + 传送逻辑
├── command/
│   └── ModCommands.kt                # /md 指令树
├── event/
│   └── ModEvents.kt                  # 服务器生命周期 + 死亡 + 经验获取事件
├── level/
│   └── SpaceLevelConfig.kt           # 空间等级系统配置（升级公式、扩展参数）
└── mixin/
    ├── MinecraftServerAccessor.java   # 访问 MinecraftServer 私有字段
    ├── ItemEntityAccessor.java        # 访问 ItemEntity.itemAge
    └── PlayerEntityMixin.java         # 注入 addExperience 捕获经验获取

src/client/kotlin/org/mo/minddomain/client/
├── MinddomainClient.kt                # 客户端初始化器
├── keybinding/
│   └── ModKeyBindings.kt             # I/O 快捷键绑定
└── render/
    └── WhiteSkyRenderer.kt           # 纯白天空渲染
```

## 架构概述

### 动态维度系统

核心设计：每个玩家的空间是一个完全独立的 `ServerWorld` 实例。

```
玩家使用空间球 → MindDomainState 分配 spaceId
    → DynamicWorldManager 创建 ServerWorld (minddomain:space_<id>)
    → 配置 WorldBorder + barrier 层 + 落脚点
    → 绑定 playerUuid → spaceId
```

**关键类**：
- `DynamicWorldManager`：通过 `MinecraftServerAccessor` Mixin 访问 `MinecraftServer.worlds` 私有字段，将动态创建的 `ServerWorld` 注入服务器维度映射。
- `MindDomainChunkGenerator`：生成纯虚空区块（全空气），落脚点和边界由 `DynamicWorldManager` 事后放置。

### 数据持久化

`MindDomainState` 继承 `PersistentState`，保存在主世界存档文件夹中。

数据结构：
- `nextSpaceId: Int` — 空间 ID 递增分配器
- `spaces: Map<Int, SpaceInfo>` — 空间元数据（大小、等级、经验）
- `playerSpaceMap: Map<UUID, Int>` — 玩家 UUID → 空间绑定
- `playerSpacePositions: Map<UUID, BlockPos>` — 空间内记忆坐标
- `playerReturnPositions: Map<UUID, ReturnPosition>` — 返回位置

### 物品数据组件

空间球使用 1.21 DataComponent 系统：

```kotlin
data class SpaceBallData(
    val size: Int,          // 空间边长（品质）
    val spaceId: Int = -1,  // 关联空间ID（-1=新球）
    val ownerName: String = "" // 原拥有者（死亡掉落时设置）
)
```

### 网络通信

两个 C2S Payload：
- `EnterSpaceC2SPayload`：客户端 I 键 → 服务端进入空间
- `LeaveSpaceC2SPayload`：客户端 O 键 → 服务端离开空间

传送使用 `ServerPlayerEntity.teleportTo(TeleportTarget)`。

### 天空渲染

客户端三层白色效果：
1. `DimensionEffects`：注册 `minddomain:mind_domain` 效果 ID，SkyType.NONE + 白色雾气
2. `WhiteSkyRenderer`：通过 Fabric API `DimensionRenderingRegistry` 注册，清除帧缓冲为白色
3. 自定义生物群系 `white_void`：sky_color/fog_color 均为 0xFFFFFF

## API 参考

### DynamicWorldManager

```
// 获取或创建空间维度
DynamicWorldManager.getOrCreateWorld(server, spaceId, size): ServerWorld?

// 扩展空间大小（数据 + 物理边界 + 客户端同步）
DynamicWorldManager.expandSpace(server, spaceId, levelsGained): Int?

// 服务器启动时重建所有维度
DynamicWorldManager.rebuildAllWorlds(server)
```

### MindDomainState

```
val state = MindDomainState.get(server)

// 空间管理
state.allocateSpace(size): Int          // 分配新空间，返回 spaceId
state.bindSpace(uuid, spaceId)          // 绑定玩家到空间
state.unbindSpace(uuid): Int?           // 解绑，返回原 spaceId
state.getPlayerSpaceId(uuid): Int?      // 查询绑定
state.getSpaceInfo(spaceId): SpaceInfo? // 查询空间信息

// 经验与等级
state.addSpaceExperience(spaceId, amount): LevelUpResult? // 添加经验，返回升级结果

// 位置记忆
state.setSpacePosition(uuid, blockPos)  // 记录空间内坐标
state.getSpacePosition(uuid): BlockPos? // 读取空间内坐标
state.setReturnPosition(uuid, returnPos)// 记录返回位置
state.getReturnPosition(uuid): ReturnPosition? // 读取返回位置
```

### ModDimensions

```
ModDimensions.getWorldKey(spaceId): RegistryKey<World>  // 空间ID → 维度Key
ModDimensions.isMindDomainWorld(worldKey): Boolean       // 判断是否为心灵空间
```

## 开发规范

- **语言**：Kotlin，目标 Java 21
- **映射**：Yarn Mappings
- **注释**：所有公开 API 使用 KDoc 注释，描述用途、参数、返回值
- **命名**：遵循 Kotlin 命名规范，包名全小写，类名 PascalCase
- **Mixin**：仅用于无法通过 Fabric API 实现的功能，集中在 `mixin` 包中
- **提交**：提交信息不得包含任何提交者信息

## 扩展指南

### 添加新的空间球大小

在创造模式物品栏或 `/give` 命令中指定 DataComponent：

```
/give @s minddomain:space_ball[minddomain:space_ball_data={size:64}]
```

### 实现等级提升

空间等级系统已实现，核心流程：

1. `PlayerEntityMixin` 注入 `PlayerEntity.addExperience()`，捕获所有正值经验获取
2. 调用 `ModEvents.onPlayerGainExperience()` 将经验同步到空间
3. `MindDomainState.addSpaceExperience()` 累积经验并处理升级（仅更新等级和经验，不修改空间大小）
4. 升级时调用 `DynamicWorldManager.expandSpace()` 原子性地更新持久化数据、物理边界和 WorldBorder
5. 通过 `WorldBorderInitializeS2CPacket` 手动同步 WorldBorder 到客户端（动态维度的自动同步不可靠）

升级公式（`SpaceLevelConfig`）：
- 等级 N → N+1 需要 `100 × N` 经验
- 每升一级空间边长 +2（`EXPANSION_PER_LEVEL = 2`）

**注意**：`addExperience` 仅在获得经验时被调用（正值），附魔消耗走 `addExperienceLevels(-n)` 不会触发，因此空间等级只增不减。
