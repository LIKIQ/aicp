// app/src/main/java/com/kiq/aicp/ui/chat/StickerText.kt
// 把带 [标记] 的消息渲染成"文字 + 表情图"混排。
//
// 走 InlineTextContent 而不是把文字切成一堆 Text 拼进 Row：
// 后者一旦换行就会错位，表情跟它前后的字被拆到两行去。
// InlineTextContent 是 Compose 自己的行内占位机制，换行、选中、行高都由 Text 统一算。
//
// Placeholder 的尺寸只能用 sp。这意味着表情会跟着系统字体缩放一起变大 ——
// 这里当成特性而不是问题：字调大的人本来就希望画面里的东西都大一点。
//
// 只有一个表情且没有别的文字时走大图分支，不套气泡。主流 IM 都是这么处理的，
// 一张表情被小气泡框住会显得很局促。

package com.kiq.aicp.ui.chat

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.Text
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kiq.aicp.domain.sticker.StickerParser
import com.kiq.aicp.domain.sticker.StickerSegment
import java.io.File

/** 行内表情的边长，跟正文字号成比例 */
private val INLINE_STICKER_SIZE = 30.sp

/** 独立成条的大表情 */
private val SOLO_STICKER_SIZE = 120.dp

/**
 * @param stickerIndex label → 相对路径。空表示这个会话没有可用表情，直接当纯文本渲染
 */
@Composable
fun StickerText(
	text: String,
	stickerIndex: Map<String, String>,
	resolveFile: (String) -> File,
	style: TextStyle,
	color: Color,
	modifier: Modifier = Modifier,
) {
	val segments = remember(text, stickerIndex) {
		if (stickerIndex.isEmpty()) emptyList() else StickerParser.parse(text) { stickerIndex[it] }
	}

	val images = segments.filterIsInstance<StickerSegment.Image>()
	if (images.isEmpty()) {
		Text(text = text, style = style, color = color, modifier = modifier)
		return
	}

	val inlineContent = mutableMapOf<String, InlineTextContent>()
	val annotated = buildAnnotatedString {
		segments.forEachIndexed { index, segment ->
			when (segment) {
				is StickerSegment.Text -> append(segment.text)

				is StickerSegment.Image -> {
					val id = "sticker_$index"
					// 第二个参数是无法渲染时的兜底文本，比如复制走的时候
					appendInlineContent(id, "[${segment.label}]")
					inlineContent[id] = InlineTextContent(
						Placeholder(
							width = INLINE_STICKER_SIZE,
							height = INLINE_STICKER_SIZE,
							placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter,
						),
					) {
						InlineSticker(resolveFile(segment.localPath), segment.label)
					}
				}
			}
		}
	}

	Text(
		text = annotated,
		inlineContent = inlineContent,
		style = style,
		color = color,
		modifier = modifier,
	)
}

/** 整条消息只有一个表情时用的大图 */
@Composable
fun SoloSticker(localPath: String, label: String, resolveFile: (String) -> File) {
	val bitmap by rememberLocalImage(resolveFile(localPath), 360)

	Box(
		modifier = Modifier.size(SOLO_STICKER_SIZE),
		contentAlignment = Alignment.Center,
	) {
		bitmap?.let {
			Image(
				bitmap = it,
				contentDescription = "表情 $label",
				modifier = Modifier.size(SOLO_STICKER_SIZE),
				contentScale = ContentScale.Fit,
			)
		}
	}
}

@Composable
private fun InlineSticker(file: File, label: String) {
	val bitmap by rememberLocalImage(file, 120)

	Box(
		modifier = Modifier
			.size(30.dp)
			.clip(RoundedCornerShape(4.dp)),
		contentAlignment = Alignment.Center,
	) {
		bitmap?.let {
			Image(
				bitmap = it,
				contentDescription = "表情 $label",
				modifier = Modifier.size(30.dp),
				contentScale = ContentScale.Fit,
			)
		}
	}
}

/**
 * 判断这条消息是不是"光秃秃一个表情"。
 * 前后的空白不算内容 —— 模型很爱在表情后面多打一个空格。
 */
fun soloStickerOf(text: String, stickerIndex: Map<String, String>): StickerSegment.Image? {
	if (stickerIndex.isEmpty()) return null
	val segments = StickerParser.parse(text.trim()) { stickerIndex[it] }
	val single = segments.singleOrNull() ?: return null
	return single as? StickerSegment.Image
}
