// app/src/test/java/com/kiq/aicp/domain/websearch/WebSearchServiceTest.kt
// 联网搜索编排层的测试：判定 → 搜 → 抓正文 这一整条链路。
//
// 盯着四件事：
// 1. 开关关掉、没有对话历史、没配 Base URL / Key 这三种情况必须零成本 ——
//    一次模型调用都不许发出去。这是花钱的地方，回归了用户只会看到账单涨，
//    UI 上一点异常都看不出来。
// 2. 判定说不用搜，连搜索客户端都不该被碰。
// 3. 判定走的是压缩模型那条便宜路，不是主模型。
// 4. 判定抛错、搜索返回 null、RSS 里一条结果都没有、页面抓不下来、抓到的是 SPA 空壳 ——
//    一律静默降级成"这轮没搜"，不抛异常、不把整轮回复带下水。
//
// 另外钉住"只抓 quota 篇"这条：webSearchFetchPages 是用户可调的，
// 写成 forEach 抓全部只会多几个 HTTP 往返，测不出来但账和时延都会变。
//
// 用 Robolectric 是因为失败分支里有 android.util.Log.w，
// 裸 JVM 跑那句会抛 Stub!，把"静默降级"的用例弄成假红。

package com.kiq.aicp.domain.websearch

import com.kiq.aicp.data.remote.LlmChunk
import com.kiq.aicp.data.remote.LlmException
import com.kiq.aicp.data.remote.LlmMessage
import com.kiq.aicp.data.remote.LlmParams
import com.kiq.aicp.data.remote.LlmProvider
import com.kiq.aicp.data.remote.WebSearchClient
import com.kiq.aicp.domain.model.AicpSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WebSearchServiceTest {

	private val provider = FakeLlmProvider()
	private val client = FakeWebSearchClient()

	/** 钉住"今天"，判定提示词和注入段里的日期就能直接断言 */
	private val service = WebSearchService(provider, client, today = { TODAY })

	// ---------------- 固定装置 ----------------

	/**
	 * hasEndpoint 要求 baseUrl 和 apiKey 都非空，否则 run() 在判定之前就返回 Empty，
	 * 后面的逻辑一条都测不到。compressModel 刻意跟 model 写成不同的值，
	 * 这样才能验出判定到底用了哪个。
	 */
	private fun settings(
		enabled: Boolean = true,
		baseUrl: String = "https://api.example.com/v1",
		apiKey: String = "sk-test-key",
		fetchPages: Int = 0,
		resultCount: Int = 5,
		pageChars: Int = 600,
	) = AicpSettings(
		baseUrl = baseUrl,
		apiKey = apiKey,
		model = "main-big",
		compressModel = "cheap-mini",
		webSearchEnabled = enabled,
		webSearchResultCount = resultCount,
		webSearchFetchPages = fetchPages,
		webSearchPageChars = pageChars,
	)

	private val tail = listOf("用户：北京今天天气怎么样", "小雨：我去看看")

	// ---------------- 零成本的三条短路 ----------------

	@Test
	fun `开关关掉时直接返回空结果，一次模型调用都不发`() = runTest {
		val outcome = service.run(tail, settings(enabled = false))

		assertEquals(WebSearchOutcome.Empty, outcome)
		assertEquals("关掉开关还在调模型，等于白花钱", 0, provider.completeCount)
		assertTrue(client.queries.isEmpty())
	}

	@Test
	fun `没有对话历史时不判定也不搜`() = runTest {
		val outcome = service.run(emptyList(), settings())

		assertTrue(outcome.isEmpty)
		assertEquals(0, provider.completeCount)
		assertEquals(0, client.fetched.size)
	}

	@Test
	fun `没配 Base URL 或 Key 时不判定也不搜`() = runTest {
		assertTrue(service.run(tail, settings(baseUrl = "")).isEmpty)
		assertTrue(service.run(tail, settings(apiKey = "")).isEmpty)
		assertTrue(service.run(tail, settings(baseUrl = " ", apiKey = " ")).isEmpty)

		assertEquals(0, provider.completeCount)
		assertTrue(client.queries.isEmpty())
	}

	// ---------------- 判定 ----------------

	@Test
	fun `判定说不用搜时连搜索客户端都不碰`() = runTest {
		provider.reply = """{"search": false}"""

		val outcome = service.run(tail, settings())

		assertEquals(WebSearchOutcome.Empty, outcome)
		assertEquals(1, provider.completeCount)
		assertTrue("判定说不搜却还是搜了：${client.queries}", client.queries.isEmpty())
	}

	@Test
	fun `判定走压缩模型而不是主模型`() = runTest {
		provider.reply = DECIDE_YES
		client.rss = RSS_TWO_ITEMS
		val config = settings()

		service.run(tail, config)

		assertEquals(1, provider.paramsSeen.size)
		assertEquals(config.effectiveCompressModel(), provider.paramsSeen.single().model)
		assertEquals("cheap-mini", provider.paramsSeen.single().model)
		assertFalse("判定用了主模型，钱白花", provider.paramsSeen.single().model == config.model)
		// 顺手确认注入的"今天"确实进了判定提示词，否则模型没法把"今天"翻成检索词
		assertTrue(provider.messagesSeen.single().single().content.contains("今天是 $TODAY。"))
	}

	@Test
	fun `判定抛异常时静默降级，不抛给调用方`() = runTest {
		provider.failure = LlmException("服务端 500", LlmException.Kind.SERVER)
		client.rss = RSS_TWO_ITEMS

		val outcome = service.run(tail, settings())

		assertEquals(WebSearchOutcome.Empty, outcome)
		assertEquals(1, provider.completeCount)
		assertTrue("判定都失败了还去搜", client.queries.isEmpty())
	}

	// ---------------- 搜索 ----------------

	@Test
	fun `判定要搜时按 RSS 解析出命中条目`() = runTest {
		provider.reply = DECIDE_YES
		client.rss = RSS_TWO_ITEMS

		val outcome = service.run(tail, settings())

		assertEquals(QUERY, outcome.query)
		assertEquals(listOf(QUERY), client.queries)
		assertEquals(2, outcome.hits.size)

		val first = outcome.hits[0]
		assertEquals("北京天气预报 - 天气网", first.title)
		assertEquals("https://www.tianqi.com/beijing/", first.link)
		assertEquals("北京今天多云，最高气温 26℃", first.snippet)

		assertEquals("北京实时空气质量", outcome.hits[1].title)
		assertEquals("https://www.aqistudy.cn/beijing/", outcome.hits[1].link)
	}

	@Test
	fun `搜索接口返回 null 时返回空结果且不抛异常`() = runTest {
		provider.reply = DECIDE_YES
		client.rss = null

		val outcome = service.run(tail, settings(fetchPages = 1))

		assertEquals(WebSearchOutcome.Empty, outcome)
		assertEquals(listOf(QUERY), client.queries)
		assertTrue("搜都没搜到还去抓页面", client.fetched.isEmpty())
	}

	@Test
	fun `RSS 里一条 item 都没有时返回空结果`() = runTest {
		provider.reply = DECIDE_YES
		client.rss = RSS_NO_ITEM

		val outcome = service.run(tail, settings(fetchPages = 1))

		assertEquals(WebSearchOutcome.Empty, outcome)
		assertEquals(1, client.queries.size)
		assertTrue(client.fetched.isEmpty())
	}

	// ---------------- 抓正文 ----------------

	@Test
	fun `抓页面配额为零时一个页面都不抓`() = runTest {
		provider.reply = DECIDE_YES
		client.rss = RSS_TWO_ITEMS
		client.page = WEATHER_PAGE

		val outcome = service.run(tail, settings(fetchPages = 0))

		assertEquals(2, outcome.hits.size)
		assertEquals("配额是 0 却发了 HTTP 请求：${client.fetched}", 0, client.fetched.size)
		assertTrue(outcome.hits.all { it.passage.isEmpty() })
		// 摘要必须还在，否则这轮等于什么都没给模型
		assertTrue(outcome.hits.all { it.snippet.isNotEmpty() })
	}

	@Test
	fun `配额为一时只抓第一条，第二条仍然只有摘要`() = runTest {
		provider.reply = DECIDE_YES
		client.rss = RSS_TWO_ITEMS
		client.page = WEATHER_PAGE

		val outcome = service.run(tail, settings(fetchPages = 1))

		assertEquals(2, outcome.hits.size)
		assertEquals(listOf("https://www.tianqi.com/beijing/"), client.fetched)

		val picked = outcome.hits[0].passage
		assertTrue("第一条应该拿到正文，实际是空的", picked.isNotEmpty())
		assertTrue("丢了天气标题行：\n$picked", picked.contains("最高气温 26℃"))
		assertTrue("丢了数据行：\n$picked", picked.contains("空气质量：优 湿度：75%"))
		assertFalse("导航菜单混进正文了：\n$picked", picked.contains("首页"))
		assertEquals(2, picked.lines().size)

		assertEquals("超配额的第二条不该有正文", "", outcome.hits[1].passage)
	}

	@Test
	fun `页面抓失败时退回只用摘要，整轮不作废`() = runTest {
		provider.reply = DECIDE_YES
		client.rss = RSS_TWO_ITEMS
		client.page = null

		val outcome = service.run(tail, settings(fetchPages = 1))

		assertEquals(2, outcome.hits.size)
		assertEquals(listOf("https://www.tianqi.com/beijing/"), client.fetched)
		assertTrue(outcome.hits.all { it.passage.isEmpty() })
		assertEquals("北京今天多云，最高气温 26℃", outcome.hits[0].snippet)
	}

	@Test
	fun `抓到 SPA 空壳时正文为空但命中照常返回`() = runTest {
		provider.reply = DECIDE_YES
		client.rss = RSS_TWO_ITEMS
		client.page = "<html><body><script>var a=1</script></body></html>"

		val outcome = service.run(tail, settings(fetchPages = 1))

		assertEquals(2, outcome.hits.size)
		assertEquals(1, client.fetched.size)
		assertEquals("", outcome.hits[0].passage)
		assertTrue(outcome.hits[0].snippet.isNotEmpty())
	}

	// ---------------- 注入段 ----------------

	@Test
	fun `compose 空结果给空串，有结果时带上注入的日期`() = runTest {
		assertEquals("", service.compose(WebSearchOutcome.Empty))

		provider.reply = DECIDE_YES
		client.rss = RSS_TWO_ITEMS
		val text = service.compose(service.run(tail, settings()))

		assertTrue(text.startsWith(WebSearchComposer.SECTION_TITLE))
		assertTrue("注入段里没带检索日期：\n$text", text.contains(TODAY))
		assertTrue(text.contains(QUERY))
		assertTrue(text.contains("## 北京天气预报 - 天气网"))
		assertTrue(text.contains("（来源：tianqi.com）"))
	}

	private companion object {
		const val TODAY = "2026-08-26"
		const val QUERY = "北京 天气 今天"

		val DECIDE_YES = """{"search": true, "query": "$QUERY"}"""

		/** channel 自己也带 title/link，用来兜住"别把频道信息当第一条结果"那条约束 */
		val RSS_TWO_ITEMS = """
			<?xml version="1.0" encoding="UTF-8"?>
			<rss version="2.0"><channel>
			<title>必应：北京 天气</title>
			<link>https://cn.bing.com/search?q=beijing</link>
			<item>
				<title>北京天气预报 - 天气网</title>
				<link>https://www.tianqi.com/beijing/</link>
				<description>北京今天多云，最高气温 <b>26</b>℃</description>
				<pubDate>Tue, 26 Aug 2026 09:00:00 GMT</pubDate>
			</item>
			<item>
				<title>北京实时空气质量</title>
				<link>https://www.aqistudy.cn/beijing/</link>
				<description>北京 AQI 42，空气质量优</description>
			</item>
			</channel></rss>
		""".trimIndent()

		val RSS_NO_ITEM = """
			<?xml version="1.0" encoding="UTF-8"?>
			<rss version="2.0"><channel>
			<title>必应：北京 天气</title>
			<link>https://cn.bing.com/search?q=beijing</link>
			<description>没有匹配的结果</description>
			</channel></rss>
		""".trimIndent()

		/**
		 * 照着天气站的形态写：一排短导航链接 + 两行像样的数据句。
		 * 两行是硬要求 —— PassagePicker 默认 minLines=2，只有一行入选整段作废。
		 */
		val WEATHER_PAGE = """
			<html><head><title>北京天气</title><script>var c=1;</script></head><body>
			<ul><li><a href="/">首页</a></li><li><a href="/bj/">北京</a></li><li><a href="/aqi/">空气</a></li></ul>
			<div class="today"><h2>今天北京白天多云，最高气温 26℃，最低气温 15℃</h2></div>
			<p>空气质量：优 湿度：75% 风向：北风 2级</p>
			</body></html>
		""".trimIndent()
	}
}

