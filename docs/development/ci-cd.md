# CI/CD 构建与发布工作流

本文档详细记录 MindDomain 项目的 GitHub Actions 自动构建与发布工作流的设计、配置细节和使用方法。

## 概述

工作流文件位于 `.github/workflows/build-and-release.yml`，负责自动化构建 Mod JAR 并发布到 GitHub Releases。支持三种触发方式，对应不同的发布类型。

| 触发方式 | 发布类型 | JAR 文件名格式 | 示例 |
|---------|---------|---------------|------|
| 推送 Tag `v*` | 正式版 Release | `MindDomain-<version>.jar` | `MindDomain-1.4.0-SNAPSHOT.jar` |
| 推送到任意分支 | 预发布版 Pre-release | `MindDomain-<version>+build.<time>.jar` | `MindDomain-1.4.0-SNAPSHOT+build.20260201182405.jar` |
| 手动触发 (workflow_dispatch) | 正式版 Release | `MindDomain-<version>.jar` | `MindDomain-1.1.0-SNAPSHOT.jar` |

> 时间戳格式为北京时间 (CST, UTC+8) `YYYYMMDDHHmmss`，例如 `20260201182405` 表示 2026 年 2 月 1 日 18:24:05 CST。

---

## 触发条件

### 1. 自动触发 — 分支推送（预发布版）

每次向任意分支推送提交时自动触发。构建产物以预发布版形式发布到 GitHub Releases，文件名中附带北京时间戳，tag 格式为 `build-<commit-short-sha>`。

```yaml
on:
  push:
    branches: ['**']
```

### 2. 自动触发 — Tag 推送（正式版）

推送以 `v` 开头的 tag 时触发正式版发布。tag 名称直接作为 Release 的 tag。

```yaml
on:
  push:
    tags: ['v*']
```

**发布正式版示例：**

```bash
git tag v1.4.0-SNAPSHOT
git push origin v1.4.0-SNAPSHOT
```

### 3. 手动触发 — workflow_dispatch（补发历史版本）

通过 GitHub Actions 页面手动触发，指定一个已有的 tag 名称进行构建。该功能主要用于：

- 补发工作流创建之前的历史版本
- 重新构建某个 tag 的发布

```yaml
workflow_dispatch:
  inputs:
    tag:
      description: '要构建并发布的 Tag 名称（例如 v1.2.0-SNAPSHOT）'
      required: true
      type: string
```

**操作步骤：** GitHub 仓库 → **Actions** → **Build & Release** → **Run workflow** → 填入 tag 名 → 点击运行。

---

## 并发控制

使用 `concurrency` 确保同一触发源不会产生多个并行构建，新的推送会取消旧的正在运行的构建。

```yaml
concurrency:
  group: build-${{ github.event.inputs.tag || github.ref }}
  cancel-in-progress: true
```

- **分支推送**：`github.event.inputs.tag` 为空，回退到 `github.ref`（如 `refs/heads/main`）
- **Tag 推送**：`github.event.inputs.tag` 为空，回退到 `github.ref`（如 `refs/tags/v1.4.0-SNAPSHOT`）
- **手动触发**：使用 `github.event.inputs.tag` 的值（如 `v1.2.0-SNAPSHOT`）

---

## 构建环境

| 组件 | 版本 | 说明 |
|------|------|------|
| **Runner** | `ubuntu-latest` | GitHub 托管的 Linux 环境 |
| **JDK** | Temurin 21 | 通过 `actions/setup-java@v4` 安装 |
| **Gradle** | 9.2.1 | 通过 `gradle/actions/setup-gradle@v4` 安装 |

### 关于 Gradle Wrapper

本项目在 Windows 上开发，仓库中仅包含 `gradle/wrapper/gradle-wrapper.properties`，不包含 `gradlew` 脚本和 `gradle-wrapper.jar`。因此工作流中 **不使用** `./gradlew`，而是通过 `setup-gradle` action 的 `gradle-version` 参数显式安装 Gradle 9.2.1，然后直接调用 `gradle build`。

```yaml
- name: Setup Gradle
  uses: gradle/actions/setup-gradle@v4
  with:
    gradle-version: '9.2.1'

- name: Build with Gradle
  run: gradle build
```

> **注意：** `gradle/actions/setup-gradle@v4` 的 `arguments` 参数已在 v4 中移除。构建命令必须作为单独的 `run` 步骤执行。

---

## 工作流步骤详解

### Step 1: Checkout

```yaml
- name: Checkout repository
  uses: actions/checkout@v4
  with:
    ref: ${{ github.event.inputs.tag || github.ref }}
    fetch-depth: 0
```

- `ref`：手动触发时 checkout 到指定 tag 的提交，否则使用当前触发的 ref
- `fetch-depth: 0`：拉取完整 Git 历史，用于后续的变更日志生成

### Step 2: 构建

安装 JDK 21 和 Gradle 9.2.1 后执行 `gradle build`，产物输出到 `build/libs/MindDomain-<version>.jar`。

### Step 3: 版本提取

从 `gradle.properties` 的 `mod_version` 字段读取当前模组版本号：

```yaml
- name: Extract mod version from gradle.properties
  id: mod
  run: |
    VERSION=$(grep '^mod_version' gradle.properties | cut -d'=' -f2 | tr -d ' ')
    echo "version=$VERSION" >> "$GITHUB_OUTPUT"
```

后续步骤通过 `${{ steps.mod.outputs.version }}` 引用。

### Step 4: 判断发布类型并准备产物

根据触发方式判断是正式版还是预发布版：

| 条件 | 判定结果 |
|------|---------|
| `github.ref` 匹配 `refs/tags/v*` | 正式版 |
| `github.event.inputs.tag` 非空（手动触发） | 正式版 |
| 其他（分支推送） | 预发布版 |

