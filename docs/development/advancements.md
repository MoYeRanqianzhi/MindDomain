# MindDomain 成就记录表

本文档记录心灵空间模组的所有成就（Advancements），包括触发条件、触发位置和技术细节。

## 成就树

```
开窍 (root, task) ─── 使用空间球
└── 袖里有乾坤 (task) ─── 首次进入空间
    ├── 空间系初学者 (task) ─── 首次空间替换
    └── 壶中有日月 (goal) ─── 首次空间升级
        └── 万里山河图 (goal) ─── 空间 ≥ 128
            └── 至尊仙窍 (challenge) ─── 空间 ≥ 512
```

## 成就详情

### 开窍（Awakening）

| 属性 | 值 |
|------|-----|
| **ID** | `minddomain:awakening` |
| **中文标题** | 开窍 |
| **英文标题** | Awakening |
| **中文描述** | 使用空间球，开辟属于自己的心灵空间 |
| **英文描述** | Use a Space Ball to create your own mind domain |
| **图标** | `minddomain:space_ball` |
| **类型** | task（根成就） |
| **背景** | `minecraft:textures/block/white_concrete.png` |
| **触发条件** | 使用空间球创建或继承空间 |
| **触发位置** | `SpaceBallItem.use()` |
| **JSON 路径** | `data/minddomain/advancement/awakening.json` |

---

### 袖里有乾坤（A Universe Up One's Sleeve）

| 属性 | 值 |
|------|-----|
| **ID** | `minddomain:universe_in_sleeve` |
| **中文标题** | 袖里有乾坤 |
| **英文标题** | A Universe Up One's Sleeve |
| **中文描述** | 首次进入你的个人空间 |
| **英文描述** | Enter your personal space for the first time |
| **图标** | `minecraft:ender_pearl` |
| **类型** | task |
| **父成就** | `minddomain:awakening` |
| **触发条件** | 按 I 键进入个人空间 |
| **触发位置** | `ModNetworking.handleEnterSpace()` |
| **JSON 路径** | `data/minddomain/advancement/universe_in_sleeve.json` |

---

### 空间系初学者（Space Novice）

| 属性 | 值 |
|------|-----|
| **ID** | `minddomain:space_beginner` |
| **中文标题** | 空间系初学者 |
| **英文标题** | Space Novice |
| **中文描述** | 首次执行空间替换 |
| **英文描述** | Perform a space swap for the first time |
| **图标** | `minecraft:ender_eye` |
| **类型** | task |
| **父成就** | `minddomain:universe_in_sleeve` |
| **触发条件** | 按 V 键执行空间替换 |
| **触发位置** | `ModNetworking.handleSwapSpace()` 延迟回调内 |
| **JSON 路径** | `data/minddomain/advancement/space_beginner.json` |
| **注意** | 使用 `delayedPlayer` 引用（通过 UUID 重新获取的玩家实例） |

---

### 壶中有日月（Sun & Moon in the Gourd）

| 属性 | 值 |
|------|-----|
| **ID** | `minddomain:sun_moon_in_gourd` |
| **中文标题** | 壶中有日月 |
| **英文标题** | Sun & Moon in the Gourd |
| **中文描述** | 通过积累经验升级你的空间 |
| **英文描述** | Upgrade your space by accumulating experience |
| **图标** | `minecraft:experience_bottle` |
| **类型** | goal |
| **父成就** | `minddomain:universe_in_sleeve` |
| **触发条件** | 首次触发空间升级（经验积累到升级阈值） |
| **触发位置** | `ModEvents.onPlayerGainExperience()` |
| **JSON 路径** | `data/minddomain/advancement/sun_moon_in_gourd.json` |

---

### 万里山河图（Vast Landscape Scroll）

| 属性 | 值 |
|------|-----|
| **ID** | `minddomain:vast_landscape` |
| **中文标题** | 万里山河图 |
| **英文标题** | Vast Landscape Scroll |
| **中文描述** | 将空间扩展到 128 或更大 |
| **英文描述** | Expand your space to 128 or larger |
| **图标** | `minecraft:filled_map` |
| **类型** | goal |
| **父成就** | `minddomain:sun_moon_in_gourd` |
| **触发条件** | 空间大小达到 128（升级或继承） |
| **触发位置** | `ModAdvancements.checkSizeMilestones()` —— 在 `ModEvents.onPlayerGainExperience()` 和 `SpaceBallItem.use()` 中调用 |
| **JSON 路径** | `data/minddomain/advancement/vast_landscape.json` |

---

### 至尊仙窍（Supreme Immortal Aperture）

| 属性 | 值 |
|------|-----|
| **ID** | `minddomain:supreme_immortal_space` |
| **中文标题** | 至尊仙窍 |
| **英文标题** | Supreme Immortal Aperture |
| **中文描述** | 将空间扩展到 512 或更大 |
| **英文描述** | Expand your space to 512 or larger |
| **图标** | `minecraft:nether_star` |
| **类型** | challenge |
| **父成就** | `minddomain:vast_landscape` |
| **触发条件** | 空间大小达到 512（升级或继承） |
| **触发位置** | `ModAdvancements.checkSizeMilestones()` —— 在 `ModEvents.onPlayerGainExperience()` 和 `SpaceBallItem.use()` 中调用 |
| **JSON 路径** | `data/minddomain/advancement/supreme_immortal_space.json` |

## 技术实现

### 触发机制

所有成就使用 `minecraft:impossible` 触发器，criterion key 统一为 `"requirement"`。通过 `ModAdvancements.grant()` 程序化授予：

```kotlin
ModAdvancements.grant(player, ModAdvancements.AWAKENING)
```

内部调用 `PlayerAdvancementTracker.grantCriterion(advancement, "requirement")` 完成授予。

### 幂等性

`grantCriterion()` 在成就已完成时返回 `false`，不会重复触发 toast 通知。同一成就可安全多次调用 `grant()`。

### 持久化

成就进度由 MC 原生 `PlayerAdvancementTracker` 管理，数据存储在 `world/advancements/<uuid>.json`，无需模组额外处理。

### 里程碑检查

`ModAdvancements.checkSizeMilestones(player, currentSize)` 统一检查 128/512 两个大小里程碑，在以下场景调用：

1. **空间升级**：`ModEvents.onPlayerGainExperience()` 中，升级后检查新大小
2. **继承空间球**：`SpaceBallItem.use()` 中，使用空间球后检查继承空间的大小

这确保了继承一个 size ≥ 128 的空间球时，玩家能同时获得"开窍"和"万里山河图"成就。
