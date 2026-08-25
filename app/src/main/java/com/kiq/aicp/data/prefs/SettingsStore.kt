// app/src/main/java/com/kiq/aicp/data/prefs/SettingsStore.kt
// 设置的读写（DataStore Preferences）。
//
// API Key 落盘前先过 SecretCipher 加密，读出来立刻解密成明文放进 AicpSettings；
// 解不开就当"没配"，UI 会提示重新填 —— 好过静默拿一串乱码去请求然后收 401。
//
// 数值类设置一律在写入时就夹到合法区间，读的时候不再校验，
// 免得旧版本写进去的越界值在新版本里悄悄生效。

package com.kiq.aicp.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.kiq.aicp.domain.model.AicpSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

class SettingsStore(
	private val dataStore: DataStore<Preferences>,
	private val cipher: SecretCipher,
) {

	val settings: Flow<AicpSettings> = dataStore.data.map { it.toSettings() }

	suspend fun current(): AicpSettings = settings.first()

	suspend fun setEndpoint(baseUrl: String, model: String) {
		dataStore.edit { prefs ->
			prefs[KEY_BASE_URL] = baseUrl.trim()
			prefs[KEY_MODEL] = model.trim()
		}
	}

	/** 传 null 表示不动已存的 Key；传空串表示清空 */
	suspend fun setApiKey(apiKey: String?) {
		if (apiKey == null) return
		dataStore.edit { prefs ->
			val trimmed = apiKey.trim()
			if (trimmed.isEmpty()) {
				prefs.remove(KEY_API_KEY_ENC)
			} else {
				prefs[KEY_API_KEY_ENC] = cipher.encrypt(trimmed)
			}
		}
	}

	suspend fun setCompressModel(model: String) {
		dataStore.edit { it[KEY_COMPRESS_MODEL] = model.trim() }
	}

	suspend fun setVisionModel(model: String) {
		dataStore.edit { it[KEY_VISION_MODEL] = model.trim() }
	}

	suspend fun setMaxImagesInContext(count: Int) {
		dataStore.edit { it[KEY_MAX_IMAGES] = count.coerceIn(0, 8) }
	}

	suspend fun setAutoCompress(enabled: Boolean) {
		dataStore.edit { it[KEY_AUTO_COMPRESS] = enabled }
	}

	suspend fun setDynamicColor(enabled: Boolean) {
		dataStore.edit { it[KEY_DYNAMIC_COLOR] = enabled }
	}

	suspend fun setStickersEnabled(enabled: Boolean) {
		dataStore.edit { it[KEY_STICKERS_ENABLED] = enabled }
	}

	/**
	 * 用户自己写的记忆规则（wiki 的第三层 schema）。
	 * 不做内容校验：它是直接拼进提示词的自然语言，写什么都合法，
	 * 唯一的限制是长度 —— 太长会挤掉本该给对话的预算。
	 */
	suspend fun setMemorySchema(schema: String) {
		dataStore.edit { it[KEY_MEMORY_SCHEMA] = schema.trim().take(MAX_MEMORY_SCHEMA) }
	}

	suspend fun setStickerPromptLimit(limit: Int) {
		dataStore.edit { it[KEY_STICKER_LIMIT] = limit.coerceIn(0, 200) }
	}

	/**
	 * 内置表情是否已经灌过。
	 * 用标记而不是"检查表情表是否为空"：用户把内置表情全删了之后，
	 * 靠检查的话每次重启都会重新冒出来，那是 bug 不是功能。
	 */
	suspend fun builtInStickersImported(): Boolean =
		dataStore.data.first()[KEY_BUILTIN_STICKERS] ?: false

	suspend fun markBuiltInStickersImported() {
		dataStore.edit { it[KEY_BUILTIN_STICKERS] = true }
	}

	/**
	 * 待恢复标记。阶段一解压完置上，阶段二把文件搬完之后由 AicpApplication 清掉。
	 *
	 * 它只负责设置页那句"等重启"，不是恢复流程的判据 —— 真正决定要不要搬文件的是
	 * filesDir/restore_pending 目录在不在。原因是搬运发生在冷启动最前面，
	 * 那时候读 DataStore 要么阻塞主线程要么得等协程，两种都会让搬运晚于 Room 开库。
	 */
	val restorePending: Flow<Boolean> = dataStore.data.map { it[KEY_RESTORE_PENDING] ?: false }

	suspend fun setRestorePending(pending: Boolean) {
		dataStore.edit { prefs ->
			// 关掉时直接把键删了而不是写 false：这个键绝大多数时间就不该存在，
			// 留一个常驻的 false 只会让人以为"曾经恢复过"
			if (pending) prefs[KEY_RESTORE_PENDING] = true else prefs.remove(KEY_RESTORE_PENDING)
		}
	}

	/**
	 * 上次检查更新的时刻（毫秒）。0 表示从没查过，UpdateChecker 靠它做 24 小时节流。
	 *
	 * 为什么不塞进 AicpSettings：它不是用户能调的设置，是一条运行时痕迹。
	 * 混进去会跟着配置码被导出到另一台设备，让那台机器一装上就以为"刚查过"，
	 * 而且 ConfigCodec 那份格式契约也会为了它多背一个字段。
	 */
	suspend fun lastUpdateCheckAt(): Long = dataStore.data.first()[KEY_LAST_UPDATE_CHECK] ?: 0L

	suspend fun setLastUpdateCheckAt(epochMillis: Long) {
		// 夹一下负数：时间戳为负没有意义，写进去只会让节流算出一个巨大的间隔
		dataStore.edit { it[KEY_LAST_UPDATE_CHECK] = epochMillis.coerceAtLeast(0L) }
	}

	/**
	 * 真人模拟的几个参数。上下限都卡住：
	 * msPerChar 给到 300 就慢到像卡住了，给 0 又等于没开这个功能。
	 */
	suspend fun setHumanizeTuning(
		enabled: Boolean? = null,
		maxSegments: Int? = null,
		msPerChar: Int? = null,
		readDelayMs: Long? = null,
	) {
		dataStore.edit { prefs ->
			enabled?.let { prefs[KEY_HUMANIZE_ENABLED] = it }
			maxSegments?.let { prefs[KEY_HUMANIZE_SEGMENTS] = it.coerceIn(1, 5) }
			msPerChar?.let { prefs[KEY_HUMANIZE_MS_PER_CHAR] = it.coerceIn(10, 200) }
			readDelayMs?.let { prefs[KEY_HUMANIZE_READ_DELAY] = it.coerceIn(0L, 5_000L) }
		}
	}

	/**
	 * 主动搭话相关。
	 * 关掉总开关时顺手把后台推送也关掉 —— 留着一个"总开关关了但推送还开着"的状态，
	 * 迟早会变成"我明明关了它还在发消息"的 bug 报告。
	 */
	suspend fun setProactiveTuning(
		enabled: Boolean? = null,
		idleMinutes: Int? = null,
		pushEnabled: Boolean? = null,
		dailyLimit: Int? = null,
		quietStart: Int? = null,
		quietEnd: Int? = null,
	) {
		dataStore.edit { prefs ->
			enabled?.let {
				prefs[KEY_PROACTIVE_ENABLED] = it
				if (!it) prefs[KEY_PROACTIVE_PUSH] = false
			}
			idleMinutes?.let { prefs[KEY_PROACTIVE_IDLE] = it.coerceIn(5, 1_440) }
			pushEnabled?.let { prefs[KEY_PROACTIVE_PUSH] = it }
			dailyLimit?.let { prefs[KEY_PROACTIVE_DAILY] = it.coerceIn(1, 20) }
			quietStart?.let { prefs[KEY_QUIET_START] = it.coerceIn(0, 23) }
			quietEnd?.let { prefs[KEY_QUIET_END] = it.coerceIn(0, 23) }
		}
	}

	suspend fun setMemoryTuning(
		contextBudgetTokens: Int? = null,
		keepRecentMessages: Int? = null,
		compressTriggerTokens: Int? = null,
		compressTriggerCount: Int? = null,
		summaryMergeThreshold: Int? = null,
		memoryCardLimit: Int? = null,
		groupMaxSpeakersPerTurn: Int? = null,
	) {
		dataStore.edit { prefs ->
			contextBudgetTokens?.let { prefs[KEY_CONTEXT_BUDGET] = it.coerceIn(1_000, 128_000) }
			keepRecentMessages?.let { prefs[KEY_KEEP_RECENT] = it.coerceIn(2, 100) }
			compressTriggerTokens?.let { prefs[KEY_TRIGGER_TOKENS] = it.coerceIn(500, 100_000) }
			compressTriggerCount?.let { prefs[KEY_TRIGGER_COUNT] = it.coerceIn(4, 500) }
			summaryMergeThreshold?.let { prefs[KEY_MERGE_THRESHOLD] = it.coerceIn(2, 50) }
			memoryCardLimit?.let { prefs[KEY_CARD_LIMIT] = it.coerceIn(0, 60) }
			groupMaxSpeakersPerTurn?.let { prefs[KEY_MAX_SPEAKERS] = it.coerceIn(1, 5) }
		}
	}

	/** 设置页的"恢复默认"，只重置调优项，不动接口配置和 Key */
	suspend fun resetTuning() {
		dataStore.edit { prefs ->
			listOf(
				KEY_CONTEXT_BUDGET, KEY_KEEP_RECENT, KEY_TRIGGER_TOKENS,
				KEY_TRIGGER_COUNT, KEY_MERGE_THRESHOLD, KEY_CARD_LIMIT, KEY_MAX_SPEAKERS,
			).forEach { prefs.remove(it) }
		}
	}

	/**
	 * 从配置码导入一整份设置，一次 edit 写完。
	 *
	 * apiKey 为空时保留现有的，不清掉：明文配置码本来就不带凭证，
	 * 用户的期望是"其他设置跟过来、Key 我已经填好了"，
	 * 顺手把他的 Key 抹掉会让他以为导入弄坏了配置。
	 *
	 * 数值边界这里不再夹一遍 —— ConfigCodec.toSettings 已经夹过，
	 * 两处各夹一次迟早会出现两套不一样的上下界。
	 */
	suspend fun applyImported(settings: AicpSettings) {
		dataStore.edit { prefs ->
			prefs[KEY_BASE_URL] = settings.baseUrl
			prefs[KEY_MODEL] = settings.model
			prefs[KEY_COMPRESS_MODEL] = settings.compressModel
			prefs[KEY_VISION_MODEL] = settings.visionModel
			prefs[KEY_MAX_IMAGES] = settings.maxImagesInContext
			prefs[KEY_AUTO_COMPRESS] = settings.autoCompressEnabled
			prefs[KEY_CONTEXT_BUDGET] = settings.contextBudgetTokens
			prefs[KEY_KEEP_RECENT] = settings.keepRecentMessages
			prefs[KEY_TRIGGER_TOKENS] = settings.compressTriggerTokens
			prefs[KEY_TRIGGER_COUNT] = settings.compressTriggerCount
			prefs[KEY_MERGE_THRESHOLD] = settings.summaryMergeThreshold
			prefs[KEY_CARD_LIMIT] = settings.memoryCardLimit
			prefs[KEY_MAX_SPEAKERS] = settings.groupMaxSpeakersPerTurn
			prefs[KEY_STICKERS_ENABLED] = settings.stickersEnabled
			prefs[KEY_STICKER_LIMIT] = settings.stickerPromptLimit
			prefs[KEY_HUMANIZE_ENABLED] = settings.humanizeEnabled
			prefs[KEY_HUMANIZE_SEGMENTS] = settings.humanizeMaxSegments
			prefs[KEY_HUMANIZE_MS_PER_CHAR] = settings.humanizeMsPerChar
			prefs[KEY_HUMANIZE_READ_DELAY] = settings.humanizeReadDelayMs
			prefs[KEY_PROACTIVE_ENABLED] = settings.proactiveEnabled
			prefs[KEY_PROACTIVE_IDLE] = settings.proactiveIdleMinutes
			prefs[KEY_PROACTIVE_PUSH] = settings.proactivePushEnabled
			prefs[KEY_PROACTIVE_DAILY] = settings.proactiveDailyLimit
			prefs[KEY_QUIET_START] = settings.quietHoursStart
			prefs[KEY_QUIET_END] = settings.quietHoursEnd
			prefs[KEY_MEMORY_SCHEMA] = settings.memorySchema.take(MAX_MEMORY_SCHEMA)
			prefs[KEY_DYNAMIC_COLOR] = settings.dynamicColor

			val key = settings.apiKey.trim()
			if (key.isNotEmpty()) prefs[KEY_API_KEY_ENC] = cipher.encrypt(key)
		}
	}

	private fun Preferences.toSettings(): AicpSettings {
		val defaults = AicpSettings()
		val storedKey = this[KEY_API_KEY_ENC]
		return AicpSettings(
			baseUrl = this[KEY_BASE_URL] ?: defaults.baseUrl,
			apiKey = storedKey?.let { cipher.decrypt(it) } ?: defaults.apiKey,
			model = this[KEY_MODEL] ?: defaults.model,
			compressModel = this[KEY_COMPRESS_MODEL] ?: defaults.compressModel,
			visionModel = this[KEY_VISION_MODEL] ?: defaults.visionModel,
			maxImagesInContext = this[KEY_MAX_IMAGES] ?: defaults.maxImagesInContext,
			autoCompressEnabled = this[KEY_AUTO_COMPRESS] ?: defaults.autoCompressEnabled,
			contextBudgetTokens = this[KEY_CONTEXT_BUDGET] ?: defaults.contextBudgetTokens,
			keepRecentMessages = this[KEY_KEEP_RECENT] ?: defaults.keepRecentMessages,
			compressTriggerTokens = this[KEY_TRIGGER_TOKENS] ?: defaults.compressTriggerTokens,
			compressTriggerCount = this[KEY_TRIGGER_COUNT] ?: defaults.compressTriggerCount,
			summaryMergeThreshold = this[KEY_MERGE_THRESHOLD] ?: defaults.summaryMergeThreshold,
			memoryCardLimit = this[KEY_CARD_LIMIT] ?: defaults.memoryCardLimit,
			groupMaxSpeakersPerTurn = this[KEY_MAX_SPEAKERS] ?: defaults.groupMaxSpeakersPerTurn,
			stickersEnabled = this[KEY_STICKERS_ENABLED] ?: defaults.stickersEnabled,
			stickerPromptLimit = this[KEY_STICKER_LIMIT] ?: defaults.stickerPromptLimit,
			humanizeEnabled = this[KEY_HUMANIZE_ENABLED] ?: defaults.humanizeEnabled,
			humanizeMaxSegments = this[KEY_HUMANIZE_SEGMENTS] ?: defaults.humanizeMaxSegments,
			humanizeMsPerChar = this[KEY_HUMANIZE_MS_PER_CHAR] ?: defaults.humanizeMsPerChar,
			humanizeReadDelayMs = this[KEY_HUMANIZE_READ_DELAY] ?: defaults.humanizeReadDelayMs,
			proactiveEnabled = this[KEY_PROACTIVE_ENABLED] ?: defaults.proactiveEnabled,
			proactiveIdleMinutes = this[KEY_PROACTIVE_IDLE] ?: defaults.proactiveIdleMinutes,
			proactivePushEnabled = this[KEY_PROACTIVE_PUSH] ?: defaults.proactivePushEnabled,
			proactiveDailyLimit = this[KEY_PROACTIVE_DAILY] ?: defaults.proactiveDailyLimit,
			quietHoursStart = this[KEY_QUIET_START] ?: defaults.quietHoursStart,
			quietHoursEnd = this[KEY_QUIET_END] ?: defaults.quietHoursEnd,
			memorySchema = this[KEY_MEMORY_SCHEMA] ?: defaults.memorySchema,
			dynamicColor = this[KEY_DYNAMIC_COLOR] ?: defaults.dynamicColor,
		)
	}

	companion object {
		const val STORE_NAME = "aicp_settings"

		/** 记忆规则的长度上限。它每次压缩都会被注入，写成长篇就是持续的 token 开销 */
		const val MAX_MEMORY_SCHEMA = 600

		private val KEY_BASE_URL = stringPreferencesKey("base_url")
		private val KEY_API_KEY_ENC = stringPreferencesKey("api_key_enc")
		private val KEY_MODEL = stringPreferencesKey("model")
		private val KEY_COMPRESS_MODEL = stringPreferencesKey("compress_model")
		private val KEY_VISION_MODEL = stringPreferencesKey("vision_model")
		private val KEY_MAX_IMAGES = intPreferencesKey("max_images_in_context")
		private val KEY_AUTO_COMPRESS = booleanPreferencesKey("auto_compress")
		private val KEY_STICKERS_ENABLED = booleanPreferencesKey("stickers_enabled")
		private val KEY_STICKER_LIMIT = intPreferencesKey("sticker_prompt_limit")
		private val KEY_BUILTIN_STICKERS = booleanPreferencesKey("builtin_stickers_imported")
		private val KEY_RESTORE_PENDING = booleanPreferencesKey("restore_pending")
		private val KEY_LAST_UPDATE_CHECK = longPreferencesKey("last_update_check_at")
		private val KEY_HUMANIZE_ENABLED = booleanPreferencesKey("humanize_enabled")
		private val KEY_HUMANIZE_SEGMENTS = intPreferencesKey("humanize_max_segments")
		private val KEY_HUMANIZE_MS_PER_CHAR = intPreferencesKey("humanize_ms_per_char")
		private val KEY_HUMANIZE_READ_DELAY = longPreferencesKey("humanize_read_delay_ms")
		private val KEY_PROACTIVE_ENABLED = booleanPreferencesKey("proactive_enabled")
		private val KEY_PROACTIVE_IDLE = intPreferencesKey("proactive_idle_minutes")
		private val KEY_PROACTIVE_PUSH = booleanPreferencesKey("proactive_push_enabled")
		private val KEY_PROACTIVE_DAILY = intPreferencesKey("proactive_daily_limit")
		private val KEY_QUIET_START = intPreferencesKey("quiet_hours_start")
		private val KEY_QUIET_END = intPreferencesKey("quiet_hours_end")
		private val KEY_MEMORY_SCHEMA = stringPreferencesKey("memory_schema")
		private val KEY_DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
		private val KEY_CONTEXT_BUDGET = intPreferencesKey("context_budget_tokens")
		private val KEY_KEEP_RECENT = intPreferencesKey("keep_recent_messages")
		private val KEY_TRIGGER_TOKENS = intPreferencesKey("compress_trigger_tokens")
		private val KEY_TRIGGER_COUNT = intPreferencesKey("compress_trigger_count")
		private val KEY_MERGE_THRESHOLD = intPreferencesKey("summary_merge_threshold")
		private val KEY_CARD_LIMIT = intPreferencesKey("memory_card_limit")
		private val KEY_MAX_SPEAKERS = intPreferencesKey("group_max_speakers")
	}
}
