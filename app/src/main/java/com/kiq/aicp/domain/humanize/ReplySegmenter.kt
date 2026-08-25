// app/src/main/java/com/kiq/aicp/domain/humanize/ReplySegmenter.kt
// 把模型一次吐出来的整段回复切成几条短消息，模拟真人连着发几条的节奏。
//
// 为什么不在流式过程中边收边切：那时候手上永远只有半句话，
// "所以" 后面接的是逗号还是句号根本判断不了，切点会乱。
// 收完再切，代价是分段的停顿从第二条才开始，第一条仍然是打字机效果 —— 这个体感反而对，
// 真人也是"第一句已经在打了，后面几句陆续冒出来"。
//
// 三种情况明确不切：
// 1. 带代码块的回复。切开会破坏 ``` 配对，而且技术回答本来就该一次读完
// 2. 太长的回复。那是长篇内容（讲个故事、写段文档），切成五条反而难读
// 3. 本来就只有一句话
//
// 表情标记 [开心] 的内部绝不能出现切点：label 允许带感叹号（用户自己起的名），
// 在标记里面切一刀会同时毁掉标记和图片渲染。

package com.kiq.aicp.domain.humanize

data class HumanizeConfig(
	val enabled: Boolean = true,
	/** 最多切几条。超过三条就有刷屏感了 */
	val maxSegments: Int = 3,
	/**
	 * 一条至少这么多字，否则并进邻居。
	 * 定 6 是照真人发消息的习惯来的："你那边下雨了吗"这种七八个字的短句单独一条很自然，
	 * 但"好的。""嗯。"这种三个字以内的碎条会让屏幕看着很吵。
	 */
	val minSegmentChars: Int = 6,
	/** 整段超过这个长度就不切 */
	val maxSplitLength: Int = 400,
	/** 每个字的打字耗时。真人中文手打大约 250ms/字，那太慢了，取一个有节奏感又不磨人的值 */
	val msPerChar: Int = 55,
	val minSegmentDelayMs: Long = 400,
	val maxSegmentDelayMs: Long = 2_600,
	/** 收到用户消息后先"看一眼"再开始打字 */
	val readDelayMs: Long = 900,
) {
	companion object {
		/** 关掉真人模拟时用这个：一切照旧，一条消息直出 */
		val Disabled = HumanizeConfig(enabled = false)
	}
}

object ReplySegmenter {

	/** 句子结束的标志。中文标点为主，英文只认 ! ? —— 英文句号跟小数点、缩写分不开 */
	private const val SENTENCE_END = "。！？!?…\n"

	/** 跟在句末标点后面、应该归到上一句的收尾字符 */
	private const val TRAILING = "。！？!?…～~\"'」』）)】]>》 　"

	private val BLANK_LINES = Regex("\\n{2,}")

	/** 表情标记的区间，切点落进去就跳过 */
	private val STICKER_MARKER = Regex("""\[[^\[\]\n]{1,20}]""")

	fun split(text: String, config: HumanizeConfig): List<String> {
		val trimmed = text.trim()
		if (trimmed.isEmpty()) return listOf(trimmed)
		if (!config.enabled || config.maxSegments <= 1) return listOf(trimmed)
		if (trimmed.contains("```")) return listOf(trimmed)
		if (trimmed.length > config.maxSplitLength) return listOf(trimmed)

		// 模型自己用空行分了段就尊重它，那是它认为该断开的地方
		val byBlank = trimmed.split(BLANK_LINES).map { it.trim() }.filter { it.isNotEmpty() }
		val raw = if (byBlank.size >= 2) byBlank else splitBySentence(trimmed)

		if (raw.size <= 1) return listOf(trimmed)

		return capSegments(mergeShort(raw, config.minSegmentChars), config.maxSegments)
	}

	/** 某一段发出来之前该停多久。按这段自己的长度算 —— 长的那条本来就该打得久一点 */
	fun typingDelayMs(segment: String, config: HumanizeConfig): Long =
		(segment.length.toLong() * config.msPerChar)
			.coerceIn(config.minSegmentDelayMs, config.maxSegmentDelayMs)

	private fun splitBySentence(text: String): List<String> {
		val guarded = STICKER_MARKER.findAll(text).map { it.range }.toList()
		val parts = mutableListOf<String>()
		var start = 0
		var i = 0

		while (i < text.length) {
			if (text[i] !in SENTENCE_END || guarded.any { i in it }) {
				i++
				continue
			}

			// 把紧跟着的收尾符号一起吃掉，别让右引号被甩到下一条
			var end = i + 1
			while (end < text.length && text[end] in TRAILING) end++

			text.substring(start, end).trim().takeIf { it.isNotEmpty() }?.let { parts += it }
			start = end
			i = end
		}

		text.substring(start).trim().takeIf { it.isNotEmpty() }?.let { parts += it }
		return parts
	}

	/**
	 * 过短的段并进邻居。开头那条太短时往后并（"哈哈" + 后面一句），
	 * 其余往前并 —— 语义上后半句总是贴着前半句的。
	 */
	private fun mergeShort(parts: List<String>, minChars: Int): List<String> {
		if (parts.size <= 1) return parts

		val result = mutableListOf<String>()
		parts.forEach { part ->
			val last = result.lastOrNull()
			if (last != null && last.length < minChars) {
				result[result.lastIndex] = joinPieces(last, part)
			} else {
				result += part
			}
		}

		// 结尾那条如果还是太短，倒回去并进前一条
		if (result.size >= 2 && result.last().length < minChars) {
			val tail = result.removeAt(result.lastIndex)
			result[result.lastIndex] = joinPieces(result.last(), tail)
		}
		return result
	}

	private fun capSegments(parts: List<String>, max: Int): List<String> {
		if (parts.size <= max) return parts
		val head = parts.take(max - 1)
		val tail = parts.drop(max - 1).reduce { acc, s -> joinPieces(acc, s) }
		return head + tail
	}

	/**
	 * 拼回去时用什么连接：原本是空行分的段落，合并后保留换行；
	 * 同一段里的两句话直接接上，中间不补空格 —— 中文不需要，补了反而多一个空隙。
	 */
	private fun joinPieces(left: String, right: String): String =
		if (left.endsWith("\n") || right.startsWith("\n")) "$left$right" else left + right
}
