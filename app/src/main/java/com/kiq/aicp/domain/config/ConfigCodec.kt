/*
 * app/src/main/java/com/kiq/aicp/domain/config/ConfigCodec.kt
 * 配置码：把设置打包成一段可复制粘贴的文字，换机时贴回来
 * 职责：
 * - 导出：设置 → JSON → 可选 AES-GCM 加密 → Base64url → 带前缀的一段文字
 * - 导入：从一段杂乱文本里认出配置码 → 解码 → 校验 → 还原成设置
 * - 只搬设置，不搬数据（性格、会话、记忆走文件备份那条路）
 *
 * 为什么另立 ConfigPayload 而不直接序列化 AicpSettings：
 * 这段文字是要跨版本活很久的格式契约，而 AicpSettings 是随时会重构的运行时模型。
 * 绑在一起的话，哪天给某个字段改个名，用户手上的旧配置码就静默失效了。
 * 两边字段对不齐由 ConfigCodecTest 的反射比对盯着，漏字段会红。
 */
package com.kiq.aicp.domain.config

import com.kiq.aicp.data.backup.BackupCrypto
import com.kiq.aicp.domain.model.AicpSettings
import java.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** 配置码本身有问题（认不出、版本太新、内容坏了）。口令错走 BackupPasswordException */
class ConfigCodeException(message: String) : Exception(message)

/**
 * 配置码里装的东西。字段名就是格式的一部分，改名等于换格式，只能加不能改。
 * apiKey 只在加密模式下有值——明文码里带凭证等于把 Key 贴在公告板上。
 */
@Serializable
data class ConfigPayload(
	val v: Int = ConfigCodec.CONFIG_VERSION,
	val baseUrl: String = "",
	val apiKey: String = "",
	val model: String = "",
	val compressModel: String = "",
	val visionModel: String = "",
	val maxImagesInContext: Int = 2,
	val autoCompressEnabled: Boolean = true,
	val contextBudgetTokens: Int = 6000,
	val keepRecentMessages: Int = 10,
	val compressTriggerTokens: Int = 3000,
	val compressTriggerCount: Int = 30,
	val summaryMergeThreshold: Int = 8,
	val memoryCardLimit: Int = 12,
	val groupMaxSpeakersPerTurn: Int = 2,
	val stickersEnabled: Boolean = true,
	val stickerPromptLimit: Int = 0,
	val humanizeEnabled: Boolean = true,
	val humanizeMaxSegments: Int = 3,
	val humanizeMsPerChar: Int = 55,
	val humanizeReadDelayMs: Long = 900,
	val proactiveEnabled: Boolean = false,
	val proactiveIdleMinutes: Int = 180,
	val proactivePushEnabled: Boolean = false,
	val proactiveDailyLimit: Int = 3,
	val quietHoursStart: Int = 23,
	val quietHoursEnd: Int = 8,
	val keepAliveEnabled: Boolean = false,
	val memorySchema: String = "",
	val dynamicColor: Boolean = true,
)

object ConfigCodec {

	/** 格式版本。加字段不用动它（缺字段走默认值），改字段语义才要抬 */
	const val CONFIG_VERSION = 1

	/** 明文码前缀 */
	const val PLAIN_PREFIX = "AICP1."

	/** 加密码前缀。一眼就能看出要不要问口令 */
	const val SEALED_PREFIX = "AICP1E."

	private val json = Json {
		ignoreUnknownKeys = true
		encodeDefaults = true
	}

	/**
	 * 从一段文字里认出配置码。
	 *
	 * 允许用户连上下文一起粘（"这是我的配置码：AICP1.xxx 记得导入"），
	 * 因为"复制粘贴识别"的实际场景就是从聊天记录里整段抄过来，
	 * 要求他精确选中那一串反而是把麻烦推给他。
	 */
	private val codePattern = Regex("AICP1E?\\.[A-Za-z0-9_-]+")

	/**
	 * 打包成配置码。
	 *
	 * password 为空就走明文，同时把 apiKey 抹掉——明文码是要贴到微信、笔记、
	 * 甚至截图里的东西，凭证不能跟着走。填了口令才带 Key，而且那份码没口令解不开。
	 */
	fun encode(settings: AicpSettings, password: CharArray? = null): String {
		val sealed = password != null && password.isNotEmpty()
		val payload = settings.toPayload(includeKey = sealed)
		val bytes = json.encodeToString(ConfigPayload.serializer(), payload).toByteArray()

		// 这里不用 !!：password 的判空写在 sealed 里，编译器能顺着 val 推过来
		return if (sealed) {
			SEALED_PREFIX + encodeBase64(BackupCrypto.sealBytes(bytes, password))
		} else {
			PLAIN_PREFIX + encodeBase64(bytes)
		}
	}

	/** 这段文字里的配置码要不要口令。认不出配置码时返回 false，让 decode 去报准确的错 */
	fun needsPassword(raw: String): Boolean =
		codePattern.find(raw.filterNot { it.isWhitespace() })
			?.value
			?.startsWith(SEALED_PREFIX) == true

