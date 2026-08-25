// app/src/test/java/com/kiq/aicp/domain/StickerParserTest.kt
// [标记] 解析的边界测试。纯 JVM，不碰 Android。
//
// 重点守两类误判：
// - markdown 链接 [标题](url) 不能被当成表情，模型输出链接的频率很高
// - 查不到的标记必须原样留着，不能吞掉

package com.kiq.aicp.domain

import com.kiq.aicp.domain.sticker.StickerParser
import com.kiq.aicp.domain.sticker.StickerSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StickerParserTest {

	private val known = mapOf(
		"开心" to "stickers/happy.png",
		"无语" to "stickers/speechless.png",
		"猫猫头" to "stickers/cat.gif",
	)

	private val resolve: (String) -> String? = { known[it] }

	private fun textOf(segments: List<StickerSegment>): String =
		segments.filterIsInstance<StickerSegment.Text>().joinToString("") { it.text }

	private fun imagesOf(segments: List<StickerSegment>): List<String> =
		segments.filterIsInstance<StickerSegment.Image>().map { it.label }

	@Test
	fun `纯文字只产出一个文本段`() {
		val segments = StickerParser.parse("今天天气不错", resolve)

		assertEquals(1, segments.size)
		assertTrue(segments[0] is StickerSegment.Text)
	}

	@Test
	fun `标记被切成独立的图片段，两侧文字各自成段`() {
		val segments = StickerParser.parse("你好[开心]再见", resolve)

		assertEquals(3, segments.size)
		assertEquals("你好", (segments[0] as StickerSegment.Text).text)
		assertEquals("开心", (segments[1] as StickerSegment.Image).label)
		assertEquals("stickers/happy.png", (segments[1] as StickerSegment.Image).localPath)
		assertEquals("再见", (segments[2] as StickerSegment.Text).text)
	}

	@Test
	fun `开头和结尾的标记不会产生空文本段`() {
		val head = StickerParser.parse("[开心]后面", resolve)
		assertEquals(2, head.size)
		assertTrue(head[0] is StickerSegment.Image)

		val tail = StickerParser.parse("前面[开心]", resolve)
		assertEquals(2, tail.size)
		assertTrue(tail[1] is StickerSegment.Image)

		val only = StickerParser.parse("[开心]", resolve)
		assertEquals(1, only.size)
		assertTrue(only[0] is StickerSegment.Image)
	}

	@Test
	fun `连续两个标记之间不插空文本`() {
		val segments = StickerParser.parse("[开心][无语]", resolve)

		assertEquals(2, segments.size)
		assertEquals(listOf("开心", "无语"), imagesOf(segments))
	}

	@Test
	fun `查不到的标记原样留在文本里`() {
		val segments = StickerParser.parse("这个[不存在的表情]怎么办", resolve)

		assertEquals(1, segments.size)
		assertEquals("这个[不存在的表情]怎么办", textOf(segments))
	}

	@Test
	fun `已知和未知标记混在一起时各归各位`() {
		val segments = StickerParser.parse("[开心]然后[没有这个]接着[无语]", resolve)

		assertEquals(listOf("开心", "无语"), imagesOf(segments))
		assertEquals("然后[没有这个]接着", textOf(segments))
	}

	@Test
	fun `markdown 链接不会被误当成表情`() {
		val segments = StickerParser.parse("看这个[开心](https://example.com/a)链接", resolve)

		assertTrue("链接标题被吃成表情了", imagesOf(segments).isEmpty())
		assertEquals("看这个[开心](https://example.com/a)链接", textOf(segments))
	}

	@Test
	fun `跨行的方括号不匹配`() {
		val segments = StickerParser.parse("[开\n心]", resolve)

		assertTrue(imagesOf(segments).isEmpty())
	}

	@Test
	fun `嵌套方括号不会匹配到外层`() {
		val segments = StickerParser.parse("[[开心]]", resolve)

		// 内层 [开心] 命中，外层的方括号作为文字留下
		assertEquals(listOf("开心"), imagesOf(segments))
		assertEquals("[]", textOf(segments))
	}

	@Test
	fun `超长方括号内容不参与匹配`() {
		val long = "这是一段非常长的方括号内容用来确认正则的长度上限生效了"
		val segments = StickerParser.parse("[$long]", resolve)

		assertTrue(imagesOf(segments).isEmpty())
	}

	@Test
	fun `空文本返回空列表`() {
		assertTrue(StickerParser.parse("", resolve).isEmpty())
	}

	@Test
	fun `labelsIn 只返回已知标记且去重`() {
		val labels = StickerParser.labelsIn("[开心][无语][开心][没这个]", known.keys)

		assertEquals(listOf("开心", "无语"), labels)
	}

	@Test
	fun `hasSticker 判断是否值得走富文本渲染`() {
		assertTrue(StickerParser.hasSticker("嗨[开心]", known.keys))
		assertFalse(StickerParser.hasSticker("嗨[没这个]", known.keys))
		assertFalse(StickerParser.hasSticker("纯文字", known.keys))
	}

	@Test
	fun `提示词里明确写了只认方括号，并列出全部标记`() {
		val prompt = StickerParser.buildPrompt(listOf("开心", "无语"))

		assertTrue(prompt.contains("[开心]"))
		assertTrue(prompt.contains("[无语]"))
		assertTrue("要明确排除圆括号写法", prompt.contains("圆括号"))
	}

	@Test
	fun `没有表情时提示词为空，不给模型塞废话`() {
		assertEquals("", StickerParser.buildPrompt(emptyList()))
	}
}
