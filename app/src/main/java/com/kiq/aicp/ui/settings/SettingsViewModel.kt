// app/src/main/java/com/kiq/aicp/ui/settings/SettingsViewModel.kt
// 设置页状态。
//
// 接口配置（Base URL / Key / 模型）走"本地草稿 + 保存按钮"，不做输入即存：
// API Key 每敲一个字符就加密写盘既费电又没意义，而且中途的半截 Key 存下来会让"测试连接"给出误导结论。
// 滑块和开关类设置反过来，即时生效更顺手。
//
// 草稿字段用 null 表示"跟随已保存值"，非 null 才是用户改过的内容 —— 这样不用额外维护 dirty 标记。
//
// 备份与恢复的状态单独攒在 backup 这条流里：它是一次性动作的进度和结果（转圈、确认框、成败提示），
// 跟"设置项的当前值"不是一回事，混进 AicpSettings 只会让那个 data class 越来越不像设置。
//
// 口令一律用 CharArray 往下传，拿到就用、用完立刻 fill 抹掉。抹不掉的是输入框里那份 String
// （Compose 的 TextField 只吃 String），这一段的暴露面认了，但没必要让它顺着调用链一路留下去。

package com.kiq.aicp.ui.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.kiq.aicp.AicpApplication
import com.kiq.aicp.data.attach.AttachmentStore
import com.kiq.aicp.data.backup.BackupManager
import com.kiq.aicp.data.backup.BackupPasswordException
import com.kiq.aicp.data.backup.DataSummary
import com.kiq.aicp.data.backup.ExportSummary
import com.kiq.aicp.data.backup.StartupRestoreOutcome
import com.kiq.aicp.data.prefs.SettingsStore
import com.kiq.aicp.data.remote.LlmException
import com.kiq.aicp.data.remote.LlmMessage
import com.kiq.aicp.data.remote.LlmParams
import com.kiq.aicp.data.remote.LlmProvider
import com.kiq.aicp.data.remote.UpdateChecker
import com.kiq.aicp.data.remote.UpdateInfo
import com.kiq.aicp.data.remote.UpdateResult
import com.kiq.aicp.domain.config.ConfigCodeException
import com.kiq.aicp.domain.config.ConfigCodec
import com.kiq.aicp.domain.config.ConfigDiff
import com.kiq.aicp.domain.model.AicpSettings
import com.kiq.aicp.domain.model.ChatRole
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class EndpointDraft(
	val baseUrl: String? = null,
	val apiKey: String? = null,
	val model: String? = null,
	val compressModel: String? = null,
) {
	val touched: Boolean
		get() = baseUrl != null || apiKey != null || model != null || compressModel != null
}

sealed interface ConnectionTest {
	data object Running : ConnectionTest
	data class Ok(val reply: String) : ConnectionTest
	data class Fail(val message: String, val retryable: Boolean) : ConnectionTest
}

/** 备份相关的在跑任务。导出和解压都可能几秒，UI 靠它决定禁用按钮和转圈文案 */
enum class BackupJob { EXPORTING, CHECKING, RESTORING, CANCELLING }

/** 待确认的恢复：用户已经选了哪份 zip，加上"会被替换掉的东西有多少" */
data class RestoreConfirm(val zip: Uri, val summary: DataSummary)

/** 导出前的口令环节。target 是 SAF 已经建好的那个文件，口令填不填都要经过这一步 */
data class ExportPasswordPrompt(val target: Uri)

/**
 * 恢复时的口令环节。
 * error 单独挂在这里而不是走 BackupUiState.error：口令输错要能在框里原地重来，
 * 提示得贴在输入框下面，而不是把人打回文件选择那一步 —— 重选文件比重输口令烦得多。
 */
data class RestorePasswordPrompt(val zip: Uri, val error: String? = null)

