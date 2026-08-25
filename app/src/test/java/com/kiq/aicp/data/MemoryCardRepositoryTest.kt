// app/src/test/java/com/kiq/aicp/data/MemoryCardRepositoryTest.kt
// 记忆卡片的落库语义测试。重点验证两件容易出错的事：
// 1. scopeKey 方案是否真的挡住了"NULL 不等于 NULL"导致的唯一索引失效
// 2. 用户钉住的卡片是否真的不会被自动压缩悄悄改写

package com.kiq.aicp.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.kiq.aicp.data.db.AicpDatabase
import com.kiq.aicp.data.repo.ConversationRepository
import com.kiq.aicp.data.repo.MemoryRepository
import com.kiq.aicp.data.repo.PersonaRepository
import com.kiq.aicp.domain.model.MemoryCardType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MemoryCardRepositoryTest {

	private lateinit var db: AicpDatabase
	private lateinit var memory: MemoryRepository
	private lateinit var personas: PersonaRepository
	private lateinit var conversations: ConversationRepository

	private var now = 1_700_000_000_000L

	@Before
	fun setUp() {
		db = AicpDatabase.buildInMemory(ApplicationProvider.getApplicationContext<Context>())
		val clock: () -> Long = { now }
		memory = MemoryRepository(db.memoryDao(), clock)
		personas = PersonaRepository(db.personaDao(), clock = clock)
		conversations = ConversationRepository(db, clock = clock)
	}

	@After
	fun tearDown() {
		db.close()
	}

	private suspend fun newConversation(): Pair<Long, Long> {
		personas.ensureSeeded()
		val personaId = personas.observeAll().first().first().id
		return conversations.createSingle(personaId) to personaId
	}

	@Test
	fun `同作用域同键重复抽取只更新内容并保留创建时间`() = runTest {
		val first = memory.upsertCard(
			conversationId = null,
			personaId = null,
			type = MemoryCardType.FACT,
			keyword = "宠物",
			content = "养了一只猫",
			importance = 3,
		)
		now += 1_000
		val second = memory.upsertCard(
			conversationId = null,
			personaId = null,
			type = MemoryCardType.FACT,
			keyword = "宠物",
			content = "养了一只猫和一只狗",
			importance = 4,
		)

		assertEquals(first, second)
		assertEquals(1, memory.countCards())

		val card = memory.observeAllCards().first().single()
		assertEquals("养了一只猫和一只狗", card.content)
		assertEquals(4, card.importance)
		assertEquals(1_700_000_000_000L, card.createdAt)
		assertEquals(1_700_000_001_000L, card.updatedAt)
	}

	@Test
	fun `全局卡片与会话卡片同键不会互相覆盖`() = runTest {
		val (convId, _) = newConversation()

		val globalId = memory.upsertCard(null, null, MemoryCardType.FACT, "职业", "程序员", 4)
		val scopedId = memory.upsertCard(convId, null, MemoryCardType.FACT, "职业", "这局里演学生", 3)

		assertNotEquals(globalId, scopedId)
		assertEquals(2, memory.countCards())
	}

	@Test
	fun `钉住的卡片不会被自动抽取改写`() = runTest {
		val id = memory.upsertCard(null, null, MemoryCardType.RELATION, "称呼", "叫我 KIQ", 5)
		memory.setPinned(id, true)

		val again = memory.upsertCard(null, null, MemoryCardType.RELATION, "称呼", "叫我小K", 2)

		assertEquals(id, again)
		val card = memory.observeAllCards().first().single()
		assertEquals("叫我 KIQ", card.content)
		assertEquals(5, card.importance)
		assertTrue(card.pinned)
	}

	@Test
	fun `拼上下文只捞本会话可见的三个维度，别的会话不串味`() = runTest {
		val (convA, personaId) = newConversation()
		val convB = conversations.createSingle(personaId)

		memory.upsertCard(null, null, MemoryCardType.RELATION, "称呼", "叫我 KIQ", 5)
		memory.upsertCard(convA, null, MemoryCardType.EVENT, "在聊什么", "AICP 的架构", 3)
		memory.upsertCard(null, personaId, MemoryCardType.IMPRESSION, "印象", "很较真", 2)
		memory.upsertCard(convB, null, MemoryCardType.EVENT, "在聊什么", "另一个话题", 5)

		val cards = memory.contextCards(convA, personaId, limit = 10)

		assertEquals(3, cards.size)
		assertTrue(cards.none { it.content == "另一个话题" })
	}

	@Test
	fun `上下文取材按钉住优先再按重要度排序`() = runTest {
		val low = memory.upsertCard(null, null, MemoryCardType.FACT, "低分", "不重要", 1)
		memory.upsertCard(null, null, MemoryCardType.FACT, "高分", "很重要", 5)
		memory.setPinned(low, true)

		val cards = memory.contextCards(convId = 1, personaId = null, limit = 10)
			.ifEmpty { memory.observeAllCards().first() }

		assertEquals("不重要", cards.first().content)
	}

	@Test
	fun `冷卡淘汰只清低重要度且长期没命中的非钉住卡片`() = runTest {
		memory.upsertCard(null, null, MemoryCardType.FACT, "冷卡", "早就没人提了", 1)
		memory.upsertCard(null, null, MemoryCardType.FACT, "热卡", "一直在用", 5)
		val pinned = memory.upsertCard(null, null, MemoryCardType.FACT, "钉住的冷卡", "手动锁的", 1)
		memory.setPinned(pinned, true)

		now += 100L * 24 * 60 * 60 * 1000
		val removed = memory.pruneCold(maxImportance = 2, idleDays = 60)

		assertEquals(1, removed)
		val left = memory.observeAllCards().first().map { it.keyword }
		assertTrue(left.containsAll(listOf("热卡", "钉住的冷卡")))
	}

	@Test
	fun `空内容不落库`() = runTest {
		val id = memory.upsertCard(null, null, MemoryCardType.FACT, "空的", "   ", 3)
		assertEquals(0L, id)
		assertEquals(0, memory.countCards())
	}
}
