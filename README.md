# OpenAnise-Novel

> 运行在 Android 上的**端侧网文写作 AI Agent** —— 把「想故事」变成「可交付章节」。
> On-device AI writing agent for Chinese web novels, built on the OpenMinis base.
>
> ⚠️ **当前状态：基座迁移进行中**（分支 `minis-migration`）。进度真相源是
> [PROGRESS.md](https://github.com/qq1375828505/OpenAnise-Novel/blob/main/PROGRESS.md)（位于 `main` 分支）。

## 这是什么

| | |
|---|---|
| 基座 | [OpenMinis](https://github.com/OpenMinis/OpenMinis)（GPL-3.0 · Kotlin/Compose · Android） |
| 产品身份 | **OpenAnise-Novel**（旧名 PenAi / Anise Write，均 302 跳转） |
| 包名 | `com.ai.assistance.quro`（刻意不改：保老用户覆盖升级与签名） |
| 写作能力 | 七阶段写章工作流 · 40+ 写作工具 · 25 套中文网文流派预设 · 6 个写作子代理 · 伏笔/人物关系/记忆三件套图谱 |

原代号 PenAi（2026-10-22 → Anise Write → 2026-11-07 → OpenAnise-Novel）。 historique 见
[PROJECT_RECORD.md](https://github.com/qq1375828505/OpenAnise-Novel/blob/main/PROJECT_RECORD.md)。

## 分支说明

| 分支 | 内容 |
|---|---|
| `main` | 只放自产文档（PROGRESS / PROJECT_RECORD / 计划书），不放基座代码 |
| `minis-migration` | 迁移主线：OpenMinis 源码（已改包名）+ 写作域增量层 |
| `ci-setup` | CI：Go/gomobile 现构 rclone.aar + 适配新基座的 `build.yml` |
| `polish` | 产品身份改名（app_name ×23 / rootProject.name）+ 本 README + AGENTS.md |

## 构建

- **本地不编译（工程红线）**：CI 是唯一编译器。推 `minis-migration` 自动触发，或在 Actions 页 `workflow_dispatch`。
- CI 链路：JDK 17 + Android SDK 36 / build-tools 36.0.0 / CMake 3.22.1 / NDK 27 → Go 1.25 gomobile 现构 `rclone.aar` → `:app:assembleDebug`（github + store 两风味）→ 单元测试 → 上传 artifact。
- 本地排查用（需自备 JDK 17 / NDK / Go 1.25）：按上游 [BUILDING.md](https://github.com/OpenMinis/OpenMinis/blob/main/BUILDING.md) 的 Android 段，并先跑 `deps/build_rclone_android.sh` 把 `rclone.aar` 复制到 `app/libs/`。

## 许可

- 基座 OpenMinis 为 **GPL-3.0**；换证（LICENSE/NOTICE）是迁移待办，完成前仓库仍标 Apache-2.0。
- `assets/skills/penai/`（流派预设）、写作域代码、文档为项目自产内容。
- 内部保留 OpenMinis 代码标识符（`MinisApp` / `Theme.Minis` / `minis://` 协议 / 非中文语言包译文），以保持与上游可合并；只替换产品身份层。

## 目录

```
app/                                      宿主应用（包名 com.ai.assistance.quro）
  src/main/java/com/ai/assistance/quro/   写作域增量层（tools/novel/…，从 ZorvAI 线移植）
  src/main/res/values*/strings.xml        app_name = OpenAnise-Novel（23 个语言包）
deps/build_rclone_android.sh             CI 现构 rclone.aar 的最小依赖集（上游资产）
deps/rclone-mobile/                       rclone gomobile 绑定源（595K）
.github/workflows/build.yml               云端构建（唯一编译器）
```
