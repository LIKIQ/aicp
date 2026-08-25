// app/src/main/java/com/kiq/aicp/data/db/entity/MemorySummaryEntity.kt
// 段落摘要表（分层记忆的 L1 / L2）。
//
// level 1 = 一段原文被压成的摘要
// level 2 = 若干条 level 1 再压一层（会话超长时递归收敛，避免摘要本身把预算吃光）
// 被上层合并过的旧摘要置 superseded=true，保留痕迹但不再进上下文。

package com.kiq.aicp.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
	tableName = "memory_summaries",
	foreignKeys = [
		ForeignKey(
			entity = ConversationEntity::class,
			parentColumns = ["id"],
			childColumns = ["conversationId"],
			onDelete = ForeignKey.CASCADE,
		),
	],
	indices = [
		Index(value = ["conversationId", "level", "superseded"]),
	],
)
data class MemorySummaryEntity(
	@PrimaryKey(autoGenerate = true)
	val id: Long = 0,

	val conversationId: Long,

	/** 1 = 段摘要，2 = 摘要的摘要 */
	val level: Int,

	val content: String,

	/** 这条摘要覆盖的原文区间（左开右闭，跟压缩游标口径一致） */
	val fromMessageId: Long,
	val toMessageId: Long,

	val messageCount: Int,
	val tokenEstimate: Int,

	/** 被更高层摘要吸收后置 true */
	val superseded: Boolean = false,

	/**
	 * 没网 / 没配 Key 时会先落一条机械裁剪的占位摘要，标记它待重压。
	 * 有网之后由压缩器补一次真正的语义压缩，然后把这个标记清掉。
	 */
	val needsSemanticRedo: Boolean = false,

	val createdAt: Long,
)
