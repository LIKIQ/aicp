/*
 * app/src/main/java/com/kiq/aicp/domain/memory/MemoryLinter.kt
 * 记忆体检：让模型给整个记忆库做一次结构检查
 * 职责：
 * - 拉全量条目送给模型，收回"该合并/有矛盾/已过时"的建议
 * - 把模型给的序号映回真实条目，产出待用户确认的清单
 * - 用户点确认后才真正改库，并留下操作日志
 *
 * 作用域的分工要说清楚：
 * 送给模型的清单里刻意不标条目属于哪个会话或哪个性格——模型只管看内容像不像同一件事。
 * 但合并会真的删条目，跨作用域合并会把只对某个性格可见的印象混进全局事实里，
 * 所以 scopeKey 的把关放在 MemoryRepository.mergeEntries 里做，模型提了也执行不了。
 * 矛盾提问不改数据，跨作用域反而是有价值的发现，就放它过。
 */
package com.kiq.aicp.domain.memory

import com.kiq.aicp.data.db.entity.MemoryEntryEntity
import com.kiq.aicp.data.db.entity.MemoryLogKind
import com.kiq.aicp.data.remote.LlmException
import com.kiq.aicp.data.remote.LlmMessage
import com.kiq.aicp.data.remote.LlmParams
import com.kiq.aicp.data.remote.LlmProvider
import com.kiq.aicp.data.repo.MemoryRepository
import com.kiq.aicp.domain.model.AicpSettings
import com.kiq.aicp.domain.model.ChatRole

/** 建议合并。keep 留下，absorb 被并进去后删除 */
data class MergeItem(
	val keep: MemoryEntryEntity,
	val absorb: List<MemoryEntryEntity>,
	val mergedBody: String,
	val reason: String,
)

/** 需要问用户才能定的矛盾 */
data class ConflictItem(
	val entry: MemoryEntryEntity,
	val question: String,
	val reason: String,
)

/** 建议清理的过时条目 */
data class StaleItem(
	val entry: MemoryEntryEntity,
	val reason: String,
)

data class LintReport(
	val merges: List<MergeItem>,
	val conflicts: List<ConflictItem>,
	val stale: List<StaleItem>,
	val notes: List<String>,
	/** 这次实际送去检查了几条，UI 上要让用户知道体检覆盖了多少 */
	val checkedCount: Int,
) {
	val isEmpty: Boolean
		get() = merges.isEmpty() && conflicts.isEmpty() && stale.isEmpty() && notes.isEmpty()
}

sealed interface LintOutcome {
	data class Done(val report: LintReport) : LintOutcome
	data class Failed(val reason: String, val retryable: Boolean) : LintOutcome

	/** 条目太少，体检没有意义 */
	data class TooFew(val count: Int) : LintOutcome
}

