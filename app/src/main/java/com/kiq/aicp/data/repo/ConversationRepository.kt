// app/src/main/java/com/kiq/aicp/data/repo/ConversationRepository.kt
// 会话的创建与维护。单聊 = 只有一个参与者的群聊，所以两条路径共用 conversation_personas。
//
// 建会话时如果性格有开场白，会顺手插一条 assistant 消息 ——
// 这样会话列表立刻有预览文字，用户点进去也不是一片空白。

package com.kiq.aicp.data.repo

import androidx.room.withTransaction
import com.kiq.aicp.data.attach.AttachmentStore
import com.kiq.aicp.data.db.AicpDatabase
import com.kiq.aicp.data.db.entity.ConversationEntity
import com.kiq.aicp.data.db.entity.ConversationPersonaCrossRef
import com.kiq.aicp.data.db.entity.MessageEntity
import com.kiq.aicp.data.db.entity.PersonaEntity
import com.kiq.aicp.domain.memory.TokenEstimator
import com.kiq.aicp.domain.model.ChatRole
import com.kiq.aicp.domain.model.ConversationMode
import com.kiq.aicp.domain.model.MessageStatus
import kotlinx.coroutines.flow.Flow

class ConversationRepository(
	private val db: AicpDatabase,
	/** 删会话时要连带清掉它所有附件的磁盘文件；单测里可以不传 */
	private val attachmentStore: AttachmentStore? = null,
	private val clock: () -> Long = System::currentTimeMillis,
) {

	private val convDao = db.conversationDao()
	private val personaDao = db.personaDao()
	private val messageDao = db.messageDao()
	private val attachmentDao = db.attachmentDao()

	fun observeActive(): Flow<List<ConversationEntity>> = convDao.observeActive()

	fun observeArchived(): Flow<List<ConversationEntity>> = convDao.observeArchived()

	fun observeById(id: Long): Flow<ConversationEntity?> = convDao.observeById(id)

	fun observeParticipantPersonas(convId: Long): Flow<List<PersonaEntity>> =
		convDao.observeParticipantPersonas(convId)

	fun observeParticipants(convId: Long): Flow<List<ConversationPersonaCrossRef>> =
		convDao.observeParticipants(convId)

	/** 全部会话的参与者关联，会话列表用它给单聊算出该显示谁的头像 */
	fun observeAllParticipants(): Flow<List<ConversationPersonaCrossRef>> =
		convDao.observeAllParticipants()

	suspend fun getById(id: Long): ConversationEntity? = convDao.getById(id)

	suspend fun participants(convId: Long): List<ConversationPersonaCrossRef> =
		convDao.getParticipants(convId)

	/** 一对一会话。personaId 不存在时抛异常，别静默建出一个没人的会话 */
	suspend fun createSingle(personaId: Long): Long = db.withTransaction {
		val persona = requireNotNull(personaDao.getById(personaId)) {
			"性格 $personaId 不存在，无法建会话"
		}
		val now = clock()
		val convId = convDao.insert(
			ConversationEntity(
				title = persona.name,
				mode = ConversationMode.SINGLE,
				createdAt = now,
				updatedAt = now,
				lastMessageAt = now,
			),
		)
		convDao.addParticipant(
			ConversationPersonaCrossRef(conversationId = convId, personaId = personaId, joinedAt = now),
		)
		insertGreetingIfAny(convId, persona, now)
		convId
	}

	/** 群聊。至少两个性格才有意义，少于两个直接按单聊处理 */
	suspend fun createGroup(personaIds: List<Long>, title: String? = null): Long {
		val ids = personaIds.distinct()
		require(ids.isNotEmpty()) { "群聊至少要有一个性格" }
		if (ids.size == 1) return createSingle(ids.first())

		return db.withTransaction {
			val personas = personaDao.getByIds(ids)
			require(personas.isNotEmpty()) { "所选性格都不存在" }
			val now = clock()
			val convId = convDao.insert(
				ConversationEntity(
					title = title?.trim()?.takeIf { it.isNotEmpty() }
						?: personas.joinToString("、") { it.name },
					mode = ConversationMode.GROUP,
					createdAt = now,
					updatedAt = now,
					lastMessageAt = now,
				),
			)
			personas.forEach { p ->
				convDao.addParticipant(
					ConversationPersonaCrossRef(conversationId = convId, personaId = p.id, joinedAt = now),
				)
			}
			// 群聊只让第一个性格开口，四个人一起打招呼太吵
			insertGreetingIfAny(convId, personas.first(), now)
			convId
		}
	}

	suspend fun addParticipant(convId: Long, personaId: Long) = db.withTransaction {
		val persona = requireNotNull(personaDao.getById(personaId)) { "性格 $personaId 不存在" }
		val now = clock()
		convDao.addParticipant(
			ConversationPersonaCrossRef(conversationId = convId, personaId = persona.id, joinedAt = now),
		)
		// 拉人进群后会话就变群聊了
		convDao.getById(convId)?.let { conv ->
			if (conv.mode == ConversationMode.SINGLE && convDao.getParticipants(convId).size > 1) {
				convDao.update(conv.copy(mode = ConversationMode.GROUP, updatedAt = now))
			}
		}
	}

	suspend fun removeParticipant(convId: Long, personaId: Long) =
		convDao.removeParticipant(convId, personaId)

	suspend fun setParticipantMuted(convId: Long, personaId: Long, muted: Boolean) =
		convDao.setParticipantMuted(convId, personaId, muted)

	/** 会话里某个性格的心情值。null 表示这条关联没了（人被移出群了） */
	suspend fun moodOf(convId: Long, personaId: Long): ConversationPersonaCrossRef? =
		convDao.getParticipant(convId, personaId)

	suspend fun updateMood(convId: Long, personaId: Long, mood: Int, updatedAt: Long) =
		convDao.updateMood(convId, personaId, mood, updatedAt)

	suspend fun rename(id: Long, title: String) =
		convDao.rename(id, title.trim().ifEmpty { "未命名会话" }, clock())

	/**
	 * 群聊的名称和头像。单聊不该调这个 —— 单聊显示的是性格自己的名字和头像，
	 * 两处都能改同一样东西的话用户永远搞不清改的是哪个。
	 *
	 * 换了头像图就删旧文件；把 avatarPath 传 null 表示清空头像，旧图同样要删。
	 */
	suspend fun updateGroupProfile(
		id: Long,
		title: String,
		avatarEmoji: String,
		avatarPath: String?,
	) {
		val old = convDao.getById(id) ?: return
		convDao.update(
			old.copy(
				title = title.trim().ifEmpty { old.title },
				avatarEmoji = avatarEmoji.trim(),
				avatarPath = avatarPath,
				updatedAt = clock(),
			),
		)

		val oldPath = old.avatarPath
		if (oldPath != null && oldPath != avatarPath) {
			attachmentStore?.delete(listOf(oldPath))
		}
	}

	/** 孤儿清理用：还被引用着的群头像路径 */
	suspend fun collectAvatarPaths(): List<String> = convDao.collectAvatarPaths()

	suspend fun setPinned(id: Long, pinned: Boolean) = convDao.setPinned(id, pinned)

	suspend fun setArchived(id: Long, archived: Boolean) = convDao.setArchived(id, archived)

	/** 外键 CASCADE 会连带清掉消息、附件行、摘要、卡片和参与者关联；附件的磁盘文件要自己删 */
	suspend fun delete(id: Long) {
		val orphanPaths = db.withTransaction {
			val paths = attachmentDao.collectPathsOfConversation(id)
			convDao.deleteById(id)
			paths
		}
		if (orphanPaths.isNotEmpty()) attachmentStore?.delete(orphanPaths)
	}

	private suspend fun insertGreetingIfAny(convId: Long, persona: PersonaEntity, now: Long) {
		val greeting = persona.greeting.trim()
		if (greeting.isEmpty()) return
		messageDao.insert(
			MessageEntity(
				conversationId = convId,
				role = ChatRole.ASSISTANT,
				personaId = persona.id,
				content = greeting,
				tokenEstimate = TokenEstimator.estimateMessage(greeting),
				status = MessageStatus.OK,
				createdAt = now,
			),
		)
		convDao.touchLastMessage(convId, now, greeting.take(60).replace('\n', ' '))
	}
}
