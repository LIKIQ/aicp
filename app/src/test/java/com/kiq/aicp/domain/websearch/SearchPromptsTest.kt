// app/src/test/java/com/kiq/aicp/domain/websearch/SearchPromptsTest.kt
// 判定结果解析的契约：小模型会用各种方式糟蹋这个 JSON，
// 但只要它表达的意思是"要搜 + 搜什么"，我们就得认；表达不清就一律当"不搜"。
// 判定失败绝不能阻断回复，所以这里没有"抛异常"这种预期结果。

package com.kiq.aicp.domain.websearch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchPromptsTest {

	@Test
	fun `标准 JSON 直接解析`() {
		val d = SearchPrompts.parseDecision("""{"search": true, "query": "上海 天气 今天"}""")

		assertTrue(d.shouldSearch)
		assertEquals("上海 天气 今天", d.query)
	}

	@Test
	fun `代码围栏包着也能解析`() {
		val d = SearchPrompts.parseDecision(
			"""
			```json
			{"search": true, "query": "deepseek v4 价格"}
			```
			""".trimIndent(),
		)

		assertTrue(d.shouldSearch)
		assertEquals("deepseek v4 价格", d.query)
	}

	@Test
	fun `前后夹着客套话也能解析`() {
		val d = SearchPrompts.parseDecision("好的，我判断如下：{\"search\": true, \"query\": \"天气\"} 希望有帮助")

		assertTrue(d.shouldSearch)
		assertEquals("天气", d.query)
	}

	@Test
	fun `模型说不用搜`() {
		assertFalse(SearchPrompts.parseDecision("""{"search": false}""").shouldSearch)
	}

	@Test
	fun `认 need_search 和 keywords 这对别名`() {
		val d = SearchPrompts.parseDecision("""{"need_search": true, "keywords": "北京 房价"}""")

		assertTrue(d.shouldSearch)
		assertEquals("北京 房价", d.query)
	}

	@Test
	fun `说要搜却不给词就当不搜`() {
		assertFalse(SearchPrompts.parseDecision("""{"search": true, "query": "  "}""").shouldSearch)
	}

	@Test
	fun `非法 JSON 一律当不搜`() {
		assertFalse(SearchPrompts.parseDecision("我觉得需要搜一下天气").shouldSearch)
		assertFalse(SearchPrompts.parseDecision("").shouldSearch)
		assertFalse(SearchPrompts.parseDecision("{search: yes,,,}").shouldSearch)
	}

	@Test
	fun `检索词过长被截断且换行被压平`() {
		val long = "词".repeat(120)
		val d = SearchPrompts.parseDecision("""{"search": true, "query": "北京\n天气 $long"}""")

		assertTrue(d.shouldSearch)
		assertEquals(SearchPrompts.MAX_QUERY_CHARS, d.query.length)
		assertFalse(d.query.contains('\n'))
	}

	@Test
	fun `判定提示词带上日期和对话尾部`() {
		val prompt = SearchPrompts.decisionPrompt(
			today = "2026-08-26",
			tail = listOf("用户：帮我查下上海今天天气", "小雨：好呀"),
		)

		assertTrue(prompt.contains("2026-08-26"))
		assertTrue(prompt.contains("帮我查下上海今天天气"))
	}

	@Test
	fun `对话尾部只取最后几条`() {
		val tail = (1..20).map { "用户：第 $it 句" }
		val prompt = SearchPrompts.decisionPrompt("2026-08-26", tail)

		assertTrue(prompt.contains("第 20 句"))
		assertFalse(prompt.contains("第 1 句\n"))
	}
}
