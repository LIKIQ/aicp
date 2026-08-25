// app/src/main/java/com/kiq/aicp/ui/chat/ChatViewModel.kt
// 聊天页状态与发送流程。整个应用的主干逻辑都在这里汇合：
// 组装上下文 → 流式请求 → 落库 → 触发压缩。
//
// 流式输出走"双轨"：
// - UI 轨：每个增量都更新 streamingText，打字机效果无延迟
// - 落库轨：按字数和时间节流写 Room，避免每个 token 都写一次盘
// 崩了或被杀进程时，库里至少是最近一次 flush 的完整文本，不会拼出乱序内容。
//
// 附件是"选完立刻落盘"，attachments 里躺的都是已经在磁盘上的东西。相册多选进来一批时
// 逐张独立成败（saveOneImage），一张读不出来不拖累其余的，最后只汇总一条提示。

package com.kiq.aicp.ui.chat

import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.kiq.aicp.AicpApplication
import com.kiq.aicp.data.attach.AttachmentStore
import com.kiq.aicp.data.attach.SavedAttachment
import com.kiq.aicp.data.attach.TextExtractor
import com.kiq.aicp.data.db.entity.ConversationEntity
import com.kiq.aicp.data.db.entity.MessageAttachmentEntity
import com.kiq.aicp.data.db.entity.MessageEntity
import com.kiq.aicp.data.db.entity.PersonaEntity
import com.kiq.aicp.data.db.entity.StickerEntity
import com.kiq.aicp.data.prefs.SettingsStore
import com.kiq.aicp.data.remote.LlmChunk
import com.kiq.aicp.data.remote.LlmException
import com.kiq.aicp.data.remote.LlmMessage
import com.kiq.aicp.data.remote.LlmParams
import com.kiq.aicp.data.remote.LlmProvider
import com.kiq.aicp.data.repo.ChatRepository
import com.kiq.aicp.data.repo.ConversationRepository
import com.kiq.aicp.data.repo.MemoryRepository
import com.kiq.aicp.data.repo.PendingAttachment
import com.kiq.aicp.data.repo.PersonaRepository
import com.kiq.aicp.data.repo.ProactiveRepository
import com.kiq.aicp.data.repo.StickerRepository
import com.kiq.aicp.domain.group.SpeakerCandidate
import com.kiq.aicp.domain.group.SpeakerScheduler
import com.kiq.aicp.domain.memory.CompressionResult
import com.kiq.aicp.domain.memory.ContextBuilder
import com.kiq.aicp.domain.memory.MemoryCompressor
import com.kiq.aicp.domain.model.AicpSettings
import com.kiq.aicp.domain.model.AttachmentKind
import com.kiq.aicp.domain.model.ChatRole
import com.kiq.aicp.domain.model.MessageStatus
import com.kiq.aicp.domain.humanize.HumanizeConfig
import com.kiq.aicp.domain.humanize.MoodTracker
import com.kiq.aicp.domain.humanize.ProactiveContext
import com.kiq.aicp.domain.humanize.ProactiveDecider
import com.kiq.aicp.domain.humanize.ReplySegmenter
import com.kiq.aicp.domain.sticker.StickerParser
import java.io.File
import java.time.LocalTime
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 已落盘、还没随消息发出去的附件。id 只在本地用于删除，跟数据库无关 */
data class PendingAttachmentUi(
	val id: String,
	val kind: AttachmentKind,
	val saved: SavedAttachment,
	val extractedText: String? = null,
	val truncated: Boolean = false,
	val textHeavy: Boolean = false,
) {
	val isImage: Boolean get() = kind == AttachmentKind.IMAGE

	fun toPending(): PendingAttachment = PendingAttachment(
		kind = kind,
		saved = saved,
		extractedText = extractedText,
		truncated = truncated,
		textHeavy = textHeavy,
	)
}

/** 一条消息最多挂几个附件。卡在这里是为了不让一次请求把 token 顶爆 */
private const val MAX_PENDING_ATTACHMENTS = 6

/**
 * 「会话资料」对话框的草稿。null 表示对话框没开。
 *
 * 放在 ViewModel 而不是 Composable 的 remember 里，是因为选图要走 suspend 的 saveAvatar：
 * 落盘结果得有地方接住，失败提示也要走统一的 error 通道。顺带旋屏不丢用户填的名字。
 *
 * originalPath 记着进对话框时库里那张图，用来判断"当前这张是刚落盘还没保存的草稿图"，
 * 取消或者连着换几次图的时候好把中间产物删掉，不然相册里选几次就在磁盘上堆几张孤儿。
 */
data class GroupProfileDraft(
	val title: String,
	val avatarEmoji: String,
	val avatarPath: String?,
	val originalPath: String?,
	/** 选图落盘期间置位，UI 拿它禁掉确定按钮 */
	val saving: Boolean = false,
)

