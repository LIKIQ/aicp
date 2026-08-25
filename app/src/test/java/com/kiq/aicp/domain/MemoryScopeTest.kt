// app/src/test/java/com/kiq/aicp/domain/MemoryScopeTest.kt
// 记忆作用域键的测试。
// 这个键直接参与 memory_cards 的唯一索引，格式一旦变了老数据就全部对不上，
// 所以这里把格式本身也钉住了 —— 想改格式必须同时写数据库迁移。

package com.kiq.aicp.domain

import com.kiq.aicp.domain.memory.MemoryScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryScopeTest {

	@Test
	fun `全局作用域用短横线占位而不是 null 字面量`() {
		assertEquals("c:-|p:-", MemoryScope.global())
	}

	@Test
	fun `会话级与性格级的键互不相同`() {
		val conv = MemoryScope.conversationWide(7)
		val persona = MemoryScope.personaWide(7)
		assertEquals("c:7|p:-", conv)
		assertEquals("c:-|p:7", persona)
		assertTrue(conv != persona)
	}

	@Test
	fun `同一对 id 生成的键稳定可复现`() {
		assertEquals(MemoryScope.key(3, 9), MemoryScope.key(3, 9))
		assertEquals("c:3|p:9", MemoryScope.key(3, 9))
	}

	@Test
	fun `单聊取四个作用域，顺序从宽到窄`() {
		val keys = MemoryScope.contextKeys(conversationId = 12, personaId = 5)
		assertEquals(
			listOf("c:-|p:-", "c:12|p:-", "c:-|p:5", "c:12|p:5"),
			keys,
		)
	}

	@Test
	fun `不带性格时只取全局和会话两个作用域`() {
		val keys = MemoryScope.contextKeys(conversationId = 12, personaId = null)
		assertEquals(listOf("c:-|p:-", "c:12|p:-"), keys)
	}

	@Test
	fun `不同会话的键不会互相污染`() {
		val a = MemoryScope.contextKeys(1, 5)
		val b = MemoryScope.contextKeys(2, 5)
		// 只有全局键和该性格的跨会话印象是共享的，其余必须隔离
		assertEquals(listOf("c:-|p:-", "c:-|p:5"), a.intersect(b.toSet()).toList())
	}
}
