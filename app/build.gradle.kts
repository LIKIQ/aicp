// app/build.gradle.kts
// AICP 主模块构建脚本

import java.time.LocalDate

// 关键约束（都是查证过的，别随手改）：
// - AGP 9.x 内建 Kotlin，禁止 apply org.jetbrains.kotlin.android
// - compileSdk = 37：不是想追新，是编不过 36 —— Compose 1.12.0 / core-ktx 1.19.0 / okhttp-android 5.5.0
//   等 16 个依赖都声明了 "requires compileSdk >= 37"，卡在 36 直接 BUILD FAILED
// - targetSdk = 36：Play 2026-08-31 的强制线正好是 36，行为变更先不吃；
//   而且 Robolectric 是按 targetSdk 选 android-all jar 的，36 落在 4.16.1 支持范围内，单测不用加 @Config
// - minSdk = 26：AndroidKeystore AES/GCM 在 26+ 稳定，adaptive icon 也只需 anydpi-v26 一套
// - Room 注解走 KSP，schema 导出到 app/schemas 供后续迁移测试比对

plugins {
	alias(libs.plugins.android.application)
	alias(libs.plugins.compose.compiler)
	alias(libs.plugins.kotlin.serialization)
	alias(libs.plugins.ksp)
}

android {
	namespace = "com.kiq.aicp"
	compileSdk = 37

	defaultConfig {
		applicationId = "com.kiq.aicp"
		minSdk = 26
		targetSdk = 36
		// 每次交付给 KIQ 的包都要递增 versionCode，否则装机时系统不认为是"更新"，
		// 他也没法从系统信息里分辨手上装的到底是哪一版。
		// 1 = P0~P6（基础聊天与记忆），2 = P7~P13（附件、表情包、真人模拟、头像备注）
		versionCode = 2
		versionName = "0.2.0"
		testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

		// 精确到天而不是毫秒：带毫秒会让每次构建的 BuildConfig 都变，
		// 增量编译直接失效，每次都全量重编
		buildConfigField("String", "BUILD_DATE", "\"${LocalDate.now()}\"")
	}

	buildTypes {
		debug {
			isMinifyEnabled = false
		}
		release {
			isMinifyEnabled = true
			isShrinkResources = true
			proguardFiles(
				getDefaultProguardFile("proguard-android-optimize.txt"),
				"proguard-rules.pro",
			)
		}
	}

	compileOptions {
		sourceCompatibility = JavaVersion.VERSION_17
		targetCompatibility = JavaVersion.VERSION_17
	}

	buildFeatures {
		compose = true
		// AGP 8 起 BuildConfig 默认不生成，要版本号和构建日期就得显式打开
		buildConfig = true
	}

	// Robolectric 要读 res/ 里的资源，必须打开
	testOptions {
		unitTests {
			isIncludeAndroidResources = true
			all {
				// JDK 17 起模块被封装，Robolectric 大量用反射改内部状态，
				// 不开这些 add-opens 会直接抛 InaccessibleObjectException
				it.jvmArgs(
					"--add-opens=java.base/java.lang=ALL-UNNAMED",
					"--add-opens=java.base/java.util=ALL-UNNAMED",
					"--add-opens=java.base/java.io=ALL-UNNAMED",
					"--add-opens=java.base/java.net=ALL-UNNAMED",
					"--add-opens=java.base/java.nio=ALL-UNNAMED",
					"--add-opens=java.base/java.text=ALL-UNNAMED",
					"--add-opens=java.base/java.security=ALL-UNNAMED",
					"--add-opens=java.base/java.util.concurrent=ALL-UNNAMED",
					"--add-opens=java.base/sun.security.x509=ALL-UNNAMED",
					"--add-opens=java.base/jdk.internal.access=ALL-UNNAMED",
				)
			}
		}
	}

	packaging {
		resources {
			excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/DEPENDENCIES")
		}
	}
}

// Room 的 schema JSON 落到 app/schemas，迁移测试靠它做前后版本比对
ksp {
	arg("room.schemaLocation", "$projectDir/schemas")
	arg("room.generateKotlin", "true")
}

dependencies {
	implementation(libs.core.ktx)
	implementation(libs.activity.compose)

	implementation(platform(libs.compose.bom))
	implementation(libs.compose.ui)
	implementation(libs.compose.ui.graphics)
	implementation(libs.compose.ui.tooling.preview)
	implementation(libs.compose.material3)
	implementation(libs.lifecycle.viewmodel.compose)
	implementation(libs.lifecycle.runtime.compose)
	implementation(libs.navigation.compose)

	implementation(libs.room.runtime)
	implementation(libs.room.ktx)
	ksp(libs.room.compiler)

	implementation(libs.datastore.preferences)
	implementation(libs.coroutines.android)
	implementation(libs.serialization.json)
	implementation(libs.okhttp)
	implementation(libs.work.runtime.ktx)

	debugImplementation(libs.compose.ui.tooling)

	testImplementation(libs.junit)
	testImplementation(libs.robolectric)
	testImplementation(libs.coroutines.test)
	testImplementation(libs.room.testing)
	testImplementation(libs.androidx.test.core)
	testImplementation(libs.androidx.test.ext.junit)
	testImplementation(libs.okhttp.mockwebserver)
}
