// [T-fix-k2] Kotlin 2.1.0 的 K2 后端在 SpeechOffloadHandler.providerFromMic 的
// `when (val capture = ...)` 智能转换上崩（Back-end JVM Internal error: Couldn't
// transform method node；上游无 CI，从未触发）。2.1.20 修掉一批 K2 后段崩溃，
// 是语言版本不变（2.1）的最小补丁升级；KSP 走 1.0.31（KSP1 线，Room 2.6.1 不受影响）。
plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.1.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.20" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.1.20" apply false
    id("com.google.devtools.ksp") version "2.1.20-1.0.31" apply false
}
