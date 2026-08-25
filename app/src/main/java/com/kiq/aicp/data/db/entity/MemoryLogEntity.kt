// app/src/main/java/com/kiq/aicp/data/db/entity/MemoryLogEntity.kt
// 记忆操作日志（v6 新增）—— wiki 结构里的 log.md。
//
// Karpathy 那份里 log 的作用有两个：给人一条时间线看见 wiki 是怎么长起来的，
// 以及让 LLM 知道最近做过什么。对我们还有第三个更实际的用处：
// 记忆被自动改写是件用户看不见的事，出了问题（「它怎么记成这样了」）没有任何线索可查。
// 有这张表就能回答"这个条目是哪一轮、因为哪段对话变成现在这样的"。
//
// 刻意做成 append-only，不提供 update：日志被改写就失去了作为证据的意义。
// 清理靠按时间批量删旧记录，不靠改。

package com.kiq.aicp.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** 日志类型。用字符串存而不是枚举，将来加类型不用动迁移 */
object MemoryLogKind {
	/** 一次压缩：读了一段对话，产出摘要并更新了若干条目 */
	const val INGEST = "INGEST"

	/** 一次体检：找矛盾、过时、可合并 */
	const val LINT = "LINT"

	/** 用户手动改了条目 */
	const val MANUAL = "MANUAL"

	/** 从旧卡片迁移过来 */
	const val MIGRATE = "MIGRATE"
}

@Entity(
	tableName = "memory_logs",
	indices = [Index("createdAt"), Index("conversationId")],
)
data class MemoryLogEntity(
	@PrimaryKey(autoGenerate = true)
	val id: Long = 0,

	/**
	 * 相关会话。null 表示全局操作（比如跨会话的 lint）。
	 * 刻意不加外键：会话被删掉之后，"当时发生过什么"这条记录仍然有价值
	 */
	val conversationId: Long? = null,

	/** 见 MemoryLogKind */
	val kind: String,

	/** 一句话说清这次干了什么，给人看的 */
	val summary: String,

	/** 受影响的条目标题，"|" 分隔。空串表示没动条目（比如只产出了摘要） */
	val touchedTitles: String = "",

	val createdAt: Long,
)