class MemoryLinter(
	private val memoryRepository: MemoryRepository,
	private val llmProvider: LlmProvider,
	private val clock: () -> Long = System::currentTimeMillis,
) {

	/**
	 * 跑一次体检。只读不写，产出的建议要用户逐条确认。
	 * 这里不加节流：体检是用户手点的，他愿意点几次就几次。
	 */
	suspend fun lint(settings: AicpSettings): LintOutcome {
		val all = memoryRepository.allEntries()
		if (all.size < MIN_ENTRIES) return LintOutcome.TooFew(all.size)

		val candidates = LintPrompts.lintCandidates(all)

		val raw = try {
			llmProvider.complete(
				messages = listOf(
					LlmMessage(ChatRole.SYSTEM, LintPrompts.lintSystem()),
					LlmMessage(
						ChatRole.USER,
						LintPrompts.lintUser(candidates, clock(), settings.memorySchema),
					),
				),
				params = LlmParams(
					model = settings.effectiveCompressModel(),
					temperature = 0.2f,
					topP = 0.9f,
					maxTokens = LINT_MAX_TOKENS,
				),
			)
		} catch (e: LlmException) {
			return LintOutcome.Failed(
				reason = e.message ?: "体检调用失败",
				retryable = e.kind.retryable,
			)
		}

		val parsed = LintPrompts.parseLint(raw, candidates.size)
		if (!parsed.strict) {
			return LintOutcome.Failed("模型没有按格式回复，这次体检作废", retryable = true)
		}

		val report = parsed.toReport(candidates)
		memoryRepository.appendLog(
			conversationId = null,
			kind = MemoryLogKind.LINT,
			summary = logLine(report),
		)
		return LintOutcome.Done(report)
	}

	/** 序号转真实条目。1-based 转下标就在这一处做，别处不许再算一遍 */
	private fun ParsedLint.toReport(candidates: List<MemoryEntryEntity>): LintReport {
		fun at(index: Int): MemoryEntryEntity? = candidates.getOrNull(index - 1)

		val mergeItems = merges.mapNotNull { m ->
			val keep = at(m.keep) ?: return@mapNotNull null
			val absorb = m.absorb.mapNotNull(::at).filterNot { it.pinned || it.id == keep.id }
			if (absorb.isEmpty()) return@mapNotNull null
			MergeItem(keep = keep, absorb = absorb, mergedBody = m.body, reason = m.reason)
		}

		val conflictItems = conflicts.mapNotNull { c ->
			at(c.target)?.let { ConflictItem(entry = it, question = c.question, reason = c.reason) }
		}

		// 钉住的条目不出现在删除建议里。用户钉过就是明确表过态了
		val staleItems = stale.mapNotNull { s ->
			at(s.target)?.takeUnless { it.pinned }?.let { StaleItem(entry = it, reason = s.reason) }
		}

		return LintReport(
			merges = mergeItems,
			conflicts = conflictItems,
			stale = staleItems,
			notes = notes,
			checkedCount = candidates.size,
		)
	}

	private fun logLine(report: LintReport): String = if (report.isEmpty) {
		"体检了 ${report.checkedCount} 条，没发现问题"
	} else {
		"体检了 ${report.checkedCount} 条：可合并 ${report.merges.size} 组，" +
			"待确认 ${report.conflicts.size} 处，可清理 ${report.stale.size} 条"
	}

	/**
	 * 用户确认某组合并。返回是否真的动了库。
	 * 会失败的正常情况：条目在体检之后被别的操作删掉或钉住了，
	 * 这时候放弃这条建议就好，不要报错惊动用户。
	 */
	suspend fun applyMerge(item: MergeItem): Boolean {
		val removed = memoryRepository.mergeEntries(
			keepId = item.keep.id,
			absorbIds = item.absorb.map { it.id },
			mergedBody = item.mergedBody,
		)
		if (removed.isEmpty()) return false

		memoryRepository.appendLog(
			conversationId = null,
			kind = MemoryLogKind.MANUAL,
			summary = "把「${removed.joinToString("」「")}」并进了「${item.keep.title}」",
			touchedTitles = listOf(item.keep.title) + removed,
		)
		return true
	}

	/** 用户确认清理某条过时条目 */
	suspend fun applyDelete(item: StaleItem): Boolean {
		if (item.entry.pinned) return false
		memoryRepository.deleteEntry(item.entry.id)
		memoryRepository.appendLog(
			conversationId = null,
			kind = MemoryLogKind.MANUAL,
			summary = "清理了过时条目「${item.entry.title}」",
			touchedTitles = listOf(item.entry.title),
		)
		return true
	}

	/**
	 * 用户看过这处矛盾了（不管他是去改条目还是心里有数），把标记清掉。
	 * 不清的话每次进记忆页都挂着一个红标，看久了就当背景板了。
	 */
	suspend fun dismissConflict(item: ConflictItem) {
		memoryRepository.clearConflictNote(item.entry.id)
		memoryRepository.appendLog(
			conversationId = null,
			kind = MemoryLogKind.MANUAL,
			summary = "确认了「${item.entry.title}」的疑点",
			touchedTitles = listOf(item.entry.title),
		)
	}

	companion object {
		/** 少于这个数没什么可检的，直接告诉用户攒够再来 */
		const val MIN_ENTRIES = 5

		/** 建议里含合并后的完整正文，token 要给够，不然 JSON 被截断整次作废 */
		const val LINT_MAX_TOKENS = 2_000
	}
}

