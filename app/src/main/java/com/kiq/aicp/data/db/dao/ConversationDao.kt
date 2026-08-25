// app/src/main/java/com/kiq/aicp/data/db/dao/ConversationDao.kt
// 会话表 + 参与者关联表的读写。
// 压缩相关的三个更新方法（onCompressSuccess / onCompressFailure / updatePendingTokens）
// 都写成单条 UPDATE，避免"读实体-改字段-整行写回"跟流式写入打架覆盖掉别的字段。

package com.kiq.aicp.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.kiq.aicp.data.db.entity.ConversationEntity
import com.kiq.aicp.data.db.entity.ConversationPersonaCrossRef
import com.kiq.aicp.data.db.entity.PersonaEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {

	@Query(
		"SELECT * FROM conversations WHERE archived = 0 " +
			"ORDER BY pinned DESC, lastMessageAt DESC, id DESC",
	)
	fun observeActive(): Flow<List<ConversationEntity>>

	@Query("SELECT * FROM conversations WHERE archived = 1 ORDER BY lastMessageAt DESC")
	fun observeArchived(): Flow<List<ConversationEntity>>

	/** 一次性拿活跃会话。后台任务只要快照，开 Flow 订阅还得手动取消 */
	@Query(
		"SELECT * FROM conversations WHERE archived = 0 " +
			"ORDER BY pinned DESC, lastMessageAt DESC, id DESC",
	)
	suspend fun activeList(): List<ConversationEntity>

	@Query("SELECT * FROM conversations WHERE id = :id")
	fun observeById(id: Long): Flow<ConversationEntity?>

	@Query("SELECT * FROM conversations WHERE id = :id")
	suspend fun getById(id: Long): ConversationEntity?

	@Insert
	suspend fun insert(conversation: ConversationEntity): Long

	@Update
	suspend fun update(conversation: ConversationEntity)

	@Query("DELETE FROM conversations WHERE id = :id")
	suspend fun deleteById(id: Long)

	@Query(
		"UPDATE conversations SET lastMessageAt = :at, lastMessagePreview = :preview, " +
			"updatedAt = :at WHERE id = :id",
	)
	suspend fun touchLastMessage(id: Long, at: Long, preview: String)

	@Query("UPDATE conversations SET pendingTokens = :tokens WHERE id = :id")
	suspend fun updatePendingTokens(id: Long, tokens: Int)

	@Query(
		"UPDATE conversations SET compressedUntilMessageId = :until, pendingTokens = :pendingTokens, " +
			"compressFailureCount = 0, lastCompressAttemptAt = :at WHERE id = :id",
	)
	suspend fun onCompressSuccess(id: Long, until: Long, pendingTokens: Int, at: Long)

	@Query(
		"UPDATE conversations SET compressFailureCount = compressFailureCount + 1, " +
			"lastCompressAttemptAt = :at WHERE id = :id",
	)
	suspend fun onCompressFailure(id: Long, at: Long)

	@Query("UPDATE conversations SET pinned = :pinned WHERE id = :id")
	suspend fun setPinned(id: Long, pinned: Boolean)

	@Query("UPDATE conversations SET archived = :archived WHERE id = :id")
	suspend fun setArchived(id: Long, archived: Boolean)

	@Query("UPDATE conversations SET title = :title, updatedAt = :at WHERE id = :id")
	suspend fun rename(id: Long, title: String, at: Long)

	// ---- 参与者 ----

	/** 重复拉同一个性格进群时直接忽略，不要报冲突 */
	@Insert(onConflict = OnConflictStrategy.IGNORE)
	suspend fun addParticipant(ref: ConversationPersonaCrossRef)

	@Query("DELETE FROM conversation_personas WHERE conversationId = :convId AND personaId = :personaId")
	suspend fun removeParticipant(convId: Long, personaId: Long)

	@Query("SELECT * FROM conversation_personas WHERE conversationId = :convId ORDER BY joinedAt ASC")
	suspend fun getParticipants(convId: Long): List<ConversationPersonaCrossRef>

	@Query("SELECT * FROM conversation_personas WHERE conversationId = :convId ORDER BY joinedAt ASC")
	fun observeParticipants(convId: Long): Flow<List<ConversationPersonaCrossRef>>

	/**
	 * 全部会话的参与者关联，给会话列表算头像用。
	 * 整表拉出来是有意的：行数等于会话数乘参与者数，几十上百行的量级，
	 * 比让列表逐个会话查一次（N+1）划算得多。
	 */
	@Query("SELECT * FROM conversation_personas ORDER BY conversationId ASC, joinedAt ASC")
	fun observeAllParticipants(): Flow<List<ConversationPersonaCrossRef>>

	@Query(
		"SELECT p.* FROM personas p " +
			"INNER JOIN conversation_personas cp ON p.id = cp.personaId " +
			"WHERE cp.conversationId = :convId ORDER BY cp.joinedAt ASC",
	)
	fun observeParticipantPersonas(convId: Long): Flow<List<PersonaEntity>>

	@Query(
		"UPDATE conversation_personas SET muted = :muted " +
			"WHERE conversationId = :convId AND personaId = :personaId",
	)
	suspend fun setParticipantMuted(convId: Long, personaId: Long, muted: Boolean)

	@Query(
		"SELECT * FROM conversation_personas WHERE conversationId = :convId AND personaId = :personaId",
	)
	suspend fun getParticipant(convId: Long, personaId: Long): ConversationPersonaCrossRef?

	@Query(
		"UPDATE conversation_personas SET mood = :mood, moodUpdatedAt = :updatedAt " +
			"WHERE conversationId = :convId AND personaId = :personaId",
	)
	suspend fun updateMood(convId: Long, personaId: Long, mood: Int, updatedAt: Long)

	@Query("SELECT COUNT(*) FROM conversation_personas WHERE personaId = :personaId")
	suspend fun countConversationsUsing(personaId: Long): Int

	/** 孤儿文件清理用：所有还被引用着的群头像路径 */
	@Query("SELECT avatarPath FROM conversations WHERE avatarPath IS NOT NULL")
	suspend fun collectAvatarPaths(): List<String>
}
