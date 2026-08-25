// app/src/main/java/com/kiq/aicp/domain/memory/ContextPacker.kt
// 上下文装箱与系统提示词拼装，两个都是纯函数，方便单独测。
//
// 装箱规则里 minCount 那个参数很要紧：预算再紧也必须带上最后一两条原文，
// 否则模型收到的就是"一堆摘要 + 一句没有上文的话"，回出来的东西会很奇怪。

package com.kiq.aicp.domain.memory

import com.kiq.aicp.data.db.entity.MemoryCardEntity
import com.kiq.aicp.data.db.entity.MemoryEntryEntity
import com.kiq.aicp.domain.sticker.StickerEmotion

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
		cards: List<MemoryCardEntity> = emptyList(),
		entries: List<MemoryEntryEntity> = emptyList(),
		indexLines: List<IndexLine> = emptyList(),
		longTermSummaries: List<String>,
		recentSummaries: List<String>,
		groupMates: List<String>,
		stickerEmotions: List<String> = emptyList(),
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

		// 条目版的记忆。跟卡片的区别是每条有标题和一段正文，所以用小标题分块，
		// 平铺成一行会让 200 字的正文糊成一片读不出边界
		if (entries.isNotEmpty()) {
			appendLine()
			appendLine()
			appendLine("# 你记得关于对方的事")
			appendLine("这些是你早就知道的信息，说话时自然地用上，不要复述这份清单，也不要说「根据我的记忆」。")
			entries.forEach { entry ->
				appendLine()
				appendLine("## ${entry.title}")
				appendLine(entry.body.trim())
				// 矛盾要让模型看见：悄悄用新说法会让用户觉得"它记错了还不承认"
				entry.conflictNote?.takeIf { it.isNotBlank() }?.let {
					appendLine("（这里有前后不一致：${it.trim()}。不确定时可以问一句确认）")
				}
			}
		}

		// index：知道有这回事但细节没带上来。写清楚"记不清细节"是有意的 ——
		// 不加这句模型会拿一行摘要当全部事实往下编
		if (indexLines.isNotEmpty()) {
			appendLine()
			appendLine("# 你还隐约记得这些，但细节想不起来了")
			appendLine("需要用到时可以顺口问一句确认，不要凭这一行摘要编出细节。")
			indexLines.forEach { appendLine("- ${it.title}：${it.oneLiner}") }
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
		// 夹在人设和记忆中间会稀释前面那些更重要的角色设定。
		// 给的是情绪而不是图名，具体哪张图由 StickerRepository.pickForEmotion 挑
		if (stickerEmotions.isNotEmpty()) {
			appendLine()
			appendLine()
			append(StickerEmotion.promptFor(stickerEmotions))
		}
	}.trim()
}
