// app/src/main/java/com/kiq/aicp/domain/memory/ContextBuilder.kt
// 把「人设 + 记忆卡片 + 摘要 + 最近原文」按 token 预算组装成一次请求的消息列表。
//
// 预算分配：人设先扣掉，剩下的 60% 给最近原文、40% 给记忆（卡片优先，然后 L2，然后 L1 从新到旧）。
// 记忆那 40% 没用满的部分会退回去多带几条更早的原文 —— 空着不用等于白花钱。
//
// 群聊有个必须处理的坑：别的性格说过的话不能当成 assistant 塞进去，
// 否则模型会把那些当成"自己之前说的"，然后开始模仿别人的语气。
// 这里统一转成 user 角色并加【名字】前缀。

package com.kiq.aicp.domain.memory

import com.kiq.aicp.data.attach.AttachmentStore
import com.kiq.aicp.data.db.entity.MessageAttachmentEntity
import com.kiq.aicp.data.db.entity.MessageEntity
import com.kiq.aicp.data.db.entity.PersonaEntity
import com.kiq.aicp.data.repo.ChatRepository
import com.kiq.aicp.data.repo.MemoryRepository
import com.kiq.aicp.data.repo.StickerRepository
import com.kiq.aicp.data.remote.LlmImage
import com.kiq.aicp.data.remote.LlmMessage
import com.kiq.aicp.domain.model.AicpSettings
import com.kiq.aicp.domain.model.AttachmentKind
import com.kiq.aicp.domain.model.ChatRole
import com.kiq.aicp.domain.humanize.MoodTracker

data class BuiltContext(
	val messages: List<LlmMessage>,
	val estimatedTokens: Int,
	/** 这次真正用上的卡片，回写 hitCount 用 */
	val usedCardIds: List<Long>,
	val recentMessageCount: Int,
	val summaryCount: Int,
	/** 因为预算不够被丢掉的更早原文条数，UI 上可以据此提示"再聊会自动压缩" */
	val droppedMessageCount: Int,
	/** 这次真正上传的图片张数，UI 上可以提示"本次带了 N 张图" */
	val imageCount: Int = 0,
)

