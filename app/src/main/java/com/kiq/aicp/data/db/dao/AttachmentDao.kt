// app/src/main/java/com/kiq/aicp/data/db/dao/AttachmentDao.kt
// 消息附件读写（v2 新增）。
//
// 删除附件行不会自动删磁盘文件 —— SQLite 的 CASCADE 管不到文件系统。
// 所以提供了 collectPaths* 这两个查询：调用方先把路径捞出来，删完行再去删文件。
// 顺序反了就会留下孤儿文件，永远没人清。

package com.kiq.aicp.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.kiq.aicp.data.db.entity.MessageAttachmentEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AttachmentDao {

	@Insert
	suspend fun insert(attachment: MessageAttachmentEntity): Long

	@Insert
	suspend fun insertAll(attachments: List<MessageAttachmentEntity>): List<Long>

	@Query("SELECT * FROM message_attachments WHERE messageId = :messageId ORDER BY id ASC")
	suspend fun byMessage(messageId: Long): List<MessageAttachmentEntity>

	@Query("SELECT * FROM message_attachments WHERE messageId IN (:messageIds) ORDER BY id ASC")
	suspend fun byMessages(messageIds: List<Long>): List<MessageAttachmentEntity>

	/**
	 * 会话内所有附件，给聊天页整体订阅用。
	 * 用 JOIN 而不是让 UI 逐条查：一屏几十条消息各查一次附件会把主线程拖垮。
	 */
	@Query(
		"SELECT a.* FROM message_attachments a " +
			"INNER JOIN messages m ON m.id = a.messageId " +
			"WHERE m.conversationId = :convId ORDER BY a.messageId ASC, a.id ASC",
	)
	fun observeByConversation(convId: Long): Flow<List<MessageAttachmentEntity>>

	@Query("SELECT localPath FROM message_attachments WHERE messageId = :messageId")
	suspend fun collectPathsOfMessage(messageId: Long): List<String>

	@Query(
		"SELECT a.localPath FROM message_attachments a " +
			"INNER JOIN messages m ON m.id = a.messageId WHERE m.conversationId = :convId",
	)
	suspend fun collectPathsOfConversation(convId: Long): List<String>

	@Query("SELECT localPath FROM message_attachments")
	suspend fun collectAllPaths(): List<String>

	@Query("DELETE FROM message_attachments WHERE id = :id")
	suspend fun deleteById(id: Long)

	@Query("SELECT COUNT(*) FROM message_attachments")
	suspend fun count(): Int
}
