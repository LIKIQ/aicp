// app/src/test/java/com/kiq/aicp/domain/websearch/WebSearchComposerTest.kt
// 注入段的格式契约。这段文字是模型唯一能看到的"网上信息"，
// 所以三件事必须钉住：没结果时一个字都不加、正文优先于摘要、
// 那两句约束（不是你的记忆 / 别说根据搜索结果）必须在。

package com.kiq.aicp.domain.websearch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebSearchComposerTest {

	private fun hit(
		title: String = "上海今天天气",
		link: String = "https://www.tianqi.com/shanghai/",
		snippet: String = "上海天气网提供实时温度与降水概率",
		passage: String = "",
	) = SearchHit(title = title, link = link, snippet = snippet, passage = passage)

	@Test
	fun `没有结果时返回空串`() {
		assertEquals("", WebSearchComposer.compose(WebSearchOutcome.Empty, "2026-08-26"))
		assertEquals(
			"",
			WebSearchComposer.compose(WebSearchOutcome("天气", emptyList()), "2026-08-26"),
		)
	}

	@Test
	fun `标题带上检索日期和检索词`() {
		val text = WebSearchComposer.compose(
			WebSearchOutcome("上海 天气 今天", listOf(hit())),
			"2026-08-26",
		)

		assertTrue(text.startsWith(WebSearchComposer.SECTION_TITLE))
		assertTrue(text.contains("2026-08-26"))
		assertTrue(text.contains("上海 天气 今天"))
	}

	@Test
	fun `两句防串味的约束必须在`() {
		val text = WebSearchComposer.compose(WebSearchOutcome("天气", listOf(hit())), "2026-08-26")

		assertTrue(text.contains("不是你本来就知道的事"))
		assertTrue(text.contains("根据搜索结果"))
	}

	@Test
	fun `有正文节选时不再重复摘要`() {
		val text = WebSearchComposer.compose(
			WebSearchOutcome(
				"天气",
				listOf(hit(snippet = "这是摘要", passage = "这是正文节选：31℃")),
			),
			"2026-08-26",
		)

		assertTrue(text.contains("这是正文节选：31℃"))
		assertFalse(text.contains("这是摘要"))
	}

	@Test
	fun `没抓到正文就退回摘要`() {
		val text = WebSearchComposer.compose(
			WebSearchOutcome("天气", listOf(hit(snippet = "这是摘要", passage = ""))),
			"2026-08-26",
		)

		assertTrue(text.contains("这是摘要"))
	}

	@Test
	fun `来源只写域名且去掉 www`() {
		val text = WebSearchComposer.compose(
			WebSearchOutcome("天气", listOf(hit(link = "https://www.tianqi.com/beijing/today/"))),
			"2026-08-26",
		)

		assertTrue(text.contains("（来源：tianqi.com）"))
		assertFalse(text.contains("/beijing/today/"))
	}

	@Test
	fun `多条结果各成一段`() {
		val text = WebSearchComposer.compose(
			WebSearchOutcome(
				"天气",
				listOf(hit(title = "第一条"), hit(title = "第二条"), hit(title = "第三条")),
			),
			"2026-08-26",
		)

		assertEquals(3, Regex("^## ", RegexOption.MULTILINE).findAll(text).count())
	}

	@Test
	fun `标题空着也不会拼出空的小标题`() {
		val text = WebSearchComposer.compose(
			WebSearchOutcome("天气", listOf(hit(title = "  "))),
			"2026-08-26",
		)

		assertTrue(text.contains("## （无标题）"))
	}
}