class ContextBuilder(
	private val chatRepository: ChatRepository,
	private val memoryRepository: MemoryRepository,
	/** 读图片字节用。传 null 表示这个环境不带图（单测里常这么用） */
	private val attachmentStore: AttachmentStore? = null,
	/** 表情包清单来源。传 null 表示这个环境不带表情 */
	private val stickerRepository: StickerRepository? = null,
) {

	suspend fun build(
		conversationId: Long,
		speaker: PersonaEntity,
		settings: AicpSettings,
		groupMates: List<PersonaEntity> = emptyList(),
		/** 说话人此刻的心情，-2..2。0 表示平静，不会产生额外提示词 */
		mood: Int = MoodTracker.NEUTRAL,
		/**
		 * 这轮联网搜到的内容（已由 WebSearchComposer 拼好）。空串表示没搜。
		 * 群聊里几个角色共用同一份，所以是由调用方搜一次再传进来，而不是这里自己去搜。
		 */
		webResults: String = "",
	): BuiltContext {
		val nameById = (groupMates + speaker).associate { it.id to it.name }

		val personaTokens = TokenEstimator.estimateMessage(speaker.systemPrompt)
		// 先裁到预算内，再按裁完的实际长度扣账。
		// 反过来做（按预算扣、按裁完的注入）会白吃历史的额度：裁剪常常裁掉一大半，
		// 扣了 1500 实际只注入 300，剩下那 1200 谁也用不上。
		val web = clampWebResults(webResults, settings)
		val webTokens = if (web.isBlank()) 0 else TokenEstimator.estimateMessage(web)
		val available = (settings.contextBudgetTokens - personaTokens - webTokens)
			.coerceAtLeast(MIN_AVAILABLE)
		val recentQuota = (available * RECENT_RATIO).toInt()
		val memoryQuota = available - recentQuota

		// ---- 最近原文（从新往旧装） ----
		val candidates = chatRepository.recentForContext(
			convId = conversationId,
			limit = (settings.keepRecentMessages * CANDIDATE_FACTOR).coerceAtMost(MAX_CANDIDATES),
		)
		val newestFirst = candidates.asReversed()
		val recentPack = ContextPacker.takeWithin(
			items = newestFirst.take(settings.keepRecentMessages),
			budget = recentQuota,
			minCount = MIN_RECENT,
		) { it.tokenEstimate }

		// ---- 记忆条目（wiki 第二层） ----
		// 原文没花完的额度先并进记忆预算，最后记忆没花完的再退回去带更早的原文，两头都别空着
		val memoryBudget = memoryQuota + (recentQuota - recentPack.tokens).coerceAtLeast(0)

		// 检索用的探针文本：最近几条对话。用它去撞条目的标题和别名，
		// 撞上的条目带正文进上下文 —— 这是不用 embedding 的那条检索路
		val probeText = recentPack.taken.takeLast(PROBE_MESSAGES).joinToString(" ") { it.content }

		val entryCandidates = if (settings.memoryCardLimit > 0) {
			// 底座：钉住和高重要度的，每轮必带
			val base = memoryRepository.contextEntries(
				convId = conversationId,
				personaId = speaker.id,
				limit = settings.memoryCardLimit,
			)
			// 检索：跟当前话题相关的。相关条目排在底座前面 ——
			// 装箱是按顺序砍尾巴的，排前面才不会先被砍掉
			val related = memoryRepository.relatedEntries(
				convId = conversationId,
				personaId = speaker.id,
				text = probeText,
				limit = RELATED_LIMIT,
			)
			val relatedIds = related.map { it.id }.toSet()
			related + base.filterNot { it.id in relatedIds }
		} else {
			emptyList()
		}

		val entryPack = ContextPacker.takeWithin(
			items = entryCandidates,
			budget = (memoryBudget * CARD_RATIO).toInt(),
		) { TokenEstimator.estimateText(it.body) + SystemPromptComposer.CARD_OVERHEAD_TOKENS }

		// index：没被带上正文的那些条目，只给标题和一行摘要。
		// 作用是让模型知道"这些事我知道但细节想不起来了"，而不是干脆表现得从没听过 ——
		// 人的记忆本来就是这样的，而且它可以据此主动问一句
		val takenTitles = entryPack.taken.map { it.title }.toSet()
		val indexLines = if (settings.memoryCardLimit > 0) {
			memoryRepository.entryIndex(conversationId, speaker.id, INDEX_LIMIT)
				.filterNot { it.title in takenTitles }
		} else {
			emptyList()
		}

		// ---- L2 长期记忆（条数很少，尽量全带） ----
		val longTerm = memoryRepository.activeSummaries(conversationId, level = LEVEL_LONG_TERM)
		val longTermPack = ContextPacker.takeWithin(
			items = longTerm,
			budget = memoryBudget - entryPack.tokens,
		) { it.tokenEstimate }

		// ---- L1 段摘要（从新往旧装，装不下的是更早的） ----
		val recentSummaries = memoryRepository.activeSummaries(conversationId, level = LEVEL_SEGMENT)
		val summaryPack = ContextPacker.takeWithin(
			items = recentSummaries.asReversed(),
			budget = memoryBudget - entryPack.tokens - longTermPack.tokens,
		) { it.tokenEstimate }

		// ---- 记忆没花完的额度退回给原文 ----
		val leftover = memoryBudget - entryPack.tokens - longTermPack.tokens - summaryPack.tokens
		val olderCandidates = newestFirst.drop(recentPack.taken.size)
		val extraPack = if (leftover > 0 && olderCandidates.isNotEmpty()) {
			ContextPacker.takeWithin(items = olderCandidates, budget = leftover) { it.tokenEstimate }
		} else {
			ContextPacker.Packed(emptyList(), 0, olderCandidates.size)
		}

		// ---- 拼装 ----
		val history = (recentPack.taken + extraPack.taken).sortedBy { it.id }

		// 附件一次性批量查出来按 messageId 分组，别在循环里逐条查数据库
		val attachmentsByMessage = chatRepository.attachmentsOf(history.map { it.id })
			.groupBy { it.messageId }

		// 图片配额从最新往旧分配：越近的图越可能是当前正在聊的东西
		val imageAllowedMessageIds = mutableSetOf<Long>()
		var imagesTaken = 0
		for (message in history.asReversed()) {
			if (imagesTaken >= settings.maxImagesInContext) break
			val count = attachmentsByMessage[message.id]?.count { it.kind == AttachmentKind.IMAGE } ?: 0
			if (count == 0) continue
			imageAllowedMessageIds += message.id
			imagesTaken += count
		}

		val systemPrompt = SystemPromptComposer.compose(
			personaName = speaker.name,
			personaPrompt = speaker.systemPrompt,
			entries = entryPack.taken,
			indexLines = indexLines,
			longTermSummaries = longTermPack.taken.map { it.content },
			// 装箱时是新→旧，喂给模型要回到早→晚
			recentSummaries = summaryPack.taken.reversed().map { it.content },
			groupMates = groupMates.map { it.name },
			stickerEmotions = if (settings.stickersEnabled) {
				stickerRepository?.promptEmotions(settings.stickerPromptLimit).orEmpty()
			} else {
				emptyList()
			},
			moodDescription = if (settings.humanizeEnabled) MoodTracker.describe(mood) else "",
			webResults = web,
		)

		val messages = buildList {
			add(LlmMessage(ChatRole.SYSTEM, systemPrompt))
			history.forEach { message ->
				add(
					message.toLlmMessage(
						speakerId = speaker.id,
						nameById = nameById,
						attachments = attachmentsByMessage[message.id].orEmpty(),
						allowImages = message.id in imageAllowedMessageIds,
					),
				)
			}
		}

		val imageCount = messages.sumOf { it.images.size }

		return BuiltContext(
			messages = messages,
			estimatedTokens = TokenEstimator.estimateMessages(messages.map { it.content }) +
				imageCount * ChatRepository.IMAGE_TOKEN_ESTIMATE,
			usedCardIds = entryPack.taken.map { it.id },
			recentMessageCount = history.size,
			summaryCount = longTermPack.taken.size + summaryPack.taken.size,
			droppedMessageCount = extraPack.dropped,
			imageCount = imageCount,
		)
	}

	/**
	 * 网上信息超预算时按"结果块"整块裁，不按行裁。
	 *
	 * 按行裁踩过一次坑：PassagePicker 有条"一行都装不下就截一刀"的分支，能吐出
	 * 单行两千字的正文，按行装到它就停手，结果只剩个空的 `## 标题` 头，
	 * 后面那几条明明装得下的摘要全被这一行挡在门外。给模型一个"我查到了"的空壳
	 * 比什么都不给更糟——它会开始编内容。
	 *
	 * 所以改成：装不下的那块直接跳过继续试后面的（正文太长就退化成只给摘要），
	 * 一块都装不进去时整段作废。
	 */
	private fun clampWebResults(webResults: String, settings: AicpSettings): String {
		if (webResults.isBlank()) return ""
		val budget = settings.webSearchBudgetTokens
		if (TokenEstimator.estimateMessage(webResults) <= budget) return webResults

		// 第一块是标题加那几句"这不是你的记忆"的约束，它必须跟着走，否则模型不知道这段是什么
		val blocks = webResults.split(BLOCK_SEPARATOR)
		// 没有结果块可切的时候（调用方自己拼的一段散文）退回按行裁，
		// 总比因为切不出块就整段作废好
		if (blocks.size == 1) return clampByLines(webResults, budget)

		val header = blocks.first()
		if (TokenEstimator.estimateMessage(header) > budget) return ""

		val kept = StringBuilder(header)
		var used = TokenEstimator.estimateMessage(header)
		var taken = 0

		for (block in blocks.drop(1)) {
			val piece = BLOCK_SEPARATOR + block
			val cost = TokenEstimator.estimateMessage(piece)
			if (used + cost > budget) continue
			kept.append(piece)
			used += cost
			taken++
		}

		return if (taken == 0) "" else kept.toString()
	}

	/** 按行砍尾巴，不砍到半句里 */
	private fun clampByLines(text: String, budget: Int): String {
		val kept = StringBuilder()
		for (line in text.lineSequence()) {
			val candidate = if (kept.isEmpty()) line else "$kept\n$line"
			if (TokenEstimator.estimateMessage(candidate) > budget) break
			kept.clear()
			kept.append(candidate)
		}
		return kept.toString()
	}

	private suspend fun MessageEntity.toLlmMessage(
		speakerId: Long,
		nameById: Map<Long, String>,
		attachments: List<MessageAttachmentEntity>,
		allowImages: Boolean,
	): LlmMessage {
		val files = attachments.filter { it.kind == AttachmentKind.FILE }
		val imageAttachments = attachments.filter { it.kind == AttachmentKind.IMAGE }

		val fileBlock = files.joinToString("\n\n") { file ->
			buildString {
				append("[附件文件：${file.fileName}]")
				if (file.truncated) append("（内容很长，下面只是前一部分）")
				val body = file.extractedText?.trim().orEmpty()
				if (body.isEmpty()) append("（这个格式没能抽出文本）") else append('\n').append(body)
			}
		}

		// 超出配额的旧图不重复上传，但要让模型知道这里原本有图，否则上下文会出现莫名的断裂
		val skippedImageNote = if (imageAttachments.isNotEmpty() && !allowImages) {
			"[这条消息里有 ${imageAttachments.size} 张图片，为省流量没有重复上传]"
		} else {
			""
		}

		val images = if (allowImages && attachmentStore != null) {
			imageAttachments.mapNotNull { attachment ->
				// 文件被清理掉了就跳过这张，不能让整个请求失败
				runCatching {
					LlmImage(
						base64 = attachmentStore.readBase64(attachment.localPath),
						mimeType = attachment.mimeType,
						highDetail = attachment.textHeavy,
					)
				}.getOrNull()
			}
		} else {
			emptyList()
		}

		val body = listOf(content.trim(), fileBlock, skippedImageNote)
			.filter { it.isNotBlank() }
			.joinToString("\n\n")
			.ifEmpty {
				// 只发图没打字：给一句默认引导，content 全空有些服务会直接报错
				if (images.isNotEmpty()) "看看这张图" else ""
			}

		return when {
			role == ChatRole.USER -> LlmMessage(ChatRole.USER, body, images = images)
			role == ChatRole.ASSISTANT && personaId == speakerId -> LlmMessage(ChatRole.ASSISTANT, body)
			role == ChatRole.ASSISTANT -> {
				val name = personaId?.let { nameById[it] } ?: "其他角色"
				LlmMessage(ChatRole.USER, "【$name】$body")
			}

			else -> LlmMessage(ChatRole.SYSTEM, body)
		}
	}

	private companion object {
		const val RECENT_RATIO = 0.6
		const val CARD_RATIO = 0.5
		const val MIN_RECENT = 2
		const val MIN_AVAILABLE = 500
		const val CANDIDATE_FACTOR = 4
		const val MAX_CANDIDATES = 200
		const val LEVEL_SEGMENT = 1
		const val LEVEL_LONG_TERM = 2

		/** 拿最近几条对话当检索探针。取太多会把早就聊完的话题也撞出来 */
		const val PROBE_MESSAGES = 4

		/** 关键词命中的条目最多带几条正文进上下文 */
		const val RELATED_LIMIT = 5

		/** index 最多列几行。每行标题加一行摘要约 20 token */
		const val INDEX_LIMIT = 30

		/** 搜索结果里每条结果的起始标记，裁剪时按它切块（见 WebSearchComposer） */
		const val BLOCK_SEPARATOR = "\n## "
	}
}
