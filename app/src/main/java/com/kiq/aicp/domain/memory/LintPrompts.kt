/*
 * app/src/main/java/com/kiq/aicp/domain/memory/LintPrompts.kt
 * 记忆体检（lint）的提示词与解析
 * 职责：
 * - 把一个作用域下的全部条目摊给模型，让它找结构问题：该合并的、互相矛盾的、已经过期的
 * - 解析模型给的建议，产出待用户确认的清单（这里只解析，不动数据库）
 *
 * 为什么让模型回序号而不是标题：
 * 模型复述中文标题时会改字——"养猫"写成"猫"、"工作"写成"职业"，
 * 回来一查唯一键就对不上，建议整条作废。给条目编号让它引用数字，
 * 数字它抄不错，代码再把序号映回真实条目 id。
 */
package com.kiq.aicp.domain.memory

import com.kiq.aicp.data.db.entity.MemoryEntryEntity
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** 建议把 absorb 里的条目并进 keep，body 是模型写好的合并正文 */
data class ParsedMerge(
	val keep: Int,
	val absorb: List<Int>,
	val body: String,
	val reason: String,
)

/**
 * 矛盾只有用户能裁决，所以模型产出的不是"改成什么"，而是"该问哪句话"。
 * 这条最贴近 karpathy 说的 wiki 会反过来提出新问题
 */
data class ParsedConflict(
	val target: Int,
	val question: String,
	val reason: String,
)

/** 建议清理的条目。删记忆不可逆，一律走用户确认 */
data class ParsedStale(
	val target: Int,
	val reason: String,
)

data class ParsedLint(
	val merges: List<ParsedMerge>,
	val conflicts: List<ParsedConflict>,
	val stale: List<ParsedStale>,
	val notes: List<String>,
	val strict: Boolean,
)

object LintPrompts {

	/** 一次体检最多接受几条建议。模型一旦开始刷建议，说明它在瞎找活干 */
	private const val MAX_PER_KIND = 8

	/** 条目太多时只送前 N 条，按重要度和命中次数排过序。整库塞进上下文会爆 */
	const val MAX_ENTRIES_PER_LINT = 40

	private val json = Json {
		ignoreUnknownKeys = true
		isLenient = true
	}

	@Serializable
	private data class MergeDto(
		val keep: Int = 0,
		val absorb: List<Int> = emptyList(),
		val body: String = "",
		val reason: String = "",
	)

	@Serializable
	private data class ConflictDto(
		val target: Int = 0,
		val question: String = "",
		val reason: String = "",
	)

	@Serializable
	private data class StaleDto(
		val target: Int = 0,
		val reason: String = "",
	)

	@Serializable
	private data class LintPayloadDto(
		val merges: List<MergeDto> = emptyList(),
		val conflicts: List<ConflictDto> = emptyList(),
		val stale: List<StaleDto> = emptyList(),
		val notes: List<String> = emptyList(),
	)

	fun lintSystem(): String = """
		你是长期记忆库的维护员。下面会给你一份记忆条目清单，每条带一个编号。
		你的活是找出结构问题，不是重写记忆。

		只输出一个 JSON 对象，不要 markdown 代码块，不要解释文字：
		{
		  "merges": [{"keep": 3, "absorb": [7], "body": "合并后的完整正文", "reason": "两条都在讲同一件事"}],
		  "conflicts": [{"target": 5, "question": "你现在住的还是之前提过的那个城市吗？", "reason": "正文里前后说了两个地方"}],
		  "stale": [{"target": 12, "reason": "三个月前的临时安排，事情已经过去"}],
		  "notes": ["反复提到家里的猫，但没有一条专门记它的条目"]
		}

		四类问题分别是：
		- merges：两条以上条目在讲同一个对象或同一件事。keep 填要保留的编号，absorb 填被并进去的编号，
		  body 要把双方的信息都保住写成一段完整正文，不许只留一边
		- conflicts：同一条目内部或跨条目出现互斥的说法。你不要自己裁决谁对，
		  写一句可以直接问用户的话，让用户来定
		- stale：明确已经失效的内容，比如过去的临时约定、已经完成的待办、已经改变的短期状态
		- notes：观察到的缺口，比如某个反复出现的人或事一直没有自己的条目

		底线，违反了这次体检就白做：
		- 宁少勿多。没把握的一律不写。一次体检出零条建议是完全正常的结果
		- 不许建议删除持久的事实、偏好、关系、重要经历，哪怕它很久没被提起
		- 不许把两条只是分类相同的条目当成可合并
		- 编号只能用清单里出现过的，不许编新编号
		- 每类最多 $MAX_PER_KIND 条
	""".trimIndent()

	/**
	 * 送去体检的条目顺序就是编号顺序，调用方拿这个列表回填序号。
	 * pinned 的条目照样送——它可能是矛盾的一方，只是最后不许被删被合。
	 */
	fun lintCandidates(entries: List<MemoryEntryEntity>): List<MemoryEntryEntity> =
		entries.sortedWith(
			compareByDescending<MemoryEntryEntity> { it.importance }
				.thenByDescending { it.hitCount }
				.thenByDescending { it.updatedAt },
		).take(MAX_ENTRIES_PER_LINT)

