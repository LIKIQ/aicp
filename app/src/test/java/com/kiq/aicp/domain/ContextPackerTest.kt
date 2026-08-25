// app/src/test/java/com/kiq/aicp/domain/ContextPackerTest.kt
// 装箱与系统提示词拼装的测试。
// minCount 那条最要紧：预算再紧也得留住最后一两条原文，否则模型收到的是"一堆摘要 + 一句没上文的话"。

package com.kiq.aicp.domain

import com.kiq.aicp.data.db.entity.MemoryCardEntity
import com.kiq.aicp.domain.memory.ContextPacker
import com.kiq.aicp.domain.memory.SystemPromptComposer
import com.kiq.aicp.domain.model.MemoryCardType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextPackerTest {

	private fun card(keyword: String, content: String) = MemoryCardEntity(
		id = 1,
		scopeKey = "c:-|p:-",
		type = MemoryCardType.FACT,
		keyword = keyword,
		content = content,
		importance = 3,
		createdAt = 0,
		updatedAt = 0,
	)

	@Test
	fun `空列表装出空箱`() {
		val packed = ContextPacker.takeWithin(emptyList<Int>(), budget = 100) { it }

		assertTrue(packed.taken.isEmpty())
		assertEquals(0, packed.tokens)
		assertEquals(0, packed.dropped)
	}

	@Test
	fun `预算够就全装进去`() {
		val packed = ContextPacker.takeWithin(listOf(10, 20, 30), budget = 100) { it }

		assertEquals(listOf(10, 20, 30), packed.taken)
		assertEquals(60, packed.tokens)
		assertEquals(0, packed.dropped)
	}

	@Test
	fun `预算不够按顺序截断并记下丢了多少`() {
		val packed = ContextPacker.takeWithin(listOf(10, 20, 30, 40), budget = 35) { it }

		assertEquals(listOf(10, 20), packed.taken)
		assertEquals(30, packed.tokens)
		assertEquals(2, packed.dropped)
	}

	@Test
	fun `minCount 保证哪怕超预算也要装够条数`() {
		val packed = ContextPacker.takeWithin(listOf(100, 200, 300), budget = 10, minCount = 2) { it }

		assertEquals(listOf(100, 200), packed.taken)
		assertEquals(300, packed.tokens)
		assertEquals(1, packed.dropped)
	}

	@Test
	fun `预算为零时仍然遵守 minCount`() {
		val packed = ContextPacker.takeWithin(listOf(50, 60), budget = 0, minCount = 1) { it }

		assertEquals(listOf(50), packed.taken)
	}

	@Test
	fun `负的 token 估算按零处理，不会把预算算回去`() {
		val packed = ContextPacker.takeWithin(listOf(-5, 30), budget = 30) { it }

		assertEquals(listOf(-5, 30), packed.taken)
		assertEquals(30, packed.tokens)
	}

	@Test
	fun `没有记忆时提示词就是纯人设`() {
		val prompt = SystemPromptComposer.compose(
			personaName = "小雪",
			personaPrompt = "  你叫小雪，说话温柔。  ",
			cards = emptyList(),
			longTermSummaries = emptyList(),
			recentSummaries = emptyList(),
			groupMates = emptyList(),
		)

		assertEquals("你叫小雪，说话温柔。", prompt)
		assertFalse(prompt.contains("你记得"))
		assertFalse(prompt.contains("群聊"))
	}

	@Test
	fun `有卡片时带上清单并明确要求不要复述`() {
		val prompt = SystemPromptComposer.compose(
			personaName = "小雪",
			personaPrompt = "你叫小雪",
			cards = listOf(card("称呼", "叫他 KIQ"), card("宠物", "养了一只猫")),
			longTermSummaries = emptyList(),
			recentSummaries = emptyList(),
			groupMates = emptyList(),
		)

		assertTrue(prompt.contains("- [称呼] 叫他 KIQ"))
		assertTrue(prompt.contains("- [宠物] 养了一只猫"))
		assertTrue(prompt.contains("不要复述"))
	}

	@Test
	fun `长期记忆和段摘要分成两块，段摘要压成单行`() {
		val prompt = SystemPromptComposer.compose(
			personaName = "小雪",
			personaPrompt = "你叫小雪",
			cards = emptyList(),
			longTermSummaries = listOf("很早以前的事"),
			recentSummaries = listOf("最近\n聊了压缩"),
			groupMates = emptyList(),
		)

		assertTrue(prompt.contains("长期记忆"))
		assertTrue(prompt.contains("很早以前的事"))
		assertTrue(prompt.contains("- 最近 聊了压缩"))
	}

	@Test
	fun `群聊时说明同场角色并禁止替别人发言`() {
		val prompt = SystemPromptComposer.compose(
			personaName = "小雪",
			personaPrompt = "你叫小雪",
			cards = emptyList(),
			longTermSummaries = emptyList(),
			recentSummaries = emptyList(),
			groupMates = listOf("老刀", "Ada"),
		)

		assertTrue(prompt.contains("老刀、Ada"))
		assertTrue(prompt.contains("不要替别人发言"))
		assertTrue(prompt.contains("小雪 的身份"))
	}
}
