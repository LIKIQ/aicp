// app/src/test/java/com/kiq/aicp/data/ContextBuilderTest.kt
// 上下文组装的集成测试（真实 Room，无网络）。
//
// 最要紧的两条：
// 1. 群聊里别人说过的话必须转成 user + 【名字】前缀。当成 assistant 塞进去，
//    模型会以为那些是自己说的，然后开始模仿别人的语气 —— 这个 bug 在 UI 上很难看出来。
// 2. 预算再小也要留住最后两条原文，否则模型收到的是一堆摘要加一句没有上文的话。
// 3. 联网搜到的那段有独立预算，超了按行砍尾巴。它是唯一由外部内容决定长度的部分，
//    不先扣预算就砍，长对话遇上长网页正文会把历史挤没，表现成"它突然不记得刚才说的话"。

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
import com.kiq.aicp.domain.memory.ParsedEntry
import com.kiq.aicp.domain.model.AicpSettings
import com.kiq.aicp.domain.model.ChatRole
import com.kiq.aicp.domain.model.MemoryCardType
import com.kiq.aicp.domain.websearch.WebSearchComposer
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
	fun `记忆条目和摘要都会拼进系统提示词`() = runTest {
		val persona = seedPersonas().first()
		val convId = conversations.createSingle(persona.id)
		chat.appendUser(convId, "在吗")

		memory.upsertEntries(
			conversationId = null,
			personaId = null,
			entries = listOf(
				ParsedEntry(
					category = MemoryCardType.RELATION,
					title = "称呼",
					aliases = listOf("怎么叫"),
					oneLiner = "叫他 KIQ",
					body = "叫他 KIQ，不要叫全名。",
					importance = 5,
					conflictNote = null,
				),
			),
		)
		memory.addSummary(convId, level = 1, content = "早些时候聊了压缩", fromMessageId = 0, toMessageId = 2, messageCount = 2)
		memory.addSummary(convId, level = 2, content = "更早的长期记忆", fromMessageId = 0, toMessageId = 1, messageCount = 1)

		val built = builder.build(convId, persona, settings)
		val system = built.messages.first().content

		// 条目渲染成小标题加正文，不再是"[键] 值"那种一行
		assertTrue(system.contains("## 称呼"))
		assertTrue(system.contains("叫他 KIQ，不要叫全名。"))
		assertTrue(system.contains("早些时候聊了压缩"))
		assertTrue(system.contains("更早的长期记忆"))
		assertEquals(1, built.usedCardIds.size)
		assertEquals(2, built.summaryCount)
	}

	@Test
	fun `条目上限设为零时不带任何条目`() = runTest {
		val persona = seedPersonas().first()
		val convId = conversations.createSingle(persona.id)
		memory.upsertEntries(
			conversationId = null,
			personaId = null,
			entries = listOf(
				ParsedEntry(
					category = MemoryCardType.FACT,
					title = "职业",
					aliases = emptyList(),
					oneLiner = "安卓开发",
					body = "安卓开发",
					importance = 5,
					conflictNote = null,
				),
			),
		)

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

	// ---------------- 联网搜到的那一段 ----------------

	/** 照 WebSearchComposer 的形状拼一段，头一行是它的段落标题，后面每行一条内容 */
	private fun webBlock(bodyLines: List<String>): String =
		(listOf("${WebSearchComposer.SECTION_TITLE}（2026-08-26 搜的「北京 天气」）") + bodyLines)
			.joinToString("\n")

	@Test
	fun `没搜的时候系统提示词里一个字都不加`() = runTest {
		val persona = seedPersonas().first()
		val convId = conversations.createSingle(persona.id)
		chat.appendUser(convId, "在吗")

		val built = builder.build(convId, persona, settings, webResults = "")
		val system = built.messages.first().content

		assertFalse("没搜却出现了搜索段的标题", system.contains(WebSearchComposer.SECTION_TITLE))
		assertFalse(system.contains("刚从搜索引擎拿到"))
	}

	@Test
	fun `搜到的内容会拼进系统提示词`() = runTest {
		val persona = seedPersonas().first()
		val convId = conversations.createSingle(persona.id)
		chat.appendUser(convId, "北京今天天气怎么样")

		val web = webBlock(listOf("## 北京天气预报", "今天白天多云，最高气温 26℃", "（来源：tianqi.com）"))
		val built = builder.build(convId, persona, settings, webResults = web)
		val system = built.messages.first().content

		assertTrue(system.contains(WebSearchComposer.SECTION_TITLE))
		assertTrue(system.contains("今天白天多云，最高气温 26℃"))
		assertTrue(system.contains("（来源：tianqi.com）"))
		// 人设仍然排在最前面，搜索段是接在后面的
		assertTrue(system.startsWith(persona.systemPrompt.take(10)))
	}

	@Test
	fun `网上信息超预算时按行砍尾巴，历史消息一条都不少`() = runTest {
		val persona = seedPersonas().first()
		val convId = conversations.createSingle(persona.id)
		repeat(6) { chat.appendUser(convId, "第 $it 句话，随便聊点什么") }

		// 结果条数和每篇字数都是用户可调的，两个拉满这段能顶到几千 token。
		// 这里刻意造一份超量的，再把它自己的预算压到 300，逼 clampWebResults 动手
		val lastLine = "这是最后一行，超预算之后它必须被砍掉。"
		val body = (1..60).map { "第${it}行：北京今天最高气温 ${it}℃，湿度 ${it}%，风力 ${it} 级。" }
		val web = webBlock(body + lastLine)

		val built = builder.build(
			convId,
			persona,
			settings.copy(webSearchBudgetTokens = 300),
			webResults = web,
		)
		val system = built.messages.first().content

		assertTrue("头一行被砍掉了，模型就不知道这批内容是搜来的", system.contains(WebSearchComposer.SECTION_TITLE))
		assertTrue("砍得太狠，前几行都没留下", system.contains(body.first()))
		assertFalse("尾巴没砍掉，超预算的内容原样进了提示词", system.contains(lastLine))
		assertFalse("倒数几行也该在预算外", system.contains(body.last()))

		// 重点：网页内容挤掉的是自己那份预算，不是对话历史
		assertTrue("历史被网页内容挤没了，只剩 ${built.messages.size} 条", built.messages.size > 1)
		assertTrue("最后一条原文没留住", built.messages.last().content.contains("第 5 句话"))
		assertTrue("带的原文太少：${built.recentMessageCount} 条", built.recentMessageCount >= 2)
	}

	@Test
	fun `网上信息没超预算时原样保留`() = runTest {
		val persona = seedPersonas().first()
		val convId = conversations.createSingle(persona.id)
		chat.appendUser(convId, "北京今天天气怎么样")

		val web = webBlock(
			listOf(
				"## 北京天气预报 - 天气网",
				"今天白天多云，最高气温 26℃，最低气温 15℃",
				"空气质量：优 湿度：75% 风向：北风 2级",
				"（来源：tianqi.com）",
			),
		)

		val built = builder.build(
			convId,
			persona,
			settings.copy(webSearchBudgetTokens = 1_500),
			webResults = web,
		)
		val system = built.messages.first().content

		assertTrue("没超预算却被动了刀", system.contains(web))
		assertTrue(system.contains("（来源：tianqi.com）"))
	}

	/**
	 * 防回归：一条超长正文不许把后面几条短摘要一起挡在门外。
	 *
	 * PassagePicker 有条"一行都装不下就截一刀"的分支，能吐出单行两千字的正文。
	 * 早先 clampWebResults 是按行装的，装到这一行就 break，结果只剩个空的
	 * `## 标题` 头，后面明明装得下的摘要全丢了 —— 给模型一个"我查到了"的空壳
	 * 比什么都不给更糟，它会顺着标题自己编内容。
	 */
	@Test
	fun `超长的第一条正文被跳过，后面的短摘要照样进上下文`() = runTest {
		val persona = seedPersonas().first()
		val convId = conversations.createSingle(persona.id)
		chat.appendUser(convId, "北京今天天气怎么样")

		val web = webBlock(
			listOf(
				"## 第一条 正文超长的那个",
				"北京今天最高气温 26℃。".repeat(200),
				"（来源：huge.example.com）",
				"## 第二条 只有摘要",
				"空气质量：优 湿度：75% 风向：北风 2级",
				"（来源：tianqi.com）",
				"## 第三条 也只有摘要",
				"明天转晴，最高 28℃",
				"（来源：weather.com.cn）",
			),
		)

		val built = builder.build(
			convId,
			persona,
			settings.copy(webSearchBudgetTokens = 300),
			webResults = web,
		)
		val system = built.messages.first().content

		assertTrue("标题头必须留着", system.contains(WebSearchComposer.SECTION_TITLE))
		assertFalse("超长正文该被整块跳过", system.contains("北京今天最高气温 26℃。".repeat(50)))
		assertTrue("被长正文挡住了，第二条摘要没进来", system.contains("空气质量：优 湿度：75%"))
		assertTrue("第三条摘要也该进来", system.contains("明天转晴，最高 28℃"))
		assertTrue("历史被挤没了", built.messages.size > 1)
	}
}
