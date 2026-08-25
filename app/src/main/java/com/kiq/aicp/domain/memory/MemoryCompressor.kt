// app/src/main/java/com/kiq/aicp/domain/memory/MemoryCompressor.kt
// 语义压缩的编排：判定是否该压 → 圈定区间 → 调模型 → 落摘要和卡片 → 推进游标 → 必要时再收敛一层。
//
// 三条硬规则：
// 1. 原文永不删除。压缩只是把 messages.compressed 置 1，让它不再进上下文，用户翻历史照样看得到。
// 2. 失败绝不推进游标。宁可下次重压，也不能出现"原文被标记已压缩但摘要没落库"的记忆黑洞。
// 3. 失败按指数退避。没网的时候每发一条消息就重试一次，只会白烧电和额度。

package com.kiq.aicp.domain.memory

import com.kiq.aicp.data.db.entity.ConversationEntity
import com.kiq.aicp.data.remote.LlmException
import com.kiq.aicp.data.remote.LlmMessage
import com.kiq.aicp.data.remote.LlmParams
import com.kiq.aicp.data.remote.LlmProvider
import com.kiq.aicp.data.repo.ChatRepository
import com.kiq.aicp.data.repo.ConversationRepository
import com.kiq.aicp.data.repo.MemoryRepository
import com.kiq.aicp.data.repo.PersonaRepository
import com.kiq.aicp.domain.model.AicpSettings
import com.kiq.aicp.domain.model.ChatRole
import com.kiq.aicp.domain.model.MemoryCardType
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface CompressionResult {
	data class NotNeeded(val reason: String) : CompressionResult

	data class Compressed(
		val summaryId: Long,
		val compressedMessages: Int,
		val cardsWritten: Int,
		val mergedSummaries: Int,
		/** false 表示模型没按 JSON 输出，摘要是兜底文本，将来可以重压 */
		val strict: Boolean,
	) : CompressionResult

	data class Failed(val reason: String, val retryable: Boolean) : CompressionResult
}

