// app/src/main/java/com/kiq/aicp/domain/humanize/MoodTracker.kt
// 会话级情绪状态。存在 conversation_personas.mood（v2 就预埋好的列）。
//
// 为什么用本地关键词规则而不是让 LLM 判断情绪：
// 每条消息多调一次模型就是多花一次钱，而情绪只是"辅助真实感"的东西，
// 判错一次的代价是这一轮语气偏了一点，不值得为它按条计费。
// 关键词表确实生硬，但它零成本、即时、能写测试。
//
// 衰减是必须的：吵完架半天不理，回来时对方还在生气才叫合理；
// 但吵完架三天不理，回来还在生气就不合理了 —— 人的情绪本来就会自己平复。
//
// mood 范围钉在 -2..2，不做更细的刻度：模型对"有点低落"和"低落值 -1.3"的反应没有区别，
// 细化只会让存进去的数字看起来精确而已。

package com.kiq.aicp.domain.humanize

import kotlin.math.abs

object MoodTracker {

	const val MIN = -2
	const val MAX = 2
	const val NEUTRAL = 0

	/** 情绪每过这么久向 0 靠一格 */
	const val DECAY_MILLIS = 6 * 60 * 60 * 1000L

	private val POSITIVE = listOf(
		"谢谢", "感谢", "多谢", "喜欢", "爱你", "太好了", "开心", "高兴", "厉害", "棒",
		"哈哈", "嘻嘻", "可爱", "温柔", "想你", "抱抱", "亲亲", "辛苦了", "麻烦你", "夸",
		"good", "nice", "thanks", "❤", "😘", "😊", "🥰", "👍",
	)

	private val NEGATIVE = listOf(
		"烦", "滚", "讨厌", "生气", "失望", "无聊", "难过", "伤心", "别说了", "闭嘴",
		"笨", "蠢", "没用", "差劲", "算了", "不想理", "懒得", "垃圾", "白痴", "神经",
		"哭", "委屈", "难受", "痛苦", "😡", "🤬", "😭", "💔",
	)

	/**
	 * 按用户这句话调整情绪。
	 * @param current 库里存的旧值
	 * @param lastUpdatedAt 旧值写入时间，0 表示没记录过
	 * @param now 当前时间
	 */
	fun next(current: Int, lastUpdatedAt: Long, now: Long, userMessage: String): Int {
		val decayed = decay(current, lastUpdatedAt, now)
		val delta = scoreOf(userMessage)
		return (decayed + delta).coerceIn(MIN, MAX)
	}

	/** 只做衰减不看内容。打开会话、AI 主动搭话这些场景用得上 */
	fun decay(current: Int, lastUpdatedAt: Long, now: Long): Int {
		if (current == NEUTRAL || lastUpdatedAt <= 0L) return current
		val elapsed = now - lastUpdatedAt
		if (elapsed <= 0L) return current

		val steps = (elapsed / DECAY_MILLIS).toInt()
		if (steps <= 0) return current

		val magnitude = (abs(current) - steps).coerceAtLeast(0)
		return if (current > 0) magnitude else -magnitude
	}

	/**
	 * 一句话值几分。命中多个同向词只算一次 ——
	 * "谢谢谢谢谢谢"不该比"谢谢"让人开心三倍。
	 * 正负都命中时按数量多的那边算，一样多就当没说什么。
	 */
	fun scoreOf(message: String): Int {
		if (message.isBlank()) return 0
		val lower = message.lowercase()
		val positives = POSITIVE.count { lower.contains(it) }
		val negatives = NEGATIVE.count { lower.contains(it) }

		return when {
			positives > negatives -> 1
			negatives > positives -> -1
			else -> 0
		}
	}

	/**
	 * 注入 system prompt 的情绪描述。
	 * 措辞刻意留白，不写"你必须表现得很生气"——
	 * 那样模型会演得过火，每句话都带情绪反而假。
	 */
	fun describe(mood: Int): String = when (mood.coerceIn(MIN, MAX)) {
		2 -> "你现在心情很好，语气自然轻快一些。"
		1 -> "你现在心情不错。"
		-1 -> "你现在情绪有点低，说话可以稍微淡一点，但不用刻意冷落对方。"
		-2 -> "你现在心情很差，回应会更短、更没劲头。别装作没事，但也不要一直提这件事。"
		else -> ""
	}
}
