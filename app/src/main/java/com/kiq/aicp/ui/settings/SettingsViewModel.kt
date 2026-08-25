// app/src/main/java/com/kiq/aicp/ui/settings/SettingsViewModel.kt
// 设置页状态。
//
// 接口配置（Base URL / Key / 模型）走"本地草稿 + 保存按钮"，不做输入即存：
// API Key 每敲一个字符就加密写盘既费电又没意义，而且中途的半截 Key 存下来会让"测试连接"给出误导结论。
// 滑块和开关类设置反过来，即时生效更顺手。
//
// 草稿字段用 null 表示"跟随已保存值"，非 null 才是用户改过的内容 —— 这样不用额外维护 dirty 标记。

package com.kiq.aicp.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.kiq.aicp.AicpApplication
import com.kiq.aicp.data.prefs.SettingsStore
import com.kiq.aicp.data.remote.LlmException
import com.kiq.aicp.data.remote.LlmMessage
import com.kiq.aicp.data.remote.LlmParams
import com.kiq.aicp.data.remote.LlmProvider
import com.kiq.aicp.domain.model.AicpSettings
import com.kiq.aicp.domain.model.ChatRole
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

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

data class SettingsUiState(
	val settings: AicpSettings = AicpSettings(),
	val draft: EndpointDraft = EndpointDraft(),
	val test: ConnectionTest? = null,
	val savedHint: Boolean = false,
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
) : ViewModel() {

	private val draft = MutableStateFlow(EndpointDraft())
	private val test = MutableStateFlow<ConnectionTest?>(null)
	private val savedHint = MutableStateFlow(false)

	val uiState: StateFlow<SettingsUiState> =
		combine(settingsStore.settings, draft, test, savedHint) { settings, d, t, hint ->
			SettingsUiState(settings = settings, draft = d, test = t, savedHint = hint)
		}.stateIn(
			scope = viewModelScope,
			started = SharingStarted.WhileSubscribed(5_000),
			initialValue = SettingsUiState(),
		)

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

	private fun launchStore(block: suspend SettingsStore.() -> Unit) {
		viewModelScope.launch { settingsStore.block() }
	}

	private fun clearTransient() {
		test.value = null
		savedHint.value = false
	}

	companion object {
		val Factory = viewModelFactory {
			initializer {
				val container = AicpApplication.container()
				SettingsViewModel(container.settingsStore, container.llmProvider)
			}
		}
	}
}
