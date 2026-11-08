# AI 协作边界（新基座：OpenMinis · 2026-11-07 起）

> 适用于在本仓库所有分支上工作的 AI 编码助手（Cursor / Claude Code / Codex / 任意 Agent）。
> 承袭旧 ZorvAI 线的红线（**密钥材料不经过 AI**），按新基座现实更新。

## 绝对禁止（Hard Block）

1. **禁止读取、输出、推断、重建签名私钥**
   - 不读 `keystore.properties` 的内容（只允许脚本读）、不读 `*.jks`/`*.keystore`/`*.p12`/`*.pem`/`*.pk8`/`*.bks`、不读 `~/.zorvai-signing/`
   - 不在回复/日志/提交信息里回显 `ZORV_STORE_PASS` / `ZORV_KEY_PASS` / `ZORV_KEYSTORE_BASE64` 的值
   - **允许且仅允许**输出公开的证书 SHA-256 指纹（核对签名一致性用）
2. **禁止自行内联签名逻辑**：签名只走 `scripts/sign.sh`（移植中，见 PROGRESS 待办 #5）；不得把口令写进 `build.gradle.kts` / `gradle.properties` / CI / 脚本常量；不得新增绕开 `scripts/sign.sh` 的内联 `keytool`/`apksigner`
3. **禁止弱化安全校验**：不得把 `requireSameSignature` 改 `false`；不得建议 `--no-verify` 提交；不得把密钥内置进 APK/`assets/`
4. **禁止把密钥文件提交进仓库**（`keystore.properties` 已在 .gitignore）

## 工程铁律（承自旧线，继续有效）

- **无兜底**：解析失败即抛错，绝不返回假数据
- **断点续跑**：每阶段状态落盘，杀进程可恢复
- **显式契约**：阶段输入输出编码在数据类上，不靠约定俗成

## 工作方式

- **本地不能编译**：CI 是唯一编译器（`ci-setup` 分支的 workflow 已适配新基座）；本地改动只能静态自检
- 大文件改动用 python 精确替换 + 数量断言；Kotlin 注释避免 `/` 紧跟 `*`
- 提交规范 `[T-xxx] 模块: 描述`；`github.com` 直连时断时续，可用 ghproxy.net/ghfast.top 镜像拉取
- **不写会话记忆**（用户已关闭）；进度一律落 PROGRESS.md

## 基座边界（迁移线专用，勿越界）

- 不改上游 OpenMinis 的代码标识符与协议：`MinisApp` / `Theme.Minis` / `minis://` / 20 个非中文语言包译文一律保留——这是「跟着上游升级、不独修宿主」策略的根基
- 只改三层：**产品身份层**（app_name / rootProject.name / 仓库名）、**写作域增量层**（quro 包下代码）、**CI/工程层**
- 包名 `com.ai.assistance.quro` 与签名**不动**（换钥后果：老用户无法覆盖安装、已装插件全失效）

## 产品身份（2026-11-07 定）

| 项 | 值 |
|---|---|
| 名字 | **OpenAnise-Novel**（仓库名即此；PenAi/AniseWrite/Anise-Write 均 302 跳转） |
| 包名 | `com.ai.assistance.quro` |
| 启动器名 | `app_name` = OpenAnise-Novel（23 个语言包已改，`polish` 分支） |
| 签名 | 沿用老钥匙，4 个 Secret：`ZORV_KEYSTORE_BASE64`/`ZORV_KEY_ALIAS`/`ZORV_STORE_PASS`/`ZORV_KEY_PASS`（清单见 `.github/workflows/README.md`） |
