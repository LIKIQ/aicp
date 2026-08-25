// app/src/test/java/com/kiq/aicp/ui/ChatUiStateTest.kt
// 群聊并发流式的 UI 状态约定：
// - 多个角色同时在流时，每条气泡只能取到自己那份增量（早先的单值 streamingText 会串台）
// - 对面还在回的时候输入框不能锁死，用户得能接着说下一句
// - 段间"正在输入…"提示按角色名存，一个人结束不能把还在打字的另一个人擦掉

package com.kiq.aicp.ui

import com.kiq.aicp.data.db.entity.MessageEntity
import com.kiq.aicp.domain.model.ChatRole
import com.kiq.aicp.domain.model.MessageStatus
import com.kiq.aicp.ui.chat.ChatUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatUiStateTest {

	private fun assistantMessage(
		id: Long,
		personaId: Long,
		content: String,
		status: MessageStatus = MessageStatus.STREAMING,
	) = MessageEntity(
		id = id,
		conversationId = 1L,
		role = ChatRole.ASSISTANT,
		personaId = personaId,
		content = content,
		tokenEstimate = 0,
		status = status,
		createdAt = 1_700_000_000_000L,
	)

	@Test
	fun `两个角色同时流式时各取自己的增量`() {
		val a = assistantMessage(id = 10L, personaId = 1L, content = "")
		val b = assistantMessage(id = 11L, personaId = 2L, content = "")
		val state = ChatUiState(
			messages = listOf(a, b),
			streamingTexts = mapOf(10L to "我先说", 11L to "我也说两句"),
		)

		assertEquals("我先说", state.displayContent(a))
		assertEquals("我也说两句", state.displayContent(b))
	}

	@Test
	fun `已完成的消息不受其他角色的流式状态影响`() {
		val done = assistantMessage(id = 10L, personaId = 1L, content = "说完了", status = MessageStatus.OK)
		val streaming = assistantMessage(id = 11L, personaId = 2L, content = "")
		val state = ChatUiState(
			messages = listOf(done, streaming),
			// 先完成的那条已经从 map 里摘掉，只剩还在流的
			streamingTexts = mapOf(11L to "刚开口"),
		)

		assertEquals("说完了", state.displayContent(done))
		assertEquals("刚开口", state.displayContent(streaming))
	}

	@Test
	fun `增量为空时回退到库里的内容`() {
		val msg = assistantMessage(id = 10L, personaId = 1L, content = "占位前落库的半截", status = MessageStatus.OK)
		val state = ChatUiState(messages = listOf(msg), streamingTexts = mapOf(10L to ""))

		assertEquals("占位前落库的半截", state.displayContent(msg))
	}

	@Test
	fun `对面还在回的时候仍然可以发送`() {
		val state = ChatUiState(input = "再问一句", sending = true)

		assertTrue(state.canSend)
	}

	@Test
	fun `落盘附件期间不能发送`() {
		val state = ChatUiState(input = "带图", attaching = true)

		assertFalse(state.canSend)
	}

	@Test
	fun `多人同时打字时段间提示取排序第一个`() {
		val state = ChatUiState(typingPersonaNames = setOf("小雨", "阿澈"))

		// 取固定的那个而不是遍历顺序里的第一个：否则每次重组都可能换人
		assertEquals("小雨", state.typingPersonaName)
	}

	@Test
	fun `没人在打字时段间提示为空`() {
		assertNull(ChatUiState().typingPersonaName)
	}
}
