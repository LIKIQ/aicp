// app/src/main/java/com/kiq/aicp/ui/theme/Theme.kt
// 主题装配：Android 12+ 走系统动态取色，低版本用 Color.kt 里的固定配色。
// 气泡色不属于 Material 配色表，单独用 CompositionLocal 传下去，聊天页直接取。

package com.kiq.aicp.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val LightScheme = lightColorScheme(
	primary = AicpPurple,
	onPrimary = Color.White,
	primaryContainer = AicpPurpleContainer,
	onPrimaryContainer = AicpPurpleDark,
	secondary = AicpTeal,
	secondaryContainer = AicpTealContainer,
	surface = AicpSurfaceLight,
	onSurface = AicpOnSurfaceLight,
)

private val DarkScheme = darkColorScheme(
	primary = AicpPurpleLightOnDark,
	onPrimary = Color(0xFF23104D),
	primaryContainer = AicpPurpleContainerDark,
	onPrimaryContainer = AicpPurpleContainer,
	secondary = AicpTealOnDark,
	secondaryContainer = AicpTealContainerDark,
	surface = AicpSurfaceDark,
	onSurface = AicpOnSurfaceDark,
)

/** 聊天气泡配色，Material 配色表里没有对应角色，所以单独开一个 Local */
data class BubbleColors(
	val user: Color,
	val ai: Color,
)

val LocalBubbleColors = staticCompositionLocalOf {
	BubbleColors(user = BubbleUserLight, ai = BubbleAiLight)
}

@Composable
fun AicpTheme(
	darkTheme: Boolean = isSystemInDarkTheme(),
	dynamicColor: Boolean = true,
	content: @Composable () -> Unit,
) {
	val supportsDynamic = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
	val context = LocalContext.current

	val scheme = when {
		dynamicColor && supportsDynamic && darkTheme -> dynamicDarkColorScheme(context)
		dynamicColor && supportsDynamic -> dynamicLightColorScheme(context)
		darkTheme -> DarkScheme
		else -> LightScheme
	}

	val bubbles = if (darkTheme) {
		BubbleColors(user = BubbleUserDark, ai = BubbleAiDark)
	} else {
		BubbleColors(user = BubbleUserLight, ai = BubbleAiLight)
	}

	CompositionLocalProvider(LocalBubbleColors provides bubbles) {
		MaterialTheme(
			colorScheme = scheme,
			typography = AicpTypography,
			content = content,
		)
	}
}
