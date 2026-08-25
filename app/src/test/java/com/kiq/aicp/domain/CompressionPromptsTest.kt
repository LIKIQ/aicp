// app/src/test/java/com/kiq/aicp/domain/CompressionPromptsTest.kt
// 压缩结果解析的容错测试。
// 这里每一条都是真会遇到的：模型爱套 ```json、爱在 JSON 前面写一句"好的，以下是摘要"、
// 偶尔干脆不给 JSON。解析崩了就等于白花一次调用，所以兜底行为必须钉住。

package com.kiq.aicp.domain

import com.kiq.aicp.data.db.entity.MessageEntity
import com.kiq.aicp.domain.memory.CompressionPrompts
import com.kiq.aicp.domain.model.ChatRole
import com.kiq.aicp.domain.model.MemoryCardType
import com.kiq.aicp.domain.model.MessageStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompressionPromptsTest {

	private fun msg(id: Long, role: ChatRole, content: String, personaId: Long? = null) =
		MessageEntity(
			id = id,
			conversationId = 1,
			role = role,
			personaId = personaId,
			content = content,
			tokenEstimate = 10,
			status = MessageStatus.OK,
			createdAt = id,
		)

	@Test
	fun `标准 JSON 能解析出摘要和卡片`() {
		val raw = """
			{"summary":"用户在改一个压缩逻辑，情绪平稳。",
			 "cards":[{"type":"FACT","keyword":"职业","content":"做安卓开发","importance":4}]}
		""".trimIndent()

		val parsed = CompressionPrompts.parse(raw)

		assertTrue(parsed.strict)
		assertEquals("用户在改一个压缩逻辑，情绪平稳。", parsed.summary)
		assertEquals(1, parsed.cards.size)
		assertEquals(MemoryCardType.FACT, parsed.cards.single().type)
		assertEquals("职业", parsed.cards.single().keyword)
	}

	@Test
	fun `套了 markdown 代码块也能解析`() {
		val raw = "```json\n{\"summary\":\"聊了压缩\",\"cards\":[]}\n```"

		val parsed = CompressionPrompts.parse(raw)

		assertTrue(parsed.strict)
		assertEquals("聊了压缩", parsed.summary)
	}

	@Test
	fun `JSON 前后夹着解释文字也能解析`() {
		val raw = "好的，以下是压缩结果：\n{\"summary\":\"聊了记忆分层\",\"cards\":[]}\n希望有帮助。"

		val parsed = CompressionPrompts.parse(raw)

		assertTrue(parsed.strict)
		assertEquals("聊了记忆分层", parsed.summary)
	}

	@Test
	fun `完全不是 JSON 时整段当摘要并标记待重压`() {
		val raw = "用户今天在调压缩逻辑，心情不错，约好明天继续。"

		val parsed = CompressionPrompts.parse(raw)

		assertFalse(parsed.strict)
		assertEquals(raw, parsed.summary)
		assertTrue(parsed.cards.isEmpty())
	}

	@Test
	fun `JSON 里摘要为空时退化为非严格模式`() {
		val parsed = CompressionPrompts.parse("{\"summary\":\"   \",\"cards\":[{\"type\":\"FACT\"}]}")

		assertFalse(parsed.strict)
		assertTrue(parsed.cards.isEmpty())
	}

	@Test
	fun `空回复不会崩`() {
		val parsed = CompressionPrompts.parse("   ")
		assertFalse(parsed.strict)
		assertEquals("", parsed.summary)
	}

	@Test
	fun `不认识的类型和空字段的卡片被丢掉`() {
		val raw = """
			{"summary":"有摘要","cards":[
			 {"type":"UNKNOWN_TYPE","keyword":"x","content":"y","importance":3},
			 {"type":"FACT","keyword":"","content":"没有键","importance":3},
			 {"type":"FACT","keyword":"有键","content":"   ","importance":3},
			 {"type":"preference","keyword":"口味","content":"爱吃辣","importance":9}]}
		""".trimIndent()

		val parsed = CompressionPrompts.parse(raw)

		assertEquals(1, parsed.cards.size)
		val card = parsed.cards.single()
		// 类型大小写不敏感
		assertEquals(MemoryCardType.PREFERENCE, card.type)
		// importance 越界被夹回 1..5
		assertEquals(5, card.importance)
	}

	@Test
	fun `同类型同键的卡片只留一张`() {
		val raw = """
			{"summary":"有摘要","cards":[
			 {"type":"FACT","keyword":"宠物","content":"一只猫","importance":3},
			 {"type":"FACT","keyword":"宠物","content":"一只猫和一只狗","importance":4}]}
		""".trimIndent()

		assertEquals(1, CompressionPrompts.parse(raw).cards.size)
	}

	@Test
	fun `卡片数量超上限会截断`() {
		val cards = (1..30).joinToString(",") {
			"""{"type":"FACT","keyword":"键$it","content":"值$it","importance":3}"""
		}
		val parsed = CompressionPrompts.parse("{\"summary\":\"多卡\",\"cards\":[$cards]}")

		assertEquals(12, parsed.cards.size)
	}

	@Test
	fun `过长的键和正文会被截断`() {
		val longKeyword = "键".repeat(50)
		val longContent = "内".repeat(300)
		val raw = "{\"summary\":\"s\",\"cards\":[{\"type\":\"FACT\",\"keyword\":\"$longKeyword\"," +
			"\"content\":\"$longContent\",\"importance\":3}]}"

		val card = CompressionPrompts.parse(raw).cards.single()

		assertEquals(12, card.keyword.length)
		assertEquals(80, card.content.length)
	}

	@Test
	fun `transcript 按说话人标注并压掉换行`() {
		val messages = listOf(
			msg(1, ChatRole.USER, "今天好累\n真的"),
			msg(2, ChatRole.ASSISTANT, "怎么啦", personaId = 7),
		)

		val text = CompressionPrompts.transcriptOf(messages) { id -> if (id == 7L) "小雪" else "助手" }

		assertEquals("用户：今天好累 真的\n小雪：怎么啦", text)
	}

	@Test
	fun `摘要提示词把可用类型和字数要求都写清楚了`() {
		val system = CompressionPrompts.summarizerSystem()

		listOf("FACT", "PREFERENCE", "EVENT", "RELATION", "IMPRESSION", "summary", "cards").forEach {
			assertTrue("提示词里应该出现 $it", system.contains(it))
		}
	}

	@Test
	fun `合并提示词要求只产出摘要不产出卡片`() {
		val merged = CompressionPrompts.mergeUser(listOf("第一段摘要", "第二段摘要"))

		assertTrue(merged.contains("第一段摘要"))
		assertTrue(merged.contains("第二段摘要"))
		assertTrue(CompressionPrompts.mergeSystem().contains("空数组"))
	}
}
