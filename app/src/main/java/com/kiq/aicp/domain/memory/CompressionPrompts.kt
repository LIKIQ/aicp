// app/src/main/java/com/kiq/aicp/domain/memory/CompressionPrompts.kt
// 压缩用的提示词与结果解析。
//
// 让模型一次调用同时产出「段落摘要」和「记忆卡片」，而不是分两次问：
// 省一半 token 和一半延迟，而且两者出自同一次阅读，抽出来的卡片跟摘要口径一致。
//
// 解析必须扛得住三种常见畸形：套 ```json 代码块、JSON 前后带解释文字、干脆不给 JSON。
// 兜底策略是把整段回复当摘要用并标记 strict=false —— 宁可留一份粗摘要，也别浪费掉这次调用。

package com.kiq.aicp.domain.memory

import com.kiq.aicp.data.db.entity.MemoryEntryEntity
import com.kiq.aicp.data.db.entity.MessageEntity
import com.kiq.aicp.domain.model.ChatRole
import com.kiq.aicp.domain.model.MemoryCardType
import com.kiq.aicp.domain.util.LenientJson
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

/**
 * wiki 化 ingest 的产出：一段摘要 + 若干条目。
 *
 * 刻意不让模型区分"更新"还是"新建"——它判断这个的准确率不高，
 * 而代码手上有 (scopeKey, category, title) 唯一键，一查就知道。
 * 模型只管写内容，存在性判断交给数据库。
 */
data class ParsedEntry(
	val category: MemoryCardType,
	val title: String,
	val aliases: List<String>,
	val oneLiner: String,
	val body: String,
	val importance: Int,
	/** 新信息跟旧正文冲突时模型写在这里。null 表示没冲突 */
	val conflictNote: String?,
)

data class ParsedWikiIngest(
	val summary: String,
	val entries: List<ParsedEntry>,
	val strict: Boolean,
)

/** 注入提示词的 index 一行：让模型知道有哪个条目存在，但不占正文的篇幅 */
data class IndexLine(
	val category: MemoryCardType,
	val title: String,
	val aliases: String,
	val oneLiner: String,
)

/** 跟本次对话相关、需要连正文一起给模型看的条目（它要在旧正文上增补） */
data class RelatedEntry(
	val category: MemoryCardType,
	val title: String,
	val body: String,
)

object CompressionPrompts {

	private const val MAX_KEYWORD = 12
	private const val MAX_CARD_CONTENT = 80
	private const val MAX_CARDS = 12

	/** 一次 ingest 最多产出几个条目。再多说明模型在把闲聊也当记忆写 */
	private const val MAX_ENTRIES = 10

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

	// ---------------- wiki 化 ingest（v6 起用这条路） ----------------

	@Serializable
	private data class EntryDto(
		val category: String = "",
		val title: String = "",
		val aliases: List<String> = emptyList(),
		val oneLiner: String = "",
		val body: String = "",
		val importance: Int = 3,
		val conflict: String? = null,
	)

	@Serializable
	private data class WikiPayloadDto(
		val summary: String = "",
		val entries: List<EntryDto> = emptyList(),
	)

	/**
	 * ingest 的系统提示词。
	 *
	 * @param userSchema 用户自己写的记忆规则（wiki 三层结构的第三层）。空串表示没写。
	 * 它排在内置约定之后、优先级更高 —— 用户明确写了"别记工作细节"就该盖掉默认行为。
	 */
	fun wikiIngestSystem(userSchema: String = ""): String = buildString {
		appendLine(
			"""
			你在维护一份关于用户的个人 wiki。它不是聊天记录的摘要堆，而是一组会被反复增补的条目。
			你的任务：读这段对话，产出一段摘要，并把值得长期留下的信息写进条目。

			只输出一个 JSON 对象，不要 markdown 代码块，不要任何解释文字：
			{
			  "summary": "150 到 300 字的摘要",
			  "entries": [
			    {
			      "category": "FACT",
			      "title": "职业",
			      "aliases": ["工作"],
			      "oneLiner": "在做安卓开发，最近在写一个 AI 陪伴 App",
			      "body": "完整正文，写成连贯的话",
			      "importance": 4,
			      "conflict": null
			    }
			  ]
			}

			summary 的要求：
			- 第三人称连贯叙述，把对方称为"用户"
			- 保留情节推进、情绪变化、双方的约定和未完成的事
			- 不要写成"用户说…AI说…"的逐句转述，不要评价，不要总结教训

			entries 的要求：
			- category 只能是 FACT（客观事实）、PREFERENCE（喜好忌讳）、EVENT（发生过的事）、
			  RELATION（关系与约定）、IMPRESSION（你对用户的印象）之一
			- title 是条目名，不超过 12 字。**下面给了已有条目清单，说的是同一件事就必须复用清单里那个 title**，
			  不要造近义的新名字（"工作"和"职业"分成两个条目，记忆就裂了）
			- aliases 给这个条目的其他叫法，最多 4 个，方便以后对上号
			- oneLiner 不超过 25 字，一句话说清这个条目讲什么
			- body 不超过 180 字。**如果下面给了这个条目的现有正文，你要在它基础上增补改写，
			  不是重写覆盖** —— 旧正文里仍然成立的信息一个都不能丢
			- importance 取 1 到 5，5 表示以后每次对话都该记得
			- conflict：新信息跟现有正文矛盾时，用一句话写明"旧说法 vs 新说法"，
			  正文里采用更新的说法。不矛盾就给 null。**不要悄悄改掉旧信息不作说明**
			- 只写跨会话仍然成立的稳定信息；一次性闲聊、临时情绪不要写
			- 没有值得记的就给空数组
			""".trimIndent(),
		)

		if (userSchema.isNotBlank()) {
			appendLine()
			appendLine("以下是用户自己定的记忆规则，跟上面的通用要求冲突时以这里为准：")
			appendLine(userSchema.trim())
		}
	}.trim()

