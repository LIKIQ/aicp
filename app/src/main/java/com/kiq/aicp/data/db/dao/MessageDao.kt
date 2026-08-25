// app/src/main/java/com/kiq/aicp/data/db/dao/MessageDao.kt
// 消息原文表读写。
//
// 上下文相关的两个查询要特别小心口径：
// - getRecentForContext 用 id DESC + LIMIT 拿最近 N 条，调用方记得反转回时间正序
// - getRangeForCompress 取 (afterId, untilId] 左开右闭，跟 conversations.compressedUntilMessageId 同一口径
// 全表都按 id 排序而不是 createdAt：同一毫秒插入多条时 createdAt 会打平，id 才是稳定顺序。

package com.kiq.aicp.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.kiq.aicp.data.db.entity.MessageEntity
import com.kiq.aicp.domain.model.MessageStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {

	@Query("SELECT * FROM messages WHERE conversationId = :convId ORDER BY id ASC")
	fun observeByConversation(convId: Long): Flow<List<MessageEntity>>

	@Query("SELECT * FROM messages WHERE id = :id")
	suspend fun getById(id: Long): MessageEntity?

	/** 最近 N 条正常消息，倒序返回，调用方自己 reversed() */
	@Query(
		"SELECT * FROM messages WHERE conversationId = :convId AND compressed = 0 " +
			"AND status = :status ORDER BY id DESC LIMIT :limit",
	)
	suspend fun getRecentForContext(
		convId: Long,
		limit: Int,
		status: MessageStatus,
	): List<MessageEntity>

	/** 不压缩地拿最近几条消息。active 会话列表、主动搭话判断这些场景要的就是原样消息 */
	@Query(
		"SELECT * FROM messages WHERE conversationId = :convId ORDER BY id DESC LIMIT :limit",
	)
	suspend fun recentRaw(convId: Long, limit: Int): List<MessageEntity>

	/** 会话最新一条消息的 id。null 表示一条都没有（新会话只有开场白时也可能拿到开场白） */
	@Query("SELECT MAX(id) FROM messages WHERE conversationId = :convId")
	suspend fun lastMessageId(convId: Long): Long?

	/** 压缩取材区间：(afterId, untilId]，只要成功落地的消息 */
	@Query(
		"SELECT * FROM messages WHERE conversationId = :convId AND id > :afterId AND id <= :untilId " +
			"AND status = :status ORDER BY id ASC",
	)
	suspend fun getRangeForCompress(
		convId: Long,
		afterId: Long,
		untilId: Long,
		status: MessageStatus,
	): List<MessageEntity>

	/** 未压缩区间的条数与 token 合计，压缩触发判定用 */
	@Query(
		"SELECT COUNT(*) FROM messages WHERE conversationId = :convId AND compressed = 0 AND status = :status",
	)
	suspend fun countUncompressed(convId: Long, status: MessageStatus): Int

	@Query(
		"SELECT COALESCE(SUM(tokenEstimate), 0) FROM messages " +
			"WHERE conversationId = :convId AND compressed = 0 AND status = :status",
	)
	suspend fun sumUncompressedTokens(convId: Long, status: MessageStatus): Int

	@Query("SELECT MAX(id) FROM messages WHERE conversationId = :convId AND status = :status")
	suspend fun maxMessageId(convId: Long, status: MessageStatus): Long?

	@Insert
	suspend fun insert(message: MessageEntity): Long

	@Update
	suspend fun update(message: MessageEntity)

	/** 流式输出期间高频调用，只动这三列 */
	@Query("UPDATE messages SET content = :content, tokenEstimate = :tokens, status = :status WHERE id = :id")
	suspend fun updateStreamingContent(id: Long, content: String, tokens: Int, status: MessageStatus)

	@Query("UPDATE messages SET status = :status, errorMessage = :error WHERE id = :id")
	suspend fun updateStatus(id: Long, status: MessageStatus, error: String?)

	@Query("UPDATE messages SET compressed = 1 WHERE conversationId = :convId AND id <= :untilId")
	suspend fun markCompressedUntil(convId: Long, untilId: Long)

	@Query("DELETE FROM messages WHERE id = :id")
	suspend fun deleteById(id: Long)

	@Query("DELETE FROM messages WHERE conversationId = :convId")
	suspend fun deleteByConversation(convId: Long)

	/** 清掉发送失败的残留（用户点"清理失败消息"时用） */
	@Query("DELETE FROM messages WHERE conversationId = :convId AND status = :status")
	suspend fun deleteByStatus(convId: Long, status: MessageStatus)
}
