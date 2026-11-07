# CI 工作流说明（build）

> 基座迁移到 OpenMinis 后重写。旧（ZorvAI 时代）三件套只存在于 `c170c5eb` 历史里，
> 迁移分支 `minis-migration` 曾整体丢失 `.github/`，导致「推分支 CI 根本不触发」——
> PROGRESS 待办 #1（编译验证）卡死的根因。

## 与旧 build.yml 的差异（5 处，逐条对应）

| # | 旧（ZorvAI 基座） | 现在（OpenMinis 基座） |
|---|---|---|
| 1 | 触发分支 `[main, feature/**]` | 加 `minis-migration` + `workflow_dispatch` |
| 2 | 单测 `:app:testFullDebugUnitTest` / `testFdroidDebugUnitTest` | 风味改为 github/store → `:app:testGithubDebugUnitTest` / `:app:testStoreDebugUnitTest` |
| 3 | NDK r27 + CMake 为 MNN/llama.cpp 编译 | 仍装 NDK（CMake 构建 jieba_jni/pty_bridge/crash_handler，版本 3.22.1 被 build.gradle.kts 锁定），但无 MNN/llama 大 Native，时长约 15~25 分钟 |
| 4 | 占位 keystore.properties（ZorvAI signingConfigs 配置期强制求值） | **删除**：新基座无任何 keystore 读取，debug/release 变体都用 debug 签名配置 |
| 5 | Gradle 8.13 + 阿里云镜像源 | Gradle 8.11.1 + google/mavenCentral/jitpack（runner 直连无碍） |

## 新增：rclone.aar 的 CI 现构

- `settings.gradle.kts` 用 `flatDir { dirs("app/libs") }` 读 `app/libs/rclone.aar`，
  但该产物**不在库里**（上游 BUILDING.md：构建产物，不入库）。
- 本 workflow 用 Go 1.25 + gomobile 跑 `deps/build_rclone_android.sh` 现构，
  输出 `deps/build/rclone/rclone.aar` 后复制到 `app/libs/`。
- 随本分支搬入最小依赖集：`deps/build_rclone_android.sh`（4K）+ `deps/rclone-mobile`（595K）。
- `.gitmodules`（deps/ish、deps/proot）**未搬**：CMake 目标（jieba_jni/pty_bridge/crash_handler）
  不引用 deps/，proot loader 是仓库内预编译 .so，Android 构建不需要 iSH。

## 还缺（发布链路，与本 build 无关）

1. **release 变体目前签 debug key**：`buildTypes.release { signingConfig = debug }`。
   要沿用老签名（4 Secret + tag），需补：release signingConfig + keystore.properties 机制、
   `scripts/sign.sh`（ZorvAI 仓库资产，新分支没有）、`release.yml`。
2. **Secrets**（release 才需要，build 一个都不要）：
   `ZORV_KEYSTORE_BASE64` / `ZORV_KEY_ALIAS` / `ZORV_STORE_PASS` / `ZORV_KEY_PASS`
   （变量名与旧 `scripts/lib/common.sh` 对齐，旧 workflows/README.md 有完整清单）。
3. `scripts/prepare_android_sandbox.sh`（Alpine rootfs → assets）未搬，仅影响运行期沙箱，
   不影响编译。

## 采用方式（在 Minis 沙箱的迁移工作树里）

```sh
git fetch origin
git merge ci-setup --no-ff     # 或：git cherry-pick <ci-setup 的提交>
git push origin minis-migration
```

推上去即自动触发；也可在 Actions 页 workflow_dispatch 手动跑。
