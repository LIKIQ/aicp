// app/src/main/java/com/kiq/aicp/domain/memory/CompressionPrompts.kt
// 压缩用的提示词与结果解析。
//
// 让模型一次调用同时产出「段落摘要」和「记忆卡片」，而不是分两次问：
// 省一半 token 和一半延迟，而且两者出自同一次阅读，抽出来的卡片跟摘要口径一致。
//
// 解析必须扛得住三种常见畸形：套 ```json 代码块、JSON 前后带解释文字、干脆不给 JSON。
// 兜底策略是把整段回复当摘要用并标记 strict=false —— 宁可留一份粗摘要，也别浪费掉这次调用。

package com.kiq.aicp.domain.memory

import com.kiq.aicp.data.db.entity.MessageEntity
import com.kiq.aicp.domain.model.ChatRole
import com.kiq.aicp.domain.model.MemoryCardType
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

data class ParsedCard(
	val type: MemoryCardType,
	val keyword: String,
	val content: String,
	val importance: Int,
)

data class ParsedCompression(
	val summary: String,
	val cards: List<ParsedCard>,
	/** false = 模型没按 JSON 输出，摘要是拿整段回复兜的，将来可以重压一次 */
	val strict: Boolean,
)

object CompressionPrompts {

	private const val MAX_KEYWORD = 12
	private const val MAX_CARD_CONTENT = 80
	private const val MAX_CARDS = 12

	private val json = Json {
		ignoreUnknownKeys = true
		isLenient = true
	}

	@Serializable
	private data class CardDto(
		val type: String = "",
		val keyword: String = "",
		val content: String = "",
		val importance: Int = 3,
	)

	@Serializable
	private data class PayloadDto(
		val summary: String = "",
		val cards: List<CardDto> = emptyList(),
	)

	fun summarizerSystem(): String = """
		你是对话记忆压缩器。把给你的一段对话压成记忆，供 AI 角色在后续对话里保持连贯。

		只输出一个 JSON 对象，不要 markdown 代码块，不要任何解释文字：
		{
		  "summary": "150 到 300 字的摘要",
		  "cards": [
		    {"type": "FACT", "keyword": "职业", "content": "在做安卓开发", "importance": 4}
		  ]
		}

		summary 的要求：
		- 第三人称连贯叙述，把对方称为"用户"
		- 保留情节推进、情绪变化、双方的约定和未完成的事
		- 不要写成"用户说…AI说…"的逐句转述
		- 不要评价，不要总结教训

		cards 的要求：
		- type 只能是 FACT（客观事实）、PREFERENCE（喜好忌讳）、EVENT（发生过的事）、
		  RELATION（关系与约定）、IMPRESSION（你对用户的印象）之一
		- keyword 是不超过 8 字的归一化键，同一件事以后要能靠它对上，比如"职业""宠物""称呼"
		- content 不超过 60 字
		- importance 取 1 到 5，5 表示以后每次对话都该记得
		- 只抽跨会话仍然成立的稳定信息；一次性闲聊、临时情绪不要抽
		- 同一件事只出一张卡；没有值得记的就给空数组
	""".trimIndent()

	fun summarizerUser(transcript: String): String = """
		下面是需要压缩的对话：

		$transcript
	""".trimIndent()

	fun mergeSystem(): String = """
		你在做长期记忆的二次收敛。下面是同一段关系里按时间排列的多份对话摘要，
		把它们合并成一份更短的长期记忆。

		只输出一个 JSON 对象，不要 markdown 代码块，不要解释文字：
		{"summary": "200 到 400 字的合并摘要", "cards": []}

		要求：
		- 保留跨时间仍然成立的信息：身份、长期目标、关系状态、反复出现的习惯
		- 丢掉已经过期的细节和重复的内容
		- 有时间线的地方按先后顺序写清楚
		- cards 一律给空数组，这一步只做摘要合并
	""".trimIndent()

	fun mergeUser(summaries: List<String>): String = buildString {
		appendLine("以下是需要合并的摘要，按时间从早到晚：")
		appendLine()
		summaries.forEachIndexed { index, s ->
			appendLine("【第 ${index + 1} 段】")
			appendLine(s.trim())
			appendLine()
		}
	}.trimEnd()

	/** 把消息拼成带说话人的逐行记录。nameOf 负责把 personaId 换成角色名 */
	fun transcriptOf(messages: List<MessageEntity>, nameOf: (Long?) -> String): String =
		messages.joinToString("\n") { msg ->
			val speaker = when (msg.role) {
				ChatRole.USER -> "用户"
				ChatRole.ASSISTANT -> nameOf(msg.personaId)
				ChatRole.SYSTEM -> "系统"
			}
			"$speaker：${msg.content.replace('\n', ' ').trim()}"
		}

	fun parse(raw: String): ParsedCompression {
		val text = raw.trim()
		if (text.isEmpty()) return ParsedCompression("", emptyList(), strict = false)

		val body = extractJsonObject(stripCodeFence(text))
		val dto = body?.let { runCatching { json.decodeFromString<PayloadDto>(it) }.getOrNull() }

		val summary = dto?.summary?.trim().orEmpty()
		if (dto == null || summary.isEmpty()) {
			// 模型不听话时，整段回复当摘要，卡片这次就不要了
			return ParsedCompression(summary = text, cards = emptyList(), strict = false)
		}

		val cards = dto.cards.mapNotNull { it.toParsedOrNull() }
			.distinctBy { it.type to it.keyword }
			.take(MAX_CARDS)

		return ParsedCompression(summary = summary, cards = cards, strict = true)
	}

	private fun CardDto.toParsedOrNull(): ParsedCard? {
		val kind = MemoryCardType.fromOrNull(type) ?: return null
		val key = keyword.trim().take(MAX_KEYWORD)
		val body = content.trim().take(MAX_CARD_CONTENT)
		if (key.isEmpty() || body.isEmpty()) return null
		return ParsedCard(
			type = kind,
			keyword = key,
			content = body,
			importance = importance.coerceIn(1, 5),
		)
	}

	private fun stripCodeFence(text: String): String {
		if (!text.startsWith("```")) return text
		return text.removePrefix("```json")
			.removePrefix("```JSON")
			.removePrefix("```")
			.removeSuffix("```")
			.trim()
	}

	/** 模型爱在 JSON 前后加话，掐头去尾只取第一个 { 到最后一个 } */
	private fun extractJsonObject(text: String): String? {
		val start = text.indexOf('{')
		val end = text.lastIndexOf('}')
		return if (start >= 0 && end > start) text.substring(start, end + 1) else null
	}
}
