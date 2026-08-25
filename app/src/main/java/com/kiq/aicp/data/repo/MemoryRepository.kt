// app/src/main/java/com/kiq/aicp/data/repo/MemoryRepository.kt
// 记忆层的原子操作：摘要落库、条目 upsert、上下文取材、冷条目淘汰、操作日志。
// 压缩策略本身不在这里（在 domain/memory/MemoryCompressor.kt），这里只保证"写得对、读得准"。
//
// v6 起记忆主体是 memory_entries（wiki 条目），卡片那套方法保留但不再被新代码调用 ——
// 旧表冻结成历史归档，等新结构跑稳了再单独一次迁移清掉。

package com.kiq.aicp.data.repo

import com.kiq.aicp.data.db.dao.MemoryDao
import com.kiq.aicp.data.db.entity.MemoryCardEntity
import com.kiq.aicp.data.db.entity.MemoryEntryEntity
import com.kiq.aicp.data.db.entity.MemoryLogEntity
import com.kiq.aicp.data.db.entity.MemorySummaryEntity
import com.kiq.aicp.domain.memory.EntryMatcher
import com.kiq.aicp.domain.memory.IndexLine
import com.kiq.aicp.domain.memory.MemoryScope
import com.kiq.aicp.domain.memory.ParsedEntry
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

		/** 记忆页一次显示多少条日志 */
		const val LOG_PAGE = 50

		private const val MAX_LOG_SUMMARY = 200

		/** 日志保留天数。它是排查线索不是永久档案，留太久白占空间 */
		private const val LOG_KEEP_DAYS = 90
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

	// ---------------- 条目（wiki 第二层，v6 起的记忆主体） ----------------

	fun observeAllEntries(): Flow<List<MemoryEntryEntity>> = dao.observeAllEntries()

	fun observeEntriesVisibleTo(convId: Long): Flow<List<MemoryEntryEntity>> =
		dao.observeEntriesVisibleTo(convId)

	/** 底座条目：钉住和高重要度的，每轮必带 */
	suspend fun contextEntries(convId: Long, personaId: Long?, limit: Int): List<MemoryEntryEntity> =
		dao.getEntriesForContext(MemoryScope.contextKeys(convId, personaId), limit)

	/** index 视图，注入 system prompt 让模型知道还有哪些记忆可以调 */
	suspend fun entryIndex(convId: Long, personaId: Long?, limit: Int): List<IndexLine> =
		dao.getEntryIndex(MemoryScope.contextKeys(convId, personaId), limit)
			.map { IndexLine(it.category, it.title, it.aliases, it.oneLiner) }

	/**
	 * 关键词检索：拿文本去撞条目的标题和别名。
	 * 这是"不用 embedding 的检索"那条路，撞上就把整条正文带出来。
	 */
	suspend fun relatedEntries(
		convId: Long,
		personaId: Long?,
		text: String,
		limit: Int,
	): List<MemoryEntryEntity> {
		if (text.isBlank() || limit <= 0) return emptyList()
		val all = dao.getEntriesInScopes(MemoryScope.contextKeys(convId, personaId))
		return EntryMatcher.match(all, text, limit).map { it.entry }
	}

	/**
	 * ingest 落库。
	 *
	 * 存在性判断放在这里而不是让模型给"update/create"标记：模型判断这个的准确率不高，
	 * 而 (scopeKey, category, title) 是唯一键，查一次就有答案。
	 *
	 * 钉住的条目跳过不动 —— 用户手动编辑过的记忆不该被自动 ingest 悄悄改写，
	 * 这条规则从卡片时代就是这样，条目化之后更重要（正文更长，改写损失更大）。
	 *
	 * @return 实际写入的条目标题，给日志用
	 */
	suspend fun upsertEntries(
		conversationId: Long?,
		personaId: Long?,
		entries: List<ParsedEntry>,
	): List<String> {
		if (entries.isEmpty()) return emptyList()
		val scopeKey = MemoryScope.key(conversationId, personaId)
		val now = clock()
		val touched = mutableListOf<String>()

		entries.forEach { parsed ->
			val existing = dao.findEntry(scopeKey, parsed.category, parsed.title)
			if (existing != null && existing.pinned) return@forEach

			// 别名并集：模型每次可能只给一部分，累积起来才能覆盖它的各种叫法
			val mergedAliases = MemoryEntryEntity.joinAliases(
				existing?.aliases.orEmpty()
					.split(MemoryEntryEntity.ALIAS_SEPARATOR)
					.plus(parsed.aliases),
			)

			dao.insertEntry(
				MemoryEntryEntity(
					id = existing?.id ?: 0,
					conversationId = conversationId,
					personaId = personaId,
					scopeKey = scopeKey,
					category = parsed.category,
					title = parsed.title,
					aliases = mergedAliases,
					oneLiner = parsed.oneLiner,
					body = parsed.body,
					importance = parsed.importance,
					hitCount = existing?.hitCount ?: 0,
					lastHitAt = existing?.lastHitAt ?: 0,
					pinned = false,
					// 又被一轮对话确认过一次。反复出现的条目更可信，lint 时用得上
					sourceCount = (existing?.sourceCount ?: 0) + 1,
					conflictNote = parsed.conflictNote ?: existing?.conflictNote,
					createdAt = existing?.createdAt ?: now,
					updatedAt = now,
				),
			)
			touched += parsed.title
		}
		return touched
	}

	suspend fun markEntriesUsed(ids: List<Long>) {
		if (ids.isNotEmpty()) dao.touchEntries(ids, clock())
	}

	suspend fun setEntryPinned(id: Long, pinned: Boolean) = dao.setEntryPinned(id, pinned, clock())

	/** 用户手动改条目。改完就当钉住处理，不然下一轮 ingest 又给写回去了 */
	suspend fun editEntry(entry: MemoryEntryEntity, newBody: String, newImportance: Int) {
		val body = newBody.trim().take(MemoryEntryEntity.MAX_BODY)
		if (body.isEmpty()) return
		dao.updateEntry(
			entry.copy(
				body = body,
				importance = newImportance.coerceIn(1, 5),
				// 用户改过就清掉矛盾标记：他既然动手了，就是他认可了当前这版
				conflictNote = null,
				updatedAt = clock(),
			),
		)
	}

	suspend fun deleteEntry(id: Long) = dao.deleteEntry(id)

	/** 体检要的全量快照 */
	suspend fun allEntries(): List<MemoryEntryEntity> = dao.getAllEntries()

	/**
	 * 把 absorb 里的条目并进 keep。
	 *
	 * 三个不能省的动作：
	 * 1. 被并掉的标题和别名要全部收进 keep 的别名，不然以后用旧叫法检索就断线了
	 * 2. sourceCount 和 hitCount 相加——合并后的条目被确认过的次数确实是两边之和
	 * 3. pinned 的条目一律不许被并掉，用户钉过就是不让动
	 *
	 * 返回真正被删掉的条目标题，写日志用。
	 */
	suspend fun mergeEntries(keepId: Long, absorbIds: List<Long>, mergedBody: String): List<String> {
		val body = mergedBody.trim().take(MemoryEntryEntity.MAX_BODY)
		if (body.isEmpty()) return emptyList()

		val keep = dao.getEntriesByIds(listOf(keepId)).firstOrNull() ?: return emptyList()
		val absorbed = dao.getEntriesByIds(absorbIds.filter { it != keepId })
			.filterNot { it.pinned }
			.filter { it.scopeKey == keep.scopeKey }
		if (absorbed.isEmpty()) return emptyList()

		val aliasPool = keep.aliases.split(MemoryEntryEntity.ALIAS_SEPARATOR) +
			absorbed.flatMap { listOf(it.title) + it.aliases.split(MemoryEntryEntity.ALIAS_SEPARATOR) }

		dao.updateEntry(
			keep.copy(
				aliases = MemoryEntryEntity.joinAliases(aliasPool.filter { it != keep.title }),
				body = body,
				importance = maxOf(keep.importance, absorbed.maxOf { it.importance }),
				hitCount = keep.hitCount + absorbed.sumOf { it.hitCount },
				sourceCount = keep.sourceCount + absorbed.sumOf { it.sourceCount },
				// 合并本身就是把两种说法归一，原来的矛盾标记跟着作废
				conflictNote = null,
				updatedAt = clock(),
			),
		)
		absorbed.forEach { dao.deleteEntry(it.id) }
		return absorbed.map { it.title }
	}

	/** 体检提出的疑点问过用户之后清标记，条目内容不动 */
	suspend fun clearConflictNote(id: Long) {
		val entry = dao.getEntriesByIds(listOf(id)).firstOrNull() ?: return
		if (entry.conflictNote == null) return
		dao.updateEntry(entry.copy(conflictNote = null, updatedAt = clock()))
	}

	suspend fun countEntries(): Int = dao.countEntries()

	// ---------------- 操作日志（wiki 的 log.md） ----------------

	fun observeRecentLogs(limit: Int = LOG_PAGE): Flow<List<MemoryLogEntity>> =
		dao.observeRecentLogs(limit)

	suspend fun appendLog(
		conversationId: Long?,
		kind: String,
		summary: String,
		touchedTitles: List<String> = emptyList(),
	) {
		dao.insertLog(
			MemoryLogEntity(
				conversationId = conversationId,
				kind = kind,
				summary = summary.trim().take(MAX_LOG_SUMMARY),
				touchedTitles = touchedTitles.joinToString(MemoryEntryEntity.ALIAS_SEPARATOR),
				createdAt = clock(),
			),
		)
	}

	/** 日志只保留最近这些天的。它是排查线索，不是永久档案 */
	suspend fun pruneLogs(keepDays: Int = LOG_KEEP_DAYS): Int =
		dao.pruneLogsBefore(clock() - keepDays * 24L * 60 * 60 * 1000)

	suspend fun markCardsUsed(ids: List<Long>) {		if (ids.isNotEmpty()) dao.touchCards(ids, clock())
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
