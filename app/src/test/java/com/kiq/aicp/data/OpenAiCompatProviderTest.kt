// app/src/test/java/com/kiq/aicp/data/OpenAiCompatProviderTest.kt
// Provider 的端到端测试，用 MockWebServer 假装成一个 OpenAI 兼容服务。
//
// MockWebServer 起在 127.0.0.1 上，正好是 CleartextGuard 放行的地址，所以明文测试能跑；
// 而"公网 http 必须被拦"那条用例走的是一个假域名，根本不会真的发出去。

package com.kiq.aicp.data

import com.kiq.aicp.data.remote.LlmChunk
import com.kiq.aicp.data.remote.LlmConfig
import com.kiq.aicp.data.remote.LlmException
import com.kiq.aicp.data.remote.LlmMessage
import com.kiq.aicp.data.remote.LlmParams
import com.kiq.aicp.data.remote.OpenAiCompatProvider
import com.kiq.aicp.domain.model.ChatRole
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class OpenAiCompatProviderTest {

	private lateinit var server: MockWebServer

	private val params = LlmParams(model = "test-model", temperature = 0.5f, topP = 0.9f, maxTokens = 256)
	private val messages = listOf(
		LlmMessage(ChatRole.SYSTEM, "你是测试角色"),
		LlmMessage(ChatRole.USER, "你好"),
	)

	@Before
	fun setUp() {
		server = MockWebServer()
		server.start()
	}

	@After
	fun tearDown() {
		server.shutdown()
	}

	private fun provider(
		baseUrl: String = server.url("/v1").toString(),
		apiKey: String = "sk-test",
	) = OpenAiCompatProvider(
		baseClient = OkHttpClient(),
		configLoader = { LlmConfig(baseUrl = baseUrl, apiKey = apiKey, defaultModel = "fallback-model") },
	)

	private fun sseBody(vararg deltas: String, finishReason: String = "stop"): String = buildString {
		deltas.forEach { text ->
			append("data: {\"choices\":[{\"delta\":{\"content\":\"$text\"},\"finish_reason\":null}]}\n\n")
		}
		append("data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"$finishReason\"}]}\n\n")
		append("data: [DONE]\n\n")
	}

	@Test
	fun `流式回复按顺序吐出增量并以 Done 收尾`() = runTest {
		server.enqueue(MockResponse().setResponseCode(200).setBody(sseBody("你", "好", "呀")))

		val chunks = provider().streamChat(messages, params).toList()

		val deltas = chunks.filterIsInstance<LlmChunk.Delta>().map { it.text }
		assertEquals(listOf("你", "好", "呀"), deltas)
		assertEquals("stop", (chunks.last() as LlmChunk.Done).finishReason)
	}

	@Test
	fun `请求体带上模型 参数 和 stream 标记`() = runTest {
		server.enqueue(MockResponse().setResponseCode(200).setBody(sseBody("ok")))

		provider().streamChat(messages, params).toList()

		val recorded = server.takeRequest()
		val body = recorded.body.readUtf8()
		assertEquals("/v1/chat/completions", recorded.path)
		assertEquals("Bearer sk-test", recorded.getHeader("Authorization"))
		assertTrue(body.contains("\"model\":\"test-model\""))
		assertTrue(body.contains("\"stream\":true"))
		assertTrue(body.contains("\"max_tokens\":256"))
		assertTrue(body.contains("\"role\":\"system\""))
		assertTrue(body.contains("\"role\":\"user\""))
	}

	@Test
	fun `params 里模型为空时回落到设置里的默认模型`() = runTest {
		server.enqueue(MockResponse().setResponseCode(200).setBody(sseBody("ok")))

		provider().streamChat(messages, params.copy(model = "")).toList()

		assertTrue(server.takeRequest().body.readUtf8().contains("\"model\":\"fallback-model\""))
	}

	@Test
	fun `非流式请求取 choices 里的完整正文`() = runTest {
		server.enqueue(
			MockResponse().setResponseCode(200)
				.setBody("{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"  完整回复  \"}}]}"),
		)

		assertEquals("完整回复", provider().complete(messages, params))
		assertTrue(server.takeRequest().body.readUtf8().contains("\"stream\":false"))
	}

	@Test
	fun `401 归类为鉴权失败并带上服务端说明`() = runTest {
		server.enqueue(
			MockResponse().setResponseCode(401)
				.setBody("{\"error\":{\"message\":\"Invalid API key\"}}"),
		)

		val e = runCatching { provider().complete(messages, params) }.exceptionOrNull()
		assertTrue(e is LlmException)
		assertEquals(LlmException.Kind.AUTH, (e as LlmException).kind)
		assertTrue(e.message!!.contains("Invalid API key"))
		assertFalse(e.kind.retryable)
	}

	@Test
	fun `429 和 5xx 归类为可重试`() = runTest {
		server.enqueue(MockResponse().setResponseCode(429).setBody("{\"error\":{\"message\":\"too fast\"}}"))
		val rate = runCatching { provider().complete(messages, params) }.exceptionOrNull() as LlmException
		assertEquals(LlmException.Kind.RATE_LIMIT, rate.kind)
		assertTrue(rate.kind.retryable)

		server.enqueue(MockResponse().setResponseCode(503).setBody("upstream down"))
		val server5xx = runCatching { provider().complete(messages, params) }.exceptionOrNull() as LlmException
		assertEquals(LlmException.Kind.SERVER, server5xx.kind)
		assertTrue(server5xx.kind.retryable)
	}

	@Test
	fun `400 归类为请求错误`() = runTest {
		server.enqueue(MockResponse().setResponseCode(400).setBody("{\"error\":{\"message\":\"model not found\"}}"))

		val e = runCatching { provider().complete(messages, params) }.exceptionOrNull() as LlmException
		assertEquals(LlmException.Kind.BAD_REQUEST, e.kind)
	}

	@Test
	fun `响应结构不认识时归类为响应异常`() = runTest {
		server.enqueue(MockResponse().setResponseCode(200).setBody("这不是 JSON"))

		val e = runCatching { provider().complete(messages, params) }.exceptionOrNull() as LlmException
		assertEquals(LlmException.Kind.BAD_RESPONSE, e.kind)
	}

	@Test
	fun `没填 Base URL 或 Key 时直接报未配置，不发请求`() = runTest {
		val noUrl = runCatching { provider(baseUrl = "").complete(messages, params) }
			.exceptionOrNull() as LlmException
		assertEquals(LlmException.Kind.NO_CONFIG, noUrl.kind)

		val noKey = runCatching { provider(apiKey = "  ").complete(messages, params) }
			.exceptionOrNull() as LlmException
		assertEquals(LlmException.Kind.NO_CONFIG, noKey.kind)

		assertEquals(0, server.requestCount)
	}

	@Test
	fun `公网明文地址在发请求前就被拦掉`() = runTest {
		val e = runCatching {
			provider(baseUrl = "http://api.example.com/v1").complete(messages, params)
		}.exceptionOrNull() as LlmException

		assertEquals(LlmException.Kind.CLEARTEXT_BLOCKED, e.kind)
		assertEquals(0, server.requestCount)
	}

	@Test
	fun `isConfigured 只看 Base URL 和 Key 是否都填了`() = runTest {
		assertTrue(provider().isConfigured())
		assertFalse(provider(apiKey = "").isConfigured())
		assertFalse(provider(baseUrl = "  ").isConfigured())
	}

	@Test
	fun `服务端断流没发 DONE 时最后一段也不会丢`() = runTest {
		server.enqueue(
			MockResponse().setResponseCode(200)
				.setBody("data: {\"choices\":[{\"delta\":{\"content\":\"半\"}}]}\n\ndata: {\"choices\":[{\"delta\":{\"content\":\"截\"}}]}"),
		)

		val deltas = provider().streamChat(messages, params).toList()
			.filterIsInstance<LlmChunk.Delta>().map { it.text }

		assertEquals(listOf("半", "截"), deltas)
	}
}
