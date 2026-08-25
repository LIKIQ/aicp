// app/src/main/java/com/kiq/aicp/domain/memory/EntryMatcher.kt
// 用条目标题和别名去文本里做关键词命中 —— wiki 检索的核心，不用 embedding 的那条路。
//
// Karpathy 那份里说 index 在中等规模下就够用、能免掉 embedding 基础设施。
// 这里就是它的具体落法：条目自己带标题和别名，拿用户这句话去撞，撞上就把整条正文带上。
// 对中文尤其划算 —— 中文条目标题本身就是词，contains 就是最朴素也最准的匹配，
// 不需要分词器，也不会像 embedding 那样把"喜欢猫"和"讨厌猫"算成近邻。
//
// 单字词不参与匹配：标题"猫"会在"猫腻""猫头鹰"里误命中，一个字的信息量撑不起一次检索。
// 模型被要求给有意义的标题，实际出现一个字标题的概率很低，挡掉的代价小于误命中的代价。

package com.kiq.aicp.domain.memory

import com.kiq.aicp.data.db.entity.MemoryEntryEntity

data class EntryHit(
	val entry: MemoryEntryEntity,
	/** 命中的词，用于调试和日志 */
	val term: String,
	/** 命中权重：命中词越长越可信 */
	val weight: Int,
)

object EntryMatcher {

	/** 少于两个字的词不参与匹配 */
	private const val MIN_TERM_LENGTH = 2

	/**
	 * @param text 拿来撞的文本，通常是用户最近几句话或者待压缩的对话
	 * @param limit 最多返回几条，防止一句话把整个记忆库都拖进上下文
	 */
	fun match(
		entries: List<MemoryEntryEntity>,
		text: String,
		limit: Int,
	): List<EntryHit> {
		if (entries.isEmpty() || text.isBlank() || limit <= 0) return emptyList()

		val haystack = text.lowercase()

		return entries.mapNotNull { entry -> bestHit(entry, haystack) }
			// 权重相同时让更重要的条目排前面，其次是最近更新的 ——
			// 都命中了的话，"更要紧且更新鲜"的那条更值得占上下文
			.sortedWith(
				compareByDescending<EntryHit> { it.weight }
					.thenByDescending { it.entry.importance }
					.thenByDescending { it.entry.updatedAt },
			)
			.take(limit)
	}

	/** 一个条目可能有多个词命中，取最长那个当代表 —— 长词命中说明匹配得更实 */
	private fun bestHit(entry: MemoryEntryEntity, haystack: String): EntryHit? {
		var best: String? = null
		entry.matchTerms().forEach { term ->
			if (term.length < MIN_TERM_LENGTH) return@forEach
			if (!haystack.contains(term.lowercase())) return@forEach
			// 用局部 val 接一手再比长度，省掉 best!! —— 那个 !! 在这里是多余的，
			// 编译器顺着 null 检查已经能收窄类型
			val current = best
			if (current == null || term.length > current.length) best = term
		}
		return best?.let { EntryHit(entry, it, it.length) }
	}
}
