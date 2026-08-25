// app/src/main/java/com/kiq/aicp/data/db/dao/MemoryDao.kt
// 摘要（L1/L2）与记忆卡片（L3）的读写，压缩引擎和记忆管理页共用这一个 DAO。
//
// 卡片的"存在就更新"没有用 @Upsert：@Upsert 判冲突看主键，而我们的去重键是
// UNIQUE(scopeKey, type, keyword)，主键是自增 id，对不上。
// 所以走 findCard 查旧行 → 合并 hitCount 等运行时字段 → insertCard(REPLACE)，合并逻辑在 Repository 里。

package com.kiq.aicp.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.kiq.aicp.data.db.entity.MemoryCardEntity
import com.kiq.aicp.data.db.entity.MemoryEntryEntity
import com.kiq.aicp.data.db.entity.MemoryLogEntity
import com.kiq.aicp.data.db.entity.MemorySummaryEntity
import com.kiq.aicp.domain.model.MemoryCardType
import kotlinx.coroutines.flow.Flow

@Dao
interface MemoryDao {

	// ---------------- 摘要 ----------------

	/** 进上下文的摘要：没被上层合并掉的那些，按覆盖区间正序 */
	@Query(
		"SELECT * FROM memory_summaries WHERE conversationId = :convId AND level = :level " +
			"AND superseded = 0 ORDER BY fromMessageId ASC",
	)
	suspend fun getActiveSummaries(convId: Long, level: Int): List<MemorySummaryEntity>

	@Query(
		"SELECT * FROM memory_summaries WHERE conversationId = :convId " +
			"ORDER BY level DESC, fromMessageId ASC",
	)
	fun observeSummaries(convId: Long): Flow<List<MemorySummaryEntity>>

	@Query(
		"SELECT COUNT(*) FROM memory_summaries WHERE conversationId = :convId " +
			"AND level = :level AND superseded = 0",
	)
	suspend fun countActiveSummaries(convId: Long, level: Int): Int

	/** 没网时落的占位摘要，等有网了补一次真压缩 */
	@Query(
		"SELECT * FROM memory_summaries WHERE conversationId = :convId " +
			"AND needsSemanticRedo = 1 AND superseded = 0 ORDER BY id ASC",
	)
	suspend fun getSummariesNeedingRedo(convId: Long): List<MemorySummaryEntity>

	@Insert
	suspend fun insertSummary(summary: MemorySummaryEntity): Long

	@Update
	suspend fun updateSummary(summary: MemorySummaryEntity)

	@Query("UPDATE memory_summaries SET superseded = 1 WHERE id IN (:ids)")
	suspend fun markSummariesSuperseded(ids: List<Long>)

	@Query("DELETE FROM memory_summaries WHERE id = :id")
	suspend fun deleteSummary(id: Long)

	@Query("DELETE FROM memory_summaries WHERE conversationId = :convId")
	suspend fun deleteSummariesByConversation(convId: Long)

	// ---------------- 卡片 ----------------

	/** 拼上下文用：按 钉住 > 重要度 > 最近更新 取前 limit 条 */
	@Query(
		"SELECT * FROM memory_cards WHERE scopeKey IN (:scopeKeys) " +
			"ORDER BY pinned DESC, importance DESC, updatedAt DESC LIMIT :limit",
	)
	suspend fun getCardsForContext(scopeKeys: List<String>, limit: Int): List<MemoryCardEntity>

	@Query(
		"SELECT * FROM memory_cards WHERE scopeKey = :scopeKey AND type = :type " +
			"AND keyword = :keyword LIMIT 1",
	)
	suspend fun findCard(scopeKey: String, type: MemoryCardType, keyword: String): MemoryCardEntity?

	@Query("SELECT * FROM memory_cards ORDER BY pinned DESC, updatedAt DESC")
	fun observeAllCards(): Flow<List<MemoryCardEntity>>

	@Query(
		"SELECT * FROM memory_cards WHERE conversationId = :convId OR conversationId IS NULL " +
			"ORDER BY pinned DESC, importance DESC, updatedAt DESC",
	)
	fun observeCardsVisibleTo(convId: Long): Flow<List<MemoryCardEntity>>

	@Insert(onConflict = OnConflictStrategy.REPLACE)
	suspend fun insertCard(card: MemoryCardEntity): Long

	@Update
	suspend fun updateCard(card: MemoryCardEntity)

	@Query("DELETE FROM memory_cards WHERE id = :id")
	suspend fun deleteCard(id: Long)

	@Query("UPDATE memory_cards SET hitCount = hitCount + 1, lastHitAt = :at WHERE id IN (:ids)")
	suspend fun touchCards(ids: List<Long>, at: Long)

	@Query("UPDATE memory_cards SET pinned = :pinned, updatedAt = :at WHERE id = :id")
	suspend fun setCardPinned(id: Long, pinned: Boolean, at: Long)

	@Query("SELECT COUNT(*) FROM memory_cards")
	suspend fun countCards(): Int

