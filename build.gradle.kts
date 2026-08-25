// build.gradle.kts (root)
// AICP 根构建脚本
// 这里只声明插件版本、不 apply（apply false），实际生效在 :app
// AGP 9.x 自带 Kotlin 编译能力，所以插件列表里没有 org.jetbrains.kotlin.android，别加

plugins {
	alias(libs.plugins.android.application) apply false
	alias(libs.plugins.compose.compiler) apply false
	alias(libs.plugins.kotlin.serialization) apply false
	alias(libs.plugins.ksp) apply false
}
