# 📌 项目进度 · PROGRESS

> **AI 接手指引：先读本文件，不要全仓扫描。**
> 本文件是唯一进度真相源；技术细节再看 [PROJECT_RECORD.md](./PROJECT_RECORD.md)。
> **⚠️ 记忆系统由用户主动关闭——不要写入会话记忆，进度一律落到本文件。**

---

## 🔀 当前主任务：基座迁移 ZorvAI → OpenMinis（2026-11-07 进行中）

> **ZorvAI 基座全部弃用**（含参考价值），新基座 = [OpenMinis](https://github.com/OpenMinis/OpenMinis)。
>
> **2026-11-07 改名**：Anise Write → **OpenAnise-Novel**（GitHub 仓库名已改，旧名 302 跳转；包名 `com.ai.assistance.quro`、签名、`assets/skills/penai` 资产路径均不变）。

### 已锁定决策（用户 11-05 授权决定，不再确认）

| 决策 | 结论 | 含义 |
|---|---|---|
| 许可证 | **GPL-3.0** | OpenMinis 本身 GPL-3.0，换基座后本项目随改为 GPL-3.0 |
| 包名/签名 | **沿用 `com.ai.assistance.quro`** | 老用户可直接升级，签名不变 |
| 移植范围 | **减量移植 6 块** | 只搬真正有价值的：一致性检查 / RRF 记忆检索 / 润色去AI味 / 标题爽点水文报告 / 25流派+6子代理技能包 / 关系图 v2；其余交给 OpenMinis 原生文件工具 |

### 已完成（迁移里程碑）

| # | 事项 | 状态 | 位置/证据 |
|---|------|------|-----------|
| 1 | OpenMinis 源码拉取 | ✅ | `/var/minis/mounts/Ai/OpenMinis`（124MB，src/android 单模块 `:app`，1497 kt / 40万行，v1.15，compileSdk 36） |
| 2 | deps submodule 补齐 | ✅ | `deps/ish-arm64`(25MB) + `deps/proot`(2.2MB)，codeload 拉取（github.com 直连不通时用） |
| 3 | 包名批量替换 | ✅ | 762 个文件 `com.openminis.app` → `com.ai.assistance.quro`；`build.gradle.kts` namespace/applicationId 已改 |
| 4 | 源码目录统一 | ✅ | `com/openminis/app/` → `com/ai/assistance/quro/` |
| 5 | 零耦合写作域文件拷贝 | ✅ | 17 文件 / 4509 行进 `minis-base/app/src/main/java/com/ai/assistance/quro/`（数据层/资料库/伏笔图谱/记忆双轨 RRF/工作流/子代理匹配器） |
| 6 | **NovelTools.kt 首块落地** | ✅ | 201 行：`novel_write` 复合工具（list/read/write/search/stats/rule/backup 七个 action），直接操作 `/var/minis/mounts/小说/小说项目/` 的 .txt 章节，走 `PRootKernel.resolveSessionHostPath` 解析路径 |
| 7 | 工具注册到 OpenMinis | ✅ | `AgentTools.kt:59 add(NovelTools.definition())` + `ChatViewModel.kt:14153 NovelTools.NAME -> NovelTools.execute(...)` + import |

### 工作区布局

```
/var/minis/workspace/anise-v2/
├── minis-base/     ← OpenMinis 源码（已改包名）+ NovelTools.kt + 17 个写作域文件
├── minis-shared/
└── minis-deps/
```

### 待办（下一步）

| # | 事项 | 说明 |
|---|------|------|
| 1 | **编译验证**（CI） | NovelTools.kt 改动尚未过编译；本地不能编译，推 GitHub 让 CI 跑 `:app:assembleDebug` |
| 2 | 后续 5 块移植 | 一致性检查 / RRF 检索 / 润色去AI味 / 报告类 / 流派技能包 / 关系图（NovelTools 只解决了「文件读写」这一层） |
| 3 | UI 玻璃毛边 | iOS 端玻璃效果 → Android Compose 移植（OpenMinis iOS 侧参考 `src/ios/`） |
| 4 | GPL-3.0 换证 | LICENSE/NOTICE/README 许可声明改写 |
| 5 | 签名 + 发布 | 沿用老签名（4 Secret + tag） |

### 旧仓库（ZorvAI 线）状态——**冻结，不再修**

- HEAD = `c170c5e`，CI 失败根因已定位：Phase 9 裁剪误删 `QuroChatViewModel` 134 行 + `jsonToMap` 丢失 + `QuroTool.kt` 返回值没改完。
- **迁移路线下该 bug 作废**（旧代码不进新基座），不再修、不再推。

---

## ✅ 旧线已完成（全部 10 阶段，供参考）

| 阶段 | 内容 | 证据 |
|------|------|------|
| Phase 0-8 | 环境/AI闭环/工具域44个/技能流派/子代理/基座修复/关系图v2/审查精修 | ✅ `f113092` 绿 |
| Phase 9 | 裁剪与包体优化（集群/MCP/bot/ACI/CMS/插件/KaleidoBox 等） | ✅ `9a74d0b`（迁移线作废） |
| P1-4 | 记忆双轨（十节快照+五表图谱+RRF K=60） | ✅ `f113092` |

**已取消**：⑧ 富文本编辑器（2026-10-23 用户决策）。

---

## 🚫 明确不做

- 富文本编辑器（WebView 富文本）
- 小说项目 CLI 辅助工具（`novel_cli.py` 已写于 `/var/minis/workspace/novel-tools/`，但那是小说项目辅助，**不是软件迁移**，不再继续）
- 旧 ZorvAI 仓库的 CI 修复

---

## 🔧 AI 工作约定（避免重复踩坑）

1. 本地**不能编译**，CI 是唯一编译器；本地改动只能静态自检；
2. 改大文件一律 python 精确替换脚本 + `count==1` 断言；
3. Kotlin 注释避免 `/` 紧跟 `*`（嵌套块注释杀手）；
4. `github.com` 直连时断时续 → 走 `api.github.com` / `codeload.github.com`；
5. `/var/minis/mounts/**` 多只读，写文件放 `/var/minis/workspace/**`；
6. **不写会话记忆**（用户已关闭）；进度只落 PROGRESS.md；
7. 提交规范 `[Phase N] 模块: 描述`；`feature/penai` 与 `main` 始终同步推。

*最后更新：2026-11-07 · 基座迁移进行中：NovelTools 首块已落地并注册，待 CI 编译验证后继续剩 5 块*