	/** 冷卡淘汰：钉住的不动，只清低重要度且长期没被用到的 */
	@Query(
		"DELETE FROM memory_cards WHERE pinned = 0 AND importance <= :maxImportance " +
			"AND lastHitAt < :before",
	)
	suspend fun pruneColdCards(maxImportance: Int, before: Long): Int

	// ---------------- 条目（wiki 第二层，v6 起取代卡片） ----------------

	/**
	 * index 视图：只取分类、标题、别名、一行摘要，不带正文。
	 * 注入 system prompt 的索引走这个查询 —— 全表正文一起塞进上下文的话，
	 * 条目一多就把预算吃光了，而索引的作用只是让模型知道"有哪些记忆存在"。
	 */
	@Query(
		"SELECT category, title, aliases, oneLiner FROM memory_entries " +
			"WHERE scopeKey IN (:scopeKeys) ORDER BY category ASC, importance DESC LIMIT :limit",
	)
	suspend fun getEntryIndex(scopeKeys: List<String>, limit: Int): List<MemoryEntryIndexRow>

	/** 底座条目：钉住和高重要度的，每轮必带 */
	@Query(
		"SELECT * FROM memory_entries WHERE scopeKey IN (:scopeKeys) " +
			"ORDER BY pinned DESC, importance DESC, updatedAt DESC LIMIT :limit",
	)
	suspend fun getEntriesForContext(scopeKeys: List<String>, limit: Int): List<MemoryEntryEntity>

	/** 按 id 精确取，关键词命中后补齐正文用 */
	@Query("SELECT * FROM memory_entries WHERE id IN (:ids)")
	suspend fun getEntriesByIds(ids: List<Long>): List<MemoryEntryEntity>

	/** 作用域内全部条目，检索匹配和 lint 都要遍历 */
	@Query("SELECT * FROM memory_entries WHERE scopeKey IN (:scopeKeys)")
	suspend fun getEntriesInScopes(scopeKeys: List<String>): List<MemoryEntryEntity>

	@Query(
		"SELECT * FROM memory_entries WHERE scopeKey = :scopeKey AND category = :category " +
			"AND title = :title LIMIT 1",
	)
	suspend fun findEntry(
		scopeKey: String,
		category: MemoryCardType,
		title: String,
	): MemoryEntryEntity?

	@Query("SELECT * FROM memory_entries ORDER BY pinned DESC, updatedAt DESC")
	fun observeAllEntries(): Flow<List<MemoryEntryEntity>>

	/** 体检要的一次性快照。Flow 那个用来喂 UI，这个用来喂 lint */
	@Query("SELECT * FROM memory_entries")
	suspend fun getAllEntries(): List<MemoryEntryEntity>

	@Query(
		"SELECT * FROM memory_entries WHERE conversationId = :convId OR conversationId IS NULL " +
			"ORDER BY pinned DESC, importance DESC, updatedAt DESC",
	)
	fun observeEntriesVisibleTo(convId: Long): Flow<List<MemoryEntryEntity>>

	@Insert(onConflict = OnConflictStrategy.REPLACE)
	suspend fun insertEntry(entry: MemoryEntryEntity): Long

	@Update
	suspend fun updateEntry(entry: MemoryEntryEntity)

	@Query("DELETE FROM memory_entries WHERE id = :id")
	suspend fun deleteEntry(id: Long)

	@Query("UPDATE memory_entries SET hitCount = hitCount + 1, lastHitAt = :at WHERE id IN (:ids)")
	suspend fun touchEntries(ids: List<Long>, at: Long)

	@Query("UPDATE memory_entries SET pinned = :pinned, updatedAt = :at WHERE id = :id")
	suspend fun setEntryPinned(id: Long, pinned: Boolean, at: Long)

	@Query("SELECT COUNT(*) FROM memory_entries")
	suspend fun countEntries(): Int

	@Query(
		"DELETE FROM memory_entries WHERE pinned = 0 AND importance <= :maxImportance " +
			"AND lastHitAt < :before",
	)
	suspend fun pruneColdEntries(maxImportance: Int, before: Long): Int

	// ---------------- 操作日志（wiki 的 log.md） ----------------

	@Insert
	suspend fun insertLog(log: MemoryLogEntity): Long

	@Query("SELECT * FROM memory_logs ORDER BY createdAt DESC LIMIT :limit")
	fun observeRecentLogs(limit: Int): Flow<List<MemoryLogEntity>>

	@Query("SELECT * FROM memory_logs ORDER BY createdAt DESC LIMIT :limit")
	suspend fun recentLogs(limit: Int): List<MemoryLogEntity>

	@Query("DELETE FROM memory_logs WHERE createdAt < :before")
	suspend fun pruneLogsBefore(before: Long): Int

	@Query("SELECT COUNT(*) FROM memory_logs")
	suspend fun countLogs(): Int
}

/** index 查询的投影行。只有索引需要的四列，不含正文 */
data class MemoryEntryIndexRow(
	val category: MemoryCardType,
	val title: String,
	val aliases: String,
	val oneLiner: String,
)
