// app/src/main/java/com/kiq/aicp/ui/theme/Type.kt
// 字体规格。聊天类应用只需要微调正文行高 —— 默认 bodyLarge 的行高对中文偏挤。

package com.kiq.aicp.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val AicpTypography = Typography().let { base ->
	base.copy(
		bodyLarge = base.bodyLarge.copy(
			fontSize = 16.sp,
			lineHeight = 26.sp,
		),
		bodyMedium = base.bodyMedium.copy(
			fontSize = 14.sp,
			lineHeight = 22.sp,
		),
		titleMedium = TextStyle(
			fontFamily = FontFamily.Default,
			fontWeight = FontWeight.SemiBold,
			fontSize = 17.sp,
			lineHeight = 24.sp,
		),
	)
}
