# 交接记录 · OpenAnise-Novel（2026-11-08 09:35 · 重装后环境已重建 · 四分支合并完成版）

> **新会话的 AI 请先读本文件 + 仓库 PROGRESS.md**（`https://github.com/qq1375828505/OpenAnise-Novel` main 分支是进度真相源）。
> **本版 = 用户即将卸载重装 DSH 平台**。重装清空 Linux 环境（/root 全部、已装工具、会话记录），**/sdcard 保留**（本项目全部文件、本文档都在）。

---

## 1. 一句话现状

OpenAnise-Novel（网文写作 Agent）从 ZorvAI 迁移到 OpenMinis 基座：**基建全通、CI 全绿、6 块领域能力已落地 4 块**（一致性检查/记忆检索/润色去AI味/标题爽点水文报告），全部代码在远端 `fix-compile` 分支（HEAD `022e4808`）。剩：关系图 v2 界面、25 流派+6 子代理技能包、四分支合并、发布链执行。

## 2. 重装后环境重建清单（新会话第一件事）

> **2026-11-08 已全部重建完成**（用户重新提供 PAT）。下表保留作参考/复检用。

| 项 | 状态 | 重建方法 |
|---|---|---|
| GitHub token | ✅ 已重建 | `/root/.dsh/github-token`(600) + `~/.git-credentials`(store helper) + `/usr/local/bin/ghtok`（读 token 不回显） |
| gitm 镜像脚本 | ✅ 已重建 | `/usr/local/bin/gitm`：clone/fetch/pull 走镜像（默认 ghproxy.net，`GITM_MIRROR=` 可换；镜像只读不能 push） |
| zstd 等工具 | ❌ 未装（按需） | `apt-get update && apt-get install -y zstd`（读旧会话日志用） |
| gh CLI | ❌ 未装 | forge-github 功能需要，装：`apt-get install -y gh` |
| curl | 本来就没装 | 用 python3 urllib + `ghtok` |
| bash 沙箱 | 本机 proot 无 landlock/bubblewrap，**不可用**；本会话审批政策已改为「不问、需提权直接拒」 | 直接调用（不带 `sandbox_permissions` 参数），被拒即不可用 |
| /tmp 材料 | ❌ 已被清空（重装前就没） | 见 §6：全部可再生，无需抢救 |

### ⚠️ 本机网络结论（2026-11-08 实测，勿再浪费时间验证）

- **通**：`api.github.com`、`codeload.github.com`（tarball）、`registry.npmmirror.com`。
- **不通**：`github.com` 的 **git HTTPS(443)**（GnuTLS recv error -110，TLS 非正常终止）；`ghproxy.net` 镜像 clone/fetch 亦失败（连接重置 / partial clone 不支持）。
- **结论：远端读写一律走 GitHub REST API**（`/merges`、Git Data、Contents），本地取快照走 codeload tarball。所有 git clone/push 尝试都是浪费时间。
- 推送小走 Contents API，推送大（>1MB）走 Git Data API（blobs/trees/commits/refs）；合树可分层建 tree 避免请求过大。
- **本地工作树已重建**：`/sdcard/Ai/Anise Write` 的 `.git` 原指向 `/var/minis/workspace/anise-write-git`（已随重装清空），已删除指针、`git init` + 基线 commit，`git status` 干净、可用。本地是**纯文档工作区**（31 个文件，代码全在远端）。

## 3. 仓库与分支（github.com/qq1375828505/OpenAnise-Novel）

| 分支 | 内容 | 状态 |
|---|---|---|
| `main` | 文档（PROGRESS 真相源 / PROJECT_RECORD / MERGE_PLAN / design） | 最新 `2ac601c1`（05:00） |
| `minis-migration` | 迁移主线源码（OpenMinis+包名替换+NovelTools） | `2d323f67`，待收编 fix-compile |
| `fix-compile` | **最全最新**：主线 + CI 修复 + writing/数据层/memory 全部集成（15 新文件 + 6 工具注册） | HEAD `022e4808`，**CI 全绿**（#96 #113） |
| `polish` | 产品身份改名（app_name×23 + rootProject.name + README/AGENTS/.gitignore） | 可合并，零冲突预期 |
| `release-chain` | 签名四件套 + release.yml + keystore 模板（**已含 polish**） | 可合并，零冲突预期 |
| `ci-setup` | CI 初版 + deps/rclone-mobile 源码树 | 可合并，唯一冲突点 build.yml（以 fix-compile 版为准） |

## 4. CI 记录精华（教训已吸收进现状）

1-5 轮：排序 API / 漏 deps / 取消 / K2 崩溃 / 编译 OOM（堆 2g 太小）→ 修复三件套：gradle.properties 内存（jvm 3g + Metaspace 1g + kotlin daemon 3g + workers 2）+ build.yml 删不存在的 testStoreDebugUnitTest（上游 beforeVariants 禁用 store 非 release 变体）+ 暂停上游测试源码单测（Kotlin 2.1.20 下 9 错 5 文件，适配后恢复）。writing 集成 run #95 红（companion 误用于 standalone object → 常量提升修复）→ #96 绿。数据层+memory run #113 绿。

## 5. 移植进度：6 块落地 4 块

