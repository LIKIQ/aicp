// app/src/test/java/com/kiq/aicp/data/WebSearchClientTest.kt
// BingRssSearchClient 的端到端测试，用 MockWebServer 分别假装必应的 RSS 接口和被抓的网页。
//
// 盯着三件后人最容易改坏的事：
// 1. 搜索请求必须带 q 和 format=rss，中文检索词要能原样还原 —— 编码错了必应只会回一份空 RSS，
//    而且这种失败是静默的（searchRaw 一律返回 null），线上根本看不出来
// 2. 抓页面那条路的三道闸门：CleartextGuard 挡公网明文、Content-Type 只放 html、响应体封顶 512KB
// 3. 定编码的顺序：响应头 → meta charset → UTF-8。GBK 站点抓回乱码比抓不到更糟，模型会拿乱码当真
//
// 另外刻意留了"环回 http 放行"这条：MockWebServer 就起在 127.0.0.1 上，
// 这正是工程为局域网自建服务保留的能力。谁要是顺手把 fetchPage 收紧成只准 https，这条会先红。
// 还有一条"搜索不做 html 校验"，防的是有人把 expectHtml 那段逻辑抄到搜索路径上。

package com.kiq.aicp.data

import com.kiq.aicp.data.remote.BingRssSearchClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class WebSearchClientTest {

	private lateinit var server: MockWebServer

	@Before
	fun setUp() {
		server = MockWebServer()
		server.start()
	}

	@After
	fun tearDown() {
		server.shutdown()
	}

	/** ioDispatcher 换成 Unconfined：挂起函数直接在测试线程上跑完，不用跟线程池抢时序 */
	private fun client(endpoint: String = server.url("/search").toString()) = BingRssSearchClient(
		baseClient = OkHttpClient(),
		searchEndpoint = endpoint,
		ioDispatcher = Dispatchers.Unconfined,
	)

	private fun enqueue(code: Int = 200, contentType: String? = null, body: String = "") {
		val response = MockResponse().setResponseCode(code).setBody(body)
		contentType?.let { response.setHeader("Content-Type", it) }
		server.enqueue(response)
	}

	/** 编码相关的用例必须按字节喂，不能让 MockWebServer 拿 UTF-8 重编一遍 */
	private fun enqueueBytes(contentType: String, bytes: ByteArray) {
		server.enqueue(
			MockResponse()
				.setResponseCode(200)
				.setHeader("Content-Type", contentType)
				.setBody(Buffer().write(bytes)),
		)
	}

	@Test
	fun `正常搜索能拿到原始 RSS，请求上带着 q 和 format=rss`() = runTest {
		enqueue(contentType = "application/rss+xml; charset=utf-8", body = RSS)

		val raw = client().searchRaw("kotlin 协程")

		assertNotNull(raw)
		assertTrue("拿到的应该是没动过的 RSS 原文", raw!!.contains("<rss version=\"2.0\">"))
		assertTrue(raw.contains("Kotlin 协程入门"))

		val path = requireNotNull(server.takeRequest().path)
		assertTrue("必应认 q 这个参数名", path.contains("q="))
		assertTrue("不带 format=rss 会返回整页 HTML", path.contains("format=rss"))
	}

	@Test
	fun `中文检索词经过 percent 编码后能原样还原`() = runTest {
		enqueue(contentType = "application/rss+xml", body = RSS)

		client().searchRaw("上海 天气")

		val recorded = server.takeRequest()
		val path = requireNotNull(recorded.path)
		// 线上真实发出去的是 %E4%B8%8A%E6%B5%B7%20%E5%A4%A9%E6%B0%94，这里顺手钉一下别退化成 + 或原文
		assertTrue("中文必须是 percent 编码", path.contains("%E4%B8%8A%E6%B5%B7"))

		val url = requireNotNull(recorded.requestUrl)
		assertEquals("上海 天气", url.queryParameter("q"))
		assertEquals("rss", url.queryParameter("format"))
	}

	@Test
	fun `空白检索词直接返回 null，一个请求都不发`() = runTest {
		assertNull(client().searchRaw("   "))
		assertNull(client().searchRaw(""))

		assertEquals("空检索词连 socket 都不该碰", 0, server.requestCount)
	}

	@Test
	fun `搜索接口返回 500 时静默降级成 null`() = runTest {
		enqueue(code = 500, body = "服务暂时不可用")

		assertNull(client().searchRaw("kotlin"))
		assertEquals(1, server.requestCount)
	}

	@Test
	fun `搜索路径不做 html 校验，text xml 一样收`() = runTest {
		// RSS 的 Content-Type 各家不一样，text/xml、application/xml、application/rss+xml 都见过，
		// 所以搜索这条路只看状态码。这条用例就是拦着别人把 expectHtml 那套搬过来
		enqueue(contentType = "text/xml; charset=utf-8", body = RSS)

		val raw = client().searchRaw("kotlin")

		assertNotNull("text/xml 是 RSS 的常见声明方式，不能被当成非法内容扔掉", raw)
		assertTrue(raw!!.contains("Kotlin 协程入门"))
	}

	@Test
	fun `fetchPage 正常抓到 html 正文`() = runTest {
		enqueue(contentType = "text/html; charset=utf-8", body = "<html><body><p>协程是轻量级线程</p></body></html>")

		val html = client().fetchPage(server.url("/page").toString())

		assertNotNull(html)
		assertTrue(html!!.contains("协程是轻量级线程"))
		assertEquals(1, server.requestCount)
	}

	@Test
	fun `fetchPage 拒收非 html 的内容`() = runTest {
		enqueue(contentType = "application/pdf", body = "%PDF-1.7 这里其实是一坨二进制")

		val result = client().fetchPage(server.url("/doc.pdf").toString())

		assertNull("PDF 读成文本只会变成垃圾，喂给模型不如不喂", result)
		// 请求还是发出去了：Content-Type 得等响应头回来才知道
		assertEquals(1, server.requestCount)
	}

	@Test
	fun `fetchPage 在发请求之前就掐掉公网明文 http`() = runTest {
		val result = client().fetchPage("http://example.com/x")

		assertNull(result)
		assertEquals("守卫是纯函数，必须拦在 execute 之前", 0, server.requestCount)
	}

	@Test
	fun `fetchPage 放行环回地址的 http`() = runTest {
		// MockWebServer 就在 127.0.0.1 上，这条同时替局域网自建服务（ollama / LM Studio）站岗
		enqueue(contentType = "text/html", body = "<html><body>本机页面</body></html>")

		val html = client().fetchPage(server.url("/page").toString())

		assertNotNull("环回和 RFC1918 是工程刻意保留的明文场景，不能一刀切成只准 https", html)
		assertTrue(html!!.contains("本机页面"))
	}

	@Test
	fun `响应头声明 gbk 时按 gbk 解码`() = runTest {
		val body = "<html><body><p>上海今天多云转晴</p></body></html>"
		enqueueBytes("text/html; charset=gbk", body.toByteArray(charset("GBK")))

		val html = client().fetchPage(server.url("/gbk").toString())

		assertNotNull(html)
		assertTrue("响应头有 charset 就该直接信它", html!!.contains("上海今天多云转晴"))
		assertTrue("不该出现替换字符", !html.contains('\uFFFD'))
	}

	@Test
	fun `响应头没声明 charset 时靠 meta 探测出 gbk`() = runTest {
		// 中文老站的典型样子：Content-Type 只写 text/html，真正的编码藏在 meta 里。
		// 只信响应头的实现会在这里把整页解成乱码
		val body = "<html><head><meta charset=\"gbk\"><title>天气</title></head>" +
			"<body><p>上海今天多云转晴</p></body></html>"
		enqueueBytes("text/html", body.toByteArray(charset("GBK")))

		val html = client().fetchPage(server.url("/meta").toString())

		assertNotNull(html)
		assertTrue("meta charset 必须被认出来", html!!.contains("上海今天多云转晴"))
		assertTrue(!html.contains('\uFFFD'))
	}

	@Test
	fun `超大响应被截到 512KB`() = runTest {
		// 全用 ASCII 构造，这样字节数和字符数一致，断言才好写
		val huge = "a".repeat(700 * 1024)
		enqueue(contentType = "text/html", body = huge)

		val html = client().fetchPage(server.url("/huge").toString())

		assertNotNull(html)
		assertTrue("超过封顶值说明截断没生效：${html!!.length}", html.length <= MAX_BODY_CHARS)
		assertTrue("也不能截成空的", html.length > 0)
	}

	@Test
	fun `非法 URL 返回 null 而不是抛异常`() = runTest {
		assertNull(client().fetchPage("这不是url"))
		assertNull(client().fetchPage(""))
		assertNull(client().fetchPage("ftp://example.com/a.txt"))

		assertEquals(0, server.requestCount)
	}

	/**
	 * 响应头声明 utf-8、正文其实是 GBK。国内站点 Nginx 默认头没改就是这样，
	 * 只信响应头会抓回一整页 U+FFFD，模型会拿这堆乱码当真话往下编。
	 */
	@Test
	fun `响应头 charset 声明错时回退到 meta 探测`() = runTest {
		val html = "<html><head><meta charset=\"gbk\"></head><body><p>上海今天多云转晴</p></body></html>"
		enqueueBytes("text/html; charset=utf-8", html.toByteArray(charset("GBK")))

		val page = client().fetchPage(server.url("/page").toString())

		assertNotNull(page)
		assertTrue(page!!.contains("上海今天多云转晴"))
	}

	/** okhttp 不猜类型，配置粗糙的站点压根不发这个头。一律拒掉等于白抓一次 */
	@Test
	fun `完全不带 Content-Type 的页面照样抓`() = runTest {
		enqueue(body = "<html><body><p>没有声明类型的页面</p></body></html>")

		val page = client().fetchPage(server.url("/page").toString())

		assertNotNull(page)
		assertTrue(page!!.contains("没有声明类型的页面"))
	}

	/** 声明了但不是 html 的还是要拦住，别把 PDF 和视频流读进来 */
	@Test
	fun `声明了非 html 类型仍然拒绝`() = runTest {
		enqueue(contentType = "application/pdf", body = "%PDF-1.7 binary junk")

		assertNull(client().fetchPage(server.url("/doc.pdf").toString()))
	}

	@Test
	fun `截断处的半个多字节字符不会留下替换字符`() = runTest {
		// 用中文把 body 撑到超过 512KB，那一刀必然砍在某个字的中间
		val body = "中".repeat(300_000)
		enqueueBytes("text/html; charset=utf-8", body.toByteArray())

		val page = client().fetchPage(server.url("/page").toString())

		assertNotNull(page)
		assertTrue(page!!.isNotEmpty())
		assertTrue("末尾不该留替换字符", page.last() != '\uFFFD')
	}

	private companion object {
		/** 跟 BingRssSearchClient.MAX_BODY_BYTES 对齐，那边是 private 拿不到，只能在这儿抄一份 */
		const val MAX_BODY_CHARS = 512 * 1024

		val RSS = """
			<?xml version="1.0" encoding="utf-8"?>
			<rss version="2.0">
			  <channel>
			    <title>kotlin 协程 - 必应</title>
			    <item>
			      <title>Kotlin 协程入门</title>
			      <link>https://example.com/coroutines</link>
			      <description>协程是轻量级线程，挂起不占线程</description>
			      <pubDate>Mon, 25 Aug 2025 10:00:00 GMT</pubDate>
			    </item>
			  </channel>
			</rss>
		""".trimIndent()
	}
}
