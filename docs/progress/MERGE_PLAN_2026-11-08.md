# 四分支合并清单（2026-11-08 · 基于 compare API 实测）

> 执行方式：**全程 GitHub 服务端 merge API**（`POST /repos/{owner}/{repo}/merges`），本机零 git 操作，最规范。
> 每步合并自动触发 CI（cancel-in-progress 生效 → **每步必须等上一轮绿了再合并下一步**）。
> 出问题回滚：合并前记下 `fix-compile` HEAD，用 Git Data API 把 ref 指回去即可。

## 0. 前置状态（compare 实测）

| 分支 | HEAD | 与 fix-compile 关系 |
|---|---|---|
| minis-migration | 2d323f67 | fix-compile 的祖先（fix-compile 由此切出） |
| fix-compile | 585fc4a1+（集成中，HEAD 动态） | **合并基座**：最新最全且 CI 绿 |
| ci-setup | 589da2b4 | 领先 2 提交（deps/rclone-mobile 源码树 59 文件 + workflows README），落后 7 |
| polish | 10c43c98 | 领先 2 提交（app_name×23 语言包 + rootProject.name + README/AGENTS/.gitignore），落后 7 |
| release-chain | e7046c20 | **已含 polish**（领先 3 = polish 的 2 + 自己的 1：scripts 四件套 + release.yml + keystore 模板） |

## 1. 合并顺序（严格按此序）

### 第 1 步：`polish` → `fix-compile`
- 预期：**零冲突**（fix-compile 侧 7 个提交全是 CI 文件，与 polish 改动零交集）
- 内容：应用名 ×23 语言包、rootProject.name、README、AGENTS.md、.gitignore
- 验证：CI 绿（改名不影响编译，但确认资源合并无问题）

### 第 2 步：`release-chain` → `fix-compile`
- 预期：**零冲突**（polish 已并入，release-chain 只剩自己的 1 个提交：scripts/sign|common|verify|purge + release.yml + keystore.properties.example）
- 注意：`scripts/purge-before-push.sh` 是推送前密钥扫描闸，合并后**不得放宽**（AGENTS.md 红线）
- 验证：CI 绿

### 第 3 步：`ci-setup` → `fix-compile`（唯一可能冲突的）
- 冲突预判：`.github/workflows/build.yml` 双方都改过（fix-compile 版 = 实测绿的最新版，ci-setup 版 = 初版）
  - **处置：冲突时一律以 fix-compile 侧为准**（我们的内存参数 + 单测注释都在里面）
  - `deps/rclone-mobile/**`（约 50 文件）：fix-compile 没有 → 干净新增，接受 ci-setup 版
  - `deps/build_rclone_android.sh`：若内容冲突，以 fix-compile 侧为准（它在 CI 里实测跑通了 3 轮）
- 验证：CI 绿 + 重点看 rclone 构建步骤仍成功

### 第 4 步：`fix-compile` → `minis-migration`
- 预期：**fast-forward**（minis-migration 是祖先，无新提交），零冲突
- 这一步之后：**用户沙箱工作树 `git pull` 同步**（只有你能在 App 沙箱里做）

### 第 5 步：`minis-migration`（或 fix-compile）→ `main`
- main 从「文档态」变「完整态」；PROGRESS.md 真相源地位不变
- 验证：main 的 README 渲染正常

### 收尾（可选）
- 删除已合并的四个分支（ci-setup/polish/release-chain/fix-compile），仓库只剩 main + minis-migration
- 建议先不删，等真机验证通过后再清

## 2. 执行前置条件

1. **writing 块 + 17 文件/memory 块集成全部绿**（合并的是「完整功能」不是「半成品」）
2. `purge-before-push.sh` 密钥扫描通过（合并签名相关分支后手动跑一次确认）
3. 用户确认开始

## 3. 每步 API 形态

```
POST /repos/qq1375828505/OpenAnise-Novel/merges
{"base": "fix-compile", "head": "polish", "commit_message": "[T-merge] 合并 polish：产品身份改名"}
```
- 201 = 成功（返回合并 commit）→ 等 CI
- 409 = 冲突 → 按 §1 第 3 步处置，必要时改用 Git Data API 手工合树
- 204 = 已经是最新的（无需合并）