data class ChatUiState(
	val conversation: ConversationEntity? = null,
	val participants: List<PersonaEntity> = emptyList(),
	val messages: List<MessageEntity> = emptyList(),
	val settings: AicpSettings = AicpSettings(),
	val input: String = "",
	val sending: Boolean = false,
	val error: String? = null,
	val notice: String? = null,
	/** 正在流式输出的那条消息，UI 用 streamingText 覆盖它的正文 */
	val streamingMessageId: Long? = null,
	val streamingText: String = "",
	val attachments: List<PendingAttachmentUi> = emptyList(),
	val attaching: Boolean = false,
	/** 会话内所有消息的附件，按 messageId 分组给气泡渲染用 */
	val attachmentsByMessage: Map<Long, List<MessageAttachmentEntity>> = emptyMap(),
	/** label → 表情图相对路径。渲染 [标记] 时查这张表，比每条消息查库便宜 */
	val stickerIndex: Map<String, String> = emptyMap(),
	/** 分段发送的段间"正在输入…"提示，null 表示当前没在等下一段 */
	val typingPersonaName: String? = null,
	/** 「会话资料」对话框的草稿，null 表示没打开 */
	val profileDraft: GroupProfileDraft? = null,
) {
	val title: String get() = conversation?.title ?: "会话"
	val isGroup: Boolean get() = participants.size > 1
	val canSend: Boolean get() = (input.isNotBlank() || attachments.isNotEmpty()) && !sending && !attaching
	val notConfigured: Boolean get() = !settings.hasEndpoint

	/**
	 * 还能再挂几个附件。UI 拿它当相册多选的 maxItems，让用户在系统选择器里就选不超，
	 * 比选完 8 张回来再弹一句"最多 6 个"好受得多。
	 *
	 * 选择器那个上限只是提示，真正的把关还在 guardAttachmentQuota —— 选择器的 maxItems
	 * 不允许小于 2，配额剩 1 的时候必然会多放一张进来。
	 */
	val remainingAttachmentQuota: Int
		get() = (MAX_PENDING_ATTACHMENTS - attachments.size).coerceAtLeast(0)

	/**
	 * 单聊的对面是谁。恰好一个参与者才算单聊；性格被删光时是 null，
	 * 这时头像交给 Avatar 的名字首字兜底，不至于空一块。
	 */
	val soloPersona: PersonaEntity? get() = participants.singleOrNull()

	/**
	 * 顶栏头像的 emoji。整块二选一而不是逐字段回退：单聊没配图就该退到它自己的 emoji /
	 * 首字，绝不能莫名捡起群头像来用。
	 */
	val avatarEmoji: String
		get() = soloPersona?.avatarEmoji ?: conversation?.avatarEmoji.orEmpty()

	val avatarPath: String?
		get() {
			val solo = soloPersona
			return if (solo != null) solo.avatarPath else conversation?.avatarPath
		}

	/** 单聊时顶栏副标题显示性格备注；群聊那行留给参与者列表，所以这里返回空串 */
	val soloNote: String get() = if (isGroup) "" else soloPersona?.note.orEmpty()

	fun personaOf(id: Long?): PersonaEntity? = id?.let { pid -> participants.firstOrNull { it.id == pid } }

	fun attachmentsOf(message: MessageEntity): List<MessageAttachmentEntity> =
		attachmentsByMessage[message.id].orEmpty()

	/** 渲染用：正在流式的那条取实时文本 */
	fun displayContent(message: MessageEntity): String =
		if (message.id == streamingMessageId && streamingText.isNotEmpty()) streamingText else message.content
}

private data class Transient(
	val input: String = "",
	val sending: Boolean = false,
	val error: String? = null,
	val notice: String? = null,
	val streamingMessageId: Long? = null,
	val streamingText: String = "",
	val attachments: List<PendingAttachmentUi> = emptyList(),
	val attaching: Boolean = false,
	val typingPersonaName: String? = null,
	val profileDraft: GroupProfileDraft? = null,
)

