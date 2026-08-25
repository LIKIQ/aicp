// app/src/main/java/com/kiq/aicp/data/db/entity/MemoryCardEntity.kt
// 记忆卡片表（分层记忆的 L3）。压缩时顺手从对话里抽出来的稳定事实。
//
// scopeKey 这个字段是为了绕开 SQLite 的一个坑：
// UNIQUE 约束里 NULL != NULL，所以 (conversationId, personaId, type, keyword) 这种带可空列的唯一索引
// 根本挡不住"全局卡片"重复插入。于是把作用域归一化成字符串 "c:12|p:3" / "c:-|p:-" 再参与唯一索引。

package com.kiq.aicp.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.kiq.aicp.domain.model.MemoryCardType

@Entity(
	tableName = "memory_cards",
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
	indices = [
		Index(value = ["scopeKey", "type", "keyword"], unique = true),
		Index("conversationId"),
		Index("personaId"),
	],
)
data class MemoryCardEntity(
	@PrimaryKey(autoGenerate = true)
	val id: Long = 0,

	/** null = 不限会话（跨会话共享的用户事实） */
	val conversationId: Long? = null,

	/** null = 与性格无关；非 null 时通常是 IMPRESSION 类型 */
	val personaId: Long? = null,

	/** 归一化后的作用域串，唯一索引用，由 MemoryScope.key() 生成，别手拼 */
	val scopeKey: String,

	val type: MemoryCardType,

	/** 归一化的键，比如"职业""宠物""称呼"，同键再抽到就是更新而不是新增 */
	val keyword: String,

	val content: String,

	/** 1..5。上下文预算不够时从低分开始砍 */
	val importance: Int,

	/** 被拼进上下文的次数与最近一次时间，用于冷卡淘汰 */
	val hitCount: Int = 0,
	val lastHitAt: Long = 0,

	/** 用户手动钉住：永不淘汰、永不被自动覆盖 */
	val pinned: Boolean = false,

	val createdAt: Long,
	val updatedAt: Long,
)
