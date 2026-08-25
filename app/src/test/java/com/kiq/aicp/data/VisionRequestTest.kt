// app/src/test/java/com/kiq/aicp/data/VisionRequestTest.kt
// 多模态请求体的形状测试，用 MockWebServer 抓真实发出的 JSON 来断言。
//
// 这里守的是三条查证来的硬约束，任何一条写错都会被服务端直接 400：
// 1. 没图时 content 必须还是普通字符串（数组形式有些老服务不认）
// 2. 有图时 text 项必须排在 image_url 前面（OpenRouter 因内容解析顺序明确要求）
// 3. 图片只能挂 user 消息（DeepSeek 文档写明放 system/assistant 会 400）
//
// 解析 JSON 用 kotlinx.serialization 而不是 org.json：
// android.jar 里的 org.json 是 stub，纯 JVM 测试里一调就抛 "Stub!"，
// 要用它就得把整个测试类挂上 Robolectric，为解个 JSON 启动一遍 Android 环境不值。

package com.kiq.aicp.data

import com.kiq.aicp.data.remote.LlmConfig
import com.kiq.aicp.data.remote.LlmImage
import com.kiq.aicp.data.remote.LlmMessage
import com.kiq.aicp.data.remote.LlmParams
import com.kiq.aicp.data.remote.OpenAiCompatProvider
import com.kiq.aicp.domain.model.ChatRole
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class VisionRequestTest {

	private lateinit var server: MockWebServer

	private val params = LlmParams(model = "vision-model", temperature = 0.5f, topP = 0.9f, maxTokens = 512)

	@Before
	fun setUp() {
		server = MockWebServer()
		server.start()
	}

	@After
	fun tearDown() {
		server.shutdown()
	}

	private fun provider() = OpenAiCompatProvider(
		baseClient = OkHttpClient(),
		configLoader = {
			LlmConfig(
				baseUrl = server.url("/v1").toString(),
				apiKey = "sk-test",
				defaultModel = "fallback",
			)
		},
	)

	private fun okReply() = MockResponse().setResponseCode(200)
		.setBody("{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"看到了\"}}]}")

	private suspend fun sendAndCaptureBody(messages: List<LlmMessage>): JsonObject {
		server.enqueue(okReply())
		provider().complete(messages, params)
		return Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
	}

	private fun JsonObject.contentOf(messageIndex: Int): JsonElement =
		this["messages"]!!.jsonArray[messageIndex].jsonObject["content"]!!

	private fun JsonArray.typeAt(index: Int): String =
		this[index].jsonObject["type"]!!.jsonPrimitive.content

	private fun JsonArray.imageUrlAt(index: Int): JsonObject =
		this[index].jsonObject["image_url"]!!.jsonObject

	@Test
	fun `没有图片时 content 保持普通字符串`() = runTest {
		val content = sendAndCaptureBody(listOf(LlmMessage(ChatRole.USER, "纯文字"))).contentOf(0)

		assertTrue("content 应该是字符串而不是数组", content is JsonPrimitive)
		assertEquals("纯文字", content.jsonPrimitive.content)
	}

	@Test
	fun `带图片时 content 展开成数组，且文字排在图片前面`() = runTest {
		val content = sendAndCaptureBody(
			listOf(
				LlmMessage(
					role = ChatRole.USER,
					content = "这张图里有什么",
					images = listOf(LlmImage(base64 = "QUJD", mimeType = "image/jpeg")),
				),
			),
		).contentOf(0)

		assertTrue("content 应该是数组", content is JsonArray)
		val parts = content.jsonArray
		assertEquals(2, parts.size)
		assertEquals("text", parts.typeAt(0))
		assertEquals("这张图里有什么", parts[0].jsonObject["text"]!!.jsonPrimitive.content)
		assertEquals("image_url", parts.typeAt(1))
	}

	@Test
	fun `图片 URL 带上 data URI 前缀和正确的 mime`() = runTest {
		val content = sendAndCaptureBody(
			listOf(
				LlmMessage(
					role = ChatRole.USER,
					content = "看图",
					images = listOf(LlmImage(base64 = "QUJD", mimeType = "image/png")),
				),
			),
		).contentOf(0)

		assertEquals(
			"data:image/png;base64,QUJD",
			content.jsonArray.imageUrlAt(1)["url"]!!.jsonPrimitive.content,
		)
	}

	@Test
	fun `只有截图档才传 detail high`() = runTest {
		val normal = sendAndCaptureBody(
			listOf(
				LlmMessage(
					ChatRole.USER,
					"普通图",
					images = listOf(LlmImage("QUJD", "image/jpeg", highDetail = false)),
				),
			),
		).contentOf(0)
		assertFalse("普通图不该带 detail", normal.jsonArray.imageUrlAt(1).containsKey("detail"))

		val screenshot = sendAndCaptureBody(
			listOf(
				LlmMessage(
					ChatRole.USER,
					"截图",
					images = listOf(LlmImage("QUJD", "image/jpeg", highDetail = true)),
				),
			),
		).contentOf(0)
		assertEquals(
			"high",
			screenshot.jsonArray.imageUrlAt(1)["detail"]!!.jsonPrimitive.content,
		)
	}

	@Test
	fun `多张图按顺序全部带上`() = runTest {
		val parts = sendAndCaptureBody(
			listOf(
				LlmMessage(
					ChatRole.USER,
					"三张图",
					images = listOf(
						LlmImage("QQ==", "image/jpeg"),
						LlmImage("Qg==", "image/png"),
						LlmImage("Qw==", "image/webp"),
					),
				),
			),
		).contentOf(0).jsonArray

		assertEquals(4, parts.size)
		assertEquals("data:image/jpeg;base64,QQ==", parts.imageUrlAt(1)["url"]!!.jsonPrimitive.content)
		assertEquals("data:image/png;base64,Qg==", parts.imageUrlAt(2)["url"]!!.jsonPrimitive.content)
		assertEquals("data:image/webp;base64,Qw==", parts.imageUrlAt(3)["url"]!!.jsonPrimitive.content)
	}

	@Test
	fun `挂在 system 或 assistant 上的图片被丢掉而不是硬塞进去`() = runTest {
		val body = sendAndCaptureBody(
			listOf(
				LlmMessage(ChatRole.SYSTEM, "人设", images = listOf(LlmImage("QUJD", "image/jpeg"))),
				LlmMessage(ChatRole.ASSISTANT, "我说过的话", images = listOf(LlmImage("QUJD", "image/jpeg"))),
				LlmMessage(ChatRole.USER, "用户的图", images = listOf(LlmImage("QUJD", "image/jpeg"))),
			),
		)

		assertTrue("system 的 content 应退回字符串", body.contentOf(0) is JsonPrimitive)
		assertTrue("assistant 的 content 应退回字符串", body.contentOf(1) is JsonPrimitive)
		assertTrue("user 的 content 应该是数组", body.contentOf(2) is JsonArray)
	}

	@Test
	fun `只发图没打字时不会产出空的 text 项`() = runTest {
		val parts = sendAndCaptureBody(
			listOf(LlmMessage(ChatRole.USER, "", images = listOf(LlmImage("QUJD", "image/jpeg")))),
		).contentOf(0).jsonArray

		assertEquals(1, parts.size)
		assertEquals("image_url", parts.typeAt(0))
	}
}