class MemoryCompressor(
	private val chatRepository: ChatRepository,
	private val memoryRepository: MemoryRepository,
	private val conversationRepository: ConversationRepository,
	private val personaRepository: PersonaRepository,
	private val llmProvider: LlmProvider,
	private val clock: () -> Long = System::currentTimeMillis,
) {

	/** 压缩是低频重活，全局串行就够，不必按会话分锁 */
	private val gate = Mutex()

	/**
	 * 检查并执行一次压缩。
	 * @param force 用户在界面上手动点"整理记忆"时传 true，跳过阈值和退避，但仍然要有足够的可压材料
	 */
	suspend fun compressIfNeeded(
		conversationId: Long,
		settings: AicpSettings,
		force: Boolean = false,
	): CompressionResult = gate.withLock {
		if (!settings.autoCompressEnabled && !force) {
			return@withLock CompressionResult.NotNeeded("自动压缩已关闭")
		}
		val conv = conversationRepository.getById(conversationId)
			?: return@withLock CompressionResult.NotNeeded("会话不存在")

		if (!force && inBackoff(conv)) {
			return@withLock CompressionResult.NotNeeded("上次压缩失败，还在退避窗口内")
		}

		val pendingTokens = chatRepository.uncompressedTokens(conversationId)
		val pendingCount = chatRepository.uncompressedCount(conversationId)
		val triggered = pendingTokens >= settings.compressTriggerTokens ||
			pendingCount >= settings.compressTriggerCount
		if (!force && !triggered) {
			return@withLock CompressionResult.NotNeeded(
				"未达阈值（$pendingTokens/${settings.compressTriggerTokens} token，" +
					"$pendingCount/${settings.compressTriggerCount} 条）",
			)
		}

		// 圈定区间：游标之后的全部未压缩消息，去掉要留作原文的最近若干条
		val uncompressed = chatRepository.rangeForCompress(
			convId = conversationId,
			afterId = conv.compressedUntilMessageId,
			untilId = Long.MAX_VALUE,
		)
		val target = uncompressed.dropLast(settings.keepRecentMessages)
		if (target.size < MIN_BATCH) {
			return@withLock CompressionResult.NotNeeded("可压缩的只有 ${target.size} 条，不值得调一次模型")
		}
		val untilId = target.last().id

		// 说话人名字：transcript 里要标出谁说的，否则多角色对话压出来分不清主体
		val participantIds = conversationRepository.participants(conversationId).map { it.personaId }
		val personaNames = personaRepository.getByIds(participantIds).associate { it.id to it.name }
		val soloPersonaId = participantIds.singleOrNull()

		val transcript = CompressionPrompts.transcriptOf(target) { id ->
			id?.let { personaNames[it] } ?: "助手"
		}

		val raw = try {
			llmProvider.complete(
				messages = listOf(
					LlmMessage(ChatRole.SYSTEM, CompressionPrompts.summarizerSystem()),
					LlmMessage(ChatRole.USER, CompressionPrompts.summarizerUser(transcript)),
				),
				params = LlmParams(
					model = settings.effectiveCompressModel(),
					temperature = 0.3f,
					topP = 0.9f,
					maxTokens = SUMMARY_MAX_TOKENS,
				),
			)
		} catch (e: LlmException) {
			chatRepository.markCompressionFailed(conversationId)
			return@withLock CompressionResult.Failed(
				reason = e.message ?: "压缩调用失败",
				retryable = e.kind.retryable,
			)
		}

		val parsed = CompressionPrompts.parse(raw)
		if (parsed.summary.isBlank()) {
			chatRepository.markCompressionFailed(conversationId)
			return@withLock CompressionResult.Failed("模型没有产出摘要", retryable = true)
		}

		val summaryId = memoryRepository.addSummary(
			convId = conversationId,
			level = LEVEL_SEGMENT,
			content = parsed.summary,
			fromMessageId = conv.compressedUntilMessageId,
			toMessageId = untilId,
			messageCount = target.size,
			needsSemanticRedo = !parsed.strict,
		)

		var cardsWritten = 0
		parsed.cards.forEach { card ->
			val (scopeConversation, scopePersona) = scopeOf(card.type, conversationId, soloPersonaId)
			val id = memoryRepository.upsertCard(
				conversationId = scopeConversation,
				personaId = scopePersona,
				type = card.type,
				keyword = card.keyword,
				content = card.content,
				importance = card.importance,
			)
			if (id > 0) cardsWritten++
		}

		// 到这一步才推进游标：摘要和卡片都已经落库了
		chatRepository.commitCompression(conversationId, untilId)

		val merged = maybeMergeSummaries(conversationId, settings)

		CompressionResult.Compressed(
			summaryId = summaryId,
			compressedMessages = target.size,
			cardsWritten = cardsWritten,
			mergedSummaries = merged,
			strict = parsed.strict,
		)
	}

	/**
	 * 卡片作用域的归属规则（决定这条记忆以后在哪些会话里生效）：
	 * - FACT / PREFERENCE / RELATION：用户本人的稳定信息，跨会话跨性格共享。
	 *   "叫我 KIQ"这种约定，换个性格聊也应该记得。
	 * - EVENT：具体发生过的事，只在本会话里生效，免得两条不相干的会话互相串味。
	 * - IMPRESSION：某个性格对用户的印象，跨会话但只属于它自己。
	 *   群聊里没法归属到单一角色，退化成会话级。
	 */
	private fun scopeOf(
		type: MemoryCardType,
		conversationId: Long,
		soloPersonaId: Long?,
	): Pair<Long?, Long?> = when (type) {
		MemoryCardType.FACT,
		MemoryCardType.PREFERENCE,
		MemoryCardType.RELATION,
		-> null to null

		MemoryCardType.EVENT -> conversationId to null

		MemoryCardType.IMPRESSION ->
			if (soloPersonaId != null) null to soloPersonaId else conversationId to null
	}

	/**
	 * L1 攒够了就再压一层。
	 * 合并失败不算主流程失败：这一步只影响以后的上下文长度，摘要本身已经安全落库了。
	 * 返回被合并掉的 L1 条数。
	 */
	private suspend fun maybeMergeSummaries(conversationId: Long, settings: AicpSettings): Int {
		val segments = memoryRepository.activeSummaries(conversationId, LEVEL_SEGMENT)
		if (segments.size < settings.summaryMergeThreshold) return 0

		// 只合最旧的一批，最近的段摘要留着当中期记忆
		val batch = segments.take(settings.summaryMergeThreshold)

		val raw = try {
			llmProvider.complete(
				messages = listOf(
					LlmMessage(ChatRole.SYSTEM, CompressionPrompts.mergeSystem()),
					LlmMessage(ChatRole.USER, CompressionPrompts.mergeUser(batch.map { it.content })),
				),
				params = LlmParams(
					model = settings.effectiveCompressModel(),
					temperature = 0.3f,
					topP = 0.9f,
					maxTokens = MERGE_MAX_TOKENS,
				),
			)
		} catch (_: LlmException) {
			return 0
		}

		val parsed = CompressionPrompts.parse(raw)
		if (parsed.summary.isBlank()) return 0

		memoryRepository.addSummary(
			convId = conversationId,
			level = LEVEL_LONG_TERM,
			content = parsed.summary,
			fromMessageId = batch.first().fromMessageId,
			toMessageId = batch.last().toMessageId,
			messageCount = batch.sumOf { it.messageCount },
			needsSemanticRedo = !parsed.strict,
		)
		memoryRepository.supersede(batch.map { it.id })
		return batch.size
	}

	/** 指数退避：30s、1m、2m、4m…上限约 16 分钟 */
	private fun inBackoff(conv: ConversationEntity): Boolean {
		if (conv.compressFailureCount <= 0) return false
		val shift = (conv.compressFailureCount - 1).coerceIn(0, MAX_BACKOFF_SHIFT)
		val wait = BASE_BACKOFF_MS shl shift
		return clock() - conv.lastCompressAttemptAt < wait
	}

	private companion object {
		const val MIN_BATCH = 4
		const val SUMMARY_MAX_TOKENS = 1_200
		const val MERGE_MAX_TOKENS = 1_600
		const val LEVEL_SEGMENT = 1
		const val LEVEL_LONG_TERM = 2
		const val BASE_BACKOFF_MS = 30_000L
		const val MAX_BACKOFF_SHIFT = 5
	}
}