	/**
	 * ingest 的用户消息：已有条目索引 + 相关条目正文 + 待压缩对话。
	 *
	 * index 只给标题和一行摘要，相关条目才给正文 —— 全量正文塞进去，
	 * 条目一多就把这次调用的上下文吃光了。这也是 Karpathy 那份里
	 * "index 在中等规模下够用、不需要 embedding"的具体落法。
	 */
	fun wikiIngestUser(
		index: List<IndexLine>,
		related: List<RelatedEntry>,
		transcript: String,
	): String = buildString {
		if (index.isNotEmpty()) {
			appendLine("【已有条目清单】说同一件事时请复用这里的 title：")
			index.forEach { line ->
				val alias = line.aliases.takeIf { it.isNotBlank() }?.let("（别名：%s）"::format).orEmpty()
				appendLine("- [${line.category.name}] ${line.title}$alias：${line.oneLiner}")
			}
			appendLine()
		}

		if (related.isNotEmpty()) {
			appendLine("【跟这段对话相关的条目现有正文】请在这些内容基础上增补，不要丢掉已有信息：")
			related.forEach { entry ->
				appendLine("- [${entry.category.name}] ${entry.title}")
				appendLine("  ${entry.body}")
			}
			appendLine()
		}

		appendLine("【需要处理的对话】")
		append(transcript)
	}.trim()

	fun parseWiki(raw: String): ParsedWikiIngest {
		val text = raw.trim()
		if (text.isEmpty()) return ParsedWikiIngest("", emptyList(), strict = false)

		val body = extractJsonObject(stripCodeFence(text))
		val dto = body?.let { runCatching { json.decodeFromString<WikiPayloadDto>(it) }.getOrNull() }

		val summary = dto?.summary?.trim().orEmpty()
		if (dto == null || summary.isEmpty()) {
			// 模型不听话时整段回复当摘要，条目这次就不要了 —— 宁可少一次条目更新，
			// 也不能把一段没结构的文本硬塞进条目正文
			return ParsedWikiIngest(summary = text, entries = emptyList(), strict = false)
		}

		val entries = dto.entries.mapNotNull { it.toParsedOrNull() }
			.distinctBy { it.category to it.title }
			.take(MAX_ENTRIES)

		return ParsedWikiIngest(summary = summary, entries = entries, strict = true)
	}

	private fun EntryDto.toParsedOrNull(): ParsedEntry? {
		val kind = MemoryCardType.fromOrNull(category) ?: return null
		val cleanTitle = title.trim().take(MemoryEntryEntity.MAX_TITLE)
		val cleanBody = body.trim().take(MemoryEntryEntity.MAX_BODY)
		if (cleanTitle.isEmpty() || cleanBody.isEmpty()) return null

		// oneLiner 缺失时用正文开头兜底：index 里少一行摘要，模型就少一条判断依据
		val liner = oneLiner.trim().ifEmpty { cleanBody }.take(MemoryEntryEntity.MAX_ONE_LINER)

		return ParsedEntry(
			category = kind,
			title = cleanTitle,
			aliases = aliases.map { it.trim() }.filter { it.isNotEmpty() && it != cleanTitle },
			oneLiner = liner,
			body = cleanBody,
			importance = importance.coerceIn(1, 5),
			conflictNote = conflict?.trim()?.takeIf { it.isNotEmpty() },
		)
	}

	private fun stripCodeFence(text: String): String = LenientJson.stripCodeFence(text)

	/** 模型爱在 JSON 前后加话，掐头去尾只取第一个 { 到最后一个 } */
	private fun extractJsonObject(text: String): String? = LenientJson.extractObject(text)
}