	fun lintUser(
		entries: List<MemoryEntryEntity>,
		nowMillis: Long,
		schema: String = "",
	): String {
		val lines = entries.mapIndexed { index, entry ->
			buildString {
				append(index + 1).append(". [").append(entry.category.name).append("] ")
				append(entry.title)
				if (entry.aliases.isNotEmpty()) {
					append("（又叫：").append(entry.aliases.replace('|', '、')).append("）")
				}
				if (entry.pinned) append("（已钉住）")
				append('\n')
				append("   摘要：").append(entry.oneLiner).append('\n')
				append("   正文：").append(entry.body).append('\n')
				append("   来源 ").append(entry.sourceCount).append(" 次")
				append("，最近用到：").append(relativeTime(entry.lastHitAt, nowMillis))
				entry.conflictNote?.let { append("\n   已标记的疑点：").append(it) }
			}
		}

		val rules = schema.trim()
		val schemaBlock = if (rules.isEmpty()) "" else {
			"\n用户自己定的记忆规则，体检时一并对照，违反规则的条目算问题：\n" + rules + "\n"
		}

		return "记忆条目清单，共 " + entries.size + " 条：\n\n" +
			lines.joinToString("\n\n") +
			"\n" + schemaBlock + "\n按系统提示的格式输出 JSON。"
	}

	/** 给模型看的时间。"73 天前"比时间戳好判断，也比日期少一层推算 */
	private fun relativeTime(at: Long, now: Long): String {
		if (at <= 0) return "还没用到过"
		val days = (now - at) / 86_400_000L
		return when {
			days <= 0 -> "今天"
			days == 1L -> "昨天"
			days < 30 -> days.toString() + " 天前"
			else -> (days / 30).toString() + " 个月前"
		}
	}

	/**
	 * entryCount 是送出去的条目数，用来卡序号范围。
	 * 模型偶尔会引用一个不存在的编号，那条建议直接扔掉——
	 * 宁可漏一条建议，也不能让它指到别的条目上去删错东西。
	 */
	fun parseLint(raw: String, entryCount: Int): ParsedLint {
		val text = raw.trim()
		if (text.isEmpty() || entryCount <= 0) return empty()

		val body = extractJsonObject(stripCodeFence(text))
		val dto = body?.let { runCatching { json.decodeFromString<LintPayloadDto>(it) }.getOrNull() }
			?: return empty()

		fun valid(index: Int) = index in 1..entryCount

		val merges = dto.merges.mapNotNull { m ->
			val absorb = m.absorb.filter { valid(it) && it != m.keep }.distinct()
			val mergedBody = m.body.trim().take(MemoryEntryEntity.MAX_BODY)
			if (!valid(m.keep) || absorb.isEmpty() || mergedBody.isEmpty()) return@mapNotNull null
			ParsedMerge(
				keep = m.keep,
				absorb = absorb,
				body = mergedBody,
				reason = m.reason.trim(),
			)
		}.take(MAX_PER_KIND)

		// 同一条目被安排进多组合并时只认第一组，否则第二组的 keep 可能已经被并掉了
		val takenByMerge = mutableSetOf<Int>()
		val dedupedMerges = merges.filter { m ->
			val touched = m.absorb + m.keep
			if (touched.any { it in takenByMerge }) false else {
				takenByMerge.addAll(touched)
				true
			}
		}

		val conflicts = dto.conflicts.mapNotNull { c ->
			val question = c.question.trim()
			if (!valid(c.target) || question.isEmpty()) return@mapNotNull null
			ParsedConflict(target = c.target, question = question, reason = c.reason.trim())
		}.distinctBy { it.target }.take(MAX_PER_KIND)

		// 已经被合并动过的条目不再列为可删，两个操作撞在同一条上会互相踩
		val stale = dto.stale.mapNotNull { s ->
			if (!valid(s.target) || s.target in takenByMerge) return@mapNotNull null
			ParsedStale(target = s.target, reason = s.reason.trim())
		}.distinctBy { it.target }.take(MAX_PER_KIND)

		val notes = dto.notes.map { it.trim() }.filter { it.isNotEmpty() }.take(MAX_PER_KIND)

		return ParsedLint(
			merges = dedupedMerges,
			conflicts = conflicts,
			stale = stale,
			notes = notes,
			strict = true,
		)
	}

	private fun empty() = ParsedLint(emptyList(), emptyList(), emptyList(), emptyList(), strict = false)

	private fun stripCodeFence(text: String): String {
		if (!text.startsWith("```")) return text
		return text.removePrefix("```json")
			.removePrefix("```JSON")
			.removePrefix("```")
			.removeSuffix("```")
			.trim()
	}

	private fun extractJsonObject(text: String): String? {
		val start = text.indexOf('{')
		val end = text.lastIndexOf('}')
		return if (start >= 0 && end > start) text.substring(start, end + 1) else null
	}
}
