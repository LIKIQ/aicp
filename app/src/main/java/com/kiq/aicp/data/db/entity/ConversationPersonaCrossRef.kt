// app/src/main/java/com/kiq/aicp/data/db/entity/ConversationPersonaCrossRef.kt
// 会话 ↔ 性格 关联表。单聊放一条，群聊放多条。
// 删会话连带删关联；删性格也连带删关联（但不删历史消息，见 MessageEntity 的说明）。

package com.kiq.aicp.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
	tableName = "conversation_personas",
	primaryKeys = ["conversationId", "personaId"],
	foreignKeys = [
		ForeignKey(
			entity = ConversationEntity::class,
			parentColumns = ["id"],
			childColumns = ["conversationId"],
			onDelete = ForeignKey.CASCADE,
		),
		ForeignKey(
			entity = PersonaEntity::class,
			parentColumns = ["id"],
			childColumns = ["personaId"],
			onDelete = ForeignKey.CASCADE,
		),
	],
	indices = [Index("personaId")],
)
data class ConversationPersonaCrossRef(
	val conversationId: Long,
	val personaId: Long,

	/** 群聊发言权重，越大越容易被调度到；单聊里这个值没意义 */
	val speakWeight: Float = 1f,

	/** 群聊里可以临时静音某个性格，不动关联关系 */
	val muted: Boolean = false,

	/**
	 * 心情值 -100..100（v2 新增）。
	 * 放在关联表而不是 personas 上是有意的：同一个性格在不同会话里的心情可以不一样，
	 * 跟你吵过架的那个"小雪"不该影响另一个会话里的她。
	 * defaultValue 必须和迁移 SQL 里的 DEFAULT 对齐，否则 Room 启动时 schema 校验会报 mismatch。
	 */
	@ColumnInfo(defaultValue = "0")
	val mood: Int = 0,

	@ColumnInfo(defaultValue = "0")
	val moodUpdatedAt: Long = 0,

	val joinedAt: Long,
)
