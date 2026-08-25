// app/src/main/java/com/kiq/aicp/domain/group/SpeakerScheduler.kt
// 群聊发言调度：决定用户这条消息之后，哪些性格开口、按什么顺序。
//
// 没有用"再调一次模型问谁该说话"这种做法 —— 那要多花一次请求和几秒延迟，
// 而实际体验里规则调度已经够自然了：
//   1. 被点名的优先（消息里出现了名字或 @名字）
//   2. 否则挑"最久没说话"的，同样久的按权重高的优先
//   3. 静音的性格永远不开口
// 纯函数，所有分支都能单测。

package com.kiq.aicp.domain.group

data class SpeakerCandidate(
	val personaId: Long,
	val name: String,
	val weight: Float,
	val muted: Boolean,
	/** 上次发言时间，没说过话给 0 */
	val lastSpokeAt: Long,
)

object SpeakerScheduler {

	fun pick(
		candidates: List<SpeakerCandidate>,
		userText: String,
		maxSpeakers: Int,
	): List<Long> {
		val available = candidates.filterNot { it.muted }
		if (available.isEmpty()) return emptyList()
		if (available.size == 1) return listOf(available.single().personaId)

		val limit = maxSpeakers.coerceAtLeast(1)

		// 被点名的先说，按名字在文本里出现的先后排
		val mentioned = available
			.mapNotNull { c -> mentionIndex(userText, c.name)?.let { it to c } }
			.sortedBy { it.first }
			.map { it.second }

		if (mentioned.isNotEmpty()) {
			return mentioned.take(limit).map { it.personaId }
		}

		// 没人被点名：轮转，让冷板凳先上
		return available
			.sortedWith(compareBy<SpeakerCandidate> { it.lastSpokeAt }.thenByDescending { it.weight })
			.take(limit)
			.map { it.personaId }
	}

	/** 返回名字在文本中出现的位置，没提到返回 null。@名字 和裸名字都算 */
	private fun mentionIndex(userText: String, name: String): Int? {
		if (name.isBlank()) return null
		val text = userText.lowercase()
		val target = name.lowercase()
		val at = text.indexOf("@$target")
		if (at >= 0) return at
		val bare = text.indexOf(target)
		return if (bare >= 0) bare else null
	}
}
