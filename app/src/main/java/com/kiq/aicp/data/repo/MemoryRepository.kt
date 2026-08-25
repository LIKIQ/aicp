// app/src/main/java/com/kiq/aicp/data/repo/MemoryRepository.kt
// 记忆层的原子操作：摘要落库、卡片 upsert、上下文取材、冷卡淘汰。
// 压缩策略本身不在这里（在 domain/memory/MemoryCompressor.kt），这里只保证"写得对、读得准"。

package com.kiq.aicp.data.repo

import com.kiq.aicp.data.db.dao.MemoryDao
import com.kiq.aicp.data.db.entity.MemoryCardEntity
import com.kiq.aicp.data.db.entity.MemorySummaryEntity
import com.kiq.aicp.domain.memory.MemoryScope
import com.kiq.aicp.domain.memory.TokenEstimator
import com.kiq.aicp.domain.model.MemoryCardType
import kotlinx.coroutines.flow.Flow

class MemoryRepository(
	private val dao: MemoryDao,
	private val clock: () -> Long = System::currentTimeMillis,
) {

	companion object {
		private const val MAX_KEYWORD_LEN = 24
		private const val MAX_CARD_CONTENT_LEN = 300
	}

	// ---------------- 摘要 ----------------

	fun observeSummaries(convId: Long): Flow<List<MemorySummaryEntity>> = dao.observeSummaries(convId)

	suspend fun activeSummaries(convId: Long, level: Int): List<MemorySummaryEntity> =
		dao.getActiveSummaries(convId, level)

	suspend fun countActiveSummaries(convId: Long, level: Int): Int =
		dao.countActiveSummaries(convId, level)

	suspend fun addSummary(
		convId: Long,
		level: Int,
		content: String,
		fromMessageId: Long,
		toMessageId: Long,
		messageCount: Int,
		needsSemanticRedo: Boolean = false,
	): Long {
		val body = content.trim()
		return dao.insertSummary(
			MemorySummaryEntity(
				conversationId = convId,
				level = level,
				content = body,
				fromMessageId = fromMessageId,
				toMessageId = toMessageId,
				messageCount = messageCount,
				tokenEstimate = TokenEstimator.estimateText(body),
				needsSemanticRedo = needsSemanticRedo,
				createdAt = clock(),
			),
		)
	}

	suspend fun supersede(ids: List<Long>) {
		if (ids.isNotEmpty()) dao.markSummariesSuperseded(ids)
	}

	suspend fun summariesNeedingRedo(convId: Long): List<MemorySummaryEntity> =
		dao.getSummariesNeedingRedo(convId)

	suspend fun replaceSummaryBody(summary: MemorySummaryEntity, newContent: String) {
		val body = newContent.trim()
		dao.updateSummary(
			summary.copy(
				content = body,
				tokenEstimate = TokenEstimator.estimateText(body),
				needsSemanticRedo = false,
			),
		)
	}

	// ---------------- 卡片 ----------------

	fun observeAllCards(): Flow<List<MemoryCardEntity>> = dao.observeAllCards()

	fun observeCardsVisibleTo(convId: Long): Flow<List<MemoryCardEntity>> =
		dao.observeCardsVisibleTo(convId)

	suspend fun contextCards(convId: Long, personaId: Long?, limit: Int): List<MemoryCardEntity> =
		dao.getCardsForContext(MemoryScope.contextKeys(convId, personaId), limit)

	/**
	 * 同一 (作用域, 类型, 关键词) 只保留一条，再抽到就更新内容。
	 * 已被用户钉住的卡片直接跳过 —— 手动编辑过的记忆不该被自动压缩悄悄改写。
	 * 返回卡片 id；跳过时返回已有卡片的 id。
	 */
	suspend fun upsertCard(
		conversationId: Long?,
		personaId: Long?,
		type: MemoryCardType,
		keyword: String,
		content: String,
		importance: Int,
	): Long {
		val scopeKey = MemoryScope.key(conversationId, personaId)
		val key = keyword.trim().take(MAX_KEYWORD_LEN).ifEmpty { type.name.lowercase() }
		val body = content.trim().take(MAX_CARD_CONTENT_LEN)
		if (body.isEmpty()) return 0

		val existing = dao.findCard(scopeKey, type, key)
		if (existing != null && existing.pinned) return existing.id

		val now = clock()
		return dao.insertCard(
			MemoryCardEntity(
				id = existing?.id ?: 0,
				conversationId = conversationId,
				personaId = personaId,
				scopeKey = scopeKey,
				type = type,
				keyword = key,
				content = body,
				importance = importance.coerceIn(1, 5),
				hitCount = existing?.hitCount ?: 0,
				lastHitAt = existing?.lastHitAt ?: 0,
				pinned = false,
				createdAt = existing?.createdAt ?: now,
				updatedAt = now,
			),
		)
	}

	suspend fun markCardsUsed(ids: List<Long>) {
		if (ids.isNotEmpty()) dao.touchCards(ids, clock())
	}

	suspend fun setPinned(id: Long, pinned: Boolean) = dao.setCardPinned(id, pinned, clock())

	suspend fun editCard(card: MemoryCardEntity, newContent: String, newImportance: Int) {
		dao.updateCard(
			card.copy(
				content = newContent.trim().take(MAX_CARD_CONTENT_LEN),
				importance = newImportance.coerceIn(1, 5),
				updatedAt = clock(),
			),
		)
	}

	suspend fun deleteCard(id: Long) = dao.deleteCard(id)

	suspend fun countCards(): Int = dao.countCards()

	/** 冷卡淘汰：importance <= maxImportance 且超过 idleDays 没被用到的非钉住卡片 */
	suspend fun pruneCold(maxImportance: Int = 2, idleDays: Int = 60): Int {
		val before = clock() - idleDays.toLong() * 24 * 60 * 60 * 1000
		return dao.pruneColdCards(maxImportance, before)
	}
}
