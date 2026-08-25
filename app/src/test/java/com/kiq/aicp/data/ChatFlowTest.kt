// app/src/test/java/com/kiq/aicp/data/ChatFlowTest.kt
// 一整条聊天链路的落库行为：建会话 → 发言 → 流式回复 → 压缩提交。
// 重点盯住两处最容易出账不平的地方：会话上的冗余字段（预览 / pendingTokens）和压缩游标。

package com.kiq.aicp.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.kiq.aicp.data.db.AicpDatabase
import com.kiq.aicp.data.repo.ChatRepository
import com.kiq.aicp.data.repo.ConversationRepository
import com.kiq.aicp.data.repo.PersonaRepository
import com.kiq.aicp.domain.model.ConversationMode
import com.kiq.aicp.domain.model.MessageStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ChatFlowTest {

	private lateinit var db: AicpDatabase
	private lateinit var personas: PersonaRepository
	private lateinit var conversations: ConversationRepository
	private lateinit var chat: ChatRepository

	private var now = 1_700_000_000_000L

	@Before
	fun setUp() {
		db = AicpDatabase.buildInMemory(ApplicationProvider.getApplicationContext<Context>())
		val clock: () -> Long = { now }
		personas = PersonaRepository(db.personaDao(), clock = clock)
		conversations = ConversationRepository(db, clock = clock)
		chat = ChatRepository(db, clock = clock)
	}

	@After
	fun tearDown() {
		db.close()
	}

	private suspend fun firstPersonaId(): Long {
		personas.ensureSeeded()
		return personas.observeAll().first().first().id
	}

	@Test
	fun `建单聊会插入开场白并刷新会话预览`() = runTest {
		val personaId = firstPersonaId()
		val persona = personas.getById(personaId)!!

		val convId = conversations.createSingle(personaId)

		val conv = conversations.getById(convId)!!
		assertEquals(persona.name, conv.title)
		assertEquals(ConversationMode.SINGLE, conv.mode)
		assertTrue(conv.lastMessagePreview.isNotEmpty())

		val messages = chat.observeMessages(convId).first()
		assertEquals(1, messages.size)
		assertEquals(persona.greeting, messages.single().content)
		assertEquals(personaId, messages.single().personaId)
	}

	@Test
	fun `用户发言后会话预览和 pendingTokens 都跟着更新`() = runTest {
		val convId = conversations.createSingle(firstPersonaId())

		now += 1_000
		chat.appendUser(convId, "  帮我看下这段压缩逻辑  ")

		val conv = conversations.getById(convId)!!
		assertEquals("帮我看下这段压缩逻辑", conv.lastMessagePreview)
		assertEquals(1_700_000_001_000L, conv.lastMessageAt)
		// 冗余字段必须跟实际 SUM 对得上，否则压缩阈值判断会漂
		assertEquals(chat.uncompressedTokens(convId), conv.pendingTokens)
		assertEquals(2, chat.uncompressedCount(convId))
	}

	@Test
	fun `流式回复从 STREAMING 收尾成 OK`() = runTest {
		val personaId = firstPersonaId()
		val convId = conversations.createSingle(personaId)
		chat.appendUser(convId, "在吗")

		val msgId = chat.startAssistant(convId, personaId)
		assertEquals(MessageStatus.STREAMING, chat.getMessage(msgId)!!.status)

		chat.updateStreaming(msgId, "在的")
		chat.updateStreaming(msgId, "在的，怎么了")
		chat.finishAssistant(msgId, "在的，怎么了？")

		val done = chat.getMessage(msgId)!!
		assertEquals(MessageStatus.OK, done.status)
		assertEquals("在的，怎么了？", done.content)
		assertTrue(done.tokenEstimate > 0)
		assertEquals("在的，怎么了？", conversations.getById(convId)!!.lastMessagePreview)
	}

	@Test
	fun `空回复收尾会被判失败并带上原因`() = runTest {
		val personaId = firstPersonaId()
		val convId = conversations.createSingle(personaId)

		val msgId = chat.startAssistant(convId, personaId)
		chat.finishAssistant(msgId, "   ")

		val failed = chat.getMessage(msgId)!!
		assertEquals(MessageStatus.FAILED, failed.status)
		assertNotNull(failed.errorMessage)
	}

	@Test
	fun `提交压缩会推进游标 标记原文 并重算未压缩预算`() = runTest {
		val personaId = firstPersonaId()
		val convId = conversations.createSingle(personaId)
		repeat(6) { i ->
			now += 1_000
			chat.appendUser(convId, "第 $i 条用户消息，随便写点东西撑长度")
		}

		val all = chat.observeMessages(convId).first()
		val cutoff = all[3].id
		val tokensBefore = chat.uncompressedTokens(convId)

		chat.commitCompression(convId, cutoff)

		val conv = conversations.getById(convId)!!
		assertEquals(cutoff, conv.compressedUntilMessageId)
		assertEquals(0, conv.compressFailureCount)
		assertEquals(chat.uncompressedTokens(convId), conv.pendingTokens)
		assertTrue("压缩后未压缩预算必须下降", conv.pendingTokens < tokensBefore)

		// 原文不能被删，只是不再进上下文
		assertEquals(all.size, chat.observeMessages(convId).first().size)
		val context = chat.recentForContext(convId, limit = 50)
		assertTrue(context.none { it.id <= cutoff })
	}

	@Test
	fun `压缩失败只累加失败计数，不动游标`() = runTest {
		val convId = conversations.createSingle(firstPersonaId())
		chat.appendUser(convId, "一条消息")

		chat.markCompressionFailed(convId)
		chat.markCompressionFailed(convId)

		val conv = conversations.getById(convId)!!
		assertEquals(2, conv.compressFailureCount)
		assertEquals(0L, conv.compressedUntilMessageId)
	}

	@Test
	fun `拉第二个性格进单聊会自动升级为群聊`() = runTest {
		personas.ensureSeeded()
		val ids = personas.observeAll().first().map { it.id }
		val convId = conversations.createSingle(ids[0])

		conversations.addParticipant(convId, ids[1])

		assertEquals(ConversationMode.GROUP, conversations.getById(convId)!!.mode)
		assertEquals(2, conversations.participants(convId).size)
	}

	@Test
	fun `删会话会级联清掉它的消息`() = runTest {
		val convId = conversations.createSingle(firstPersonaId())
		chat.appendUser(convId, "留个痕迹")
		assertTrue(chat.observeMessages(convId).first().isNotEmpty())

		conversations.delete(convId)

		assertTrue(chat.observeMessages(convId).first().isEmpty())
	}

	@Test
	fun `清理失败消息后预算重新对账`() = runTest {
		val personaId = firstPersonaId()
		val convId = conversations.createSingle(personaId)
		val msgId = chat.startAssistant(convId, personaId)
		chat.updateStreaming(msgId, "半截话")
		chat.failAssistant(msgId, "网络断了")

		chat.clearFailed(convId)

		assertTrue(chat.observeMessages(convId).first().none { it.status == MessageStatus.FAILED })
		assertEquals(chat.uncompressedTokens(convId), conversations.getById(convId)!!.pendingTokens)
	}
}