class ChatViewModel(
	private val conversationId: Long,
	private val chatRepository: ChatRepository,
	private val conversationRepository: ConversationRepository,
	private val personaRepository: PersonaRepository,
	private val memoryRepository: MemoryRepository,
	private val settingsStore: SettingsStore,
	private val contextBuilder: ContextBuilder,
	private val compressor: MemoryCompressor,
	private val llmProvider: LlmProvider,
	private val attachmentStore: AttachmentStore,
	private val textExtractor: TextExtractor,
	private val stickerRepository: StickerRepository,
	private val proactiveRepository: ProactiveRepository,
) : ViewModel() {

	private val transient = MutableStateFlow(Transient())
	private var sendJob: Job? = null
	private var idleJob: Job? = null
	private var attachSeq = 0L

	val uiState: StateFlow<ChatUiState> = combine(
		conversationRepository.observeById(conversationId),
		conversationRepository.observeParticipantPersonas(conversationId),
		chatRepository.observeMessages(conversationId),
		chatRepository.observeAttachments(conversationId),
		stickerRepository.observeIndex(),
		settingsStore.settings,
		transient,
	) { values ->
		@Suppress("UNCHECKED_CAST")
		val conversation = values[0] as ConversationEntity?

		@Suppress("UNCHECKED_CAST")
		val participants = values[1] as List<PersonaEntity>

		@Suppress("UNCHECKED_CAST")
		val messages = values[2] as List<MessageEntity>

		@Suppress("UNCHECKED_CAST")
		val attachments = values[3] as List<MessageAttachmentEntity>

		@Suppress("UNCHECKED_CAST")
		val stickerIndex = values[4] as Map<String, String>
		val settings = values[5] as AicpSettings
		val t = values[6] as Transient

		ChatUiState(
			conversation = conversation,
			participants = participants,
			messages = messages,
			settings = settings,
			input = t.input,
			sending = t.sending,
			error = t.error,
			notice = t.notice,
			streamingMessageId = t.streamingMessageId,
			streamingText = t.streamingText,
			attachments = t.attachments,
			attaching = t.attaching,
			attachmentsByMessage = attachments.groupBy { it.messageId },
			stickerIndex = stickerIndex,
			typingPersonaName = t.typingPersonaName,
			profileDraft = t.profileDraft,
		)
	}.stateIn(
		scope = viewModelScope,
		started = SharingStarted.WhileSubscribed(5_000),
		initialValue = ChatUiState(),
	)

	/** 表情面板的数据源。跟 stickerIndex 分开是因为面板要按分组和导入顺序展示，不只要路径 */
	val stickers: StateFlow<List<StickerEntity>> = stickerRepository.observeAll()
		.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

	fun onInputChange(value: String) = transient.update { it.copy(input = value, error = null) }

	/** 表情面板点一下就把标记追加到输入框末尾，让用户还能继续接着打字 */
	fun appendStickerMarker(label: String) = transient.update {
		it.copy(input = it.input + "[$label]", error = null)
	}

	/** 参与者管理面板要列出所有性格供拉人进群 */
	val allPersonas: StateFlow<List<PersonaEntity>> = personaRepository.observeAll()
		.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

	fun dismissError() = transient.update { it.copy(error = null) }

	fun dismissNotice() = transient.update { it.copy(notice = null) }

	/**
	 * 选完图立刻压缩落盘，不等发送时才做 —— 用户能马上看到缩略图，
	 * 也把"图太大/格式不认"的失败提前暴露出来。
	 *
	 * textHeavy 为 true 走截图档（长边 1568、detail:high），给满屏是字的图用。
	 * 附件菜单里原来那个「截图」项已经撤了，现在调用方恒传 false；参数和底层那套档位都留着，
	 * 将来改成按图片内容自动判别时只改传参，不用把这条路重新铺一遍。
	 */
	fun attachImage(uri: Uri, textHeavy: Boolean) {
		viewModelScope.launch {
			if (!guardAttachmentQuota()) return@launch
			transient.update { it.copy(attaching = true, error = null) }
			val failure = saveOneImage(uri, textHeavy)
			transient.update {
				it.copy(
					attaching = false,
					error = failure?.let { reason -> "这张图没能读进来：$reason" },
				)
			}
		}
	}

	/**
	 * 相册多选一次进来一批。
	 *
	 * 逐张独立成败：第二张不是图片、第三张压完还超限，都不该拖累已经读进来的那几张，
	 * 所以每张单独 runCatching，最后汇总成一条提示 —— 一张一条 snackbar 会连着弹好几次，
	 * 用户只看得见最后那条。
	 *
	 * 配额在循环里每张都重新看一次：系统选择器的 maxItems 是个提示不是保证，
	 * 而且它压根不接受小于 2 的上限，配额剩 1 的时候必然会多放一张进来。
	 */
	fun attachImages(uris: List<Uri>, textHeavy: Boolean) {
		if (uris.isEmpty()) return
		// 只选了一张就走单张那条路：能直说是什么毛病，不用套"1 张失败"这种汇总口气
		if (uris.size == 1) {
			attachImage(uris.first(), textHeavy)
			return
		}

		viewModelScope.launch {
			transient.update { it.copy(attaching = true, error = null) }

			var added = 0
			var overQuota = 0
			val failures = mutableListOf<String>()
			for ((index, uri) in uris.withIndex()) {
				if (remainingQuota() <= 0) {
					overQuota = uris.size - index
					break
				}
				val failure = saveOneImage(uri, textHeavy)
				if (failure == null) added++ else failures += failure
			}

			val summary = buildString {
				if (added > 0) append("加了 $added 张")
				if (failures.isNotEmpty()) {
					if (isNotEmpty()) append("，")
					// 只报第一条原因：一批图挂了往往是同一个毛病，全列出来 snackbar 也装不下
					append("${failures.size} 张失败：${failures.first()}")
				}
				if (overQuota > 0) {
					if (isNotEmpty()) append("，")
					append("$overQuota 张没加（一条消息最多 $MAX_PENDING_ATTACHMENTS 个附件）")
				}
			}

			// 一张都没成才算错误，有加进来的就只是普通通知。两条通道在 UI 上都是 snackbar，
			// 分开纯粹是语义上的区别
			transient.update {
				if (added > 0) {
					it.copy(attaching = false, notice = summary)
				} else {
					it.copy(attaching = false, error = summary)
				}
			}
		}
	}

	/**
	 * 落盘一张图并挂进待发列表。成功返回 null，失败返回给人看的原因。
	 *
	 * 单张和批量共用这一份，免得两处各写一遍"落盘 → 进列表"，改一处忘一处。
	 * attaching 标记不在这里动：批量那边要整批只闪一次转圈。
	 */
	private suspend fun saveOneImage(uri: Uri, textHeavy: Boolean): String? =
		runCatching { attachmentStore.saveImage(uri, textHeavy) }.fold(
			onSuccess = { saved ->
				transient.update {
					it.copy(
						attachments = it.attachments + PendingAttachmentUi(
							id = "a${attachSeq++}",
							kind = AttachmentKind.IMAGE,
							saved = saved,
							textHeavy = textHeavy,
						),
					)
				}
				null
			},
			onFailure = { e -> e.message ?: "读不出来这张图" },
		)

	/** 文件在这一步就把正文抽出来，抽不出来的类型当场退回，不让用户等到发送才失败 */
	fun attachFile(uri: Uri) {
		viewModelScope.launch {
			if (!guardAttachmentQuota()) return@launch
			transient.update { it.copy(attaching = true, error = null) }

			val saved = runCatching { attachmentStore.saveFile(uri) }.getOrElse { e ->
				transient.update { it.copy(attaching = false, error = "这个文件没能读进来：${e.message}") }
				return@launch
			}

			if (!TextExtractor.isSupported(saved.fileName, saved.mimeType)) {
				attachmentStore.delete(listOf(saved.localPath))
				transient.update {
					it.copy(attaching = false, error = "读不了这种文件，目前认：${TextExtractor.supportedHint()}")
				}
				return@launch
			}

			val extracted = runCatching {
				textExtractor.extract(attachmentStore.resolve(saved.localPath), saved.fileName, saved.mimeType)
			}.getOrElse { e ->
				attachmentStore.delete(listOf(saved.localPath))
				transient.update { it.copy(attaching = false, error = "文件内容没解开：${e.message}") }
				return@launch
			}

			if (extracted.text.isBlank()) {
				attachmentStore.delete(listOf(saved.localPath))
				transient.update { it.copy(attaching = false, error = "这个文件里没找到文字内容") }
				return@launch
			}

			transient.update {
				it.copy(
					attaching = false,
					attachments = it.attachments + PendingAttachmentUi(
						id = "a${attachSeq++}",
						kind = AttachmentKind.FILE,
						saved = saved,
						extractedText = extracted.text,
						truncated = extracted.truncated,
					),
				)
			}
		}
	}

	/** 撤掉还没发出去的附件，磁盘文件一并删掉，不留垃圾 */
	fun removeAttachment(id: String) {
		val target = transient.value.attachments.firstOrNull { it.id == id } ?: return
		transient.update { it.copy(attachments = it.attachments.filterNot { a -> a.id == id }) }
		viewModelScope.launch { attachmentStore.delete(listOf(target.saved.localPath)) }
	}

	/** 气泡渲染要把 localPath 变成真文件，这层薄封装省得把 store 传进 Composable */
	fun resolveAttachment(localPath: String): File = attachmentStore.resolve(localPath)

	/**
	 * 剩余配额以 transient 为准，不读 uiState —— uiState 是 combine 出来的，
	 * 连着落盘几张图时它可能还没追上，拿它算配额会多放几张进来。
	 */
	private fun remainingQuota(): Int = MAX_PENDING_ATTACHMENTS - transient.value.attachments.size

	private fun guardAttachmentQuota(): Boolean {
		if (remainingQuota() > 0) return true
		transient.update { it.copy(error = "一条消息最多带 $MAX_PENDING_ATTACHMENTS 个附件") }
		return false
	}

	fun send() {
		val text = transient.value.input.trim()
		val pending = transient.value.attachments
		if ((text.isEmpty() && pending.isEmpty()) || transient.value.sending) return

		transient.update {
			it.copy(input = "", sending = true, error = null, notice = null, attachments = emptyList())
		}

		sendJob = viewModelScope.launch {
			try {
				val settings = settingsStore.current()
				if (!settings.hasEndpoint) {
					// 附件已经从待发列表摘掉了，失败时放回去，别让用户重选一遍
					transient.update {
						it.copy(
							sending = false,
							input = text,
							attachments = pending,
							error = "还没配置接口地址和 API Key，去设置页填一下",
						)
					}
					return@launch
				}

				if (pending.isEmpty()) {
					chatRepository.appendUser(conversationId, text)
				} else {
					chatRepository.appendUserWithAttachments(
						convId = conversationId,
						text = text,
						attachments = pending.map { it.toPending() },
					)
				}

				val speakers = pickSpeakers(text, settings)
				if (speakers.isEmpty()) {
					transient.update { it.copy(sending = false, error = "这个会话里没有可发言的性格") }
					return@launch
				}

				// 心情在请求前更新：这样这一轮回复就已经带上"你刚被夸了/被呛了"的状态
				updateMoodsFor(text, settings)

				// 已读延迟：真人是"看到消息 → 停一下 → 开始打字"，不是瞬间回
				val readDelay = settings.humanizeConfig().takeIf { it.enabled }?.readDelayMs ?: 0L
				if (readDelay > 0) delay(readDelay)

				for (persona in speakers) {
					val ok = streamOneReply(persona, settings)
					if (!ok) break
				}

				runCompression(settings)
			} catch (e: Exception) {
				// 协程取消由 stop() 单独处理，这里只兜住真正的异常
				if (e is kotlinx.coroutines.CancellationException) throw e
				transient.update { it.copy(error = e.message ?: "发送失败") }
			} finally {
				transient.update {
					it.copy(sending = false, streamingMessageId = null, streamingText = "")
				}
			}
		}
	}

	/** 中断当前回复。已经流出来的半截文本保留，用户能看到说到哪断了 */
	fun stop() {
		val streamingId = transient.value.streamingMessageId
		val partial = transient.value.streamingText
		sendJob?.cancel()
		sendJob = null

		viewModelScope.launch {
			if (streamingId != null) {
				if (partial.isBlank()) {
					chatRepository.failAssistant(streamingId, "已手动停止")
				} else {
					chatRepository.finishAssistant(streamingId, partial)
				}
			}
			transient.update {
				it.copy(sending = false, streamingMessageId = null, streamingText = "", notice = "已停止")
			}
		}
	}

	private suspend fun pickSpeakers(userText: String, settings: AicpSettings): List<PersonaEntity> {
		val refs = conversationRepository.participants(conversationId)
		if (refs.isEmpty()) return emptyList()

		val personas = personaRepository.getByIds(refs.map { it.personaId }).associateBy { it.id }
		val messages = chatRepository.observeMessages(conversationId).first()
		val lastSpoke = messages
			.filter { it.role == ChatRole.ASSISTANT && it.personaId != null }
			.groupBy { it.personaId!! }
			.mapValues { (_, list) -> list.maxOf { it.createdAt } }

		val candidates = refs.mapNotNull { ref ->
			val persona = personas[ref.personaId] ?: return@mapNotNull null
			SpeakerCandidate(
				personaId = ref.personaId,
				name = persona.name,
				weight = ref.speakWeight,
				muted = ref.muted,
				lastSpokeAt = lastSpoke[ref.personaId] ?: 0L,
			)
		}

		return SpeakerScheduler
			.pick(candidates, userText, settings.groupMaxSpeakersPerTurn)
			.mapNotNull { personas[it] }
	}

	/**
	 * 返回 false 表示这轮出错了，群聊时后面的角色就别再发了。
	 *
	 * @param extraInstruction 追加在上下文末尾的一条 system 指令。主动搭话用它告诉模型
	 * "这次是你主动开口"，普通回复传 null。
	 */
	private suspend fun streamOneReply(
		persona: PersonaEntity,
		settings: AicpSettings,
		extraInstruction: String? = null,
	): Boolean {
		val mates = uiState.value.participants.filter { it.id != persona.id }
		val context = contextBuilder.build(
			conversationId = conversationId,
			speaker = persona,
			settings = settings,
			groupMates = mates,
			mood = currentMoodOf(persona.id, settings),
		)
		memoryRepository.markCardsUsed(context.usedCardIds)

		val messageId = chatRepository.startAssistant(conversationId, persona.id)
		transient.update { it.copy(streamingMessageId = messageId, streamingText = "") }

		val buffer = StringBuilder()
		var pendingChars = 0
		var lastFlushAt = 0L

		return try {
			llmProvider.streamChat(
				messages = if (extraInstruction == null) {
					context.messages
				} else {
					context.messages + LlmMessage(ChatRole.SYSTEM, extraInstruction)
				},
				params = LlmParams(
					// 带图这轮必须走视觉模型，普通模型收到 content 数组会直接 400；
					// 所以视觉模型压过性格自己的模型指定
					model = if (context.imageCount > 0) {
						settings.effectiveVisionModel()
					} else {
						persona.modelOverride?.takeIf { it.isNotBlank() } ?: settings.model
					},
					temperature = persona.temperature,
					topP = persona.topP,
					// 主动搭话只说一两句，别让它借着大额度写小作文
					maxTokens = if (extraInstruction == null) {
						persona.maxTokens
					} else {
						minOf(persona.maxTokens, ProactiveDecider.MAX_TOKENS)
					},
				),
			).collect { chunk ->
				when (chunk) {
					is LlmChunk.Delta -> {
						buffer.append(chunk.text)
						transient.update { it.copy(streamingText = buffer.toString()) }

						pendingChars += chunk.text.length
						val nowMs = System.currentTimeMillis()
						if (pendingChars >= STREAM_FLUSH_CHARS || nowMs - lastFlushAt >= STREAM_FLUSH_MS) {
							chatRepository.updateStreaming(messageId, buffer.toString())
							pendingChars = 0
							lastFlushAt = nowMs
						}
					}

					is LlmChunk.Done -> Unit
				}
			}
			// 分段时第一条只留第一段，剩下的稍后作为独立消息陆续发出。
			// 先算好再 finish，避免"全文落库 → 改写成第一段"中间闪一下全文
			val reply = buffer.toString()
			val segments = ReplySegmenter.split(reply, settings.humanizeConfig())

			chatRepository.finishAssistant(messageId, segments.first())
			transient.update { it.copy(streamingMessageId = null, streamingText = "") }
			bumpStickerUsage(reply)
			emitFollowUpSegments(persona, segments.drop(1), settings)
			true
		} catch (e: kotlinx.coroutines.CancellationException) {
			throw e
		} catch (e: Exception) {
			// 半截文本要留下，别让用户以为什么都没发生
			if (buffer.isNotEmpty()) chatRepository.updateStreaming(messageId, buffer.toString())
			chatRepository.failAssistant(messageId, e.message ?: "请求失败")
			transient.update {
				it.copy(
					streamingMessageId = null,
					streamingText = "",
					error = describeError(e),
				)
			}
			false
		}
	}

	/**
	 * 把第一段之后的段落按打字节奏陆续发出来。第一段已经在 streamOneReply 里
	 * 落进原来那条消息了，这里只管后续。
	 *
	 * bumpStickerUsage 已经按全文统计过一次，所以这里的分段落库不再重复统计。
	 *
	 * 中途被 stop() 取消时 CancellationException 会往外抛，剩下的段落就不发了 ——
	 * 用户按停止就是不想再看下文。已经落库的那几条保留。
	 */
	private suspend fun emitFollowUpSegments(
		persona: PersonaEntity,
		followUps: List<String>,
		settings: AicpSettings,
	) {
		if (!settings.humanizeEnabled || followUps.isEmpty()) return
		val config = settings.humanizeConfig()

		followUps.forEach { segment ->
			// typing 标记让 UI 显示"正在输入…"，跟真人打字的间隙对上
			transient.update { it.copy(typingPersonaName = persona.name) }
			delay(ReplySegmenter.typingDelayMs(segment, config))
			transient.update { it.copy(typingPersonaName = null) }
			chatRepository.appendAssistantSegment(conversationId, persona.id, segment)
		}
	}

	// ---------------- 前台主动搭话 ----------------

	/**
	 * 页面在前台时开一个空闲哨兵。
	 *
	 * 用轮询而不是"算出还差多久然后 delay 那么久"：后者要处理用户中途发消息、
	 * 切走再回来、系统改时间这些情况下的重算，每种都是一个 bug 温床。
	 * 每分钟醒一次判一遍状态最笨也最不会错，代价是一个几乎不干活的协程。
	 *
	 * 前台不守免打扰：你自己开着 App 看着屏幕，这不叫打扰。
	 */
	fun startIdleWatch() {
		if (idleJob?.isActive == true) return
		idleJob = viewModelScope.launch {
			while (true) {
				delay(IDLE_CHECK_INTERVAL_MS)
				runCatching { maybeSpeakProactively() }
					.onFailure { Log.w(TAG, "前台主动搭话失败", it) }
			}
		}
	}

	fun stopIdleWatch() {
		idleJob?.cancel()
		idleJob = null
	}

	private suspend fun maybeSpeakProactively() {
		if (transient.value.sending) return

		val settings = settingsStore.current()
		if (!settings.proactiveEnabled || !settings.hasEndpoint) return

		val messages = uiState.value.messages
		if (messages.isEmpty()) return

		val used = settings.proactiveDailyLimit -
			proactiveRepository.remainingQuota(settings.proactiveDailyLimit)

		val decision = ProactiveDecider.decide(
			settings = settings,
			ctx = ProactiveContext(
				participantCount = uiState.value.participants.size,
				trailingAssistantCount = ProactiveDecider.trailingAssistantCount(
					messages.map { it.role == ChatRole.ASSISTANT },
				),
				idleMillis = System.currentTimeMillis() - messages.last().createdAt,
				todayProactiveCount = used,
				hourOfDay = LocalTime.now().hour,
			),
			respectQuietHours = false,
		)
		if (!decision.shouldSpeak) return

		val speaker = pickProactiveSpeaker() ?: return

		transient.update { it.copy(sending = true, error = null) }
		try {
			streamOneReply(speaker, settings, extraInstruction = ProactiveDecider.INSTRUCTION)
			proactiveRepository.recordGlobalProactive()
		} finally {
			transient.update { it.copy(sending = false) }
		}
	}

	/**
	 * 挑一个开口的性格：优先不是上次发言的那个（群聊里免得同一个人自言自语），
	 * 且必须自己允许主动搭话（persona.proactiveEnabled）。
	 */
	private fun pickProactiveSpeaker(): PersonaEntity? {
		val allowed = uiState.value.participants.filter { it.proactiveEnabled }
		if (allowed.isEmpty()) return null

		val lastSpeaker = uiState.value.messages.lastOrNull { it.role == ChatRole.ASSISTANT }?.personaId
		return allowed.firstOrNull { it.id != lastSpeaker } ?: allowed.first()
	}

	/** 取库里的心情并先做时间衰减 —— 隔了半天再聊，情绪本来就该平复一些 */	private suspend fun currentMoodOf(personaId: Long, settings: AicpSettings): Int {
		if (!settings.humanizeEnabled) return MoodTracker.NEUTRAL
		val ref = conversationRepository.moodOf(conversationId, personaId) ?: return MoodTracker.NEUTRAL
		return MoodTracker.decay(ref.mood, ref.moodUpdatedAt, System.currentTimeMillis())
	}

	/** 用户这句话对每个参与者的心情都算一次。群聊里大家听到的是同一句话 */
	private suspend fun updateMoodsFor(userMessage: String, settings: AicpSettings) {
		if (!settings.humanizeEnabled || userMessage.isBlank()) return
		val now = System.currentTimeMillis()
		uiState.value.participants.forEach { persona ->
			val ref = conversationRepository.moodOf(conversationId, persona.id) ?: return@forEach
			val next = MoodTracker.next(ref.mood, ref.moodUpdatedAt, now, userMessage)
			if (next != ref.mood) {
				conversationRepository.updateMood(conversationId, persona.id, next, now)
			}
		}
	}

	/**
	 * 模型真把标记写进回复里才算用过一次。
	 * 只是出现在候选清单里不算 —— 那样所有表情的计数会一起涨，排序就失去意义了。
	 */
	private suspend fun bumpStickerUsage(reply: String) {
		val known = uiState.value.stickerIndex.keys
		if (known.isEmpty()) return
		runCatching { stickerRepository.bumpUsage(StickerParser.labelsIn(reply, known)) }
	}

	private fun describeError(e: Exception): String = when {
		e is LlmException && e.kind == LlmException.Kind.NO_CONFIG -> "还没配置接口，去设置页填 Base URL 和 Key"
		e is LlmException && e.kind == LlmException.Kind.CLEARTEXT_BLOCKED -> e.message ?: "地址被安全策略拒绝"
		e is LlmException && e.kind.retryable -> "${e.message}（可以点重试）"
		else -> e.message ?: "请求失败"
	}

	/** 重试：把最后一条失败的助手消息删掉，用它之前的上下文重新生成 */
	fun retryLast() {
		if (transient.value.sending) return
		val failed = uiState.value.messages.lastOrNull { it.status == MessageStatus.FAILED } ?: return

		transient.update { it.copy(sending = true, error = null) }
		sendJob = viewModelScope.launch {
			try {
				val settings = settingsStore.current()
				chatRepository.deleteMessage(failed.id)
				val persona = failed.personaId?.let { personaRepository.getById(it) }
					?: uiState.value.participants.firstOrNull()
				if (persona == null) {
					transient.update { it.copy(error = "找不到要重试的性格") }
					return@launch
				}
				streamOneReply(persona, settings)
				runCompression(settings)
			} catch (e: Exception) {
				if (e is kotlinx.coroutines.CancellationException) throw e
				transient.update { it.copy(error = e.message ?: "重试失败") }
			} finally {
				transient.update { it.copy(sending = false) }
			}
		}
	}

	fun deleteMessage(messageId: Long) {
		viewModelScope.launch { chatRepository.deleteMessage(messageId) }
	}

	fun clearFailed() {
		viewModelScope.launch { chatRepository.clearFailed(conversationId) }
	}

	/** 设置页之外的手动入口：用户主动"整理记忆" */
	fun compressNow() {
		viewModelScope.launch {
			transient.update { it.copy(notice = "正在整理记忆…") }
			val settings = settingsStore.current()
			val result = compressor.compressIfNeeded(conversationId, settings, force = true)
			transient.update { it.copy(notice = describeCompression(result, manual = true)) }
		}
	}

	fun setParticipantMuted(personaId: Long, muted: Boolean) {
		viewModelScope.launch {
			conversationRepository.setParticipantMuted(conversationId, personaId, muted)
		}
	}

	fun addParticipant(personaId: Long) {
		viewModelScope.launch {
			runCatching { conversationRepository.addParticipant(conversationId, personaId) }
				.onFailure { e -> transient.update { it.copy(error = e.message ?: "拉人失败") } }
		}
	}

	fun removeParticipant(personaId: Long) {
		viewModelScope.launch {
			if (uiState.value.participants.size <= 1) {
				transient.update { it.copy(error = "至少要留一个性格在会话里") }
				return@launch
			}
			conversationRepository.removeParticipant(conversationId, personaId)
		}
	}

	fun rename(title: String) {
		viewModelScope.launch { conversationRepository.rename(conversationId, title) }
	}

	// ---------------- 群聊资料（名称 + 头像） ----------------

	/**
	 * 打开「会话资料」对话框。
	 *
	 * 只有群聊能开：单聊的名字和头像跟着那个性格走，要改就去性格编辑页改。
	 * 两个地方都能改同一样东西的话，用户永远搞不清自己改的是哪个、为什么另一处没变。
	 */
	fun openGroupProfile() {
		val conv = uiState.value.conversation ?: return
		if (!uiState.value.isGroup) return
		transient.update {
			it.copy(
				profileDraft = GroupProfileDraft(
					title = conv.title,
					avatarEmoji = conv.avatarEmoji,
					avatarPath = conv.avatarPath,
					originalPath = conv.avatarPath,
				),
			)
		}
	}

	/** 关掉对话框。这一轮新落盘、又没点确定的头像图要删掉，别在磁盘上留孤儿 */
	fun closeGroupProfile() {
		val draft = transient.value.profileDraft
		transient.update { it.copy(profileDraft = null) }

		val stale = draft?.avatarPath?.takeIf { it != draft.originalPath } ?: return
		viewModelScope.launch { attachmentStore.delete(listOf(stale)) }
	}

	fun onGroupProfileTitleChange(value: String) = transient.update {
		it.copy(profileDraft = it.profileDraft?.copy(title = value))
	}

	/** 传空串就是把 emoji 清掉，回到名字首字那层兜底 */
	fun onGroupProfileEmojiChange(emoji: String) = transient.update {
		it.copy(profileDraft = it.profileDraft?.copy(avatarEmoji = emoji))
	}

	/**
	 * 选完图立刻压缩落盘，跟聊天里发图一个套路：用户马上看到预览，
	 * "这不是图片/压完还太大"这类失败也当场就报出来，而不是等点了确定才崩。
	 */
	fun pickGroupAvatar(uri: Uri) {
		val draft = transient.value.profileDraft ?: return
		viewModelScope.launch {
			transient.update { it.copy(profileDraft = it.profileDraft?.copy(saving = true), error = null) }
			runCatching { attachmentStore.saveAvatar(uri) }
				.onSuccess { saved ->
					// 连着换图时把上一张草稿图删掉，只留最后选定的那张
					draft.avatarPath?.takeIf { it != draft.originalPath }
						?.let { attachmentStore.delete(listOf(it)) }
					transient.update {
						it.copy(profileDraft = it.profileDraft?.copy(avatarPath = saved.localPath, saving = false))
					}
				}
				.onFailure { e ->
					transient.update {
						it.copy(
							profileDraft = it.profileDraft?.copy(saving = false),
							error = "这张头像没能存下来：${e.message}",
						)
					}
				}
		}
	}

	/**
	 * 清掉图片头像，退回 emoji。
	 * 库里那张旧图不在这里删 —— 用户可能点了取消，删早了就找不回来了；
	 * 真正的删除交给保存时的 updateGroupProfile。
	 */
	fun clearGroupAvatarImage() {
		val draft = transient.value.profileDraft ?: return
		transient.update { it.copy(profileDraft = it.profileDraft?.copy(avatarPath = null)) }

		val stale = draft.avatarPath?.takeIf { it != draft.originalPath } ?: return
		viewModelScope.launch { attachmentStore.delete(listOf(stale)) }
	}

	/** 保存群资料。落库失败时草稿留着，用户不用把名字重打一遍 */
	fun saveGroupProfile() {
		val draft = transient.value.profileDraft ?: return
		viewModelScope.launch {
			runCatching {
				conversationRepository.updateGroupProfile(
					id = conversationId,
					title = draft.title,
					avatarEmoji = draft.avatarEmoji,
					avatarPath = draft.avatarPath,
				)
			}
				.onSuccess { transient.update { it.copy(profileDraft = null, notice = "已更新会话资料") } }
				.onFailure { e -> transient.update { it.copy(error = e.message ?: "会话资料没保存上") } }
		}
	}

	private suspend fun runCompression(settings: AicpSettings) {
		val result = compressor.compressIfNeeded(conversationId, settings)
		val hint = describeCompression(result, manual = false)
		if (hint != null) transient.update { it.copy(notice = hint) }
	}

	private fun describeCompression(result: CompressionResult, manual: Boolean): String? = when (result) {
		is CompressionResult.Compressed -> buildString {
			append("已把 ${result.compressedMessages} 条旧对话压成记忆")
			if (result.cardsWritten > 0) append("，记住了 ${result.cardsWritten} 条新信息")
			if (result.mergedSummaries > 0) append("，并合并了 ${result.mergedSummaries} 段旧摘要")
		}

		is CompressionResult.Failed -> "记忆整理失败：${result.reason}"
		is CompressionResult.NotNeeded -> if (manual) "暂时不用整理：${result.reason}" else null
	}

	companion object {
		private const val TAG = "ChatViewModel"
		private const val STREAM_FLUSH_CHARS = 12
		private const val STREAM_FLUSH_MS = 120L

		/** 空闲哨兵的轮询间隔。一分钟一次足够，主动搭话的阈值是按小时算的 */
		private const val IDLE_CHECK_INTERVAL_MS = 60_000L

		fun factory(conversationId: Long): (CreationExtras) -> ChatViewModel = { _ ->
			val c = AicpApplication.container()
			ChatViewModel(
				conversationId = conversationId,
				chatRepository = c.chatRepository,
				conversationRepository = c.conversationRepository,
				personaRepository = c.personaRepository,
				memoryRepository = c.memoryRepository,
				settingsStore = c.settingsStore,
				contextBuilder = c.contextBuilder,
				compressor = c.memoryCompressor,
				llmProvider = c.llmProvider,
				attachmentStore = c.attachmentStore,
				textExtractor = c.textExtractor,
				stickerRepository = c.stickerRepository,
				proactiveRepository = c.proactiveRepository,
			)
		}

		fun viewModelFactoryFor(conversationId: Long) = viewModelFactory {
			initializer { factory(conversationId)(this) }
		}
	}
}
