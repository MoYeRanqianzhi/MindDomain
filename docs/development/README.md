# MindDomain 开发文档

本目录包含 MindDomain 模组的开发文档，按版本拆分管理。

## 文档结构

| 文件 | 说明 |
|------|------|
| `README.md` | 本文件 — 文档索引、项目概览、开发规范、API 参考 |
| `v1.1.0.md` | v1.1.0 — 基础功能实现（空间球、维度创建、等级系统等） |
| `v1.2.0-SNAPSHOT.md` | v1.2.0-SNAPSHOT — 空间替换、纯白渲染链、高度上限修复等 |
| `v1.2.1-SNAPSHOT.md` | v1.2.1-SNAPSHOT — 空间替换方块实体/状态绑定/多部件实体修复 |
| `v1.3.0-SNAPSHOT.md` | v1.3.0-SNAPSHOT — 模组 Logo 更新 & 死亡掉落 3D 空间球实体 |
| `v1.4.0-SNAPSHOT.md` | v1.4.0-SNAPSHOT — 成就系统 |
| `advancements.md` | 成就记录表 — 所有成就的 ID、触发条件、触发位置、技术细节 |
| `ci-cd.md` | CI/CD 工作流 — 构建与发布工作流的设计、配置细节和使用方法 |

### 版本区分规则

- 每个主要版本对应一个 `vX.Y.Z.md` 文件
- 快照版本使用 `vX.Y.Z-SNAPSHOT.md` 命名
- 各版本文件记录该版本的**新增功能、架构变更、新增文件、实现细节、已知问题和待办事项**
- 跨版本通用内容（项目结构、开发规范、API 参考）维护在本文件中

---

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
│   └── ModNetworking.kt              # C2S 网络包 + 传送/替换逻辑
├── command/
│   └── ModCommands.kt                # /md 指令树
├── event/
│   └── ModEvents.kt                  # 服务器生命周期 + 死亡 + 经验获取事件
├── entity/
│   ├── ModEntities.kt                # 实体类型注册
│   └── SpaceBallEntity.kt            # 空间球自定义实体（死亡掉落 3D 球体）
├── advancement/
│   └── ModAdvancements.kt            # 成就系统（程序化授予的 Advancements）
├── level/
│   └── SpaceLevelConfig.kt           # 空间等级系统配置（升级公式、扩展参数）
├── swap/
│   └── SpaceSwapManager.kt           # 空间替换系统（双向方块/实体交换）
└── mixin/
    ├── MinecraftServerAccessor.java   # 访问 MinecraftServer 私有字段
    ├── ServerWorldAccessor.java       # 访问 ServerWorld.entityManager
    └── PlayerEntityMixin.java         # 注入 addExperience 捕获经验获取

src/client/kotlin/org/mo/minddomain/client/
├── MinddomainClient.kt                # 客户端初始化器
├── keybinding/
│   └── ModKeyBindings.kt             # I/O/V 快捷键绑定
└── render/
    ├── WhiteSkyRenderer.kt           # 纯白天空渲染
    └── SpaceBallEntityRenderer.kt    # 空间球实体 3D 球体渲染器

src/client/java/org/mo/minddomain/client/mixin/
├── ClientWorldMixin.java             # 移除方向光照衰减
├── FogRendererMixin.java             # 白色雾气覆盖
└── LightmapTextureManagerMixin.java  # 光照贴图全白
```

## 开发规范

- **语言**：Kotlin，目标 Java 21
- **映射**：Yarn Mappings（`1.21.11+build.4`）
- **注释**：所有公开 API 使用 KDoc 注释，描述用途、参数、返回值
- **命名**：遵循 Kotlin 命名规范，包名全小写，类名 PascalCase
- **Mixin**：仅用于无法通过 Fabric API 实现的功能，集中在 `mixin` 包中
- **提交**：提交信息不得包含任何提交者信息

## API 参考

### DynamicWorldManager

```kotlin
// 获取或创建空间维度
DynamicWorldManager.getOrCreateWorld(server, spaceId, size): ServerWorld?

// 扩展空间大小（数据 + 物理边界 + 客户端同步）
DynamicWorldManager.expandSpace(server, spaceId, levelsGained): Int?

// 服务器启动时重建所有维度
DynamicWorldManager.rebuildAllWorlds(server)
```

### MindDomainState

```kotlin
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

```kotlin
ModDimensions.getWorldKey(spaceId): RegistryKey<World>  // 空间ID → 维度Key
ModDimensions.isMindDomainWorld(worldKey): Boolean       // 判断是否为心灵空间
```

### SpaceLevelConfig

```kotlin
SpaceLevelConfig.getExpForNextLevel(currentLevel): Int   // 当前等级→下一级所需经验
SpaceLevelConfig.getVerticalSize(size): Int              // 水平尺寸→垂直尺寸（受上限约束）
SpaceLevelConfig.getExpansionPerLevel(currentSize): Int  // 当前尺寸→每级扩展量（2或4）
```

### SpaceSwapManager

```kotlin
// 预加载空间维度区块（异步）
SpaceSwapManager.forceLoadSpaceChunks(server, spaceWorld, center, size)

// 执行双向方块/实体交换
SpaceSwapManager.executeSwap(server, realWorld, spaceWorld, center, size)
```
