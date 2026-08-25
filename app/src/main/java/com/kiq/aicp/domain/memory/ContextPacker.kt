// app/src/main/java/com/kiq/aicp/domain/memory/ContextPacker.kt
// 上下文装箱与系统提示词拼装，两个都是纯函数，方便单独测。
//
// 装箱规则里 minCount 那个参数很要紧：预算再紧也必须带上最后一两条原文，
// 否则模型收到的就是"一堆摘要 + 一句没有上文的话"，回出来的东西会很奇怪。

package com.kiq.aicp.domain.memory

import com.kiq.aicp.data.db.entity.MemoryCardEntity
import com.kiq.aicp.domain.sticker.StickerParser

internal object ContextPacker {

	data class Packed<T>(
		val taken: List<T>,
		val tokens: Int,
		val dropped: Int,
	)

	/**
	 * 按给定顺序装箱，装不下就停。顺序由调用方决定（原文和 L1 摘要传倒序，卡片传正序）。
	 * minCount 表示"哪怕超预算也至少要这么多条"。
	 */
	fun <T> takeWithin(
		items: List<T>,
		budget: Int,
		minCount: Int = 0,
		tokenOf: (T) -> Int,
	): Packed<T> {
		if (items.isEmpty()) return Packed(emptyList(), 0, 0)

		val taken = mutableListOf<T>()
		var used = 0
		for (item in items) {
			val cost = tokenOf(item).coerceAtLeast(0)
			if (used + cost > budget && taken.size >= minCount) break
			taken += item
			used += cost
		}
		return Packed(taken = taken, tokens = used, dropped = items.size - taken.size)
	}
}

internal object SystemPromptComposer {

	/** 每张卡片除正文外还有"- [键] "这点结构开销 */
	const val CARD_OVERHEAD_TOKENS = 6

	fun compose(
		personaName: String,
		personaPrompt: String,
		cards: List<MemoryCardEntity>,
		longTermSummaries: List<String>,
		recentSummaries: List<String>,
		groupMates: List<String>,
		stickerLabels: List<String> = emptyList(),
		moodDescription: String = "",
	): String = buildString {
		append(personaPrompt.trim())

		// 心情紧跟人设：它是"此刻的你"，比记忆和场景都更贴近角色本身。
		// 放到末尾的话，中间那几段清单会把它冲淡，模型经常就忽略了
		if (moodDescription.isNotEmpty()) {
			appendLine()
			appendLine()
			appendLine("# 你现在的状态")
			appendLine(moodDescription)
		}

		if (cards.isNotEmpty()) {
			appendLine()
			appendLine()
			appendLine("# 你记得关于对方的事")
			appendLine("这些是你早就知道的信息，说话时自然地用上，不要复述这份清单，也不要说「根据我的记忆」。")
			cards.forEach { appendLine("- [${it.keyword}] ${it.content}") }
		}

		if (longTermSummaries.isNotEmpty()) {
			appendLine()
			appendLine("# 更早的经历（已收敛为长期记忆）")
			longTermSummaries.forEach { appendLine(it.trim()) }
		}

		if (recentSummaries.isNotEmpty()) {
			appendLine()
			appendLine("# 最近聊过什么（按时间从早到晚）")
			recentSummaries.forEach { appendLine("- ${it.trim().replace('\n', ' ')}") }
		}

		if (groupMates.isNotEmpty()) {
			appendLine()
			appendLine("# 当前场景")
			appendLine("这是一个群聊，同场的还有：${groupMates.joinToString("、")}。")
			appendLine(
				"消息前的【名字】标明说话人。你只以 $personaName 的身份说话，" +
					"不要替别人发言，也不要在自己的回复前面加名字前缀。",
			)
		}

		// 表情清单放在最后：它是"工具说明"性质的内容，
		// 夹在人设和记忆中间会稀释前面那些更重要的角色设定
		if (stickerLabels.isNotEmpty()) {
			appendLine()
			appendLine()
			append(StickerParser.buildPrompt(stickerLabels))
		}
	}.trim()
}
