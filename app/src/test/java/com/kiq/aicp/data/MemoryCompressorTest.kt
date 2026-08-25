// app/src/test/java/com/kiq/aicp/data/MemoryCompressorTest.kt
// 压缩引擎的行为测试，全程离线（FakeLlmProvider）。
//
// 这里守的是三条底线：原文不丢、失败不推进游标、失败要退避。
// 另外把"卡片作用域按类型分流"钉住 —— 它决定一条记忆以后在哪些会话里生效，改错了会串味。

package com.kiq.aicp.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.kiq.aicp.data.db.AicpDatabase
import com.kiq.aicp.data.remote.LlmException
import com.kiq.aicp.data.repo.ChatRepository
import com.kiq.aicp.data.repo.ConversationRepository
import com.kiq.aicp.data.repo.MemoryRepository
import com.kiq.aicp.data.repo.PersonaRepository
import com.kiq.aicp.domain.memory.CompressionResult
import com.kiq.aicp.domain.memory.MemoryCompressor
import com.kiq.aicp.domain.model.AicpSettings
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
class MemoryCompressorTest {

	private lateinit var db: AicpDatabase
	private lateinit var personas: PersonaRepository
	private lateinit var conversations: ConversationRepository
	private lateinit var chat: ChatRepository
	private lateinit var memory: MemoryRepository
	private lateinit var llm: FakeLlmProvider
	private lateinit var compressor: MemoryCompressor

	private var now = 1_700_000_000_000L

	/** 用条数触发，token 阈值抬到很高，免得两个条件互相干扰 */
	private val settings = AicpSettings(
		baseUrl = "https://api.example.com",
		apiKey = "sk-test",
		model = "main-model",
		compressModel = "cheap-model",
		keepRecentMessages = 4,
		compressTriggerTokens = 1_000_000,
		compressTriggerCount = 8,
		summaryMergeThreshold = 3,
	)