data class BackupUiState(
	val job: BackupJob? = null,
	val confirm: RestoreConfirm? = null,
	val exportPrompt: ExportPasswordPrompt? = null,
	val restorePrompt: RestorePasswordPrompt? = null,
	/** 备份已解好、等重启的状态，来自 DataStore 里的 restore_pending */
	val restorePending: Boolean = false,
	val notice: String? = null,
	val error: String? = null,
) {
	val busy: Boolean get() = job != null
}

/** 配置码的在跑任务。编码和解码都要过 PBKDF2，不是瞬时的 */
enum class ConfigCodeJob { ENCODING, DECODING, APPLYING }

/** 生成出来的配置码。encrypted 决定要不要提醒"这段字里有你的 Key" */
data class GeneratedConfigCode(val code: String, val encrypted: Boolean)

/**
 * 导入面板里由 ViewModel 管的那部分。
 * 粘贴进来的正文和口令留在 Compose 本地 state 里 —— 解码失败时那两样必须原样留着，
 * 让他改个字符就能重来，而不是回另一台手机上再复制一遍。
 */
data class ConfigImportSheet(val error: String? = null)

/** 导入前的变化清单。空清单不弹框，由 ViewModel 直接给一句"跟现在一样" */
data class ConfigImportConfirm(
	val incoming: AicpSettings,
	val changes: List<String>,
	val overwritesApiKey: Boolean,
)

data class ConfigCodeUiState(
	val job: ConfigCodeJob? = null,
	val exportPrompt: Boolean = false,
	val generated: GeneratedConfigCode? = null,
	val importSheet: ConfigImportSheet? = null,
	val confirm: ConfigImportConfirm? = null,
	val notice: String? = null,
	val error: String? = null,
) {
	val busy: Boolean get() = job != null
}

/**
 * 检查更新的一次性状态。
 *
 * available 非空就弹窗，notice 只在手动检查时才有值 ——
 * 自动检查失败是常态（代理挂了、限流、没网），拿这个去打扰用户等于天天报错。
 */
data class UpdateUiState(
	val checking: Boolean = false,
	val available: UpdateInfo? = null,
	val notice: String? = null,
)

data class SettingsUiState(
	val settings: AicpSettings = AicpSettings(),
	val draft: EndpointDraft = EndpointDraft(),
	val test: ConnectionTest? = null,
	val savedHint: Boolean = false,
	val backup: BackupUiState = BackupUiState(),
	val configCode: ConfigCodeUiState = ConfigCodeUiState(),
	val update: UpdateUiState = UpdateUiState(),
) {
	val baseUrlField: String get() = draft.baseUrl ?: settings.baseUrl
	val modelField: String get() = draft.model ?: settings.model
	val compressModelField: String get() = draft.compressModel ?: settings.compressModel
	val apiKeyField: String get() = draft.apiKey ?: ""

	/** 已保存的 Key 只给脱敏展示，永远不回填到输入框 */
	val savedKeyHint: String get() = AicpSettings.maskKey(settings.apiKey)

	val canSave: Boolean get() = draft.touched
	val canTest: Boolean get() = settings.hasEndpoint && test !is ConnectionTest.Running
}

