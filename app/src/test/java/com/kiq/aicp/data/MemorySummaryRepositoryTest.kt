// app/src/test/java/com/kiq/aicp/data/MemorySummaryRepositoryTest.kt
// 摘要层（L1/L2）的落库语义：递归合并后旧摘要要退场，占位摘要要能补压。

package com.kiq.aicp.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.kiq.aicp.data.db.AicpDatabase
import com.kiq.aicp.data.repo.ConversationRepository
import com.kiq.aicp.data.repo.MemoryRepository
import com.kiq.aicp.data.repo.PersonaRepository
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
class MemorySummaryRepositoryTest {

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

	private suspend fun newConversation(): Long {
		personas.ensureSeeded()
		return conversations.createSingle(personas.observeAll().first().first().id)
	}

	@Test
	fun `合并成上层摘要后旧摘要退出 active 列表`() = runTest {
		val convId = newConversation()

		val s1 = memory.addSummary(convId, level = 1, content = "第一段", fromMessageId = 0, toMessageId = 10, messageCount = 10)
		val s2 = memory.addSummary(convId, level = 1, content = "第二段", fromMessageId = 10, toMessageId = 20, messageCount = 10)
		assertEquals(2, memory.countActiveSummaries(convId, level = 1))

		memory.addSummary(convId, level = 2, content = "合并后的长期记忆", fromMessageId = 0, toMessageId = 20, messageCount = 20)
		memory.supersede(listOf(s1, s2))

		assertEquals(0, memory.countActiveSummaries(convId, level = 1))
		assertEquals(1, memory.countActiveSummaries(convId, level = 2))
		// 退场不等于删除，历史仍然查得到
		assertEquals(3, memory.observeSummaries(convId).first().size)
	}

	@Test
	fun `占位摘要补压后清掉待重压标记`() = runTest {
		val convId = newConversation()
		memory.addSummary(
			convId = convId,
			level = 1,
			content = "（离线占位）这段还没做语义压缩",
			fromMessageId = 0,
			toMessageId = 8,
			messageCount = 8,
			needsSemanticRedo = true,
		)

		val pending = memory.summariesNeedingRedo(convId)
		assertEquals(1, pending.size)

		memory.replaceSummaryBody(pending.first(), "用户在聊压缩策略，情绪平稳，约定明天继续。")

		assertTrue(memory.summariesNeedingRedo(convId).isEmpty())
		val fixed = memory.activeSummaries(convId, level = 1).single()
		assertFalse(fixed.needsSemanticRedo)
		assertTrue(fixed.tokenEstimate > 0)
	}

	@Test
	fun `摘要的 token 估算跟着内容自动算`() = runTest {
		val convId = newConversation()
		memory.addSummary(convId, 1, "短", 0, 1, 1)
		memory.addSummary(convId, 1, "这一段明显要长得多".repeat(5), 1, 2, 1)

		val list = memory.activeSummaries(convId, level = 1)
		assertTrue(list[1].tokenEstimate > list[0].tokenEstimate)
	}

	@Test
	fun `删会话会级联清掉它的摘要`() = runTest {
		val convId = newConversation()
		memory.addSummary(convId, 1, "会话内的摘要", 0, 5, 5)
		assertEquals(1, memory.observeSummaries(convId).first().size)

		conversations.delete(convId)

		assertEquals(0, memory.observeSummaries(convId).first().size)
	}
}
