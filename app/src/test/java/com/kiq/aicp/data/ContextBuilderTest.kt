// app/src/test/java/com/kiq/aicp/data/ContextBuilderTest.kt
// 上下文组装的集成测试（真实 Room，无网络）。
//
// 最要紧的两条：
// 1. 群聊里别人说过的话必须转成 user + 【名字】前缀。当成 assistant 塞进去，
//    模型会以为那些是自己说的，然后开始模仿别人的语气 —— 这个 bug 在 UI 上很难看出来。
// 2. 预算再小也要留住最后两条原文，否则模型收到的是一堆摘要加一句没有上文的话。

package com.kiq.aicp.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.kiq.aicp.data.db.AicpDatabase
import com.kiq.aicp.data.db.entity.PersonaEntity
import com.kiq.aicp.data.repo.ChatRepository
import com.kiq.aicp.data.repo.ConversationRepository
import com.kiq.aicp.data.repo.MemoryRepository
import com.kiq.aicp.data.repo.PersonaRepository
import com.kiq.aicp.domain.memory.ContextBuilder
import com.kiq.aicp.domain.model.AicpSettings
import com.kiq.aicp.domain.model.ChatRole
import com.kiq.aicp.domain.model.MemoryCardType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ContextBuilderTest {

	private lateinit var db: AicpDatabase
	private lateinit var personas: PersonaRepository
	private lateinit var conversations: ConversationRepository
	private lateinit var chat: ChatRepository
	private lateinit var memory: MemoryRepository
	private lateinit var builder: ContextBuilder

	private var now = 1_700_000_000_000L

	private val settings = AicpSettings(
		contextBudgetTokens = 4_000,
		keepRecentMessages = 6,
		memoryCardLimit = 10,
	)

	@Before
	fun setUp() {
		db = AicpDatabase.buildInMemory(ApplicationProvider.getApplicationContext<Context>())
		val clock: () -> Long = { now }
		personas = PersonaRepository(db.personaDao(), clock = clock)
		conversations = ConversationRepository(db, clock = clock)
		chat = ChatRepository(db, clock = clock)
		memory = MemoryRepository(db.memoryDao(), clock)
		builder = ContextBuilder(chat, memory)
	}

	@After
	fun tearDown() {
		db.close()
	}

	private suspend fun seedPersonas(): List<PersonaEntity> {
		personas.ensureSeeded()
		return personas.observeAll().first()
	}

	@Test
	fun `单聊上下文以人设开头，历史按时间正序`() = runTest {
		val persona = seedPersonas().first()
		val convId = conversations.createSingle(persona.id)
		chat.appendUser(convId, "第一句")
		val a1 = chat.startAssistant(convId, persona.id)
		chat.finishAssistant(a1, "第一答")
		chat.appendUser(convId, "第二句")

		val built = builder.build(convId, persona, settings)

		assertEquals(ChatRole.SYSTEM, built.messages.first().role)
		assertTrue(built.messages.first().content.startsWith(persona.systemPrompt.take(10)))

		val body = built.messages.drop(1)
		assertEquals(persona.greeting, body[0].content)
		assertEquals("第一句", body[1].content)
		assertEquals("第一答", body[2].content)
		assertEquals("第二句", body.last().content)
		assertEquals(ChatRole.USER, body.last().role)
	}

	@Test
	fun `自己说过的话是 assistant 角色`() = runTest {
		val persona = seedPersonas().first()
		val convId = conversations.createSingle(persona.id)
		val a1 = chat.startAssistant(convId, persona.id)
		chat.finishAssistant(a1, "我说的话")

		val built = builder.build(convId, persona, settings)

		assertTrue(built.messages.any { it.role == ChatRole.ASSISTANT && it.content == "我说的话" })
	}

	@Test
	fun `群聊里别人的发言转成 user 并加名字前缀`() = runTest {
		val all = seedPersonas()
		val speaker = all[0]
		val mate = all[1]
		val convId = conversations.createGroup(listOf(speaker.id, mate.id))

		chat.appendUser(convId, "你们俩都在吗")
		val mateMsg = chat.startAssistant(convId, mate.id)
		chat.finishAssistant(mateMsg, "在的")
		val selfMsg = chat.startAssistant(convId, speaker.id)
		chat.finishAssistant(selfMsg, "我也在")

		val built = builder.build(convId, speaker, groupMates = listOf(mate), settings = settings)

		val mateLine = built.messages.single { it.content.contains("在的") }
		assertEquals(ChatRole.USER, mateLine.role)
		assertEquals("【${mate.name}】在的", mateLine.content)

		val selfLine = built.messages.single { it.content == "我也在" }
		assertEquals(ChatRole.ASSISTANT, selfLine.role)

		assertTrue(built.messages.first().content.contains(mate.name))
	}

	@Test
	fun `已压缩的原文不再进上下文`() = runTest {
		val persona = seedPersonas().first()
		val convId = conversations.createSingle(persona.id)
		repeat(6) { chat.appendUser(convId, "旧消息 $it") }
		val cutoff = chat.observeMessages(convId).first()[3].id
		chat.commitCompression(convId, cutoff)

		val built = builder.build(convId, persona, settings)

		assertFalse(built.messages.any { it.content == "旧消息 0" })
		assertTrue(built.messages.any { it.content == "旧消息 4" })
	}

	@Test
	fun `记忆卡片和摘要都会拼进系统提示词`() = runTest {
		val persona = seedPersonas().first()
		val convId = conversations.createSingle(persona.id)
		chat.appendUser(convId, "在吗")

		memory.upsertCard(null, null, MemoryCardType.RELATION, "称呼", "叫他 KIQ", 5)
		memory.addSummary(convId, level = 1, content = "早些时候聊了压缩", fromMessageId = 0, toMessageId = 2, messageCount = 2)
		memory.addSummary(convId, level = 2, content = "更早的长期记忆", fromMessageId = 0, toMessageId = 1, messageCount = 1)

		val built = builder.build(convId, persona, settings)
		val system = built.messages.first().content

		assertTrue(system.contains("[称呼] 叫他 KIQ"))
		assertTrue(system.contains("早些时候聊了压缩"))
		assertTrue(system.contains("更早的长期记忆"))
		assertEquals(1, built.usedCardIds.size)
		assertEquals(2, built.summaryCount)
	}

	@Test
	fun `卡片上限设为零时不带任何卡片`() = runTest {
		val persona = seedPersonas().first()
		val convId = conversations.createSingle(persona.id)
		memory.upsertCard(null, null, MemoryCardType.FACT, "职业", "安卓开发", 5)

		val built = builder.build(convId, persona, settings.copy(memoryCardLimit = 0))

		assertTrue(built.usedCardIds.isEmpty())
		assertFalse(built.messages.first().content.contains("职业"))
	}

	@Test
	fun `预算极小时仍然保留最近两条原文`() = runTest {
		val persona = seedPersonas().first()
		val convId = conversations.createSingle(persona.id)
		// 单条就有 200 多 token，预算根本装不下几条，逼装箱器走 minCount 那条路
		val bulky = "长".repeat(300)
		repeat(10) { chat.appendUser(convId, "$bulky$it") }

		val built = builder.build(convId, persona, settings.copy(contextBudgetTokens = 700))

		assertTrue("实际只带了 ${built.recentMessageCount} 条", built.recentMessageCount >= 2)
		assertTrue("最后一条应该是最新的那条", built.messages.last().content.endsWith("9"))
		assertTrue("应该报告有原文被丢掉", built.droppedMessageCount > 0)
	}

	@Test
	fun `记忆没花完的额度会退回去多带几条原文`() = runTest {
		val persona = seedPersonas().first()
		val convId = conversations.createSingle(persona.id)
		repeat(20) { chat.appendUser(convId, "消息 $it") }

		// 没有任何记忆，keepRecent 只有 4，但预算充足 —— 剩余额度应该用来多带更早的原文
		val built = builder.build(
			convId,
			persona,
			settings.copy(keepRecentMessages = 4, contextBudgetTokens = 8_000),
		)

		assertTrue("实际带了 ${built.recentMessageCount} 条", built.recentMessageCount > 4)
	}

	@Test
	fun `组装结果的 token 估算不会离预算太远`() = runTest {
		val persona = seedPersonas().first()
		val convId = conversations.createSingle(persona.id)
		repeat(30) { chat.appendUser(convId, "一条内容适中的消息，带点中文和 english 混排 $it") }
		memory.upsertCard(null, null, MemoryCardType.FACT, "职业", "安卓开发", 5)

		val budget = 2_000
		val built = builder.build(convId, persona, settings.copy(contextBudgetTokens = budget))

		// 估算器本身有偏差，留 20% 余量，但不能出现成倍超标
		assertTrue("实际 ${built.estimatedTokens} 超过预算 $budget 太多", built.estimatedTokens <= budget * 1.2)
	}
}