class SettingsViewModel(
	private val settingsStore: SettingsStore,
	private val llmProvider: LlmProvider,
	private val backupManager: BackupManager,
	private val updateChecker: UpdateChecker,
) : ViewModel() {

	private val draft = MutableStateFlow(EndpointDraft())
	private val test = MutableStateFlow<ConnectionTest?>(null)
	private val savedHint = MutableStateFlow(false)
	private val backup = MutableStateFlow(BackupUiState())
	private val configCode = MutableStateFlow(ConfigCodeUiState())
	private val update = MutableStateFlow(UpdateUiState())

	/**
	 * 三条一次性动作的流先合成一条再进外层 combine。
	 * 不是为了好看：combine 的类型化重载最多接五条，直接摊平就得退回不带类型的数组版本，
	 * 那会把五个参数变成 Array<Any?> 一路强转下去。
	 */
	private val transientFlows: Flow<Triple<BackupUiState, ConfigCodeUiState, UpdateUiState>> =
		combine(backup, configCode, update) { b, c, u -> Triple(b, c, u) }

	val uiState: StateFlow<SettingsUiState> =
		combine(
			settingsStore.settings,
			draft,
			test,
			savedHint,
			transientFlows,
		) { settings, d, t, hint, (b, c, u) ->
			SettingsUiState(
				settings = settings,
				draft = d,
				test = t,
				savedHint = hint,
				backup = b,
				configCode = c,
				update = u,
			)
		}.stateIn(
			scope = viewModelScope,
			started = SharingStarted.WhileSubscribed(5_000),
			initialValue = SettingsUiState(),
		)

	init {
		// restore_pending 没并进 AicpSettings：那个 data class 描述的是"用户配了什么"，
		// 而这是一次恢复动作的中间状态，塞进去以后每个读设置的地方都得跳过它
		viewModelScope.launch {
			settingsStore.restorePending.collect { pending ->
				backup.update { it.copy(restorePending = pending) }
			}
		}
		reportStartupRestore()
		// 进设置页顺手查一次。节流在 UpdateChecker 里，24 小时内重复进来不会真发请求
		checkUpdate(manual = false)
	}

	/**
	 * 检查更新。
	 *
	 * 自动检查只在"真有新版本"时才出声：失败、限流、节流一律咽掉。
	 * 手动检查相反，用户点了按钮就得给个回音，哪怕是"查不动"。
	 */
	fun checkUpdate(manual: Boolean) {
		if (update.value.checking) return
		viewModelScope.launch {
			update.update { it.copy(checking = true, notice = null) }
			val result = runCatching { updateChecker.check(manual) }
				.getOrElse { UpdateResult.Failed(it.message ?: "检查更新失败", retryable = true) }
			update.update { it.copy(checking = false) }

			when (result) {
				is UpdateResult.Available -> update.update { it.copy(available = result.info) }

				is UpdateResult.UpToDate -> if (manual) {
					update.update {
						it.copy(
							notice = "已经是最新版本（当前 ${result.currentVersion}，" +
								"仓库最新 ${result.latestTag}）",
						)
					}
				}

				is UpdateResult.Failed -> if (manual) {
					update.update {
						it.copy(
							notice = buildString {
								append("没查到更新：").append(result.reason)
								if (result.retryable) append("，可以再试一次")
							},
						)
					}
				}

				UpdateResult.Skipped -> Unit
			}
		}
	}

	fun dismissUpdateDialog() {
		update.update { it.copy(available = null) }
	}

	fun dismissUpdateNotice() {
		update.update { it.copy(notice = null) }
	}

	/**
	 * 上次冷启动搬文件的结果只在进程内存着（那时候没有 DataStore 可写），
	 * 设置页一起来就取一次并清空。取不到是常态 —— 大多数启动根本没有待恢复的备份。
	 */
	private fun reportStartupRestore() {
		when (val outcome = BackupManager.consumeStartupOutcome()) {
			is StartupRestoreOutcome.Done -> backup.update {
				it.copy(
					notice = "上次启动完成了恢复，搬回 ${outcome.movedCount} 项数据（耗时 ${outcome.elapsedMs} ms）。" +
						"API Key 不在备份里，记得重新填一次。",
				)
			}

			is StartupRestoreOutcome.Failed -> backup.update {
				it.copy(
					error = "上次启动恢复失败，已经回滚到恢复前的数据，什么都没丢：${outcome.reason}",
				)
			}

			null -> Unit
		}
	}

	fun onBaseUrlChange(value: String) {
		draft.value = draft.value.copy(baseUrl = value)
		clearTransient()
	}

	fun onApiKeyChange(value: String) {
		draft.value = draft.value.copy(apiKey = value)
		clearTransient()
	}

	fun onModelChange(value: String) {
		draft.value = draft.value.copy(model = value)
		clearTransient()
	}

	fun onCompressModelChange(value: String) {
		draft.value = draft.value.copy(compressModel = value)
		clearTransient()
	}

	fun discardDraft() {
		draft.value = EndpointDraft()
		clearTransient()
	}

	fun saveEndpoint() {
		val current = uiState.value
		val d = current.draft
		if (!d.touched) return

		viewModelScope.launch {
			settingsStore.setEndpoint(
				baseUrl = d.baseUrl ?: current.settings.baseUrl,
				model = d.model ?: current.settings.model,
			)
			// 输入框留空表示"不改动已存的 Key"；想清空要按清除按钮走 clearApiKey()
			d.apiKey?.takeIf { it.isNotBlank() }?.let { settingsStore.setApiKey(it) }
			d.compressModel?.let { settingsStore.setCompressModel(it) }

			draft.value = EndpointDraft()
			savedHint.value = true
		}
	}

	fun clearApiKey() {
		viewModelScope.launch {
			settingsStore.setApiKey("")
			draft.value = draft.value.copy(apiKey = null)
			test.value = null
		}
	}

	fun testConnection() {
		if (!uiState.value.canTest) return
		test.value = ConnectionTest.Running
		viewModelScope.launch {
			val settings = settingsStore.current()
			val result = runCatching {
				llmProvider.complete(
					messages = listOf(
						LlmMessage(ChatRole.SYSTEM, "只回复两个字：收到"),
						LlmMessage(ChatRole.USER, "在吗"),
					),
					params = LlmParams(model = settings.model, temperature = 0f, topP = 1f, maxTokens = 32),
				)
			}
			test.value = result.fold(
				onSuccess = { ConnectionTest.Ok(it.take(60).ifBlank { "(空回复)" }) },
				onFailure = { e ->
					val kind = (e as? LlmException)?.kind
					ConnectionTest.Fail(
						message = e.message ?: "未知错误",
						retryable = kind?.retryable ?: false,
					)
				},
			)
		}
	}

	fun dismissTest() {
		test.value = null
	}

	fun setAutoCompress(enabled: Boolean) = launchStore { setAutoCompress(enabled) }

	fun setDynamicColor(enabled: Boolean) = launchStore { setDynamicColor(enabled) }

	fun updateTuning(
		contextBudgetTokens: Int? = null,
		keepRecentMessages: Int? = null,
		compressTriggerTokens: Int? = null,
		compressTriggerCount: Int? = null,
		summaryMergeThreshold: Int? = null,
		memoryCardLimit: Int? = null,
		groupMaxSpeakersPerTurn: Int? = null,
	) = launchStore {
		setMemoryTuning(
			contextBudgetTokens = contextBudgetTokens,
			keepRecentMessages = keepRecentMessages,
			compressTriggerTokens = compressTriggerTokens,
			compressTriggerCount = compressTriggerCount,
			summaryMergeThreshold = summaryMergeThreshold,
			memoryCardLimit = memoryCardLimit,
			groupMaxSpeakersPerTurn = groupMaxSpeakersPerTurn,
		)
	}

	fun updateHumanizeTuning(
		enabled: Boolean? = null,
		maxSegments: Int? = null,
		msPerChar: Int? = null,
		readDelayMs: Long? = null,
	) = launchStore {
		setHumanizeTuning(
			enabled = enabled,
			maxSegments = maxSegments,
			msPerChar = msPerChar,
			readDelayMs = readDelayMs,
		)
	}

	/**
	 * 主动搭话参数。
	 *
	 * 后台排程不在这里碰：AicpApplication 一直订阅着 settingsStore，写进去它自己会去注册/取消 WorkManager。
	 * 设置页再插一手就成了两个地方管同一个排程，用户没打开过设置页的那次冷启动还会漏掉。
	 */
	fun updateProactiveTuning(
		enabled: Boolean? = null,
		idleMinutes: Int? = null,
		pushEnabled: Boolean? = null,
		dailyLimit: Int? = null,
		quietStart: Int? = null,
		quietEnd: Int? = null,
	) = launchStore {
		setProactiveTuning(
			enabled = enabled,
			idleMinutes = idleMinutes,
			pushEnabled = pushEnabled,
			dailyLimit = dailyLimit,
			quietStart = quietStart,
			quietEnd = quietEnd,
		)
	}

	fun resetTuning() = launchStore { resetTuning() }

	// ---------------- 备份与恢复 ----------------

	/**
	 * SAF 已经建好了目标文件，先问口令再打包。
	 *
	 * 中间这一步不能省：加不加密是用户当场的决定，而且这是唯一一次能把
	 * "口令丢了这份备份就永久打不开"讲给他听的时机 —— 事后再说已经没意义了。
	 */
	fun onExportTargetChosen(target: Uri) {
		if (uiState.value.backup.busy) return
		backup.update { it.copy(exportPrompt = ExportPasswordPrompt(target), notice = null, error = null) }
	}

	fun dismissExportPrompt() {
		backup.update { it.copy(exportPrompt = null) }
	}

	/** 口令框上点了确认。password 为 null 表示用户选择不加密 */
	fun confirmExport(password: CharArray?) {
		val prompt = uiState.value.backup.exportPrompt ?: return
		backup.update { it.copy(exportPrompt = null) }
		exportTo(prompt.target, password)
	}

	/** 真正写盘。写失败也不弹异常，转成一句中文摆在分区里 */
	fun exportTo(target: Uri, password: CharArray? = null) {
		if (uiState.value.backup.busy) return
		startJob(BackupJob.EXPORTING)
		viewModelScope.launch {
			val encrypted = password != null && password.isNotEmpty()
			val result = try {
				runCatching { backupManager.export(target, password) }
			} finally {
				// 口令在内存里多留一秒都没必要。输入框里那份 String 清不掉（Compose 的 TextField
				// 只吃 String），但传到这一层的 CharArray 用完就能抹
				password?.fill('\u0000')
			}
			result.fold(
				onSuccess = { summary ->
					finishJob(notice = exportNotice(summary, encrypted))
				},
				onFailure = { finishJob(error = failureText("导出失败", it)) },
			)
		}
	}

	private fun exportNotice(summary: ExportSummary, encrypted: Boolean): String {
		val head = "导出好了：${summary.fileCount} 个文件，原始体积 " +
			"${AttachmentStore.humanSize(summary.byteSize)}。"
		val tail = if (encrypted) {
			"这份备份是加密的，恢复时要输入刚才那个口令。口令只在你手里 —— 我没有保存，也没有任何找回办法，" +
				"忘了这份备份就永久打不开了。"
		} else {
			"这份备份没有口令，任何解压软件都能直接打开看。"
		}
		return "$head$tail\nAPI Key 不在备份里 —— 它的密钥绑当前设备，换机也解不开，恢复后要重新填一次。"
	}

	/**
	 * 用户选好了一份 zip。这里先数一遍当前有多少东西会被替换，再弹确认框 ——
	 * 确认放在选文件之后是刻意的：让最后一道闸门带上"选的是哪份、要覆盖多少"这两个具体信息，
	 * 反过来先确认再选文件的话，用户点"确定"时手里还什么都没有。
	 */
	fun onBackupZipPicked(zip: Uri) {
		if (uiState.value.backup.busy) return
		startJob(BackupJob.CHECKING)
		viewModelScope.launch {
			runCatching { backupManager.dataSummary() }.fold(
				onSuccess = { summary ->
					backup.update {
						it.copy(job = null, confirm = RestoreConfirm(zip, summary), notice = null, error = null)
					}
				},
				onFailure = { finishJob(error = failureText("读不出当前数据量，恢复没有开始", it)) },
			)
		}
	}

	fun dismissRestoreConfirm() {
		backup.update { it.copy(confirm = null) }
	}

	/**
	 * 确认框上点了"覆盖"。这一步只解压到暂存目录并置标记，真正换文件要等下次启动。
	 *
	 * 探测口令算在"恢复中"这段 loading 里：它要读文件头，快但不是零耗时，
	 * 空档里让按钮活着就会有人连点两次。
	 */
	fun confirmRestore() {
		val pending = uiState.value.backup.confirm ?: return
		backup.update { it.copy(job = BackupJob.RESTORING, confirm = null, notice = null, error = null) }
		viewModelScope.launch {
			runCatching { backupManager.needsPassword(pending.zip) }.fold(
				onSuccess = { needsPassword ->
					if (needsPassword) {
						// 加密备份：把转圈收掉换成口令框。这里不能自己猜口令，只能等他输
						backup.update {
							it.copy(job = null, restorePrompt = RestorePasswordPrompt(pending.zip))
						}
					} else {
						stage(pending.zip, null)
					}
				},
				onFailure = { finishJob(error = failureText("读不出这份备份，恢复没有开始", it)) },
			)
		}
	}

	/** 口令框上点了确认。输错不关框，错误提示留在框里，输入框内容也留着 */
	fun submitRestorePassword(password: CharArray) {
		val prompt = uiState.value.backup.restorePrompt ?: return
		backup.update { it.copy(job = BackupJob.RESTORING, restorePrompt = prompt.copy(error = null)) }
		viewModelScope.launch { stage(prompt.zip, password) }
	}

	fun dismissRestorePrompt() {
		backup.update { it.copy(job = null, restorePrompt = null) }
	}

	/**
	 * 解压那一下。口令错和文件被改都走 BackupPasswordException，
	 * 它的 message 本来就是写给用户看的话，不再包一层前缀 —— 包了只会变成"失败：口令不对"这种重复。
	 */
	private suspend fun stage(zip: Uri, password: CharArray?) {
		val result = try {
			runCatching { backupManager.stageRestore(zip, password) }
		} finally {
			password?.fill('\u0000')
		}
		result.fold(
			onSuccess = { staged ->
				backup.update {
					it.copy(
						job = null,
						restorePrompt = null,
						notice = "备份已经校验通过并解好了（${staged.fileCount} 个文件，" +
							"导出于 ${staged.manifest.exportedAtText}）。\n" +
							"现在把 AICP 完全退出（从任务列表里划掉）再打开，恢复就会在启动时完成。" +
							"这之前继续聊的内容会被备份里的内容覆盖掉。",
						error = null,
					)
				}
			},
			onFailure = { e ->
				val prompt = backup.value.restorePrompt
				when {
					// 正在输口令时出的口令类错误，留在框里原地重试
					e is BackupPasswordException && prompt != null -> backup.update {
						it.copy(job = null, restorePrompt = prompt.copy(error = e.message ?: DEFAULT_PASSWORD_ERROR))
					}

					e is BackupPasswordException -> backup.update {
						it.copy(job = null, restorePrompt = null, error = e.message ?: DEFAULT_PASSWORD_ERROR)
					}

					else -> backup.update {
						it.copy(
							job = null,
							restorePrompt = null,
							error = failureText("这份备份没能通过校验，现有数据一点没动", e),
						)
					}
				}
			},
		)
	}

	/** 解好了但改主意：撤掉暂存目录和标记 */
	fun cancelStagedRestore() {
		if (uiState.value.backup.busy) return
		startJob(BackupJob.CANCELLING)
		viewModelScope.launch {
			runCatching { backupManager.cancelStagedRestore() }.fold(
				onSuccess = { finishJob(notice = "已经取消这次恢复，现有数据没有变化。") },
				onFailure = { finishJob(error = failureText("取消失败", it)) },
			)
		}
	}

	fun dismissBackupMessage() {
		backup.update { it.copy(notice = null, error = null) }
	}

	// ---------------- 配置码 ----------------

	/**
	 * 要不要问口令。纯前缀判断、零 IO，放这儿是为了让 Composable 只管画，不掺业务判断 ——
	 * 明文码和加密码怎么区分是 codec 的规则，它以后改（比如加 AICP2E.）不该牵动 UI 代码。
	 * Screen 里直接引 PLAIN_PREFIX/SEALED_PREFIX 只是拿它俩当 placeholder 文案，那是格式契约不是判断。
	 */
	fun configCodeNeedsPassword(raw: String): Boolean = ConfigCodec.needsPassword(raw)

	fun openConfigExportPrompt() {
		if (uiState.value.configCode.busy) return
		configCode.update { it.copy(exportPrompt = true, notice = null, error = null) }
	}

	fun dismissConfigExportPrompt() {
		configCode.update { it.copy(exportPrompt = false) }
	}

	/**
	 * 生成配置码。password 为 null 走明文码（不带 Key），非空走加密码（带 Key）。
	 *
	 * encode 会跑 PBKDF2，那是实打实的 CPU 活儿，所以扔到 IO 上 ——
	 * 在主线程做的话生成那一下会把整页卡住，而它偏偏是用户刚点完按钮最盯着屏幕的时刻。
	 */
	fun generateConfigCode(password: CharArray?) {
		if (uiState.value.configCode.busy) return
		val encrypted = password != null && password.isNotEmpty()
		configCode.update { it.copy(job = ConfigCodeJob.ENCODING, exportPrompt = false, notice = null, error = null) }
		viewModelScope.launch {
			val result = try {
				runCatching {
					val current = settingsStore.current()
					withContext(Dispatchers.IO) { ConfigCodec.encode(current, password) }
				}
			} finally {
				password?.fill('\u0000')
			}
			result.fold(
				onSuccess = { code ->
					configCode.update {
						it.copy(job = null, generated = GeneratedConfigCode(code, encrypted))
					}
				},
				onFailure = { e ->
					configCode.update { it.copy(job = null, error = configFailureText("生成配置码失败", e)) }
				},
			)
		}
	}

	fun dismissGeneratedCode() {
		configCode.update { it.copy(generated = null) }
	}

	fun openConfigImportSheet() {
		if (uiState.value.configCode.busy) return
		configCode.update { it.copy(importSheet = ConfigImportSheet(), notice = null, error = null) }
	}

	fun dismissConfigImportSheet() {
		configCode.update { it.copy(importSheet = null, job = null) }
	}

	/**
	 * 识别粘贴进来的配置码，解出来先给变化清单，不直接写。
	 *
	 * 失败只把话贴回面板里（importSheet.error），面板和输入框都不动 ——
	 * 他可能只是口令打错一个字符，清掉输入框等于逼他回另一台手机重新复制。
	 */
	fun submitConfigCode(raw: String, password: CharArray?) {
		if (uiState.value.configCode.busy) return
		configCode.update { it.copy(job = ConfigCodeJob.DECODING, importSheet = ConfigImportSheet()) }
		viewModelScope.launch {
			val result = try {
				runCatching {
					val current = settingsStore.current()
					val incoming = withContext(Dispatchers.IO) { ConfigCodec.decode(raw, password) }
					Triple(
						incoming,
						ConfigDiff.describe(current, incoming),
						ConfigDiff.overwritesApiKey(incoming),
					)
				}
			} finally {
				password?.fill('\u0000')
			}
			result.fold(
				onSuccess = { (incoming, changes, overwritesKey) ->
					if (changes.isEmpty() && !overwritesKey) {
						// 空清单不弹框：一个"没有任何变化，确认导入吗"的对话框只会让人愣住
						configCode.update {
							it.copy(
								job = null,
								importSheet = null,
								notice = "这段配置码和你现在的设置完全一样，不用导入。",
							)
						}
					} else {
						configCode.update {
							it.copy(
								job = null,
								confirm = ConfigImportConfirm(incoming, changes, overwritesKey),
							)
						}
					}
				},
				onFailure = { e ->
					configCode.update {
						it.copy(job = null, importSheet = ConfigImportSheet(error = codeErrorText(e)))
					}
				},
			)
		}
	}

	fun dismissConfigImportConfirm() {
		configCode.update { it.copy(confirm = null) }
	}

	/** 清单确认之后才真写盘。apiKey 为空时 applyImported 会保留现有的，这里不用再判 */
	fun applyImportedConfig() {
		val confirm = uiState.value.configCode.confirm ?: return
		configCode.update { it.copy(job = ConfigCodeJob.APPLYING, confirm = null) }
		viewModelScope.launch {
			runCatching { settingsStore.applyImported(confirm.incoming) }.fold(
				onSuccess = {
					configCode.update {
						it.copy(job = null, importSheet = null, notice = importedNotice(confirm))
					}
				},
				onFailure = { e ->
					configCode.update { it.copy(job = null, error = configFailureText("写入配置失败", e)) }
				},
			)
		}
	}

	fun dismissConfigMessage() {
		configCode.update { it.copy(notice = null, error = null) }
	}

	/**
	 * 导入成功后的那句话。
	 * 单独拎出来是因为有个边界：清单为空但 Key 被换了（两台机器设置一样、只是补个 Key），
	 * 直接拼"改了 0 项"就成了病句。
	 */
	private fun importedNotice(confirm: ConfigImportConfirm): String {
		val keyLine = if (confirm.overwritesApiKey) "API Key 也换成了码里那个，建议顺手测一次连接。" else ""
		return if (confirm.changes.isEmpty()) {
			"配置已经导入。$keyLine"
		} else {
			"配置已经导入，改了 ${confirm.changes.size} 项。$keyLine"
		}
	}

	/**
	 * ConfigCodeException 和 BackupPasswordException 的 message 本来就是给用户看的话，
	 * 原样展示；别的异常（真出了意外）才套一层前缀，免得屏幕上只剩一个类名。
	 */
	private fun codeErrorText(e: Throwable): String = when (e) {
		is ConfigCodeException, is BackupPasswordException -> e.message ?: DEFAULT_CODE_ERROR
		else -> failureText("这段配置码没能读出来", e)
	}

	private fun configFailureText(prefix: String, e: Throwable): String = when (e) {
		is ConfigCodeException, is BackupPasswordException -> e.message ?: DEFAULT_CODE_ERROR
		else -> failureText(prefix, e)
	}

	private fun startJob(job: BackupJob) {
		backup.update { it.copy(job = job, notice = null, error = null) }
	}

	private fun finishJob(notice: String? = null, error: String? = null) {
		backup.update { it.copy(job = null, notice = notice, error = error) }
	}

	/** 底层抛的多半是 IllegalStateException + 中文消息；拿不到消息时退回类名，总比空白好 */
	private fun failureText(prefix: String, e: Throwable): String {
		val detail = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
		return "$prefix：$detail"
	}

	private fun launchStore(block: suspend SettingsStore.() -> Unit) {
		viewModelScope.launch { settingsStore.block() }
	}

	private fun clearTransient() {
		test.value = null
		savedHint.value = false
	}

	companion object {
		/**
		 * 口令类错误的兜底措辞。
		 *
		 * data 层那六条 throw 全都传了字面量，`BackupPasswordException` 的构造参数也是非空 String，
		 * 所以实际到不了这一句。留着只是因为继承下来的 Throwable.message 静态类型是 String?：
		 * 用 !! 是拿崩溃换好看，用 orEmpty() 是给用户一个空白提示框，两个都不如给句能读的话。
		 * 它是 UI 措辞不是数据层契约，所以放在这儿而不是 backup 包里。
		 */
		private const val DEFAULT_PASSWORD_ERROR = "口令不对，或者这份备份被改动过"

		/** 同上，配置码那条路的兜底措辞 */
		private const val DEFAULT_CODE_ERROR = "这段配置码读不出来，确认一下是不是复制全了"

		val Factory = viewModelFactory {
			initializer {
				val container = AicpApplication.container()
				SettingsViewModel(
					container.settingsStore,
					container.llmProvider,
					container.backupManager,
					container.updateChecker,
				)
			}
		}
	}
}