	@Before
	fun setUp() {
		db = AicpDatabase.buildInMemory(ApplicationProvider.getApplicationContext<Context>())
		val clock: () -> Long = { now }
		personas = PersonaRepository(db.personaDao(), clock = clock)
		conversations = ConversationRepository(db, clock = clock)
		chat = ChatRepository(db, clock = clock)
		memory = MemoryRepository(db.memoryDao(), clock)
		llm = FakeLlmProvider()
		compressor = MemoryCompressor(
			chatRepository = chat,
			memoryRepository = memory,
			conversationRepository = conversations,
			personaRepository = personas,
			llmProvider = llm,
			clock = clock,
		)
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

	/** 每轮产生一条用户消息和一条助手消息 */
	private suspend fun sendRounds(convId: Long, personaId: Long, rounds: Int) {
		repeat(rounds) { i ->
			now += 1_000
			chat.appendUser(convId, "第 $i 个问题，这里写长一点方便撑起 token 估算")
			val msgId = chat.startAssistant(convId, personaId)
			chat.finishAssistant(msgId, "第 $i 个回答，同样写长一点，模拟真实回复长度")
		}
	}

	@Test
	fun `未达阈值时压根不调模型`() = runTest {
		val (convId, personaId) = newConversation()
		sendRounds(convId, personaId, rounds = 2)

		val result = compressor.compressIfNeeded(convId, settings)

		assertTrue(result is CompressionResult.NotNeeded)
		assertEquals(0, llm.completeCalls.size)
	}

	@Test
	fun `可压材料不足四条时也不调模型`() = runTest {
		val (convId, personaId) = newConversation()
		// keepRecentMessages=4 时 9 条里能压 5 条；把保留条数抬到 8，target 就只剩 1 条
		sendRounds(convId, personaId, rounds = 4)
		val tooFew = settings.copy(keepRecentMessages = 8)

		assertTrue(compressor.compressIfNeeded(convId, tooFew) is CompressionResult.NotNeeded)
		// 手动整理也不该为了一条消息去调一次模型
		assertTrue(compressor.compressIfNeeded(convId, tooFew, force = true) is CompressionResult.NotNeeded)
		assertEquals(0, llm.completeCalls.size)
	}

	@Test
	fun `达到条数阈值后落摘要 条目 并推进游标`() = runTest {
		val (convId, personaId) = newConversation()
		sendRounds(convId, personaId, rounds = 4)
		llm.scriptedReplies = mutableListOf(
			FakeLlmProvider.wikiIngestJson(
				summary = "用户连着问了几个问题，情绪平稳。",
				entries = listOf(Triple("FACT", "职业", "在做安卓开发")),
			),
		)

		val result = compressor.compressIfNeeded(convId, settings)

		val compressed = result as CompressionResult.Compressed
		assertTrue(compressed.strict)
		assertEquals(1, compressed.cardsWritten)
		assertTrue(compressed.compressedMessages >= 4)

		val summary = memory.activeSummaries(convId, level = 1).single()
		assertEquals("用户连着问了几个问题，情绪平稳。", summary.content)
		assertFalse(summary.needsSemanticRedo)

		// 条目该落进去了，正文就是模型给的那段
		val entry = memory.observeAllEntries().first().single()
		assertEquals("职业", entry.title)
		assertEquals("在做安卓开发", entry.body)
		assertEquals(1, entry.sourceCount)

		val conv = conversations.getById(convId)!!
		assertEquals(summary.toMessageId, conv.compressedUntilMessageId)
		assertEquals(0, conv.compressFailureCount)
	}

	@Test
	fun `压缩后原文仍在库里，只是不再进上下文`() = runTest {
		val (convId, personaId) = newConversation()
		sendRounds(convId, personaId, rounds = 4)
		val before = chat.observeMessages(convId).first().size
		llm.scriptedReplies = mutableListOf(FakeLlmProvider.compressionJson("摘要"))

		compressor.compressIfNeeded(convId, settings)

		assertEquals(before, chat.observeMessages(convId).first().size)
		val cursor = conversations.getById(convId)!!.compressedUntilMessageId
		assertTrue(chat.recentForContext(convId, limit = 50).none { it.id <= cursor })
	}

	@Test
	fun `保留最近若干条不被标记为已压缩`() = runTest {
		val (convId, personaId) = newConversation()
		sendRounds(convId, personaId, rounds = 4)
		llm.scriptedReplies = mutableListOf(FakeLlmProvider.compressionJson("摘要"))

		compressor.compressIfNeeded(convId, settings)

		assertEquals(settings.keepRecentMessages, chat.uncompressedCount(convId))
	}

	@Test
	fun `压缩走的是压缩专用模型而不是主模型`() = runTest {
		val (convId, personaId) = newConversation()
		sendRounds(convId, personaId, rounds = 4)
		llm.scriptedReplies = mutableListOf(FakeLlmProvider.compressionJson("摘要"))

		compressor.compressIfNeeded(convId, settings)

		assertEquals("cheap-model", llm.completeParams.first().model)
	}

	@Test
	fun `压缩专用模型留空时回落到主模型`() = runTest {
		val (convId, personaId) = newConversation()
		sendRounds(convId, personaId, rounds = 4)
		llm.scriptedReplies = mutableListOf(FakeLlmProvider.compressionJson("摘要"))

		compressor.compressIfNeeded(convId, settings.copy(compressModel = ""))

		assertEquals("main-model", llm.completeParams.first().model)
	}

	@Test
	fun `提示词里带上了说话人标注`() = runTest {
		val (convId, personaId) = newConversation()
		val personaName = personas.getById(personaId)!!.name
		sendRounds(convId, personaId, rounds = 4)
		llm.scriptedReplies = mutableListOf(FakeLlmProvider.compressionJson("摘要"))

		compressor.compressIfNeeded(convId, settings)

		val userPrompt = llm.completeCalls.first().last().content
		assertTrue(userPrompt.contains("用户："))
		assertTrue(userPrompt.contains("$personaName："))
	}

	@Test
	fun `模型失败时不推进游标，只累加失败计数`() = runTest {
		val (convId, personaId) = newConversation()
		sendRounds(convId, personaId, rounds = 4)
		llm.failure = LlmException("网络断了", LlmException.Kind.NETWORK)

		val result = compressor.compressIfNeeded(convId, settings)

		val failed = result as CompressionResult.Failed
		assertTrue(failed.retryable)

		val conv = conversations.getById(convId)!!
		assertEquals(0L, conv.compressedUntilMessageId)
		assertEquals(1, conv.compressFailureCount)
		// 一条摘要都不该落，也不该有消息被标记压缩
		assertTrue(memory.activeSummaries(convId, level = 1).isEmpty())
		assertEquals(9, chat.uncompressedCount(convId))
	}

	@Test
	fun `失败后处于退避窗口内不再调模型`() = runTest {
		val (convId, personaId) = newConversation()
		sendRounds(convId, personaId, rounds = 4)
		llm.failure = LlmException("网络断了", LlmException.Kind.NETWORK)
		compressor.compressIfNeeded(convId, settings)
		val callsAfterFirst = llm.completeCalls.size

		// 退避基数 30 秒，只过 5 秒
		now += 5_000
		val result = compressor.compressIfNeeded(convId, settings)

		assertTrue(result is CompressionResult.NotNeeded)
		assertEquals(callsAfterFirst, llm.completeCalls.size)
	}

	@Test
	fun `退避窗口过去之后会重新尝试`() = runTest {
		val (convId, personaId) = newConversation()
		sendRounds(convId, personaId, rounds = 4)
		llm.failure = LlmException("网络断了", LlmException.Kind.NETWORK)
		compressor.compressIfNeeded(convId, settings)

		now += 60_000
		llm.failure = null
		llm.scriptedReplies = mutableListOf(FakeLlmProvider.compressionJson("恢复后压成功"))
		val result = compressor.compressIfNeeded(convId, settings)

		assertTrue(result is CompressionResult.Compressed)
		// 成功后失败计数要清零
		assertEquals(0, conversations.getById(convId)!!.compressFailureCount)
	}

	@Test
	fun `force 能跳过阈值和退避，但跳不过材料不足`() = runTest {
		val (convId, personaId) = newConversation()
		sendRounds(convId, personaId, rounds = 4)
		// 两个阈值都抬到达不到，自动路径不该动
		val strict = settings.copy(compressTriggerCount = 10_000, compressTriggerTokens = 1_000_000)

		llm.failure = LlmException("先失败一次制造退避窗口", LlmException.Kind.NETWORK)
		assertTrue(compressor.compressIfNeeded(convId, strict, force = true) is CompressionResult.Failed)

		llm.failure = null
		llm.scriptedReplies = mutableListOf(FakeLlmProvider.compressionJson("手动整理"))

		// 阈值没到，且还在退避窗口内 —— 自动路径按兵不动
		assertTrue(compressor.compressIfNeeded(convId, strict) is CompressionResult.NotNeeded)

		// force 把这两道都跳过
		assertTrue(compressor.compressIfNeeded(convId, strict, force = true) is CompressionResult.Compressed)
	}

	@Test
	fun `自动压缩关掉后只有 force 生效`() = runTest {
		val (convId, personaId) = newConversation()
		sendRounds(convId, personaId, rounds = 4)
		llm.scriptedReplies = mutableListOf(FakeLlmProvider.compressionJson("摘要"))
		val off = settings.copy(autoCompressEnabled = false)

		assertTrue(compressor.compressIfNeeded(convId, off) is CompressionResult.NotNeeded)
		assertEquals(0, llm.completeCalls.size)

		assertTrue(compressor.compressIfNeeded(convId, off, force = true) is CompressionResult.Compressed)
	}

	@Test
	fun `条目作用域按分类分流`() = runTest {
		val (convId, personaId) = newConversation()
		sendRounds(convId, personaId, rounds = 4)
		llm.scriptedReplies = mutableListOf(
			FakeLlmProvider.wikiIngestJson(
				summary = "摘要",
				entries = listOf(
					Triple("FACT", "职业", "安卓开发"),
					Triple("RELATION", "称呼", "叫他 KIQ"),
					Triple("EVENT", "本次", "改了压缩逻辑"),
					Triple("IMPRESSION", "印象", "很较真"),
				),
			),
		)

		compressor.compressIfNeeded(convId, settings)

		val scopes = memory.observeAllEntries().first().associate { it.title to it.scopeKey }
		assertEquals("c:-|p:-", scopes["职业"])
		assertEquals("c:-|p:-", scopes["称呼"])
		assertEquals("c:$convId|p:-", scopes["本次"])
		assertEquals("c:-|p:$personaId", scopes["印象"])
	}

	@Test
	fun `群聊里的印象条目退化成会话级`() = runTest {
		personas.ensureSeeded()
		val ids = personas.observeAll().first().map { it.id }
		val convId = conversations.createGroup(listOf(ids[0], ids[1]))
		sendRounds(convId, ids[0], rounds = 4)
		llm.scriptedReplies = mutableListOf(
			FakeLlmProvider.wikiIngestJson("摘要", listOf(Triple("IMPRESSION", "印象", "很较真"))),
		)

		compressor.compressIfNeeded(convId, settings)

		assertEquals("c:$convId|p:-", memory.observeAllEntries().first().single().scopeKey)
	}

	@Test
	fun `模型没给 JSON 时摘要照样落库并标记待重压`() = runTest {
		val (convId, personaId) = newConversation()
		sendRounds(convId, personaId, rounds = 4)
		llm.scriptedReplies = mutableListOf("用户今天连问了好几个问题，看起来在赶进度。")

		val result = compressor.compressIfNeeded(convId, settings) as CompressionResult.Compressed

		assertFalse(result.strict)
		assertEquals(0, result.cardsWritten)
		val summary = memory.activeSummaries(convId, level = 1).single()
		assertTrue(summary.needsSemanticRedo)
		assertEquals(1, memory.summariesNeedingRedo(convId).size)
		// 兜底摘要也要推进游标，否则下次又拿同一段去压
		assertTrue(conversations.getById(convId)!!.compressedUntilMessageId > 0)
	}

	@Test
	fun `模型回空串算失败，不落摘要`() = runTest {
		val (convId, personaId) = newConversation()
		sendRounds(convId, personaId, rounds = 4)
		llm.scriptedReplies = mutableListOf("   ")

		val result = compressor.compressIfNeeded(convId, settings)

		assertTrue(result is CompressionResult.Failed)
		assertTrue(memory.activeSummaries(convId, level = 1).isEmpty())
		assertEquals(0L, conversations.getById(convId)!!.compressedUntilMessageId)
	}

	@Test
	fun `段摘要攒够阈值会合并成长期记忆并让旧摘要退场`() = runTest {
		val (convId, personaId) = newConversation()
		// 阈值是 3：前两次压缩不该触发合并，第三次触发
		llm.responder = { messages ->
			val isMerge = messages.first().content.contains("二次收敛")
			if (isMerge) {
				FakeLlmProvider.compressionJson("这是合并后的长期记忆")
			} else {
				FakeLlmProvider.compressionJson("第 N 段摘要")
			}
		}

		repeat(3) {
			sendRounds(convId, personaId, rounds = 4)
			compressor.compressIfNeeded(convId, settings)
		}

		assertEquals(0, memory.countActiveSummaries(convId, level = 1))
		val longTerm = memory.activeSummaries(convId, level = 2).single()
		assertEquals("这是合并后的长期记忆", longTerm.content)
		assertEquals(3, memory.observeSummaries(convId).first().count { it.level == 1 && it.superseded })
	}

	@Test
	fun `合并失败不影响本次压缩已经落库的摘要`() = runTest {
		val (convId, personaId) = newConversation()
		llm.responder = { messages ->
			if (messages.first().content.contains("二次收敛")) {
				throw LlmException("合并时断网", LlmException.Kind.NETWORK)
			}
			FakeLlmProvider.compressionJson("段摘要")
		}

		repeat(3) {
			sendRounds(convId, personaId, rounds = 4)
			compressor.compressIfNeeded(convId, settings)
		}

		// 合并没成，三条 L1 都还在，也没有 L2
		assertEquals(3, memory.countActiveSummaries(convId, level = 1))
		assertEquals(0, memory.countActiveSummaries(convId, level = 2))
	}

	@Test
	fun `token 阈值也能单独触发压缩`() = runTest {
		val (convId, personaId) = newConversation()
		sendRounds(convId, personaId, rounds = 4)
		llm.scriptedReplies = mutableListOf(FakeLlmProvider.compressionJson("按 token 触发"))

		// 条数阈值抬高到不可能达到，只留 token 条件
		val result = compressor.compressIfNeeded(
			convId,
			settings.copy(compressTriggerCount = 10_000, compressTriggerTokens = 50),
		)

		assertTrue(result is CompressionResult.Compressed)
	}
}
