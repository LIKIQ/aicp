// app/src/test/java/com/kiq/aicp/data/LlmEndpointTest.kt
// Base URL 归一化的测试。用户会填各种写法，这里把实测见过的都钉住，
// 尤其是"已经带了 /v1"和"直接给了完整路径"这两种，处理错就会拼出 /v1/v1/ 然后 404。

package com.kiq.aicp.data

import com.kiq.aicp.data.remote.LlmEndpoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LlmEndpointTest {

	@Test
	fun `裸域名补上 v1 和 chat completions`() {
		assertEquals(
			"https://api.deepseek.com/v1/chat/completions",
			LlmEndpoint.chatCompletions("https://api.deepseek.com"),
		)
	}

	@Test
	fun `末尾斜杠不会拼出双斜杠`() {
		assertEquals(
			"https://api.deepseek.com/v1/chat/completions",
			LlmEndpoint.chatCompletions("https://api.deepseek.com/"),
		)
	}

	@Test
	fun `已经带 v1 的只补 chat completions`() {
		assertEquals(
			"https://open.bigmodel.cn/api/paas/v1/chat/completions",
			LlmEndpoint.chatCompletions("https://open.bigmodel.cn/api/paas/v1"),
		)
	}

	@Test
	fun `已经是完整路径就原样返回`() {
		val full = "https://api.moonshot.cn/v1/chat/completions"
		assertEquals(full, LlmEndpoint.chatCompletions(full))
		assertEquals(full, LlmEndpoint.chatCompletions("$full/"))
	}

	@Test
	fun `版本段写成 v1beta 之类时不再补 v1`() {
		assertEquals(
			"https://example.com/v1beta/chat/completions",
			LlmEndpoint.chatCompletions("https://example.com/v1beta"),
		)
	}

	@Test
	fun `局域网 ollama 的地址照样归一化`() {
		assertEquals(
			"http://192.168.1.7:11434/v1/chat/completions",
			LlmEndpoint.chatCompletions("http://192.168.1.7:11434/v1"),
		)
		assertEquals(
			"http://192.168.1.7:11434/v1/chat/completions",
			LlmEndpoint.chatCompletions("http://192.168.1.7:11434"),
		)
	}

	@Test
	fun `空白输入返回空串交给上层报未配置`() {
		assertEquals("", LlmEndpoint.chatCompletions(""))
		assertEquals("", LlmEndpoint.chatCompletions("   "))
	}

	@Test
	fun `schemeAndHost 能剥掉端口和路径`() {
		assertEquals("https" to "api.deepseek.com", LlmEndpoint.schemeAndHost("https://api.deepseek.com/v1/chat"))
		assertEquals("http" to "192.168.1.7", LlmEndpoint.schemeAndHost("http://192.168.1.7:11434/v1"))
	}

	@Test
	fun `schemeAndHost 能剥掉 userinfo`() {
		assertEquals(
			"https" to "api.x.com",
			LlmEndpoint.schemeAndHost("https://user:pass@api.x.com:8443/v1"),
		)
	}

	@Test
	fun `schemeAndHost 能处理 IPv6 字面量`() {
		assertEquals("http" to "::1", LlmEndpoint.schemeAndHost("http://[::1]:11434/v1"))
	}

	@Test
	fun `schemeAndHost 对残缺输入返回 null`() {
		assertNull(LlmEndpoint.schemeAndHost("api.deepseek.com/v1"))
		assertNull(LlmEndpoint.schemeAndHost("https://"))
		assertNull(LlmEndpoint.schemeAndHost(""))
		assertNull(LlmEndpoint.schemeAndHost("://nohost"))
	}
}
