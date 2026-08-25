// app/src/main/java/com/kiq/aicp/data/db/entity/ConversationEntity.kt
// 会话表。单聊和群聊共用这一张，参与者一律走 conversation_personas 关联表，
// 单聊就是"只有一个参与者的群聊"，省掉两套分支逻辑。
//
// 这里刻意冗余了三个字段，都是为了列表页和压缩判定不用回表扫 messages：
// - lastMessagePreview / lastMessageAt：会话列表直接读
// - pendingTokens：未压缩区间的 token 估算，压缩触发判定读它
//
// 头像两个字段是给**群聊**用的（v5 新增）。单聊刻意不单独设头像：
// 单聊的对象就是那一个性格，两处都能改同一样东西的话，用户永远搞不清自己改的是哪个。
// 所以单聊显示性格的头像，群聊才用自己的。

package com.kiq.aicp.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.kiq.aicp.domain.model.ConversationMode

@Entity(tableName = "conversations")
data class ConversationEntity(
	@PrimaryKey(autoGenerate = true)
	val id: Long = 0,

	val title: String,

	val mode: ConversationMode,

	/** 群头像 emoji，空串表示没设。单聊忽略这个字段 */
	@ColumnInfo(defaultValue = "''")
	val avatarEmoji: String = "",

	/** 群头像图片相对路径，有值时优先于 emoji。单聊忽略 */
	@ColumnInfo(defaultValue = "NULL")
	val avatarPath: String? = null,

	val pinned: Boolean = false,
	val archived: Boolean = false,

	val createdAt: Long,
	val updatedAt: Long,

	val lastMessageAt: Long = 0,
	val lastMessagePreview: String = "",

	/** 压缩游标：id <= 这个值的原文消息都已被摘要覆盖，不再进上下文 */
	val compressedUntilMessageId: Long = 0,

	/** 游标之后那段原文的 token 估算，超阈值就触发压缩 */
	val pendingTokens: Int = 0,

	/** 上次压缩尝试时间 + 连续失败次数，用来做退避，避免没网时死循环重试 */
	val lastCompressAttemptAt: Long = 0,
	val compressFailureCount: Int = 0,
)
