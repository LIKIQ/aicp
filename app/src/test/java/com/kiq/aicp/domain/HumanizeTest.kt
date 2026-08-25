// app/src/test/java/com/kiq/aicp/domain/HumanizeTest.kt
// 分段器与情绪状态的测试。纯 JVM。
//
// 分段这块最容易出的三个错：把代码块切开、在表情标记中间切一刀、
// 切出一堆"好的。""嗯。"这样的碎条。这里逐个钉住。

package com.kiq.aicp.domain

import com.kiq.aicp.domain.humanize.HumanizeConfig
import com.kiq.aicp.domain.humanize.MoodTracker
import com.kiq.aicp.domain.humanize.ReplySegmenter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HumanizeTest {

	private val config = HumanizeConfig()

	// ---------------- 分段 ----------------

	@Test
	fun `一句话不切`() {
		val segments = ReplySegmenter.split("今天过得还不错", config)

		assertEquals(1, segments.size)
	}

	@Test
	fun `多个句子按句末标点切开`() {
		val segments = ReplySegmenter.split(
			"我今天去逛了公园，天气特别好。回来的路上买了两个橘子。你那边下雨了吗？",
			config,
		)

		assertEquals(3, segments.size)
		assertTrue(segments[0].endsWith("。"))
		assertTrue(segments[2].endsWith("？"))
	}

	@Test
	fun `模型自己用空行分的段落优先尊重`() {
		val segments = ReplySegmenter.split("第一段说的事情比较长一点\n\n第二段说的另一件事", config)

		assertEquals(2, segments.size)
		assertEquals("第一段说的事情比较长一点", segments[0])
		assertEquals("第二段说的另一件事", segments[1])
	}

	@Test
	fun `切完的内容拼起来跟原文一致，不能丢字`() {
		val text = "早上起来有点冷。我加了件外套才出门。路上人不多，很安静。"

		val joined = ReplySegmenter.split(text, config).joinToString("")

		assertEquals(text, joined)
	}

	@Test
	fun `带代码块的回复绝不切`() {
		val text = """
			这样写就行：

			```kotlin
			fun main() {
				println("hi")
			}
			```

			跑一下看看。
		""".trimIndent()

		assertEquals(1, ReplySegmenter.split(text, config).size)
	}

	@Test
	fun `太长的回复不切，那是长篇内容`() {
		val long = "这是一段很长的内容。".repeat(50)

		assertEquals(1, ReplySegmenter.split(long, config).size)
	}

	@Test
	fun `表情标记内部不会被切开`() {
		// label 里带感叹号是允许的，正是这种 label 最容易被句末标点规则切坏
		val segments = ReplySegmenter.split("我来啦[开心啊!]今天想吃火锅。你想吃什么？", config)

		segments.forEach { piece ->
			val open = piece.count { it == '[' }
			val close = piece.count { it == ']' }
			assertEquals("方括号在这条里没配对：$piece", open, close)
		}
		assertTrue(segments.any { it.contains("[开心啊!]") })
	}

	@Test
	fun `过短的碎条会被并进邻居`() {
		val segments = ReplySegmenter.split("好的。我等下就去处理这件事，处理完告诉你。", config)

		assertEquals(1, segments.size)
		assertTrue(segments[0].startsWith("好的。"))
	}

	@Test
	fun `结尾的短句往前并，不留一条光秃秃的尾巴`() {
		val segments = ReplySegmenter.split("我刚刚把东西都收拾好了，桌面清空了。好了。", config)

		assertTrue("最后一条不该只有'好了。'", segments.last().length >= config.minSegmentChars)
	}

	@Test
	fun `段数超过上限时把多的并到最后一条`() {
		val text = "第一件事情说完了。第二件事情也说完了。第三件事情讲完了。第四件事情结束了。"

		val segments = ReplySegmenter.split(text, config.copy(maxSegments = 2, minSegmentChars = 1))

		assertEquals(2, segments.size)
		assertEquals(text, segments.joinToString(""))
	}

	@Test
	fun `关掉真人模拟就一条直出`() {
		val text = "第一句话说完了。第二句话也说完了。第三句话结束了。"

		assertEquals(1, ReplySegmenter.split(text, HumanizeConfig.Disabled).size)
	}

	@Test
	fun `右引号跟着前一句走，不被甩到下一条`() {
		val segments = ReplySegmenter.split(
			"他当时说：「这件事我来办。」然后就真的去办了，效率挺高的。",
			config.copy(minSegmentChars = 1),
		)

		assertTrue("引号被切散了：$segments", segments[0].endsWith("」"))
	}

	@Test
	fun `空字符串不炸`() {
		assertEquals(listOf(""), ReplySegmenter.split("   ", config))
	}

	@Test
	fun `打字停顿按长度算并且卡在上下限之间`() {
		val short = ReplySegmenter.typingDelayMs("嗯", config)
		val normal = ReplySegmenter.typingDelayMs("这是一句差不多二十个字的普通回复内容", config)
		val long = ReplySegmenter.typingDelayMs("字".repeat(500), config)

		assertEquals(config.minSegmentDelayMs, short)
		assertTrue(normal in config.minSegmentDelayMs..config.maxSegmentDelayMs)
		assertEquals(config.maxSegmentDelayMs, long)
	}

	// ---------------- 情绪 ----------------

	@Test
	fun `夸一句心情往上走，骂一句往下走`() {
		assertEquals(1, MoodTracker.next(0, 0, 0, "谢谢你，太好了"))
		assertEquals(-1, MoodTracker.next(0, 0, 0, "真烦"))
	}

	@Test
	fun `情绪封顶不会无限累积`() {
		var mood = 0
		repeat(10) { mood = MoodTracker.next(mood, 0, 0, "谢谢") }

		assertEquals(MoodTracker.MAX, mood)
	}

	@Test
	fun `同一个词说多遍只算一次`() {
		assertEquals(1, MoodTracker.scoreOf("谢谢谢谢谢谢谢谢"))
	}

	@Test
	fun `正负都命中时数量少的那边不算数`() {
		assertEquals(0, MoodTracker.scoreOf("谢谢，不过我有点烦"))
		assertEquals(-1, MoodTracker.scoreOf("谢谢，但真的很烦很讨厌"))
	}

	@Test
	fun `平静状态说普通话不动情绪`() {
		assertEquals(0, MoodTracker.next(0, 0, 0, "今天几号"))
	}

	@Test
	fun `隔了很久情绪会自己平复`() {
		val start = 1_000_000L
		val twoSteps = start + MoodTracker.DECAY_MILLIS * 2

		assertEquals(0, MoodTracker.decay(2, start, twoSteps))
		assertEquals(-1, MoodTracker.decay(-2, start, start + MoodTracker.DECAY_MILLIS))
	}

	@Test
	fun `没到衰减周期不动`() {
		val start = 1_000_000L

		assertEquals(2, MoodTracker.decay(2, start, start + MoodTracker.DECAY_MILLIS / 2))
	}

	@Test
	fun `没有历史时间戳时不衰减，避免把旧数据一次清零`() {
		assertEquals(2, MoodTracker.decay(2, 0L, 999_999_999L))
	}

	@Test
	fun `平静状态没有额外提示词，不给模型塞废话`() {
		assertEquals("", MoodTracker.describe(0))
		assertTrue(MoodTracker.describe(-2).isNotEmpty())
		assertTrue(MoodTracker.describe(2).isNotEmpty())
	}

	@Test
	fun `超出范围的存量数值也能安全描述`() {
		assertEquals(MoodTracker.describe(2), MoodTracker.describe(9))
		assertEquals(MoodTracker.describe(-2), MoodTracker.describe(-9))
	}
}
