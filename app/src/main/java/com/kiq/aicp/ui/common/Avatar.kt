// app/src/main/java/com/kiq/aicp/ui/common/Avatar.kt
// 通用头像。三级回退：图片 → emoji → 名字首字。
//
// 抽成一个组件是因为头像要出现在四个地方（会话列表、聊天页顶栏、消息气泡、性格列表），
// 各写一遍必然出现"这里显示了图片那里还是 emoji"的不一致。
//
// 名字首字这层兜底不是多余的：用户可以把 emoji 删空又不配图，
// 那时候如果什么都不画，界面上就是一个突兀的空圆。
//
// 图片解码走 rememberLocalImage（跟表情、附件同一个 LruCache），
// 头像在列表里反复出现，共用缓存能省下大量重复 decode。

package com.kiq.aicp.ui.common

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.background
import com.kiq.aicp.ui.chat.rememberLocalImage
import java.io.File

@Composable
fun Avatar(
	emoji: String,
	imagePath: String?,
	fallbackName: String,
	resolveFile: (String) -> File,
	size: Dp = 40.dp,
	modifier: Modifier = Modifier,
	background: Color = MaterialTheme.colorScheme.secondaryContainer,
) {
	Box(
		modifier = modifier
			.size(size)
			.clip(CircleShape)
			.background(background),
		contentAlignment = Alignment.Center,
	) {
		val bitmap = if (imagePath.isNullOrBlank()) {
			null
		} else {
			// targetWidthPx 按 dp 值的三倍给：xxhdpi 上 1dp≈3px，再大就是白解码
			rememberLocalImage(resolveFile(imagePath), (size.value * 3).toInt()).value
		}

		when {
			bitmap != null -> Image(
				bitmap = bitmap,
				contentDescription = null,
				modifier = Modifier.size(size),
				contentScale = ContentScale.Crop,
			)

			emoji.isNotBlank() -> Text(
				text = emoji,
				// emoji 字号跟着头像走，不然小头像里的 emoji 会溢出圆形
				fontSize = (size.value * 0.5f).sp,
				textAlign = TextAlign.Center,
			)

			else -> Text(
				text = fallbackName.trim().take(1).ifEmpty { "?" },
				fontSize = (size.value * 0.42f).sp,
				color = MaterialTheme.colorScheme.onSecondaryContainer,
				textAlign = TextAlign.Center,
			)
		}
	}
}