**正式版**产物直接复制为 `MindDomain-<version>.jar`。

**预发布版**产物重命名为 `MindDomain-<version>+build.<YYYYMMDDHHmmss>.jar`，tag 使用 `build-<commit-short-sha>` 格式。

### Step 5: 生成变更日志

使用 `git log` 生成自上一个正式版 tag 以来的提交记录：

1. 列出所有 `v*` 格式的 tag，按版本倒序排列
2. 如果当前是 tag 构建，排除自身后取第一个作为基准
3. 生成基准 tag 到 HEAD 之间的提交日志
4. 如果没有历史 tag，取最近 30 条提交

### Step 6: 上传构建产物

通过 `actions/upload-artifact@v4` 将 JAR 上传为 GitHub Actions Artifact，保留 30 天，作为备份。

### Step 7: 创建 Release

通过 `softprops/action-gh-release@v2` 创建 GitHub Release。

**正式版 Release 内容：**
- Release 名称：`MindDomain <version>`
- 包含自动生成的变更日志
- 启用 GitHub 自动生成的 Release Notes（`generate_release_notes: true`）
- 标记为最新版本（`make_latest: true`）

**预发布版 Release 内容：**
- Release 名称：`Pre-release <version>+build.<timestamp>`
- 包含分支名、完整 commit SHA、北京时间构建时间信息表格
- 包含本次提交信息和变更记录
- 标记为预发布（`prerelease: true`）
- 不标记为最新版本（`make_latest: false`）

---

## 产物命名规则

### 正式版

```
MindDomain-{mod_version}.jar
```

版本号直接取自 `gradle.properties` 中的 `mod_version`。

### 预发布版

```
MindDomain-{mod_version}+build.{YYYYMMDDHHmmss}.jar
```

`+build.` 后附加北京时间 (CST, UTC+8) 时间戳，精确到秒。符合 [SemVer 2.0](https://semver.org/) 的构建元数据规范。

---

## 补发历史版本操作指南

由于工作流文件在早期版本的提交中不存在，推送旧 tag 不会触发工作流（GitHub Actions 读取的是 tag 所指向提交中的工作流文件）。通过 `workflow_dispatch` 手动触发可解决此问题。

### 操作步骤

1. 确保目标 tag 已存在于远程仓库（可通过 `git tag -l` 查看）
2. 确保包含 `workflow_dispatch` 的工作流文件已推送到默认分支
3. 进入 GitHub 仓库 → **Actions** → **Build & Release**
4. 点击 **Run workflow**
5. 在 **tag** 输入框填入要补发的 tag（如 `v1.1.0-SNAPSHOT`）
6. 点击 **Run workflow** 执行

### 原理

手动触发时，GitHub Actions 使用 **默认分支上的工作流文件** 执行，但 checkout 步骤会切换到指定 tag 的代码进行构建。这样即使旧提交中没有工作流文件，也能正常构建和发布。

---

## 技术决策记录

### Q: 为什么不使用 `./gradlew` 而是直接用 `gradle`？

项目在 Windows 上开发，仓库中未提交 `gradlew`（Unix shell 脚本）和 `gradle-wrapper.jar`，仅保留了 `gradle/wrapper/gradle-wrapper.properties`。在 Linux CI 环境中 `./gradlew` 不存在，因此通过 `setup-gradle` action 显式安装与 `gradle-wrapper.properties` 中一致的 Gradle 9.2.1 版本。

### Q: 为什么 `setup-gradle` 不使用 `arguments` 参数？

`gradle/actions/setup-gradle@v4` 已移除 `arguments` 参数（v3 中可用）。v4 版本仅负责安装和配置 Gradle 环境（缓存、版本管理等），实际构建必须作为独立的 `run` 步骤执行。

### Q: 为什么并发控制使用 `github.event.inputs.tag || github.ref`？

早期写法为 `build-${{ github.ref }}-${{ github.event.inputs.tag || '' }}`，在非手动触发时会产生尾部 `-` 的 group 名（如 `build-refs/heads/main-`）。改为三元回退写法后，手动触发时使用 tag 名，其他情况使用 `github.ref`，避免异常后缀。

### Q: 为什么手动触发时也走正式版流程？

`workflow_dispatch` 的主要用途是补发历史版本的正式 Release。输入的 tag 名对应一个已有的版本 tag，构建结果应当与自动 tag 触发产生的正式版保持一致。

### Q: 预发布版的 tag 格式为什么用 `build-<sha>` 而不是时间戳？

Git commit SHA 具有唯一性且可追溯到具体提交，而时间戳可能因重新运行而重复。文件名中已包含时间戳用于排序，tag 使用 SHA 用于精确关联代码。

---

## 文件清单

| 文件 | 说明 |
|------|------|
| `.github/workflows/build-and-release.yml` | 构建与发布工作流定义 |
| `gradle/wrapper/gradle-wrapper.properties` | Gradle Wrapper 版本配置（指定 Gradle 9.2.1） |
| `gradle.properties` | 模组版本号等构建属性的来源 |

---

## 已知限制

- **Gradle Wrapper 不完整**：仓库中缺少 `gradlew`、`gradlew.bat`、`gradle-wrapper.jar`，CI 通过显式指定 `gradle-version` 绕过，但本地开发依赖 IDE 内置 Gradle
- **预发布版数量**：每次分支推送都会创建一个预发布 Release，长期使用后可能需要手动清理旧的预发布版本
- **变更日志精度**：变更日志基于 `git log` 生成，合并提交（merge commit）已被过滤，但 squash merge 的提交信息质量取决于提交者
