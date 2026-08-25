// app/src/main/java/com/kiq/aicp/ui/memory/MemoryViewModel.kt
// 记忆管理页状态。v6 起页面看的是 wiki 条目（memory_entries），不再是旧的扁平卡片。
//
// 这一页的存在本身就是产品承诺的一部分：记忆是自动抽取的，那用户必须能看见、能改、能删。
// 钉住（pinned）的语义是"以后自动整理不许再动这条"，MemoryRepository.upsertEntries 里有对应的跳过逻辑；
// 手动编辑等于用户认可了当前这版，editEntry 会顺手把 conflictNote 清掉。
//
// 页面要的正好是 wiki 的三层，所以这里合成一份 uiState 端上去：
// - schema：用户自己写的记忆规则，整理时注入提示词，来自 SettingsStore
// - entries：条目，页面主体
// - logs：操作时间线，回答"这条记忆是哪一轮、因为什么变成现在这样的"
//
// schema 走"草稿 + 保存按钮"，跟 SettingsViewModel 里接口配置那套一致：它是要拼进提示词的长文本，
// 每敲一个字符写一次 DataStore 除了费电没别的用，而且半截句子被存下来会立刻影响下一次整理。
// 草稿用 null 表示"跟随已保存值"，省掉一个额外的 dirty 标记。
//
// 卡片版的 setPinned/edit/delete 换成了条目版：这个页面是它们唯一的调用方，
// 页面不再显示卡片之后留着就是永远不会执行的代码。MemoryRepository 里卡片那套 API 一行没动，
// 旧表仍是完整的历史归档。
//
// 体检（MemoryLinter）走"建议 → 用户逐条点头 → 才改库"：模型对整个记忆库提意见的准确率不足以直接落库，
// 而合并和清理都会真的删条目。所以 report 是纯内存态，应用一条就从 report 里摘掉一条，
// 免得用户对着同一条建议连点两次。体检进度和待确认的批量清理都攒在 MemoryActionState 里，
// 跟"记忆本身"分开 —— 同 SettingsViewModel 里的 backup 那条流。

package com.kiq.aicp.ui.memory

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.kiq.aicp.AicpApplication
import com.kiq.aicp.data.db.entity.MemoryEntryEntity
import com.kiq.aicp.data.db.entity.MemoryLogEntity
import com.kiq.aicp.data.db.entity.MemoryLogKind
import com.kiq.aicp.data.prefs.SettingsStore
import com.kiq.aicp.data.repo.MemoryRepository
import com.kiq.aicp.domain.memory.ConflictItem
import com.kiq.aicp.domain.memory.LintOutcome
import com.kiq.aicp.domain.memory.LintReport
import com.kiq.aicp.domain.memory.MemoryLinter
import com.kiq.aicp.domain.memory.MergeItem
import com.kiq.aicp.domain.memory.StaleItem
import com.kiq.aicp.domain.model.MemoryCardType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 一次性动作的中间状态：体检的进度与结果，以及等用户点头的批量清理。
 * 它们不是"记忆的当前值"，混进上面那几个字段会让 uiState 越来越不像记忆本身。
 */
private data class MemoryActionState(
	val linting: Boolean = false,
	val report: LintReport? = null,
	/** 非 null 表示确认框正开着，里面是这次会被删掉的条目 */
	val pendingPrune: List<MemoryEntryEntity>? = null,
)

data class MemoryUiState(
	val entries: List<MemoryEntryEntity> = emptyList(),
	val logs: List<MemoryLogEntity> = emptyList(),
	/** 已经存进 DataStore 的记忆规则 */
	val savedSchema: String = "",
	/** null = 跟随已保存值；非 null 才是用户改过还没保存的内容 */
	val schemaDraft: String? = null,
	val message: String? = null,
	val linting: Boolean = false,
	/** 体检结果，纯内存态：关掉就没了，要看得再体检一次 */
	val report: LintReport? = null,
	/** 待确认的冷记忆清理清单 */
	val pendingPrune: List<MemoryEntryEntity>? = null,
) {
	val schemaField: String get() = schemaDraft ?: savedSchema

	val schemaDirty: Boolean get() = schemaDraft != null && schemaDraft != savedSchema

	val isEmpty: Boolean get() = entries.isEmpty()

	/** 有几条条目带着未处理的矛盾。数量摆在顶上，不然用户得一路滑到底才发现 */
	val conflictCount: Int get() = entries.count { !it.conflictNote.isNullOrBlank() }

	/**
	 * 按分类切一份。分组在这儿做而不是在 UI 里：
	 * 列表是"五个分区各自成段"的结构，UI 只负责按固定顺序问每个分类要数据。
	 */
	fun entriesOf(category: MemoryCardType): List<MemoryEntryEntity> =
		entries.filter { it.category == category }
}

