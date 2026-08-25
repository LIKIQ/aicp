// app/build.gradle.kts
// AICP 主模块构建脚本

import java.time.LocalDate
import java.util.Properties

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
		// 3 = P14（UI 节奏统一、角色资料卡、图片多选、内置表情机制）
		// 4 = P15~P16（数据导出恢复、备份口令加密、记忆 wiki 化与记忆体检）
		// 5 = P17（配置码导入导出、表情按情绪分类与后台识图、GitHub 版本检测、release 签名）
		// 6 = 0.5.1，修构建配置：关掉资源压缩，它把 NotificationCompat 要用的通知资源删了
		versionCode = 6
		versionName = "0.5.1"
		testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

		// 精确到天而不是毫秒：带毫秒会让每次构建的 BuildConfig 都变，
		// 增量编译直接失效，每次都全量重编
		buildConfigField("String", "BUILD_DATE", "\"${LocalDate.now()}\"")
	}

	/*
	 * release 签名。keystore.properties 和 .keystore 都在 .gitignore 里，
	 * 所以别人 clone 下来这段自动跳过，debug 构建照常。
	 *
	 * 这个密钥丢了的后果要说清楚：Android 只允许签名一致的包覆盖安装，
	 * 换了密钥的新版本装不上去，用户只能卸载重装 —— 那等于数据全丢。
	 * 所以它必须离开这台机器单独备份。
	 */
	val signingProps = rootProject.file("keystore.properties").takeIf { it.isFile }?.let { file ->
		Properties().apply { file.inputStream().use { load(it) } }
	}

	signingConfigs {
		if (signingProps != null) {
			create("release") {
				storeFile = rootProject.file(signingProps.getProperty("storeFile"))
				storePassword = signingProps.getProperty("storePassword")
				keyAlias = signingProps.getProperty("keyAlias")
				keyPassword = signingProps.getProperty("keyPassword")
			}
		}
	}

	buildTypes {
		debug {
			isMinifyEnabled = false
		}
		release {
			isMinifyEnabled = true
			/*
			 * 资源压缩关掉。
			 *
			 * 打开它之后 res/ 从 53 个条目掉到 15 个，被删的那批里有
			 * notification_bg*.9.png、notification_action_background、
			 * notification_template_* 这些 NotificationCompat 在部分 Android 版本上
			 * 会实际取用的资源 —— 而这个应用真的发通知（主动搭话推送）。
			 * 通知构造时找不到资源不会编译报错，只会在真机上运行时炸，
			 * 而那条路径要等"闲置若干小时后触发"才走到，测不出来。
			 *
			 * 它省下的是 0.36 MB（resources.arsc 0.46 → 0.1）。
			 * 代码 minify 省下的十几 MB 才是大头，那个保留。
			 *
			 * 顺带记一笔排查时踩的坑：release 包的资源文件名会被混淆成 res/2K.9.png
			 * 这种短名，按文件名搜 "launcher" 一个都搜不到，看着像图标丢了。
			 * 图标其实在，别被这个误导。
			 */
			isShrinkResources = false
			proguardFiles(
				getDefaultProguardFile("proguard-android-optimize.txt"),
				"proguard-rules.pro",
			)
			// 没有 keystore.properties 时保持未签名，让构建失败在"签名缺失"上，
			// 而不是悄悄产出一个装不上的包
			signingConfig = signingConfigs.findByName("release")
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
