# 签名与发布工具链（release-chain）

自 ZorvAI 线（c170c5eb）原样移植，安全逻辑一行未动；仅适配新基座布局。

| 文件 | 作用 |
|---|---|
| `scripts/lib/common.sh` | 凭证加载（环境变量 > keystore.properties > `~/.zorvai-signing/env.sh`）、SDK 定位、指纹提取 |
| `scripts/sign.sh` | 统一签名入口：构建 → 幂等重签（zipalign + apksigner）→ 指纹一致性校验 |
| `scripts/verify.sh` | 以 keystore 或首个 APK 为基准，比对其余 APK 证书指纹 |
| `scripts/purge-before-push.sh` | 推送前三层扫描：跟踪的密钥文件 / 硬编码口令 / 历史密钥文件 |

## 用法

```sh
scripts/sign.sh <apk路径>      # 对已产出 APK 重签（CI 以此方式调用）
scripts/sign.sh                # 构建 :app:assembleGithubRelease 后再签
scripts/verify.sh --keystore <apk>...
scripts/purge-before-push.sh   # 推送前扫描（--fix / --strict）
```

## 云端发布（.github/workflows/release.yml）

push tag `v*` 触发：4 Secret 预检（fail-fast）→ 密钥库 base64 解码到 `$RUNNER_TEMP/penai-signing/` →
与 build.yml 相同的构建环境 + `:app:assembleGithubRelease` → `scripts/sign.sh` 重签 → 附加到同名 Release。

**Secrets（只列名）**：`ZORV_KEYSTORE_BASE64`（jks 文件 base64）/ `ZORV_KEY_ALIAS` / `ZORV_STORE_PASS` / `ZORV_KEY_PASS`。
变量名与 `common.sh` 对齐；workflow 内无任何硬编码口令。

## 红线（承袭旧线，AI 协作边界见仓库根 AGENTS.md）

1. 私钥内容永不打印；只允许输出公开的证书 SHA-256 指纹
2. 口令只从 env / keystore.properties / env.sh 读取，不得硬编码
3. 项目目录内绝不新增私钥文件；密钥文件绝不入 git
4. 不得弱化校验：同签名是宿主接受插件的唯一信任来源

## 与旧线的差异

- 构建任务 `:app:assembleFullRelease` → `:app:assembleGithubRelease`（新基座风味为 github/store）
- 新基座 `buildTypes.release` 仍签 debug key（占位，未配发布签名，**属红线不碰**）——由 `sign.sh` 重签为发布密钥
- 其余逻辑（幂等、`env:` 前缀传口令不进 ps、指纹校验）原样保留