| 块 | 状态 |
|---|---|
| 润色去AI味 + 标题/爽点/水文报告 | ✅（NovelReviewTools/PolishTools/EnhanceSupport，run #96） |
| 一致性检查 | ✅（NovelAnalyzer 随数据层入树，run #113） |
| RRF 记忆检索 | ✅（memory 6 文件 + 数据层 9 文件 + 三工具注册，run #113） |
| **关系图 v2 界面** | ⏳ 未开始——数据层 GraphStore 已在树；旧线 UI 参考见 §6 重拉 |
| **25 流派+6 子代理技能包** | ⏳ 未开始——旧线 assets/skills 109 资产，需适配 Minis SKILL.md 机制 |
| UI 玻璃毛边（iOS→Android） | ⏳ 未开始（低优先级） |

## 6. ref-old 重拉方法（旧线 c170c5eb 参考源，/tmp 已丢但可完全再生）

ref-old = 本仓库 `feature/penai` 历史提交 `c170c5eb` 的检出（路径 `app/src/main/java/com/ai/assistance/quro/`：core/novel 14 文件、core/tools/novel 11、ui/novel 5（**关系图 UI 参考**）、assets/skills 109（**技能包**）、assets/prompts 10）。重拉：
- git 可用时：`gitm clone https://github.com/qq1375828505/OpenAnise-Novel && git checkout c170c5eb`
- 或 Git Trees API：`GET /repos/.../git/trees/c170c5eb?recursive=1` + Blobs API 按需取

## 7. 平台机制结论（本会话实测，勿再浪费时间验证）

1. **子代理权限**：`subagent` 子会话启动即注入「审批禁用 + 禁请求 sandbox_permissions 升级 + 权限范围锁定」；App 权限档位只在**会话创建时固化**。主对话切「完全权限」后，新派生的子代理**继承 danger-full-access，可直接跑 bash**（源码 `dsh-subagent/lib/index.js` captureDelegatedPolicyOverrides：preset 为 auto/danger-full-access 时透传，approval 钉死 never）。
2. **模型白名单**（子代理→运行限制）：同理创建时固化；「允许 AI 给子代理选模型」开 = 可显式指定 provider/model。当前会话可选仅 `custom-a/z-ai/glm-5.3-flash`；用户已勾 stepfun-ai/step-5-preview + nvidia/nemotron-3.5-super-vl-120b-a12b（**新会话生效**，glm 未勾，新会话要 glm 需重新勾上）。
3. **会话删除保护**：对话有子代理/关联任务/定时任务记录时主机拒绝删除（session-related）——归档是替代路径，手动删引擎存储文件不建议。
4. 引擎源码：本机 `/opt/dsh/node_modules/@deepseek-ai/dsh-*`（重装后仍在，npm 包）；App 开源仓库 `github.com/ccch1mneyyy/dsh-Go`。

## 8. 下一步顺序（新会话接续）

**已完成（2026-11-08）**：

1. ✅ 环境重建（§2）→ PROGRESS.md 对齐
2. ✅ **四分支合并全部完成、每步 CI 绿**：`polish`→`fix-compile`(`5a7cc40`)、`release-chain`→`fix-compile`(`1dfdbe0`)、`ci-setup`→`fix-compile`(`33ec3f4`，唯一真冲突 `build.yml` 取 fix-compile 实测版)、`fix-compile`→`minis-migration`(`a99c5e5` ff)、`minis-migration`→`main`(`64d45a6`，**main 由文档态转完整态**，2279 blob)
3. ✅ 3 个新文档已推送：`rename-to-openanise-novel.sh`、`docs/progress/MERGE_PLAN_2026-11-08.md`、`docs/progress/SKILL_PORTING_PLAN_2026-11-08.md`

**接下来（按此序）**：

1. **技能包移植**：先读 [SKILL_PORTING_PLAN_2026-11-08.md](./SKILL_PORTING_PLAN_2026-11-08.md)，**请用户先决策其中的 D1–D6**（流派 ASCII id 方案 / 默认开关 / 6 子代理形态 / 规约强制力补偿 / persona 归属），再按 B0→B5 开工（约 9 人天）。最高风险：R1 中文流派名 slugify 后为空串导致无法入库。
2. **关系图 v2 UI**：ref-old `ui/novel` 5 文件参考，数据层 GraphStore 已在树。建议从 `main` 切新分支（勿再动 fix-compile 四分支）。
3. 收尾：上游测试源码适配（9 错 5 文件，Kotlin 2.1.20 下 `similar`/`keySet` unresolved + internal 可见性，修好恢复 build.yml 单测步骤）、GPL-3.0 换证（LICENSE/NOTICE/README 仍 Apache-2.0）、本地目录改名 `Anise Write`→`OpenAnise-Novel`（挂载解除后）、计划书 docx 内部旧名。
4. 发布：用户配 4 个 Secret（ZORV_KEYSTORE_BASE64 / ZORV_KEY_ALIAS / ZORV_STORE_PASS / ZORV_KEY_PASS）→ 打 v* tag → 真机验收走 `docs/progress/ACCEPTANCE_CHECKLIST.md`。

## 9. 铁律（不变）

1. 签名红线：不读/不输出/不重建私钥；只输出公开证书 SHA-256；签名只走 scripts/sign.sh
2. 包名 `com.ai.assistance.quro` 不动；不改上游标识符（MinisApp/Theme.Minis/minis:///20 个非中文语言包译文）
3. 本地不能编译，CI 是唯一编译器；推送走 Contents API / Git Data API（>1MB 文件走后者）
4. 提交规范 `[T-xxx] 模块: 描述`；进度只落 PROGRESS.md，不写会话记忆
5. **未经用户允许不得修改/删除工作区 /sdcard/Ai 以外的本机文件**（用户 11-08 明确指令）

*最后更新：2026-11-08 05:10 · 平台重装收尾版——全部资产已安全在 GitHub 远端*
