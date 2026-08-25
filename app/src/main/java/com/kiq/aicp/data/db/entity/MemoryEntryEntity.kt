// app/src/main/java/com/kiq/aicp/data/db/entity/MemoryEntryEntity.kt
// 记忆条目表（v6 新增）—— wiki 三层结构里的第二层。
//
// 跟被它取代的 memory_cards 的本质区别：卡片是「(键, 60字内容)」的键值对，只能覆盖；
// 条目是一篇会被反复增补的小文章。新信息进来时读旧正文、合并、写回，而不是整条替换。
// 这是 Karpathy 那份 llm-wiki 的核心主张：知识编译一次然后保持更新，不是每次重新推导。
//
// 三个字段是专门为「不用 embedding 也能检索」设计的：
// - title：条目名，归一化后参与唯一索引，同名就是同一个条目
// - aliases：别名，"|" 分隔。模型这次抽「职业」下次抽「工作」，靠别名对上同一条目 ——
//   老的卡片方案就是在这里裂成两张互相矛盾的卡的
// - oneLiner：一行摘要。注入 system prompt 的 index 只带它，不带正文，
//   模型据此知道「我还有哪些记忆可查」而不必把所有正文塞进上下文
//
// memory_cards 表刻意保留不删：迁移时一对一复制过来，之后代码只读写这张新表，
// 旧表冻结成历史归档。新结构真出问题时，原始记忆还完整躺在那儿。

package com.kiq.aicp.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.kiq.aicp.domain.model.MemoryCardType

@Entity(
	tableName = "memory_entries",
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
		Index(value = ["scopeKey", "category", "title"], unique = true),
		Index("conversationId"),
		Index("personaId"),
	],
)
data class MemoryEntryEntity(
	@PrimaryKey(autoGenerate = true)
	val id: Long = 0,

	/** null = 不限会话（跨会话共享的用户事实） */
	val conversationId: Long? = null,

	/** null = 与性格无关；非 null 时通常是 IMPRESSION 分类 */
	val personaId: Long? = null,

	/**
	 * 归一化后的作用域串，唯一索引用，由 MemoryScope.key() 生成，别手拼。
	 * 这里沿用卡片时代的做法是因为 SQLite 的 UNIQUE 里 NULL != NULL，
	 * 带可空列的唯一索引挡不住重复插入。
	 */
	val scopeKey: String,

	/** 条目分类。沿用卡片的枚举，语义上现在是 wiki 的页面类别 */
	val category: MemoryCardType,

	/** 条目名，比如「职业」「养的猫」「和 KIQ 的约定」。同名即同条目 */
	val title: String,

	/** 别名，"|" 分隔。空串表示没有别名。检索匹配时和 title 一起参与 */
	val aliases: String = "",

	/** 一行摘要，注入 index 用，不超过 30 字 */
	val oneLiner: String,

	/** 正文。会被反复增补，不超过 200 字 */
	val body: String,

	/** 1..5。上下文预算不够时从低分开始砍 */
	val importance: Int,

	/** 被拼进上下文的次数与最近一次时间，用于冷条目淘汰 */
	val hitCount: Int = 0,
	val lastHitAt: Long = 0,

	/** 用户手动钉住：永不淘汰、永不被自动覆盖 */
	val pinned: Boolean = false,

	/**
	 * 被几轮对话喂过。反复确认过的条目更可信，
	 * lint 时可以据此判断「只出现过一次的说法」值不值得保留
	 */
	val sourceCount: Int = 1,

	/**
	 * 矛盾备注：新信息跟旧正文冲突时，模型把冲突写在这里而不是直接覆盖。
	 * Karpathy 那份里专门强调了「标注新数据在哪与旧断言矛盾」——
	 * 悄悄覆盖会让用户永远不知道记忆变过。null 表示没有未处理的矛盾
	 */
	val conflictNote: String? = null,

	val createdAt: Long,
	val updatedAt: Long,
) {

	/** 检索匹配用的词表：标题加所有别名 */
	fun matchTerms(): List<String> =
		(listOf(title) + aliases.split(ALIAS_SEPARATOR)).map { it.trim() }.filter { it.isNotEmpty() }

	companion object {
		const val ALIAS_SEPARATOR = "|"

		const val MAX_TITLE = 16
		const val MAX_ONE_LINER = 30
		const val MAX_BODY = 200

		/** 别名最多存几个，防止模型一口气塞二十个近义词把索引撑爆 */
		const val MAX_ALIASES = 4

		fun joinAliases(aliases: List<String>): String =
			aliases.map { it.trim() }
				.filter { it.isNotEmpty() }
				.distinct()
				.take(MAX_ALIASES)
				.joinToString(ALIAS_SEPARATOR)
	}
}
