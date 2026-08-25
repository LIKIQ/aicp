// app/src/main/java/com/kiq/aicp/data/repo/ChatRepository.kt
// 消息读写 + 会话冗余字段（lastMessageAt / lastMessagePreview / pendingTokens）的同步维护。
//
// 每次消息落库都要顺带刷会话上的冗余字段，所以这些操作一律包在事务里 ——
// 否则流式写入被中途取消，会留下"消息进了库但列表预览还是旧的"这种脏状态。
//
// pendingTokens 每次都重新 SUM 一遍而不是做增量累加：
// 压缩会把一整段 compressed 置 1，增量累加对不上账，一次 SUM 的成本在手机上完全可以忽略。

package com.kiq.aicp.data.repo

import androidx.room.withTransaction
import com.kiq.aicp.data.attach.AttachmentStore
import com.kiq.aicp.data.attach.SavedAttachment
import com.kiq.aicp.data.db.AicpDatabase
import com.kiq.aicp.data.db.entity.MessageAttachmentEntity
import com.kiq.aicp.data.db.entity.MessageEntity
import com.kiq.aicp.domain.memory.TokenEstimator
import com.kiq.aicp.domain.model.AttachmentKind
import com.kiq.aicp.domain.model.ChatRole
import com.kiq.aicp.domain.model.MessageStatus
import kotlinx.coroutines.flow.Flow

/** 已落盘、等着入库的附件 */
data class PendingAttachment(
	val kind: AttachmentKind,
	val saved: SavedAttachment,
	/** 文件才有：抽出来的正文 */
	val extractedText: String? = null,
	val truncated: Boolean = false,
	/** 图片才有：是不是带文字的截图，影响 detail 档位 */
	val textHeavy: Boolean = false,
)

