plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    // KSP 版本必须与 Kotlin 版本匹配，统一在 gradle/libs.versions.toml 里维护
    alias(libs.plugins.ksp) apply false
}
