// app/src/main/java/com/kiq/aicp/domain/model/AicpSettings.kt
// 全局设置的领域模型。默认值就是"开箱能用"的那套参数，改这里等于改产品默认行为。
//
// apiKey 在这个对象里是解密后的明文（发请求必须用明文），所以：
// - toString 一律脱敏，防止随手 log 一下就把 Key 打进日志
// - 这个对象不落盘、不进 Bundle、不往外传

package com.kiq.aicp.domain.model

import com.kiq.aicp.domain.humanize.HumanizeConfig
import com.kiq.aicp.domain.sticker.StickerParser

data class AicpSettings(
	val baseUrl: String = "",
	val apiKey: String = "",
	/** 是否把 API Key 加密保存在本机；关闭后仅在当前进程内有效 */
	val rememberApiKey: Boolean = true,
	val model: String = "",

	/** 压缩专用模型，留空表示跟随 model。用便宜模型做摘要能省不少钱 */
	val compressModel: String = "",

	/**
	 * 视觉专用模型，留空表示跟随 model。
	 * 必须能单独配：很多服务的主力模型根本不认图（DeepSeek 只有带 -vision- 的那个实验模型支持），
	 * 拿主模型传图会直接 400。
	 */
	val visionModel: String = "",

	/**
	 * 一次请求最多带几张历史图片。视觉 token 贵，而且模型第一次看图后的描述已经留在历史文本里了，
	 * 把十几张旧图反复送上去纯属烧钱。
	 */
	val maxImagesInContext: Int = 2,

	val autoCompressEnabled: Boolean = true,

	/** 一次请求最多塞给模型多少 token（含系统提示词、记忆、历史） */
	val contextBudgetTokens: Int = 6000,

	/** 无论如何都保留在上下文里的最近消息条数，压缩不动这些 */
	val keepRecentMessages: Int = 10,

	/** 未压缩区间超过这个 token 数就触发压缩 */
	val compressTriggerTokens: Int = 3000,

	/** 或者未压缩条数超过这个数也触发 */
	val compressTriggerCount: Int = 30,

	/** L1 摘要攒到这个数量就合并成一条 L2 */
	val summaryMergeThreshold: Int = 8,

	/** 每次最多带多少张记忆卡片进上下文 */
	val memoryCardLimit: Int = 12,

	/** 群聊里一轮最多几个性格开口 */
	val groupMaxSpeakersPerTurn: Int = 2,

	/**
	 * 是否让模型知道有表情包可用。
	 * 默认开，但一张表情都没导入时 promptLabels 返回空，等于自动不生效。
	 */
	val stickersEnabled: Boolean = true,

	/**
	 * 注入 system prompt 的表情标记上限。
	 * 每个标记连中括号和分隔符大约 4~6 token，40 个约 200 token，
	 * 在 6000 的预算里可以接受；调到几百个就会明显挤压记忆的位置。
	 */
	val stickerPromptLimit: Int = StickerParser.PROMPT_LIMIT,

	/**
	 * 真人模拟总开关：分段发送、已读延迟、情绪状态都归它管。
	 * 关掉就完全退回"一次请求一条消息"的老行为，方便对比和排查问题。
	 */
	val humanizeEnabled: Boolean = true,

	/** 一条回复最多切成几段 */
	val humanizeMaxSegments: Int = 3,

	/** 每个字的打字耗时（毫秒），决定段间停顿多久 */
	val humanizeMsPerChar: Int = 55,

	/** 收到消息后先"看一眼"再开始打字的时长（毫秒） */
	val humanizeReadDelayMs: Long = 900,

	/**
	 * 主动搭话总开关。默认关 —— 它会自己发起请求花钱，
	 * 这种事必须用户明确同意才能开，不能靠默认值替他决定。
	 */
	val proactiveEnabled: Boolean = false,

	/** 多久没动静就可以主动搭话（分钟） */
	val proactiveIdleMinutes: Int = 180,

	/** 后台唤醒推送。比前台那套更花钱，所以单独一个开关 */
	val proactivePushEnabled: Boolean = false,

	/** 每天最多主动搭话几次 */
	val proactiveDailyLimit: Int = 3,

	/** 免打扰开始/结束的小时数（0..23）。start > end 表示跨午夜 */
	val quietHoursStart: Int = 23,
	val quietHoursEnd: Int = 8,

	/**
	 * 保持后台运行：挂一个前台服务把进程留住，让 ProactiveWorker 的周期任务有机会准时醒。
	 *
	 * 默认关，而且不打算改 —— 它的代价是通知栏多一条撤不掉的常驻通知，
	 * 这种"用户一眼就能看见的占用"必须由他自己点开，不能靠默认值替他决定。
	 * 只有主动搭话本身开着的时候它才有意义，判据写在 AicpApplication 那条订阅里。
	 */
	val keepAliveEnabled: Boolean = false,

	/**
	 * 用户自己写的记忆规则 —— wiki 三层结构里的第三层（schema）。
	 *
	 * Karpathy 那份 llm-wiki 里 schema 是"你和 LLM 共同演进的配置文件"，
	 * 所以它不该是我硬编码在提示词里的东西。用户可以在这里写
	 * "重点记我的健康数据""别记工作细节""我说过的话优先于你的推断"，
	 * 压缩时注入，且优先级高于内置约定。
	 *
	 * 空串表示只用内置约定。
	 */
	val memorySchema: String = "",

	/**
	 * 联网搜索总开关。默认开，代价是每条消息都多一次"要不要查"的判定调用（走压缩模型）。
	 * 认这个代价是因为判定用的是便宜的小模型、提示词也短，而漏查一次的观感是模型在胡编。
	 */
	val webSearchEnabled: Boolean = true,

	/** 一次搜索取几条摘要（1..10）。搜得多不一定更准，条数上去主要是把预算吃光 */
	val webSearchResultCount: Int = 5,

	/**
	 * 摘要之外再试抓几篇正文（0..2）。抓正文要额外几个 HTTP 往返，0 表示只吃摘要。
	 * 是"试抓前几条"而不是"保证抓到几篇"：第一条抓失败或筛不出相关段落时不顺延，
	 * 直接退回用它的摘要 —— 为多抓一篇再赔一个往返，用户等不起。
	 */
	val webSearchFetchPages: Int = 1,

	/** 每篇正文最多留多少字（200..2000）。截断点靠前会丢结论，靠后会挤掉对话历史 */
	val webSearchPageChars: Int = 600,

	/** 搜索结果这一段最多占多少 token（300..4000），超了由 ContextBuilder 裁 */
	val webSearchBudgetTokens: Int = 1500,

	val dynamicColor: Boolean = true,
) {

	val hasEndpoint: Boolean
		get() = baseUrl.isNotBlank() && apiKey.isNotBlank()

	fun effectiveCompressModel(): String = compressModel.ifBlank { model }

	fun effectiveVisionModel(): String = visionModel.ifBlank { model }

	/** 分段/停顿参数打包给 ReplySegmenter。关掉真人模拟时返回 Disabled */
	fun humanizeConfig(): HumanizeConfig =
		if (!humanizeEnabled) {
			HumanizeConfig.Disabled
		} else {
			HumanizeConfig(
				enabled = true,
				maxSegments = humanizeMaxSegments,
				msPerChar = humanizeMsPerChar,
				readDelayMs = humanizeReadDelayMs,
			)
		}

	/** 某个时刻是否落在免打扰时段内。start==end 视为全天免打扰 */
	fun isQuietAt(hour: Int): Boolean {
		val h = hour.coerceIn(0, 23)
		return if (quietHoursStart <= quietHoursEnd) {
			h in quietHoursStart until quietHoursEnd
		} else {
			// 跨午夜：23 点到次日 8 点
			h >= quietHoursStart || h < quietHoursEnd
		}
	}

	val hasVisionModel: Boolean
		get() = visionModel.isNotBlank() || model.isNotBlank()

	override fun toString(): String =
		"AicpSettings(baseUrl=$baseUrl, apiKey=${maskKey(apiKey)}, model=$model, " +
			"compressModel=$compressModel, visionModel=$visionModel, " +
			"maxImagesInContext=$maxImagesInContext, autoCompress=$autoCompressEnabled, " +
			"budget=$contextBudgetTokens, keepRecent=$keepRecentMessages, " +
			"trigger=${compressTriggerTokens}t/${compressTriggerCount}c, " +
			"merge=$summaryMergeThreshold, cards=$memoryCardLimit, " +
			"speakers=$groupMaxSpeakersPerTurn, stickers=$stickersEnabled/$stickerPromptLimit, " +
			"humanize=$humanizeEnabled/${humanizeMaxSegments}seg/${humanizeMsPerChar}ms, " +
			"proactive=$proactiveEnabled/push=$proactivePushEnabled/${proactiveIdleMinutes}min/" +
			"${proactiveDailyLimit}per-day, quiet=$quietHoursStart-$quietHoursEnd, " +
			"keepAlive=$keepAliveEnabled, dynamicColor=$dynamicColor)"

	companion object {
		/** 给日志和 UI 用的脱敏展示 */
		fun maskKey(key: String): String = when {
			key.isEmpty() -> "(未设置)"
			key.length <= 8 -> "*".repeat(key.length)
			else -> "${key.take(4)}${"*".repeat(6)}${key.takeLast(4)}"
		}
	}
}