/**
 * 判定用的假模型。三个构造参数都能注入，所以手写就够，不必往工程里塞 mock 框架。
 * 计数是这套用例的主要断言对象：关掉开关时它必须一直是 0。
 */
private class FakeLlmProvider : LlmProvider {

	/** complete 被调了几次 */
	var completeCount = 0
		private set

	/** 每次收到的采样参数，用来验判定走的是压缩模型 */
	val paramsSeen = mutableListOf<LlmParams>()

	/** 每次收到的消息列表，用来验提示词里带了什么 */
	val messagesSeen = mutableListOf<List<LlmMessage>>()

	/** 预设回复 */
	var reply: String = ""

	/** 非 null 时每次调用都抛它 */
	var failure: Throwable? = null

	// 判定只走 complete，流式这条路一旦被用到就该立刻炸出来
	override fun streamChat(messages: List<LlmMessage>, params: LlmParams): Flow<LlmChunk> =
		throw UnsupportedOperationException("判定链路不该走流式")

	override suspend fun complete(messages: List<LlmMessage>, params: LlmParams): String {
		completeCount++
		paramsSeen += params
		messagesSeen += messages
		failure?.let { throw it }
		return reply
	}

	override suspend fun isConfigured(): Boolean = true
}

/** 假搜索客户端。两个方法各返回预设值，顺便记下被问了什么、抓了哪些地址 */
private class FakeWebSearchClient : WebSearchClient {

	/** searchRaw 的返回。null 表示搜索失败 */
	var rss: String? = null

	/** fetchPage 的返回。null 表示抓失败 */
	var page: String? = null

	val queries = mutableListOf<String>()
	val fetched = mutableListOf<String>()

	override suspend fun searchRaw(query: String): String? {
		queries += query
		return rss
	}

	override suspend fun fetchPage(url: String): String? {
		fetched += url
		return page
	}
}