class ChatRepository(
	private val db: AicpDatabase,
	/** 删消息时要顺手清掉附件文件；单测里可以不传 */
	private val attachmentStore: AttachmentStore? = null,
	private val clock: () -> Long = System::currentTimeMillis,
) {

	companion object {
		private const val PREVIEW_LEN = 60

		/** 各家都按图收固定 token（DeepSeek 明确每图封顶 384），取 400 保守估 */
		const val IMAGE_TOKEN_ESTIMATE = 400
	}

	private val messageDao = db.messageDao()
	private val convDao = db.conversationDao()
	private val attachmentDao = db.attachmentDao()

	fun observeMessages(convId: Long): Flow<List<MessageEntity>> =
		messageDao.observeByConversation(convId)

	suspend fun getMessage(id: Long): MessageEntity? = messageDao.getById(id)

	/** 用户发言。返回新消息 id */
	suspend fun appendUser(convId: Long, text: String): Long =
		appendUserWithAttachments(convId, text, emptyList())

	/**
	 * 带附件的用户发言。附件必须已经落盘（AttachmentStore 干的），这里只管入库。
	 *
	 * tokenEstimate 要把附件算进去，否则压缩阈值判不准：
	 * - 图片按固定值估（各家都是按图收固定 token，DeepSeek 明确每图封顶 384，这里取 400 保守些）
	 * - 文件按抽出来的正文实际估算
	 */
	suspend fun appendUserWithAttachments(
		convId: Long,
		text: String,
		attachments: List<PendingAttachment>,
	): Long = db.withTransaction {
		val body = text.trim()
		require(body.isNotEmpty() || attachments.isNotEmpty()) { "空消息不入库" }
		val now = clock()

		val attachmentTokens = attachments.sumOf { pending ->
			when (pending.kind) {
				AttachmentKind.IMAGE -> IMAGE_TOKEN_ESTIMATE
				AttachmentKind.FILE -> TokenEstimator.estimateText(pending.extractedText.orEmpty())
			}
		}

		val id = messageDao.insert(
			MessageEntity(
				conversationId = convId,
				role = ChatRole.USER,
				personaId = null,
				content = body,
				tokenEstimate = TokenEstimator.estimateMessage(body) + attachmentTokens,
				status = MessageStatus.OK,
				createdAt = now,
			),
		)

		if (attachments.isNotEmpty()) {
			attachmentDao.insertAll(
				attachments.map { pending ->
					MessageAttachmentEntity(
						messageId = id,
						kind = pending.kind,
						localPath = pending.saved.localPath,
						mimeType = pending.saved.mimeType,
						fileName = pending.saved.fileName,
						byteSize = pending.saved.byteSize,
						width = pending.saved.width,
						height = pending.saved.height,
						extractedText = pending.extractedText,
						truncated = pending.truncated,
						textHeavy = pending.textHeavy,
						createdAt = now,
					)
				},
			)
		}

		convDao.touchLastMessage(convId, now, previewOf(body, attachments))
		syncPendingTokens(convId)
		id
	}

	fun observeAttachments(convId: Long): Flow<List<MessageAttachmentEntity>> =
		attachmentDao.observeByConversation(convId)

	suspend fun attachmentsOf(messageIds: List<Long>): List<MessageAttachmentEntity> =
		if (messageIds.isEmpty()) emptyList() else attachmentDao.byMessages(messageIds)

	/** 开一条 STREAMING 占位消息，流式 chunk 往里写 */
	suspend fun startAssistant(convId: Long, personaId: Long): Long = db.withTransaction {
		val now = clock()
		messageDao.insert(
			MessageEntity(
				conversationId = convId,
				role = ChatRole.ASSISTANT,
				personaId = personaId,
				content = "",
				tokenEstimate = TokenEstimator.PER_MESSAGE_OVERHEAD,
				status = MessageStatus.STREAMING,
				createdAt = now,
			),
		)
	}

	/**
	 * 流式过程中刷新正文。传的是"到目前为止的全文"而不是增量片段，
	 * 这样中途崩了库里也是一段完整可读的话，不会拼出乱序结果。
	 */
	suspend fun updateStreaming(messageId: Long, fullText: String) {
		messageDao.updateStreamingContent(
			id = messageId,
			content = fullText,
			tokens = TokenEstimator.estimateMessage(fullText),
			status = MessageStatus.STREAMING,
		)
	}

	suspend fun finishAssistant(messageId: Long, fullText: String) = db.withTransaction {
		val body = fullText.trim()
		val msg = messageDao.getById(messageId) ?: return@withTransaction
		val now = clock()
		messageDao.updateStreamingContent(
			id = messageId,
			content = body,
			tokens = TokenEstimator.estimateMessage(body),
			status = if (body.isEmpty()) MessageStatus.FAILED else MessageStatus.OK,
		)
		if (body.isEmpty()) {
			messageDao.updateStatus(messageId, MessageStatus.FAILED, "模型没有返回任何内容")
		} else {
			convDao.touchLastMessage(msg.conversationId, now, preview(body))
			syncPendingTokens(msg.conversationId)
		}
	}

	/** 失败时保留已经流出来的半截文本，用户还能看到说到哪断了 */
	suspend fun failAssistant(messageId: Long, error: String) {
		messageDao.updateStatus(messageId, MessageStatus.FAILED, error.take(300))
	}

	/**
	 * 分段发送的后续段落。第一段留在原来的流式消息里（走 finishAssistant），
	 * 第二段起每段都是一条独立的 OK 消息，模拟真人连着发几条。
	 * 直接建成 OK 而不再走 STREAMING：这几段是已经拿到的完整文本，没有"正在打字"的过程。
	 */
	suspend fun appendAssistantSegment(convId: Long, personaId: Long, text: String): Long =
		db.withTransaction {
			val body = text.trim()
			val now = clock()
			val id = messageDao.insert(
				MessageEntity(
					conversationId = convId,
					role = ChatRole.ASSISTANT,
					personaId = personaId,
					content = body,
					tokenEstimate = TokenEstimator.estimateMessage(body),
					status = MessageStatus.OK,
					createdAt = now,
				),
			)
			convDao.touchLastMessage(convId, now, preview(body))
			syncPendingTokens(convId)
			id
		}

	suspend fun recentForContext(convId: Long, limit: Int): List<MessageEntity> =
		messageDao.getRecentForContext(convId, limit, MessageStatus.OK).reversed()

	/** 不做压缩过滤的最近消息，新→旧。主动搭话的判断要看原样的对话尾部 */
	suspend fun recentRaw(convId: Long, limit: Int): List<MessageEntity> =
		messageDao.recentRaw(convId, limit)

	suspend fun rangeForCompress(convId: Long, afterId: Long, untilId: Long): List<MessageEntity> =
		messageDao.getRangeForCompress(convId, afterId, untilId, MessageStatus.OK)

	suspend fun uncompressedCount(convId: Long): Int =
		messageDao.countUncompressed(convId, MessageStatus.OK)

	suspend fun uncompressedTokens(convId: Long): Int =
		messageDao.sumUncompressedTokens(convId, MessageStatus.OK)

	suspend fun maxMessageId(convId: Long): Long =
		messageDao.maxMessageId(convId, MessageStatus.OK) ?: 0

	/** 压缩成功后调用：标记原文已被摘要覆盖，同时推进游标并重算未压缩预算 */
	suspend fun commitCompression(convId: Long, untilMessageId: Long) = db.withTransaction {
		messageDao.markCompressedUntil(convId, untilMessageId)
		val pending = messageDao.sumUncompressedTokens(convId, MessageStatus.OK)
		convDao.onCompressSuccess(
			id = convId,
			until = untilMessageId,
			pendingTokens = pending,
			at = clock(),
		)
	}

	suspend fun markCompressionFailed(convId: Long) = convDao.onCompressFailure(convId, clock())

	suspend fun deleteMessage(id: Long) {
		// 附件的磁盘文件 SQLite 管不到：先把路径捞出来，删完行再删文件
		val orphanPaths = db.withTransaction {
			val msg = messageDao.getById(id) ?: return@withTransaction emptyList()
			val paths = attachmentDao.collectPathsOfMessage(id)
			messageDao.deleteById(id)
			syncPendingTokens(msg.conversationId)
			paths
		}
		if (orphanPaths.isNotEmpty()) attachmentStore?.delete(orphanPaths)
	}

	suspend fun clearFailed(convId: Long) = db.withTransaction {
		messageDao.deleteByStatus(convId, MessageStatus.FAILED)
		syncPendingTokens(convId)
	}

	private suspend fun syncPendingTokens(convId: Long) {
		convDao.updatePendingTokens(convId, messageDao.sumUncompressedTokens(convId, MessageStatus.OK))
	}

	private fun preview(text: String): String =
		text.replace('\n', ' ').trim().take(PREVIEW_LEN)

	/** 只发了图没打字时，会话列表得有个能看的预览，不能是空白 */
	private fun previewOf(body: String, attachments: List<PendingAttachment>): String {
		if (attachments.isEmpty()) return preview(body)
		val tag = when {
			attachments.all { it.kind == AttachmentKind.IMAGE } ->
				if (attachments.size > 1) "[${attachments.size} 张图片]" else "[图片]"

			attachments.none { it.kind == AttachmentKind.IMAGE } ->
				"[文件] ${attachments.first().saved.fileName}"

			else -> "[图片和文件]"
		}
		return preview(if (body.isEmpty()) tag else "$tag $body")
	}
}
