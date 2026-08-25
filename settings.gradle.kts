// settings.gradle.kts
// AICP 构建入口
// 职责：
// - 声明插件仓库与依赖仓库（阿里云镜像优先，官方源兜底，镜像缺包时自动回落）
// - 集中版本目录 gradle/libs.versions.toml
// - 注册子模块 :app
// 注意：maven.google.com 在本机直连不通（curl 返回 000），必须保留阿里云 google 镜像

pluginManagement {
	repositories {
		maven("https://maven.aliyun.com/repository/gradle-plugin")
		maven("https://maven.aliyun.com/repository/google")
		maven("https://maven.aliyun.com/repository/public")
		google()
		mavenCentral()
		gradlePluginPortal()
	}
}

dependencyResolutionManagement {
	repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
	repositories {
		maven("https://maven.aliyun.com/repository/google")
		maven("https://maven.aliyun.com/repository/public")
		google()
		mavenCentral()
	}
}

rootProject.name = "aicp"
include(":app")
