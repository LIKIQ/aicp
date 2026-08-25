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
	): BuiltContext {
		val nameById = (groupMates + speaker).associate { it.id to it.name }

		val personaTokens = TokenEstimator.estimateMessage(speaker.systemPrompt)
		val available = (settings.contextBudgetTokens - personaTokens).coerceAtLeast(MIN_AVAILABLE)
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

		// ---- 记忆卡片 ----
		// 原文没花完的额度先并进记忆预算，最后记忆没花完的再退回去带更早的原文，两头都别空着
		val memoryBudget = memoryQuota + (recentQuota - recentPack.tokens).coerceAtLeast(0)
		val cardCandidates = if (settings.memoryCardLimit > 0) {
			memoryRepository.contextCards(
				convId = conversationId,
				personaId = speaker.id,
				limit = settings.memoryCardLimit,
			)
		} else {
			emptyList()
		}
		val cardPack = ContextPacker.takeWithin(
			items = cardCandidates,
			budget = (memoryBudget * CARD_RATIO).toInt(),
		) { TokenEstimator.estimateText(it.content) + SystemPromptComposer.CARD_OVERHEAD_TOKENS }

		// ---- L2 长期记忆（条数很少，尽量全带） ----
		val longTerm = memoryRepository.activeSummaries(conversationId, level = LEVEL_LONG_TERM)
		val longTermPack = ContextPacker.takeWithin(
			items = longTerm,
			budget = memoryBudget - cardPack.tokens,
		) { it.tokenEstimate }

		// ---- L1 段摘要（从新往旧装，装不下的是更早的） ----
		val recentSummaries = memoryRepository.activeSummaries(conversationId, level = LEVEL_SEGMENT)
		val summaryPack = ContextPacker.takeWithin(
			items = recentSummaries.asReversed(),
			budget = memoryBudget - cardPack.tokens - longTermPack.tokens,
		) { it.tokenEstimate }

		// ---- 记忆没花完的额度退回给原文 ----
		val leftover = memoryBudget - cardPack.tokens - longTermPack.tokens - summaryPack.tokens
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
			cards = cardPack.taken,
			longTermSummaries = longTermPack.taken.map { it.content },
			// 装箱时是新→旧，喂给模型要回到早→晚
			recentSummaries = summaryPack.taken.reversed().map { it.content },
			groupMates = groupMates.map { it.name },
			stickerLabels = if (settings.stickersEnabled) {
				stickerRepository?.promptLabels(settings.stickerPromptLimit).orEmpty()
			} else {
				emptyList()
			},
			moodDescription = if (settings.humanizeEnabled) MoodTracker.describe(mood) else "",
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
			usedCardIds = cardPack.taken.map { it.id },
			recentMessageCount = history.size,
			summaryCount = longTermPack.taken.size + summaryPack.taken.size,
			droppedMessageCount = extraPack.dropped,
			imageCount = imageCount,
		)
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
	}
}
