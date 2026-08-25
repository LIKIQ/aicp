// app/src/test/java/com/kiq/aicp/data/GroupConcurrentReplyTest.kt
// 群聊并发回复的落库顺序约定。
// 上下文构建走的是 recentForContext（只认 status=OK），所以"后开口的能看到先说完的"
// 这件事完全押在两点上：先完成的那条要能独立入库、还在流的占位不能污染上下文。
// 这里用交错调用模拟两个角色同时在回。

package com.kiq.aicp.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.kiq.aicp.data.db.AicpDatabase
import com.kiq.aicp.data.repo.ChatRepository
import com.kiq.aicp.data.repo.ConversationRepository
import com.kiq.aicp.data.repo.PersonaRepository
import com.kiq.aicp.domain.model.MessageStatus
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
class GroupConcurrentReplyTest {

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

	private suspend fun twoPersonaIds(): Pair<Long, Long> {
		personas.ensureSeeded()
		val all = personas.observeAll().first()
		assertTrue("内置性格至少要有两个才能测群聊", all.size >= 2)
		return all[0].id to all[1].id
	}

	@Test
	fun `先说完的角色单独入库_后开口的角色能在上下文里读到`() = runTest {
		val (first, second) = twoPersonaIds()
		val convId = conversations.createGroup(listOf(first, second))
		chat.appendUser(convId, "你们俩谁先说")

		// 角色 A 先占位并开始流
		val idA = chat.startAssistant(convId, first)
		chat.updateStreaming(idA, "我先")

		// 此刻 A 还在流：占位是 STREAMING，不该进任何人的上下文
		val duringStream = chat.recentForContext(convId, limit = 20)
		assertFalse(
			"还在流的占位不能进上下文，否则后开口的角色会读到半截甚至空串",
			duringStream.any { it.id == idA },
		)

		// A 说完落库
		chat.finishAssistant(idA, "我先说，今天降温了")

		// B 这时才开口（错峰的效果），构建上下文时应当已经看得到 A 那句
		val beforeB = chat.recentForContext(convId, limit = 20)
		assertTrue(
			"后开口的角色必须能看到已完成角色的回复",
			beforeB.any { it.id == idA && it.content == "我先说，今天降温了" },
		)

		val idB = chat.startAssistant(convId, second)
		chat.finishAssistant(idB, "那我加件外套")

		val all = chat.observeMessages(convId).first()
		val tail = all.takeLast(2)
		assertEquals(listOf(idA, idB), tail.map { it.id })
		assertTrue(tail.all { it.status == MessageStatus.OK })
	}

	@Test
	fun `两条流式消息交错推进互不干扰`() = runTest {
		val (first, second) = twoPersonaIds()
		val convId = conversations.createGroup(listOf(first, second))
		chat.appendUser(convId, "一起说吧")

		val idA = chat.startAssistant(convId, first)
		val idB = chat.startAssistant(convId, second)

		// 交错 flush：真实并发下两个协程就是这么互相插空的
		chat.updateStreaming(idA, "我说")
		chat.updateStreaming(idB, "我也")
		chat.updateStreaming(idA, "我说第一句")
		chat.updateStreaming(idB, "我也说一句")

		val streaming = chat.observeMessages(convId).first().filter { it.status == MessageStatus.STREAMING }
		assertEquals(2, streaming.size)
		assertEquals("我说第一句", streaming.first { it.id == idA }.content)
		assertEquals("我也说一句", streaming.first { it.id == idB }.content)

		// B 先完成：谁先完成谁先入库，A 那条继续留在 STREAMING
		chat.finishAssistant(idB, "我也说一句，附议")
		val afterB = chat.observeMessages(convId).first()
		assertEquals(MessageStatus.OK, afterB.first { it.id == idB }.status)
		assertEquals(MessageStatus.STREAMING, afterB.first { it.id == idA }.status)
	}

	@Test
	fun `一个角色失败不影响另一个角色的回复`() = runTest {
		val (first, second) = twoPersonaIds()
		val convId = conversations.createGroup(listOf(first, second))
		chat.appendUser(convId, "会不会互相带崩")

		val idA = chat.startAssistant(convId, first)
		val idB = chat.startAssistant(convId, second)

		chat.updateStreaming(idA, "才说了半句")
		chat.failAssistant(idA, "请求失败")
		chat.finishAssistant(idB, "我这边正常说完了")

		val messages = chat.observeMessages(convId).first()
		val a = messages.first { it.id == idA }
		val b = messages.first { it.id == idB }
		assertEquals(MessageStatus.FAILED, a.status)
		// 半截文本要留着，用户才知道刚才发生过什么
		assertEquals("才说了半句", a.content)
		assertEquals(MessageStatus.OK, b.status)

		// 失败那条不进上下文，成功那条要进
		val context = chat.recentForContext(convId, limit = 20)
		assertFalse(context.any { it.id == idA })
		assertTrue(context.any { it.id == idB })
	}
}
