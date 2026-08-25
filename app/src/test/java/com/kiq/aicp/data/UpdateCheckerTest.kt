// app/src/test/java/com/kiq/aicp/data/UpdateCheckerTest.kt
// 版本检测的端到端测试，用 MockWebServer 假装成 gh-proxy 代理。
//
// 代理的用法是"前缀 + 完整原始 URL"，所以把前缀换成 MockWebServer 的地址之后，
// 请求路径里会原样带着 https://api.github.com/...。这里刻意做精确断言：
// 那串原始 URL 要是被 OkHttp 规范化掉一个斜杠，真实代理就认不出该转发到哪去了。
//
// 另外守住两条"不打扰用户"的约定：
// - 拿不到能读懂的答复时不记检查时刻，下次启动还要再试
// - tag 认不出来时按"已是最新"处理，绝不因为 tag 写得随意就提示更新

package com.kiq.aicp.data

import com.kiq.aicp.data.remote.UpdateChecker
import com.kiq.aicp.data.remote.UpdateResult
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class UpdateCheckerTest {

	private lateinit var server: MockWebServer

	private var lastCheck = 0L
	private var markedAt: Long? = null

	@Before
	fun setUp() {
		server = MockWebServer()
		server.start()
		lastCheck = 0L
		markedAt = null
	}

	@After
	fun tearDown() {
		server.shutdown()
	}

	private fun checker(
		currentVersion: String = "0.4.0",
		proxyPrefix: String = server.url("/").toString(),
		now: Long = NOW,
	) = UpdateChecker(
		baseClient = OkHttpClient(),
		lastCheckAt = { lastCheck },
		markChecked = { markedAt = it },
		currentVersion = currentVersion,
		proxyPrefix = proxyPrefix,
		clock = { now },
	)

	/**
	 * 真实响应有几十个字段，这里故意留着 author、reactions 这些用不上的，
	 * 顺便盯住 ignoreUnknownKeys —— 少了它 GitHub 随便加个字段就能让解析全挂。
	 */
	private fun releaseJson(
		tag: String = "v0.5.0",
		assets: String = """[{"name":"app-release.apk","browser_download_url":"$APK_RAW_URL"}]""",
	): String = """
		{
		  "tag_name": "$tag",
		  "name": "0.5.0 记忆体检",
		  "body": "- 加了记忆体检\n- 顺手修了两个崩溃",
		  "published_at": "2026-08-25T10:30:00Z",
		  "html_url": "https://github.com/LIKIQ/aicp/releases/tag/$tag",
		  "draft": false,
		  "prerelease": false,
		  "author": { "login": "LIKIQ", "id": 1 },
		  "reactions": { "total_count": 0, "+1": 0 },
		  "assets": $assets
		}
	""".trimIndent()

	private fun enqueue(code: Int, body: String) {
		server.enqueue(MockResponse().setResponseCode(code).setBody(body))
	}

	@Test
	fun `正常响应能解析出版本 标题 说明 和发布时间`() = runTest {
		enqueue(200, releaseJson())

		val result = checker().check()

		val info = (result as UpdateResult.Available).info
		assertEquals("v0.5.0", info.tag)
		assertEquals("0.5.0 记忆体检", info.title)
		assertTrue(info.notes.contains("记忆体检"))
		assertTrue("说明里的换行要留着", info.notes.contains("\n"))
		assertEquals("2026-08-25T10:30:00Z", info.publishedAt)
	}

	@Test
	fun `请求打在 releases latest 上，原始地址原样留在代理后面`() = runTest {
		enqueue(200, releaseJson())

		checker().check()

		val recorded = server.takeRequest()
		assertEquals("/https://api.github.com/repos/LIKIQ/aicp/releases/latest", recorded.path)
		assertEquals("application/vnd.github+json", recorded.getHeader("Accept"))
	}

	@Test
	fun `apk 直链也被套上代理前缀`() = runTest {
		enqueue(200, releaseJson())

		val info = (checker().check() as UpdateResult.Available).info

		val expected = server.url("/").toString().trimEnd('/') + "/" + APK_RAW_URL
		assertEquals(expected, info.apkUrl)
		assertEquals(info.apkUrl, info.downloadUrl)
	}

	@Test
	fun `assets 里没有 apk 时把人送到 release 页面`() = runTest {
		val notApk = """[{"name":"mapping.txt","browser_download_url":"https://github.com/x/mapping.txt"}]"""
		enqueue(200, releaseJson(assets = notApk))

		val info = (checker().check() as UpdateResult.Available).info

		assertNull(info.apkUrl)
		assertEquals("https://github.com/LIKIQ/aicp/releases/tag/v0.5.0", info.downloadUrl)
	}

	@Test
	fun `assets 是空数组时也不炸`() = runTest {
		enqueue(200, releaseJson(assets = "[]"))

		val info = (checker().check() as UpdateResult.Available).info

		assertNull(info.apkUrl)
		assertTrue(info.downloadUrl.startsWith("https://github.com/LIKIQ/aicp/releases/"))
	}

	@Test
	fun `限流的 403 和 429 归为可重试的失败`() = runTest {
		enqueue(403, """{"message":"API rate limit exceeded"}""")
		val forbidden = checker().check() as UpdateResult.Failed
		assertTrue(forbidden.retryable)
		assertTrue(forbidden.reason.contains("403"))

		enqueue(429, "too many requests")
		val tooMany = checker().check() as UpdateResult.Failed
		assertTrue(tooMany.retryable)
	}

	@Test
	fun `404 说明还没发过版本，重试没意义`() = runTest {
		enqueue(404, """{"message":"Not Found"}""")

		val failed = checker().check() as UpdateResult.Failed

		assertTrue(failed.reason.contains("还没有发布过"))
		assertFalse(failed.retryable)
	}

	@Test
	fun `代理或 GitHub 出 5xx 时可以重试`() = runTest {
		enqueue(502, "bad gateway")

		val failed = checker().check() as UpdateResult.Failed

		assertTrue(failed.retryable)
	}

	@Test
	fun `响应体是垃圾时报解析失败，而不是当成没有新版本`() = runTest {
		enqueue(200, "这不是 JSON")
		val garbage = checker().check()
		assertTrue("垃圾响应必须走失败分支", garbage is UpdateResult.Failed)

		enqueue(200, "<html><body>代理挂了</body></html>")
		assertTrue(checker().check() is UpdateResult.Failed)
	}

	@Test
	fun `是 JSON 但没有 tag_name 时也算失败`() = runTest {
		enqueue(200, """{"foo":"bar","assets":[]}""")

		val failed = checker().check() as UpdateResult.Failed

		assertTrue(failed.reason.contains("没有版本号"))
		assertNull("没读到版本号就不该记检查时刻", markedAt)
	}

	@Test
	fun `远端跟本地一样新或更旧时报已是最新`() = runTest {
		enqueue(200, releaseJson(tag = "v0.4.0"))
		val same = checker().check() as UpdateResult.UpToDate
		assertEquals("0.4.0", same.currentVersion)
		assertEquals("v0.4.0", same.latestTag)

		enqueue(200, releaseJson(tag = "v0.3.9"))
		assertTrue(checker().check() is UpdateResult.UpToDate)
	}

	@Test
	fun `tag 认不出来时按已是最新处理，不弹更新`() = runTest {
		enqueue(200, releaseJson(tag = "latest"))

		val result = checker().check()

		assertTrue("tag 写得随意不能变成天天提示", result is UpdateResult.UpToDate)
		assertEquals("latest", (result as UpdateResult.UpToDate).latestTag)
	}

	@Test
	fun `24 小时内的自动检查直接跳过，一个请求都不发`() = runTest {
		lastCheck = NOW - 60 * 60 * 1000L

		assertEquals(UpdateResult.Skipped, checker().check())
		assertEquals(0, server.requestCount)
	}

	@Test
	fun `手动检查无视节流`() = runTest {
		lastCheck = NOW - 60 * 60 * 1000L
		enqueue(200, releaseJson())

		val result = checker().check(manual = true)

		assertTrue(result is UpdateResult.Available)
		assertEquals(1, server.requestCount)
	}

	@Test
	fun `超过 24 小时后自动检查会再问一次`() = runTest {
		lastCheck = NOW - 25 * 60 * 60 * 1000L
		enqueue(200, releaseJson())

		assertTrue(checker().check() is UpdateResult.Available)
	}

	@Test
	fun `系统时间被往前调过时不会永远卡在刚查过`() = runTest {
		// 上次检查的时间戳比"现在"还晚，只可能是时间被改过；这时候要允许再查
		lastCheck = NOW + 10 * 24 * 60 * 60 * 1000L
		enqueue(200, releaseJson())

		assertTrue(checker().check() is UpdateResult.Available)
	}

	@Test
	fun `拿到能读懂的答复才记下检查时刻`() = runTest {
		enqueue(200, releaseJson())
		checker().check()
		assertEquals(NOW, markedAt)

		markedAt = null
		enqueue(403, "rate limited")
		checker().check()
		assertNull("失败不占节流额度，下次启动还要再试", markedAt)
	}

	@Test
	fun `连不上时静默失败而不是抛异常`() = runTest {
		// 指向一个没人监听的端口，走的是 IOException 那条路
		val result = checker(proxyPrefix = "http://127.0.0.1:1/").check()

		val failed = result as UpdateResult.Failed
		assertTrue(failed.retryable)
		assertTrue(failed.reason.contains("连不上"))
	}

	private companion object {
		/** 固定时钟，免得节流用例跟着真实时间漂 */
		const val NOW = 1_800_000_000_000L

		const val APK_RAW_URL =
			"https://github.com/LIKIQ/aicp/releases/download/v0.5.0/app-release.apk"
	}
}
