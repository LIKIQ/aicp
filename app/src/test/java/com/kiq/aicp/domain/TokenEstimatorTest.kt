// app/src/test/java/com/kiq/aicp/domain/TokenEstimatorTest.kt
// token 估算的刻度测试。
// 这里断言的是"折算规则没被人悄悄改掉"，不是"跟真 tokenizer 一致"——
// 估算器本来就允许有偏差，但偏差幅度必须是可预期的，否则压缩阈值会跟着漂。

package com.kiq.aicp.domain

import com.kiq.aicp.domain.memory.TokenEstimator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TokenEstimatorTest {

	@Test
	fun `空串算 0 个 token`() {
		assertEquals(0, TokenEstimator.estimateText(""))
	}

	@Test
	fun `汉字按 0点7 折算并向上取整`() {
		// 10 字 * 0.7 = 7.0
		assertEquals(7, TokenEstimator.estimateText("一二三四五六七八九十"))
		// 2 字 * 0.7 = 1.4 -> 2
		assertEquals(2, TokenEstimator.estimateText("你好"))
	}

	@Test
	fun `拉丁字符按四字符一个 token`() {
		assertEquals(4, TokenEstimator.estimateText("abcdefghijklmnop"))
	}

	@Test
	fun `中英混排等于两段分别折算再合`() {
		// "今天天气" 4 个汉字 * 0.7 = 2.8；空格 + 8 个 ASCII 共 9 字符 / 4 = 2.25；合计 5.05 -> 6
		// 空格算在拉丁那一侧，这条断言就是用来盯住这个口径的
		assertEquals(6, TokenEstimator.estimateText("今天天气 abcdefgh"))
	}

	@Test
	fun `全角标点算进 CJK 而不是拉丁`() {
		// "，。！" 三个全角标点 * 0.7 = 2.1 -> 3；若被当成拉丁则是 ceil(0.75)=1
		assertEquals(3, TokenEstimator.estimateText("，。！"))
	}

	@Test
	fun `单条消息要带上固定开销`() {
		val text = "帮我看下这段代码"
		assertEquals(
			TokenEstimator.estimateText(text) + TokenEstimator.PER_MESSAGE_OVERHEAD,
			TokenEstimator.estimateMessage(text),
		)
	}

	@Test
	fun `多条消息求和等于逐条求和`() {
		val list = listOf("第一句", "second line", "第三句话稍微长一点")
		assertEquals(
			list.sumOf { TokenEstimator.estimateMessage(it) },
			TokenEstimator.estimateMessages(list),
		)
	}

	@Test
	fun `非空文本至少算一个 token`() {
		assertEquals(1, TokenEstimator.estimateText("a"))
		assertTrue(TokenEstimator.estimateText(" ") >= 1)
	}

	@Test
	fun `长文本的估算量级不能跑偏`() {
		// 1000 个汉字应该落在 700 左右，用区间断言防止系数被误改
		val long = "记".repeat(1000)
		val tokens = TokenEstimator.estimateText(long)
		assertTrue("实际 $tokens", tokens in 650..750)
	}
}
