// app/src/main/java/com/kiq/aicp/domain/memory/TokenEstimator.kt
// token 估算。
//
// 为什么不接真 tokenizer：BPE 词表动辄几 MB，还跟具体模型绑定（GPT / DeepSeek / Qwen 各不一样），
// 端上背这个不值。这里只需要"够准的预算刻度"——决定什么时候触发压缩、上下文塞到哪一条为止，
// 估偏 10~20% 完全能接受，因为预算本身就留了余量。
//
// 经验系数（对中英混排的常见分词器都成立）：
//   CJK 字符 ≈ 0.7 token/字，拉丁文本 ≈ 1 token/4 字符
//   每条消息还有 role 和分隔符的固定开销，按 4 token 记

package com.kiq.aicp.domain.memory

import kotlin.math.ceil

object TokenEstimator {

	/** 每条消息在 chat 格式里的固定开销 */
	const val PER_MESSAGE_OVERHEAD = 4

	private const val CJK_RATE = 0.7
	private const val LATIN_CHARS_PER_TOKEN = 4.0

	/** 纯文本估算，不含消息固定开销 */
	fun estimateText(text: String): Int {
		if (text.isEmpty()) return 0

		var cjk = 0
		var other = 0
		for (ch in text) {
			if (isCjk(ch)) cjk++ else other++
		}

		val tokens = cjk * CJK_RATE + other / LATIN_CHARS_PER_TOKEN
		return ceil(tokens).toInt().coerceAtLeast(1)
	}

	/** 一条聊天消息的估算，含 role / 分隔符开销 */
	fun estimateMessage(content: String): Int = estimateText(content) + PER_MESSAGE_OVERHEAD

	fun estimateMessages(contents: List<String>): Int =
		contents.sumOf { estimateMessage(it) }

	/**
	 * CJK 判定：中日韩统一表意文字 + 扩展 A + 兼容表意 + 假名 + 谚文 + 全角标点。
	 * 表情符号落在补充平面（代理对），这里按 other 计，两个 char 折 0.5 token —— 跟实测的 emoji 开销接近。
	 */
	private fun isCjk(ch: Char): Boolean {
		val code = ch.code
		return code in 0x4E00..0x9FFF ||
			code in 0x3400..0x4DBF ||
			code in 0xF900..0xFAFF ||
			code in 0x3040..0x30FF ||
			code in 0xAC00..0xD7AF ||
			code in 0x3000..0x303F ||
			code in 0xFF00..0xFF60
	}
}
