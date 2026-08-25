// app/src/test/java/com/kiq/aicp/domain/ConfigCodecTest.kt
// 配置码的编解码测试。纯 JVM。
//
// 往返测试刻意让每个字段都取非默认值：如果映射漏了某个字段，
// 用默认值去测是测不出来的（默认值等于默认值，断言照样过），
// 那种漏法上线后表现为"导入后有一项设置没跟过来"，用户根本不会发现。
//
// 最后那条反射比对是这一层真正的保险：以后给 AicpSettings 加设置项、
// 忘了同步 ConfigPayload 和两个映射函数，它会立刻红。

package com.kiq.aicp.domain

import com.kiq.aicp.data.backup.BackupPasswordException
import com.kiq.aicp.domain.config.ConfigCodec
import com.kiq.aicp.domain.config.ConfigCodeException
import com.kiq.aicp.domain.config.ConfigPayload
import com.kiq.aicp.domain.model.AicpSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigCodecTest {

	private val password = "kiq-口令".toCharArray()

	/** 每个字段都不是默认值，这样漏映射一定会被往返测试抓到 */
	private val filled = AicpSettings(
		baseUrl = "https://api.example.com/v1",
		apiKey = "sk-secret-key-123",
		model = "gpt-4o",
		compressModel = "gpt-4o-mini",
		visionModel = "gpt-4o-vision",
		maxImagesInContext = 5,
		autoCompressEnabled = false,
		contextBudgetTokens = 12_000,
		keepRecentMessages = 20,
		compressTriggerTokens = 5_000,
		compressTriggerCount = 60,
		summaryMergeThreshold = 12,
		memoryCardLimit = 30,
		groupMaxSpeakersPerTurn = 4,
		stickersEnabled = false,
		stickerPromptLimit = 40,
		humanizeEnabled = false,
		humanizeMaxSegments = 6,
		humanizeMsPerChar = 80,
		humanizeReadDelayMs = 1_500,
		proactiveEnabled = true,
		proactiveIdleMinutes = 300,
		proactivePushEnabled = true,
		proactiveDailyLimit = 7,
		quietHoursStart = 1,
		quietHoursEnd = 9,
		memorySchema = "不要记我的体重",
		dynamicColor = false,
	)

	@Test
	fun `明文往返除了 apiKey 全都对得上`() {
		val code = ConfigCodec.encode(filled, password = null)

		val back = ConfigCodec.decode(code)

		// 明文码不带凭证，其余字段一字不差
		assertEquals("", back.apiKey)
		assertEquals(filled.copy(apiKey = ""), back)
	}

	@Test
	fun `加密往返连 apiKey 一起回来`() {
		val code = ConfigCodec.encode(filled, password)

		val back = ConfigCodec.decode(code, password)

		assertEquals(filled, back)
		assertEquals("sk-secret-key-123", back.apiKey)
	}

	@Test
	fun `明文码里搜不到 apiKey 的痕迹`() {
		val code = ConfigCodec.encode(filled, password = null)

		val decoded = java.util.Base64.getUrlDecoder()
			.decode(code.removePrefix(ConfigCodec.PLAIN_PREFIX))
			.decodeToString()

		assertFalse(decoded.contains("sk-secret-key-123"))
	}

	@Test
	fun `两种模式前缀不同`() {
		assertTrue(ConfigCodec.encode(filled).startsWith(ConfigCodec.PLAIN_PREFIX))
		assertTrue(ConfigCodec.encode(filled, password).startsWith(ConfigCodec.SEALED_PREFIX))
	}

	@Test
	fun `needsPassword 认得出加密码`() {
		assertTrue(ConfigCodec.needsPassword(ConfigCodec.encode(filled, password)))
		assertFalse(ConfigCodec.needsPassword(ConfigCodec.encode(filled)))
		assertFalse(ConfigCodec.needsPassword("这里面根本没有配置码"))
	}

	@Test
	fun `配置码只用 url 安全字符`() {
		val code = ConfigCodec.encode(filled, password)
		val body = code.removePrefix(ConfigCodec.SEALED_PREFIX)

		// 标准 Base64 的 + / = 在 URL、输入框、聊天软件里都可能被吃掉或转义
		assertFalse(body.contains('+'))
		assertFalse(body.contains('/'))
		assertFalse(body.contains('='))
	}

	@Test
	fun `同一份设置加密两次得到不同的码`() {
		val first = ConfigCodec.encode(filled, password)
		val second = ConfigCodec.encode(filled, password)

		assertNotEquals(first, second)
		assertEquals(ConfigCodec.decode(first, password), ConfigCodec.decode(second, password))
	}

	@Test
	fun `从一整段闲聊里也能认出配置码`() {
		val code = ConfigCodec.encode(filled)
		val messy = "我把配置发你啦：\n$code\n记得导入之后自己填一下 API Key 哦～"

		assertEquals(ConfigCodec.decode(messy), ConfigCodec.decode(code))
	}

	@Test
	fun `码中间被塞了换行和空格也能识别`() {
		val code = ConfigCodec.encode(filled)
		// 跨应用复制经常在固定宽度处折行
		val folded = code.chunked(40).joinToString("\n")

		assertEquals(ConfigCodec.decode(code), ConfigCodec.decode(folded))
	}

	@Test
	fun `认不出配置码时说清让用户看开头`() {
		val e = runCatching { ConfigCodec.decode("随便一段话，没有码") }.exceptionOrNull()

		assertTrue(e is ConfigCodeException)
		assertTrue(e!!.message!!.contains("AICP1"))
	}

	@Test
	fun `只有前缀没有内容算截断`() {
		val e = runCatching { ConfigCodec.decode("AICP1.") }.exceptionOrNull()

		assertTrue(e is ConfigCodeException)
	}

	@Test
	fun `加密码不给口令要提示需要口令而不是报损坏`() {
		val code = ConfigCodec.encode(filled, password)

		val e = runCatching { ConfigCodec.decode(code) }.exceptionOrNull()

		assertTrue(e is ConfigCodeException)
		assertTrue(e!!.message!!.contains("口令"))
	}

	@Test
	fun `口令错了透出口令错误而不是内容损坏`() {
		val code = ConfigCodec.encode(filled, password)

		val e = runCatching { ConfigCodec.decode(code, "错口令".toCharArray()) }.exceptionOrNull()

		assertTrue(e is BackupPasswordException)
		assertTrue(e!!.message!!.contains("口令不对"))
	}

	@Test
	fun `密文被改过一个字符就解不开`() {
		val code = ConfigCodec.encode(filled, password)
		// 改中间位置：那里落在盐、IV 或密文上，三者任一被动 GCM 都会验不过。
		// 别去改末尾——末尾是 4 字节的终止标记，而 Base64 的 'A' 解码出来正好是 0x00，
		// 把尾巴换成 AAAA 等于把终止标记原样写回去，什么都没破坏
		val mid = code.length / 2
		val flipped = if (code[mid] == 'X') 'Y' else 'X'
		val broken = code.take(mid) + flipped + code.drop(mid + 1)

		val e = runCatching { ConfigCodec.decode(broken, password) }.exceptionOrNull()

		assertTrue("改了密文却还能解开：$e", e is BackupPasswordException || e is ConfigCodeException)
	}

	@Test
	fun `被人为提前截断时解出的内容读不成配置`() {
		// GCM 保证每一块没被改，但拦不住"整块连同终止标记一起被砍掉"这种删减。
		// 那种情况下解密不报错，得到的是不完整的明文 —— 最终由 JSON 解析这一关拦住。
		// 这条测试钉的就是这道兜底，别让它哪天被"优化"成静默返回默认配置
		val code = ConfigCodec.encode(filled, password)
		val head = code.take(ConfigCodec.SEALED_PREFIX.length + 48)

		val e = runCatching { ConfigCodec.decode(head, password) }.exceptionOrNull()

		assertTrue("截断的码必须报错：$e", e is BackupPasswordException || e is ConfigCodeException)
	}

	@Test
	fun `格式版本比本版新就明确拒绝`() {
		val future = ConfigPayload(v = ConfigCodec.CONFIG_VERSION + 1, baseUrl = "https://x")
		val body = kotlinx.serialization.json.Json.encodeToString(ConfigPayload.serializer(), future)
		val code = ConfigCodec.PLAIN_PREFIX +
			java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(body.toByteArray())

		val e = runCatching { ConfigCodec.decode(code) }.exceptionOrNull()

		assertTrue(e is ConfigCodeException)
		assertTrue(e!!.message!!.contains("升级"))
	}

	@Test
	fun `旧码缺字段时按默认值补齐`() {
		// 手工造一份只有两个字段的 payload，模拟以后加了设置项之后拿老码来导入
		val partial = """{"v":1,"baseUrl":"https://old.example.com"}"""
		val code = ConfigCodec.PLAIN_PREFIX +
			java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(partial.toByteArray())

		val back = ConfigCodec.decode(code)

		assertEquals("https://old.example.com", back.baseUrl)
		assertEquals(AicpSettings().contextBudgetTokens, back.contextBudgetTokens)
		assertEquals(AicpSettings().dynamicColor, back.dynamicColor)
	}

	@Test
	fun `手改过的离谱数值被夹回合理范围`() {
		val absurd = """
			{"v":1,"humanizeMsPerChar":999999,"contextBudgetTokens":-5,
			"quietHoursStart":99,"proactiveDailyLimit":10000}
		""".trimIndent().filterNot { it.isWhitespace() }
		val code = ConfigCodec.PLAIN_PREFIX +
			java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(absurd.toByteArray())

		val back = ConfigCodec.decode(code)

		assertEquals(500, back.humanizeMsPerChar)
		assertEquals(1_000, back.contextBudgetTokens)
		assertEquals(23, back.quietHoursStart)
		assertEquals(50, back.proactiveDailyLimit)
	}

	@Test
	fun `设置里的每一项都必须在配置码里有位置`() {
		val settingsFields = instanceFieldsOf(AicpSettings::class.java)
		val payloadFields = instanceFieldsOf(ConfigPayload::class.java) - "v"

		val missing = settingsFields - payloadFields
		val extra = payloadFields - settingsFields

		assertTrue(
			"这些设置没进配置码，导入后会悄悄丢：$missing —— " +
				"给 AicpSettings 加字段时，ConfigPayload 和 toPayload/toSettings 两个映射都要跟上",
			missing.isEmpty(),
		)
		assertTrue("配置码里有设置里不存在的字段：$extra", extra.isEmpty())
	}
}
