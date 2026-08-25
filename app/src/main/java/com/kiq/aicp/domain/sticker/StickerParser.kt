// app/src/main/java/com/kiq/aicp/domain/sticker/StickerParser.kt
// 把带 [标记] 的文本切成"文字 / 表情"交替的片段，供气泡渲染和使用统计。
//
// 三条刻意的取舍：
//
// 1. 查不到的标记保持原文。用户自己打 [笑] 但没导入过这张图时，
//    直接吞掉会让消息缺一块，还不如原样显示。
//
// 2. 正则排除后面紧跟 "(" 的情况。模型很爱输出 markdown 链接 [标题](url)，
//    要是"标题"恰好撞上某个表情 label，链接就会被拆成表情+一串裸 url。
//
// 3. 不处理代码块。要正确判断 ``` 围栏内外得写半个 markdown 解析器，
//    而表情 label 通常是中文（开心、无语），跟代码里的中括号内容撞车概率很低。
//    真撞了用户能改 label 绕开，代价可接受。

package com.kiq.aicp.domain.sticker

sealed interface StickerSegment {
	data class Text(val text: String) : StickerSegment

	data class Image(val label: String, val localPath: String) : StickerSegment
}

object StickerParser {

	/**
	 * 标记长度卡在 20 字符：真表情名不会更长，
	 * 放宽反而会把大段方括号内容（引用块、数组字面量）拖进来试匹配。
	 *
	 * 开放给 StickerGroupResolver 复用：分组名替换和渲染切片必须用同一套匹配规则，
	 * 两边各写一个正则，早晚出现"这边认那边不认"的标记。
	 */
	internal val MARKER = Regex("""\[([^\[\]\n]{1,20})](?!\()""")

	/** 注入 system prompt 的表情清单上限，防止标记列表本身吃掉上千 token */
	const val PROMPT_LIMIT = 40

	/**
	 * @param resolve 传入 label 返回本地图片相对路径，查不到返回 null
	 */
	fun parse(text: String, resolve: (String) -> String?): List<StickerSegment> {
		if (text.isEmpty()) return emptyList()

		val segments = mutableListOf<StickerSegment>()
		var cursor = 0

		MARKER.findAll(text).forEach { match ->
			val label = match.groupValues[1]
			val path = resolve(label) ?: return@forEach

			if (match.range.first > cursor) {
				segments += StickerSegment.Text(text.substring(cursor, match.range.first))
			}
			segments += StickerSegment.Image(label, path)
			cursor = match.range.last + 1
		}

		if (cursor < text.length) segments += StickerSegment.Text(text.substring(cursor))
		return segments
	}

	/** 命中的表情 label，按出现顺序去重。用来累加 useCount */
	fun labelsIn(text: String, known: Set<String>): List<String> =
		MARKER.findAll(text)
			.map { it.groupValues[1] }
			.filter { it in known }
			.distinct()
			.toList()

	/** 文本里是否至少有一个能用的表情标记，UI 用来决定走不走富文本渲染 */
	fun hasSticker(text: String, known: Set<String>): Boolean =
		MARKER.findAll(text).any { it.groupValues[1] in known }

	/**
	 * 告诉模型有哪些表情可用。
	 * 说明写得直白一点：见过模型把标记写成「(开心)」「*开心*」各种变体，
	 * 明确"只有方括号这一种写法算"能显著减少这类跑偏。
	 */
	fun buildPrompt(labels: List<String>): String {
		if (labels.isEmpty()) return ""
		return buildString {
			append("【可用表情】你可以在回复里插入表情图，写法是英文方括号包住表情名，例如 [")
			append(labels.first())
			append("]。只有方括号这一种写法会被识别成图片，圆括号或星号都不行。\n")
			append("现有表情：")
			append(labels.joinToString("、") { "[$it]" })
			append("\n表情要和说话内容搭得上再发，不用每句都带。清单里没有的名字不要自己造，写了也只会显示成文字。")
		}
	}
}
