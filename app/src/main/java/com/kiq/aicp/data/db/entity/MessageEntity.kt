// app/src/main/java/com/kiq/aicp/data/db/entity/MessageEntity.kt
// 消息原文表（分层记忆的 L0）。
//
// 两个刻意的设计：
// 1. personaId 上只建索引、不建外键。删掉一个性格不该把聊过的话一起抹掉，
//    历史记录里那条消息仍然要显示，只是查不到对应性格了（UI 兜底显示"已删除的性格"）。
// 2. compressed 只表示"不再进 LLM 上下文"，不代表删除。用户翻历史照样看得到原文，
//    这是"本地记忆"这个卖点的底线 —— 摘要可以有损，原文不能丢。

package com.kiq.aicp.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.kiq.aicp.domain.model.ChatRole
import com.kiq.aicp.domain.model.MessageStatus

@Entity(
	tableName = "messages",
	foreignKeys = [
		ForeignKey(
			entity = ConversationEntity::class,
			parentColumns = ["id"],
			childColumns = ["conversationId"],
			onDelete = ForeignKey.CASCADE,
		),
	],
	indices = [
		Index(value = ["conversationId", "id"]),
		Index(value = ["conversationId", "compressed"]),
		Index("personaId"),
	],
)
data class MessageEntity(
	@PrimaryKey(autoGenerate = true)
	val id: Long = 0,

	val conversationId: Long,

	val role: ChatRole,

	/** ASSISTANT 消息归属的性格；USER / SYSTEM 一律为 null */
	val personaId: Long? = null,

	val content: String,

	/** 估算值，不是真实 tokenizer 结果，只用于预算控制（见 domain/memory/TokenEstimator.kt） */
	val tokenEstimate: Int,

	val status: MessageStatus,

	/** 失败原因，给 UI 上的重试按钮做提示 */
	val errorMessage: String? = null,

	val createdAt: Long,

	/** 已被摘要覆盖：UI 照常展示，但不再拼进发给模型的上下文 */
	val compressed: Boolean = false,
)