	/**
	 * 从文字里解出配置。
	 *
	 * 先把所有空白去掉再找：跨应用复制经常带上换行和空格，
	 * 用户看到的是一整串，实际粘过来中间可能夹着 \n，
	 * 不清掉的话正则只能匹配到前半截，报"内容损坏"就成了冤案。
	 */
	fun decode(raw: String, password: CharArray? = null): AicpSettings {
		val cleaned = raw.filterNot { it.isWhitespace() }
		val code = codePattern.find(cleaned)?.value
			?: throw ConfigCodeException("没认出配置码，确认复制的时候把开头的 AICP1 那一串也带上了")

		val sealed = code.startsWith(SEALED_PREFIX)
		val body = code.removePrefix(if (sealed) SEALED_PREFIX else PLAIN_PREFIX)
		if (body.isEmpty()) throw ConfigCodeException("配置码只有开头没有内容，多半是复制时截断了")

		if (sealed && (password == null || password.isEmpty())) {
			throw ConfigCodeException("这段配置码是加密的，需要导出时设的那个口令")
		}

		val decoded = runCatching { decodeBase64(body) }
			.getOrElse { throw ConfigCodeException("配置码里有不认识的字符，可能复制得不完整") }

		// 口令错时 BackupCrypto 抛的 BackupPasswordException 直接透上去，
		// 它的话已经是给用户看的，再包一层只会把"口令不对"变成"内容损坏"这种误导
		val plain = if (sealed) BackupCrypto.openBytes(decoded, password!!) else decoded

		val payload = runCatching {
			json.decodeFromString(ConfigPayload.serializer(), plain.decodeToString())
		}.getOrElse { throw ConfigCodeException("配置码的内容读不懂，可能不是本应用导出的") }

		if (payload.v > CONFIG_VERSION) {
			throw ConfigCodeException(
				"这段配置码来自更新版本的 AICP（格式 v${payload.v}），先把应用升级再导入",
			)
		}

		return payload.toSettings()
	}

	private fun encodeBase64(bytes: ByteArray): String =
		Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

	private fun decodeBase64(text: String): ByteArray = Base64.getUrlDecoder().decode(text)

	/** 两边字段一一对应，新增设置项时这里和 toSettings 都要跟上，漏了单测会红 */
	private fun AicpSettings.toPayload(includeKey: Boolean) = ConfigPayload(
		v = CONFIG_VERSION,
		baseUrl = baseUrl,
		apiKey = if (includeKey) apiKey else "",
		model = model,
		compressModel = compressModel,
		visionModel = visionModel,
		maxImagesInContext = maxImagesInContext,
		autoCompressEnabled = autoCompressEnabled,
		contextBudgetTokens = contextBudgetTokens,
		keepRecentMessages = keepRecentMessages,
		compressTriggerTokens = compressTriggerTokens,
		compressTriggerCount = compressTriggerCount,
		summaryMergeThreshold = summaryMergeThreshold,
		memoryCardLimit = memoryCardLimit,
		groupMaxSpeakersPerTurn = groupMaxSpeakersPerTurn,
		stickersEnabled = stickersEnabled,
		stickerPromptLimit = stickerPromptLimit,
		humanizeEnabled = humanizeEnabled,
		humanizeMaxSegments = humanizeMaxSegments,
		humanizeMsPerChar = humanizeMsPerChar,
		humanizeReadDelayMs = humanizeReadDelayMs,
		proactiveEnabled = proactiveEnabled,
		proactiveIdleMinutes = proactiveIdleMinutes,
		proactivePushEnabled = proactivePushEnabled,
		proactiveDailyLimit = proactiveDailyLimit,
		quietHoursStart = quietHoursStart,
		quietHoursEnd = quietHoursEnd,
		keepAliveEnabled = keepAliveEnabled,
		memorySchema = memorySchema,
		dynamicColor = dynamicColor,
	)

	/**
	 * 还原成设置。数值一律经过一遍夹取——配置码是可以手改的文本，
	 * 谁把 humanizeMsPerChar 改成 999999，导入后每条消息要打十分钟字。
	 */
	private fun ConfigPayload.toSettings() = AicpSettings(
		baseUrl = baseUrl.trim(),
		apiKey = apiKey.trim(),
		model = model.trim(),
		compressModel = compressModel.trim(),
		visionModel = visionModel.trim(),
		maxImagesInContext = maxImagesInContext.coerceIn(0, 8),
		autoCompressEnabled = autoCompressEnabled,
		contextBudgetTokens = contextBudgetTokens.coerceIn(1_000, 200_000),
		keepRecentMessages = keepRecentMessages.coerceIn(2, 100),
		compressTriggerTokens = compressTriggerTokens.coerceIn(500, 100_000),
		compressTriggerCount = compressTriggerCount.coerceIn(5, 500),
		summaryMergeThreshold = summaryMergeThreshold.coerceIn(2, 50),
		memoryCardLimit = memoryCardLimit.coerceIn(1, 100),
		groupMaxSpeakersPerTurn = groupMaxSpeakersPerTurn.coerceIn(1, 10),
		stickersEnabled = stickersEnabled,
		stickerPromptLimit = stickerPromptLimit.coerceIn(0, 200),
		humanizeEnabled = humanizeEnabled,
		humanizeMaxSegments = humanizeMaxSegments.coerceIn(1, 10),
		humanizeMsPerChar = humanizeMsPerChar.coerceIn(0, 500),
		humanizeReadDelayMs = humanizeReadDelayMs.coerceIn(0, 60_000),
		proactiveEnabled = proactiveEnabled,
		proactiveIdleMinutes = proactiveIdleMinutes.coerceIn(5, 10_080),
		proactivePushEnabled = proactivePushEnabled,
		proactiveDailyLimit = proactiveDailyLimit.coerceIn(0, 50),
		quietHoursStart = quietHoursStart.coerceIn(0, 23),
		quietHoursEnd = quietHoursEnd.coerceIn(0, 23),
		keepAliveEnabled = keepAliveEnabled,
		memorySchema = memorySchema.take(600),
		dynamicColor = dynamicColor,
	)
}