class MemoryViewModel(
	private val memoryRepository: MemoryRepository,
	private val settingsStore: SettingsStore,
	private val memoryLinter: MemoryLinter,
) : ViewModel() {

	private val schemaDraft = MutableStateFlow<String?>(null)
	private val message = MutableStateFlow<String?>(null)
	private val actions = MutableStateFlow(MemoryActionState())

	/**
	 * 库里的数据加输入草稿。
	 * 跟 actions 分两级 combine 不是为了好看：具名的 combine 最多接五路流，
	 * 硬凑成一层就得退化到 vararg 版本，那个版本给的是 Array<Any?>，每个字段都要强转。
	 */
	private val base: Flow<MemoryUiState> = combine(
		memoryRepository.observeAllEntries(),
		memoryRepository.observeRecentLogs(),
		settingsStore.settings,
		schemaDraft,
		message,
	) { entries, logs, settings, draft, msg ->
		MemoryUiState(
			entries = entries,
			logs = logs,
			savedSchema = settings.memorySchema,
			schemaDraft = draft,
			message = msg,
		)
	}

	val uiState: StateFlow<MemoryUiState> = combine(base, actions) { state, action ->
		state.copy(
			linting = action.linting,
			report = action.report,
			pendingPrune = action.pendingPrune,
		)
	}.stateIn(
		scope = viewModelScope,
		started = SharingStarted.WhileSubscribed(5_000),
		initialValue = MemoryUiState(),
	)

	fun dismissMessage() {
		message.value = null
	}

	// ---------------- 记忆规则（wiki 第三层 schema） ----------------

	/** 输入时就按上限截断，别等到保存时才让用户发现后面几十个字被吞了 */
	fun onSchemaChange(text: String) {
		schemaDraft.value = text.take(SettingsStore.MAX_MEMORY_SCHEMA)
	}

	fun saveSchema() {
		val draft = schemaDraft.value ?: return
		viewModelScope.launch {
			runCatching { settingsStore.setMemorySchema(draft) }
				.onSuccess {
					schemaDraft.value = null
					message.value = if (draft.isBlank()) {
						"记忆规则已清空，之后只按内置约定整理"
					} else {
						"记忆规则已保存，下次整理记忆时生效"
					}
				}
				.onFailure { message.value = "记忆规则没存上：${reasonOf(it)}" }
		}
	}

	// ---------------- 条目 ----------------

	fun setPinned(entry: MemoryEntryEntity, pinned: Boolean) {
		viewModelScope.launch {
			runCatching { memoryRepository.setEntryPinned(entry.id, pinned) }
				.onSuccess {
					message.value = if (pinned) {
						"已钉住「${entry.title}」，自动整理不会再改它"
					} else {
						"已取消钉住「${entry.title}」"
					}
				}
				.onFailure { message.value = "钉不上「${entry.title}」：${reasonOf(it)}" }
		}
	}

	fun edit(entry: MemoryEntryEntity, body: String, importance: Int) {
		viewModelScope.launch {
			runCatching { memoryRepository.editEntry(entry, body, importance) }
				.onSuccess {
					appendManualLog(
						conversationId = entry.conversationId,
						summary = "手动改了「${entry.title}」",
						titles = listOf(entry.title),
					)
					// 用户动过手的条目，体检报告里针对它的疑问就算处理过了，
					// 不摘掉的话报告上会一直挂着一个已经解决的问题
					dropConflict(entry.id)
					// 冲突标记是 editEntry 顺手清的，得说一声，不然用户会以为那个警示自己消失了
					message.value = if (entry.conflictNote.isNullOrBlank()) {
						"已更新「${entry.title}」"
					} else {
						"已更新「${entry.title}」，冲突标记也清掉了"
					}
				}
				.onFailure { message.value = "改不了「${entry.title}」：${reasonOf(it)}" }
		}
	}

	fun delete(entry: MemoryEntryEntity) {
		viewModelScope.launch {
			runCatching { memoryRepository.deleteEntry(entry.id) }
				.onSuccess {
					appendManualLog(
						conversationId = entry.conversationId,
						summary = "删掉了「${entry.title}」",
						titles = listOf(entry.title),
					)
					message.value = "已删除「${entry.title}」"
				}
				.onFailure { message.value = "删不掉「${entry.title}」：${reasonOf(it)}" }
		}
	}

	/**
	 * 冷条目淘汰的第一步：先查出这次会删掉哪些，交给 UI 弹确认框。
	 *
	 * 判据跟 MemoryRepository.pruneCold 给卡片用的那套一模一样：非钉住、重要度不超过 2、
	 * 而且超过 60 天没被拼进上下文。差别只在执行方式 —— Repository 现有的 pruneCold 清的是
	 * 已经冻结成归档的旧卡片表，条目版的批量删除（MemoryDao.pruneColdEntries）还没在 Repository 上开口，
	 * 所以这里按同样的判据挑出来逐条 deleteEntry。条目是十位数量级，逐条删的代价吃得下；
	 * 等 Repository 补上入口再换回一条 SQL。
	 *
	 * 快照走 allEntries() 现取而不是读 uiState.value：
	 * stateIn 在没人订阅的那五秒里值是旧的，拿它当删除依据会漏删甚至删错。
	 *
	 * lastHitAt = 0（从来没被用过）也算冷，这是照抄卡片时代的语义，不在 UI 层自己改规则。
	 */
	fun requestPruneCold() {
		viewModelScope.launch {
			val idleBefore = System.currentTimeMillis() - COLD_IDLE_DAYS * 24L * 60 * 60 * 1000
			val cold = runCatching {
				memoryRepository.allEntries().filter {
					!it.pinned && it.importance <= COLD_MAX_IMPORTANCE && it.lastHitAt < idleBefore
				}
			}.getOrElse {
				message.value = "读不到记忆列表：${reasonOf(it)}"
				return@launch
			}

			if (cold.isEmpty()) {
				message.value = "没有需要清理的冷记忆"
				return@launch
			}
			actions.update { it.copy(pendingPrune = cold) }
		}
	}

	fun cancelPruneCold() {
		actions.update { it.copy(pendingPrune = null) }
	}

	/** 用户在确认框上点了删除。列表用弹框时那份快照，不重新查 —— 他确认的就是看到的那几条 */
	fun confirmPruneCold() {
		val cold = actions.value.pendingPrune ?: return
		actions.update { it.copy(pendingPrune = null) }
		viewModelScope.launch {
			runCatching { cold.forEach { memoryRepository.deleteEntry(it.id) } }
				.onSuccess {
					appendManualLog(
						conversationId = null,
						summary = "清理冷记忆，删掉 ${cold.size} 条",
						titles = cold.map { it.title },
					)
					message.value = "清掉了 ${cold.size} 条长期没用到的低重要度记忆"
				}
				.onFailure { message.value = "清理没做完：${reasonOf(it)}" }
		}
	}

	// ---------------- 体检（wiki 的 lint） ----------------

	/**
	 * 跑一次体检。整库送模型，回来的是建议不是既成事实。
	 * running 期间直接忽略重复点击：一次要花好几秒，连点两下就是两笔 token 换一份几乎一样的报告。
	 */
	fun runLint() {
		if (actions.value.linting) return
		viewModelScope.launch {
			actions.update { it.copy(linting = true) }
			val outcome = runCatching { memoryLinter.lint(settingsStore.current()) }
				.getOrElse { LintOutcome.Failed(reasonOf(it), retryable = true) }
			actions.update { it.copy(linting = false) }

			when (outcome) {
				is LintOutcome.Done -> actions.update { it.copy(report = outcome.report) }

				is LintOutcome.TooFew -> message.value =
					"记忆还太少（${outcome.count} 条），攒够 ${MemoryLinter.MIN_ENTRIES} 条再体检"

				is LintOutcome.Failed -> message.value = buildString {
					append("体检没跑完：").append(outcome.reason)
					if (outcome.retryable) append("，可以再试一次")
				}
			}
		}
	}

	fun dismissReport() {
		actions.update { it.copy(report = null) }
	}

	/** 合并会真的删条目，所以 UI 那边还挂了一层二次确认，这里只管执行 */
	fun applyMerge(item: MergeItem) {
		viewModelScope.launch {
			runCatching { memoryLinter.applyMerge(item) }
				.onSuccess { applied ->
					dropMerge(item)
					message.value = if (applied) {
						"已把 ${item.absorb.size} 条并进「${item.keep.title}」"
					} else {
						"这条建议过期了：涉及的条目已经被改过或钉住了"
					}
				}
				.onFailure { message.value = "合并没成功：${reasonOf(it)}" }
		}
	}

	/** 忽略一条合并建议。只从报告里摘掉，库一点没动 */
	fun ignoreMerge(item: MergeItem) {
		dropMerge(item)
	}

	fun applyDelete(item: StaleItem) {
		viewModelScope.launch {
			runCatching { memoryLinter.applyDelete(item) }
				.onSuccess { applied ->
					dropStale(item)
					message.value = if (applied) {
						"已删除「${item.entry.title}」"
					} else {
						"这条建议过期了：条目已经被钉住或删掉了"
					}
				}
				.onFailure { message.value = "删不掉「${item.entry.title}」：${reasonOf(it)}" }
		}
	}

	/** 保留：用户觉得这条还有用，报告里摘掉就完事 */
	fun keepStale(item: StaleItem) {
		dropStale(item)
	}

	fun dismissConflict(item: ConflictItem) {
		viewModelScope.launch {
			runCatching { memoryLinter.dismissConflict(item) }
				.onSuccess {
					dropConflict(item.entry.id)
					message.value = "记下了，「${item.entry.title}」的疑点标记已清掉"
				}
				.onFailure { message.value = "标记没清掉：${reasonOf(it)}" }
		}
	}

	// 摘建议一律比引用：报告里的实例就是 UI 回传的那一个，
	// 用 equals 的话两条内容完全相同的建议会被一起摘掉
	private fun dropMerge(item: MergeItem) {
		actions.update { state ->
			state.copy(
				report = state.report?.let { r ->
					r.copy(merges = r.merges.filterNot { it === item })
				},
			)
		}
	}

	private fun dropStale(item: StaleItem) {
		actions.update { state ->
			state.copy(
				report = state.report?.let { r ->
					r.copy(stale = r.stale.filterNot { it === item })
				},
			)
		}
	}

	/** 按条目 id 摘，因为手动编辑那条路也要用：改过的条目，报告里的疑问就算处理过了 */
	private fun dropConflict(entryId: Long) {
		actions.update { state ->
			state.copy(
				report = state.report?.let { r ->
					r.copy(conflicts = r.conflicts.filterNot { it.entry.id == entryId })
				},
			)
		}
	}

	/**
	 * 手动操作也记一笔时间线。
	 *
	 * MemoryLogKind.MANUAL 到目前为止没人写过 —— 后台只写 INGEST，而"用户自己改的"恰恰是
	 * 事后最难还原的一类改动（自动整理有对话可查，手动改没有任何痕迹）。
	 * 写日志失败就咽下去：它是辅助线索，不该把一次已经成功的修改回报成失败。
	 */
	private suspend fun appendManualLog(conversationId: Long?, summary: String, titles: List<String>) {
		runCatching {
			memoryRepository.appendLog(
				conversationId = conversationId,
				kind = MemoryLogKind.MANUAL,
				summary = summary,
				touchedTitles = titles,
			)
		}
	}

	/** 异常转人话。Room / DataStore 抛出来的 message 多半是英文，但有总比"未知错误"强 */
	private fun reasonOf(error: Throwable): String =
		error.message?.takeIf { it.isNotBlank() } ?: "未知错误"

	companion object {
		/** 冷条目判据，跟 MemoryRepository.pruneCold 的默认值保持一致 */
		private const val COLD_MAX_IMPORTANCE = 2
		private const val COLD_IDLE_DAYS = 60

		val Factory = viewModelFactory {
			initializer {
				val c = AicpApplication.container()
				MemoryViewModel(c.memoryRepository, c.settingsStore, c.memoryLinter)
			}
		}
	}
}
