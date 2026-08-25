// app/src/main/java/com/kiq/aicp/ui/theme/Color.kt
// Compose 侧配色。
// 主色定在偏紫的一组：AI 陪聊类应用用暖紫比蓝更"有人味"，也跟启动图标底色 #1B1233 呼应。
// Android 12+ 会优先用系统取色（Theme.kt 里判断），这里是取色不可用时的兜底方案。

package com.kiq.aicp.ui.theme

import androidx.compose.ui.graphics.Color

// 浅色
val AicpPurple = Color(0xFF6B4EFF)
val AicpPurpleDark = Color(0xFF4B33CC)
val AicpPurpleContainer = Color(0xFFE7DEFF)
val AicpTeal = Color(0xFF00897B)
val AicpTealContainer = Color(0xFFB2DFDB)
val AicpSurfaceLight = Color(0xFFFFFBFE)
val AicpOnSurfaceLight = Color(0xFF1C1B1F)

// 深色
val AicpPurpleLightOnDark = Color(0xFFCFBCFF)
val AicpPurpleContainerDark = Color(0xFF3A2A80)
val AicpTealOnDark = Color(0xFF4DB6AC)
val AicpTealContainerDark = Color(0xFF00504A)
val AicpSurfaceDark = Color(0xFF141218)
val AicpOnSurfaceDark = Color(0xFFE6E1E5)

// 气泡专用：用户/AI 两侧要能一眼分开，不走 primary 免得跟按钮撞色
val BubbleUserLight = Color(0xFFDCD3FF)
val BubbleAiLight = Color(0xFFF2F0F7)
val BubbleUserDark = Color(0xFF3E3266)
val BubbleAiDark = Color(0xFF25232B)
