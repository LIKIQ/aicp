// app/src/main/java/com/kiq/aicp/domain/memory/MemoryScope.kt
// 记忆卡片的作用域归一化。
//
// 为什么要有这么个东西：memory_cards 的去重靠 UNIQUE(scopeKey, type, keyword)，
// 而 SQLite 里 NULL != NULL —— 直接拿可空的 conversationId / personaId 建唯一索引根本挡不住重复。
// 所以把作用域拍平成字符串再参与索引。这里是唯一的生成入口，别在别处手拼。

package com.kiq.aicp.domain.memory

object MemoryScope {

	private const val NONE = "-"

	/** 单一作用域键。conversationId / personaId 为 null 表示"不限" */
	fun key(conversationId: Long?, personaId: Long?): String =
		"c:${conversationId ?: NONE}|p:${personaId ?: NONE}"

	/** 跨会话、跨性格的全局用户事实 */
	fun global(): String = key(null, null)

	/** 某个性格对用户的长期印象，跨会话生效 */
	fun personaWide(personaId: Long): String = key(null, personaId)

	/** 只属于这个会话的事实 */
	fun conversationWide(conversationId: Long): String = key(conversationId, null)

	/**
	 * 组装上下文时要一次性捞的作用域集合：
	 * 全局事实 + 本会话事实 +（单聊/指定发言人时）该性格的印象与该性格在本会话里的私有记忆。
	 * personaId 传 null 表示不带性格维度（例如群聊里做整体摘要时）。
	 */
	fun contextKeys(conversationId: Long, personaId: Long?): List<String> = buildList {
		add(global())
		add(conversationWide(conversationId))
		if (personaId != null) {
			add(personaWide(personaId))
			add(key(conversationId, personaId))
		}
	}
}
