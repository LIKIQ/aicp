// app/src/main/java/com/kiq/aicp/ui/chat/ChatScreen.kt
// 聊天页。
//
// 流式渲染取的是 state.displayContent(message)：正在生成的那条用 ViewModel 里的实时文本，
// 其余的读库。所以打字机效果不受 Room 写入节流影响。
//
// 自动滚动的 key 里带了 streamingText.length / 40，让生成过程中每隔几十个字跟一次底，
// 而不是每个 token 都触发一次动画。
//
// 顶栏头像和「会话资料」入口的分工：群聊有自己的名字和头像，单聊直接借对面性格的，
// 所以菜单里那一项只对群聊放出来（细节见 GroupProfileDialog 上面的注释）。
//
// 气泡左边的头像点一下弹 PersonaProfileDialog（单聊群聊都能点）。里面的「编辑」要跳性格编辑页，
// 靠 onEditPersona 抛给导航层；这个参数有默认空实现，导航层没接线时点了只是没反应，不会崩。
//
// 尺寸全走 Dimens，页面里不再手写 dp（除了 emoji 快选那个 40dp 的方格，见 EmojiChoice）。

package com.kiq.aicp.ui.chat

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kiq.aicp.R
import com.kiq.aicp.data.db.entity.MessageEntity
import com.kiq.aicp.data.db.entity.PersonaEntity
import com.kiq.aicp.domain.model.MessageStatus
import com.kiq.aicp.ui.common.Avatar
import com.kiq.aicp.ui.theme.Dimens
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
	conversationId: Long,
	onBack: () -> Unit,
	onOpenSettings: () -> Unit,
	/**
	 * 资料卡里的「编辑」跳性格编辑页，路由由导航层给（Routes.personaEdit(personaId)）。
	 * 给默认空实现是为了不强迫导航层同步改：没接线时点了只是没反应，不会崩。
	 */
	onEditPersona: (Long) -> Unit = {},
) {
	val viewModel: ChatViewModel = viewModel(
		factory = ChatViewModel.viewModelFactoryFor(conversationId),
	)
	val state by viewModel.uiState.collectAsStateWithLifecycle()
	val allPersonas by viewModel.allPersonas.collectAsStateWithLifecycle()
	val stickers by viewModel.stickers.collectAsStateWithLifecycle()

	val listState = rememberLazyListState()
	val snackbar = remember { SnackbarHostState() }
	val clipboard = LocalClipboardManager.current
	var menuOpen by remember { mutableStateOf(false) }
	var participantsOpen by remember { mutableStateOf(false) }

	// 资料卡记 id 不记对象：每次重组从 participants 里现查，卡片开着的时候
	// 在别处改了备注或换了头像，这里立刻就是新的
	var profilePersonaId by remember { mutableStateOf<Long?>(null) }

	// 长按气泡后要操作的那条消息。存整个实体而不是 id：菜单里要显示它的时间和内容长度，
	// 而这条消息可能在菜单开着的时候就被压缩标记改了状态，拿旧快照反而稳定
	var actionMessage by remember { mutableStateOf<MessageEntity?>(null) }

	// Photo Picker 回调里拿不到"用户点的是哪个菜单项"，只能在拉起前把档位记下来。
	// 「截图」菜单项撤掉之后它恒为 false —— 状态和 attachImage 的参数一起留着，
	// 将来改成按图片内容自动判别时只要在这里赋值，下面的调用链一行都不用动
	var pendingTextHeavy by remember { mutableStateOf(false) }
	var stickerPanelOpen by remember { mutableStateOf(false) }

	// maxItems 报剩余配额，让用户在系统选择器里就选不超。但它不接受小于 2 的上限
	// （PickMultipleVisualMedia 构造时直接 require 掉），所以配额剩 1 时照样报 2，
	// 多出来的那张由 ViewModel 的配额守卫拦下并计进失败汇总。
	//
	// contract 必须 remember 住：每次重组都 new 一个的话，
	// rememberLauncherForActivityResult 里那个以 contract 为 key 的 DisposableEffect
	// 会跟着反复注销重注册，白折腾还容易丢结果
	val imagePickMaxItems = state.remainingAttachmentQuota.coerceAtLeast(2)
	val imagePickContract = remember(imagePickMaxItems) {
		ActivityResultContracts.PickMultipleVisualMedia(imagePickMaxItems)
	}

	// PickVisualMedia 系列不需要任何存储权限，系统相册进程直接给一批临时可读 uri
	val imagePicker = rememberLauncherForActivityResult(imagePickContract) { uris ->
		if (uris.isNotEmpty()) viewModel.attachImages(uris, pendingTextHeavy)
	}

	val filePicker = rememberLauncherForActivityResult(
		ActivityResultContracts.OpenDocument(),
	) { uri -> uri?.let { viewModel.attachFile(it) } }

	// 群头像单独一个 launcher。跟发图那个共用的话，回调里就分不清这次选的图
	// 是要当附件发出去还是拿来当头像；而且头像只要一张，用不着多选那个 contract
	val avatarPicker = rememberLauncherForActivityResult(
		ActivityResultContracts.PickVisualMedia(),
	) { uri -> uri?.let { viewModel.pickGroupAvatar(it) } }

	LaunchedEffect(state.messages.size, state.streamingText.length / 40) {
		if (state.messages.isNotEmpty()) {
			listState.animateScrollToItem(state.messages.lastIndex)
		}
	}

	// 只在页面真正可见时跑空闲哨兵：切到别的 tab 或者按 home 就停，
	// 不然用户压根没在看这个会话，它却在后台自己开口
	LifecycleResumeEffect(Unit) {
		viewModel.startIdleWatch()
		onPauseOrDispose { viewModel.stopIdleWatch() }
	}

	LaunchedEffect(state.error) {
		state.error?.let {
			snackbar.showSnackbar(it)
			viewModel.dismissError()
		}
	}

	LaunchedEffect(state.notice) {
		state.notice?.let {
			snackbar.showSnackbar(it)
			viewModel.dismissNotice()
		}
	}

	Scaffold(
		topBar = {
			TopAppBar(
				title = {
					Row(verticalAlignment = Alignment.CenterVertically) {
						Avatar(
							emoji = state.avatarEmoji,
							imagePath = state.avatarPath,
							fallbackName = state.title,
							resolveFile = viewModel::resolveAttachment,
							size = Dimens.avatarTopBar,
						)
						Spacer(Modifier.width(Dimens.spaceSm))
						// weight 是给头像腾位置之后必须补的：不给的话长标题会被 title 区域
						// 硬裁掉一半，连省略号都出不来
						Column(modifier = Modifier.weight(1f)) {
							Text(
								state.title,
								style = MaterialTheme.typography.titleMedium,
								maxLines = 1,
								overflow = TextOverflow.Ellipsis,
							)
							if (state.isGroup) {
								Text(
									state.participants.joinToString(" ") { "${it.avatarEmoji}${it.name}" },
									style = MaterialTheme.typography.labelSmall,
									color = MaterialTheme.colorScheme.outline,
									maxLines = 1,
								)
							} else if (state.soloNote.isNotBlank()) {
								// 备注为空就整行不画，免得标题下面吊着一道空白
								Text(
									state.soloNote,
									style = MaterialTheme.typography.labelSmall,
									color = MaterialTheme.colorScheme.outline,
									maxLines = 1,
									overflow = TextOverflow.Ellipsis,
								)
							}
						}
					}
				},
				navigationIcon = {
					IconButton(onClick = onBack) {
						Icon(painterResource(R.drawable.ic_back), contentDescription = "返回")
					}
				},
				actions = {
					IconButton(onClick = { menuOpen = true }) {
						Icon(painterResource(R.drawable.ic_more), contentDescription = "更多")
					}
					DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
						DropdownMenuItem(
							text = { Text("立即整理记忆") },
							onClick = {
								menuOpen = false
								viewModel.compressNow()
							},
						)
						DropdownMenuItem(
							text = { Text("参与者") },
							onClick = {
								menuOpen = false
								participantsOpen = true
							},
						)
						// 只有群聊才有「会话资料」：单聊的名字和头像跟着那个性格走，
						// 要改就去性格编辑页改。两个入口都能改同一样东西的话，
						// 用户永远搞不清自己改的是哪个、为什么另一处没跟着变
						if (state.isGroup) {
							DropdownMenuItem(
								text = { Text("会话资料") },
								onClick = {
									menuOpen = false
									viewModel.openGroupProfile()
								},
							)
						}
						DropdownMenuItem(
							text = { Text("清理失败消息") },
							onClick = {
								menuOpen = false
								viewModel.clearFailed()
							},
						)
					}
				},
			)
		},
		bottomBar = {
			Column {
				PendingAttachmentStrip(
					attachments = state.attachments,
					resolveAttachment = viewModel::resolveAttachment,
					onRemove = viewModel::removeAttachment,
				)
				ChatInputBar(
					input = state.input,
					sending = state.sending,
					canSend = state.canSend,
					attaching = state.attaching,
					stickerPanelOpen = stickerPanelOpen,
					onInputChange = viewModel::onInputChange,
					onSend = viewModel::send,
					onStop = viewModel::stop,
					onPickImage = { textHeavy ->
						pendingTextHeavy = textHeavy
						imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
					},
					onPickFile = { filePicker.launch(FILE_PICKER_MIME_TYPES) },
					onToggleStickerPanel = { stickerPanelOpen = !stickerPanelOpen },
				)
				if (stickerPanelOpen) {
					StickerPanel(
						stickers = stickers,
						resolveFile = viewModel::resolveAttachment,
						onPick = viewModel::appendStickerMarker,
					)
				}
			}
		},
		snackbarHost = { SnackbarHost(snackbar) },
	) { innerPadding ->
		Column(
			modifier = Modifier
				.fillMaxSize()
				.padding(innerPadding),
		) {
			if (state.notConfigured) {
				Surface(color = MaterialTheme.colorScheme.errorContainer) {
					Row(
						modifier = Modifier
							.fillMaxWidth()
							.padding(horizontal = Dimens.spaceLg, vertical = Dimens.spaceSm),
						verticalAlignment = Alignment.CenterVertically,
						horizontalArrangement = Arrangement.SpaceBetween,
					) {
						Text(
							"还没配置模型接口",
							style = MaterialTheme.typography.bodyMedium,
							color = MaterialTheme.colorScheme.onErrorContainer,
						)
						TextButton(onClick = onOpenSettings) { Text("去设置") }
					}
				}
			}

			LazyColumn(
				state = listState,
				modifier = Modifier
					.weight(1f)
					.fillMaxWidth(),
				contentPadding = PaddingValues(vertical = Dimens.spaceSm),
			) {
				itemsIndexed(state.messages, key = { _, m -> m.id }) { index, message ->
					// 时间分割线跟消息放在同一个 item 里，而不是单独发一个 item：
					// 分开发的话 key 得再造一套，而且删消息时两个 item 的增删动画会错开
					val previousAt = state.messages.getOrNull(index - 1)?.createdAt ?: 0L
					if (MessageTime.shouldShowDivider(message.createdAt, previousAt)) {
						TimeDivider(MessageTime.formatDivider(message.createdAt))
					}

					MessageRow(
						message = message,
						displayText = state.displayContent(message),
						persona = state.personaOf(message.personaId),
						showSpeakerName = state.isGroup,
						attachments = state.attachmentsOf(message),
						resolveAttachment = viewModel::resolveAttachment,
						stickerIndex = state.stickerIndex,
						onPersonaClick = { profilePersonaId = it.id },
						onLongPress = { actionMessage = message },
						onRetry = viewModel::retryLast,
						onDelete = { viewModel.deleteMessage(message.id) },
					)
				}
			}

			// 两种"正在输入"：请求刚发出还没吐字（thinking），和分段之间的打字停顿（typingPersonaName）
			val thinking = state.messages.lastOrNull()
				?.takeIf { it.status == MessageStatus.STREAMING && state.streamingText.isEmpty() }
			when {
				state.typingPersonaName != null -> ThinkingHint(state.typingPersonaName!!)
				thinking != null -> ThinkingHint(state.personaOf(thinking.personaId)?.name ?: "对方")
			}
		}
	}

	if (participantsOpen) {
		AlertDialog(
			onDismissRequest = { participantsOpen = false },
			confirmButton = {
				TextButton(onClick = { participantsOpen = false }) { Text("完成") }
			},
			title = { Text("参与者") },
			text = {
				Column(verticalArrangement = Arrangement.spacedBy(Dimens.spaceXs)) {
					state.participants.forEach { persona ->
						Row(verticalAlignment = Alignment.CenterVertically) {
							Text(
								"${persona.avatarEmoji} ${persona.name}",
								modifier = Modifier.weight(1f),
								style = MaterialTheme.typography.bodyLarge,
							)
							TextButton(onClick = { viewModel.removeParticipant(persona.id) }) {
								Text("移出")
							}
						}
					}

					val addable = allPersonas.filter { p -> state.participants.none { it.id == p.id } }
					if (addable.isNotEmpty()) {
						Text(
							"拉进来一起聊",
							style = MaterialTheme.typography.labelMedium,
							color = MaterialTheme.colorScheme.outline,
							modifier = Modifier.padding(top = Dimens.spaceSm),
						)
						addable.forEach { persona ->
							Row(verticalAlignment = Alignment.CenterVertically) {
								Text(
									"${persona.avatarEmoji} ${persona.name}",
									modifier = Modifier.weight(1f),
									style = MaterialTheme.typography.bodyMedium,
								)
								TextButton(onClick = { viewModel.addParticipant(persona.id) }) {
									Text("加入")
								}
							}
						}
					}
				}
			},
		)
	}

	// 查不到就当卡片没开：性格可能在卡片开着的时候被移出会话，
	// 这时候继续画一张"来自不存在的人"的卡片更奇怪
	profilePersonaId?.let { personaId ->
		state.personaOf(personaId)?.let { persona ->
			PersonaProfileDialog(
				persona = persona,
				resolveFile = viewModel::resolveAttachment,
				onEdit = {
					// 先关卡片再跳，不然编辑页起来了它还浮在上面
					profilePersonaId = null
					onEditPersona(persona.id)
				},
				onDismiss = { profilePersonaId = null },
			)
		}
	}

	actionMessage?.let { target ->
		MessageActionDialog(
			message = target,
			displayText = state.displayContent(target),
			onCopy = {
				clipboard.setText(AnnotatedString(state.displayContent(target)))
				actionMessage = null
			},
			onDelete = {
				viewModel.deleteMessage(target.id)
				actionMessage = null
			},
			onDismiss = { actionMessage = null },
		)
	}

	state.profileDraft?.let { draft ->
		GroupProfileDialog(
			draft = draft,
			resolveFile = viewModel::resolveAttachment,
			onTitleChange = viewModel::onGroupProfileTitleChange,
			onEmojiChange = viewModel::onGroupProfileEmojiChange,
			onPickImage = {
				avatarPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
			},
			onClearImage = viewModel::clearGroupAvatarImage,
			onConfirm = viewModel::saveGroupProfile,
			onDismiss = viewModel::closeGroupProfile,
		)
	}
}

/**
 * 角色资料卡：点气泡左边的头像弹出来。
 *
 * 单聊群聊都能点 —— 群聊里是"这谁啊"，单聊里是"我给它写的备注是啥"，都是同一个需求。
 *
 * note 是只给 KIQ 自己看的（永不进 prompt，见 PersonaEntity 上的注释），所以旁边挂一句
 * "只有你能看到"明说这件事；空着就整块不画，免得卡片下面吊一段空白。
 * tagline 同理，空的时候不占行。
 */
@Composable
private fun PersonaProfileDialog(
	persona: PersonaEntity,
	resolveFile: (String) -> File,
	onEdit: () -> Unit,
	onDismiss: () -> Unit,
) {
	AlertDialog(
		onDismissRequest = onDismiss,
		text = {
			Column(
				modifier = Modifier.fillMaxWidth(),
				horizontalAlignment = Alignment.CenterHorizontally,
				verticalArrangement = Arrangement.spacedBy(Dimens.spaceMd),
			) {
				Avatar(
					emoji = persona.avatarEmoji,
					imagePath = persona.avatarPath,
					fallbackName = persona.name,
					resolveFile = resolveFile,
					size = Dimens.avatarLarge,
				)

				// AlertDialog 的 text 槽默认给 onSurfaceVariant，名字得显式提回 onSurface，
				// 不然主标题比副标题还淡
				Text(
					text = persona.name,
					style = MaterialTheme.typography.titleLarge,
					color = MaterialTheme.colorScheme.onSurface,
				)

				if (persona.tagline.isNotBlank()) {
					Text(
						text = persona.tagline,
						style = MaterialTheme.typography.bodyMedium,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
						textAlign = TextAlign.Center,
					)
				}

				if (persona.note.isNotBlank()) {
					Surface(
						modifier = Modifier.fillMaxWidth(),
						shape = RoundedCornerShape(Dimens.radiusSmall),
						color = MaterialTheme.colorScheme.surfaceVariant,
					) {
						Column(
							modifier = Modifier.padding(Dimens.spaceMd),
							verticalArrangement = Arrangement.spacedBy(Dimens.spaceXs),
						) {
							Row(
								verticalAlignment = Alignment.CenterVertically,
								horizontalArrangement = Arrangement.spacedBy(Dimens.spaceXs),
							) {
								Text("备注", style = MaterialTheme.typography.labelMedium)
								Text(
									"只有你能看到",
									style = MaterialTheme.typography.labelSmall,
									color = MaterialTheme.colorScheme.outline,
								)
							}
							Text(
								text = persona.note,
								style = MaterialTheme.typography.bodyMedium,
								color = MaterialTheme.colorScheme.onSurfaceVariant,
							)
						}
					}
				}
			}
		},
		confirmButton = {
			TextButton(onClick = onEdit) { Text("编辑") }
		},
		dismissButton = {
			TextButton(onClick = onDismiss) { Text("关闭") }
		},
	)
}

/**
 * 「会话资料」对话框：改群名称、换群头像。
 *
 * 只给群聊用。单聊的名字和头像来自它唯一的那个性格，改动入口在性格编辑页 ——
 * 同一样东西留两个入口，用户改完一处发现另一处没变，只会以为是 bug。
 *
 * 头像的三种改法都在这儿：点 emoji 换 emoji、从相册选图、清掉图片退回 emoji。
 * 图片优先于 emoji，所以配了图之后点 emoji 是看不出变化的，
 * 提示文案里明说了这件事，免得用户以为点坏了。
 */
@Composable
private fun GroupProfileDialog(
	draft: GroupProfileDraft,
	resolveFile: (String) -> File,
	onTitleChange: (String) -> Unit,
	onEmojiChange: (String) -> Unit,
	onPickImage: () -> Unit,
	onClearImage: () -> Unit,
	onConfirm: () -> Unit,
	onDismiss: () -> Unit,
) {
	AlertDialog(
		onDismissRequest = onDismiss,
		title = { Text("会话资料") },
		text = {
			Column(verticalArrangement = Arrangement.spacedBy(Dimens.spaceMd)) {
				Row(
					verticalAlignment = Alignment.CenterVertically,
					horizontalArrangement = Arrangement.spacedBy(Dimens.spaceMd),
				) {
					Avatar(
						emoji = draft.avatarEmoji,
						imagePath = draft.avatarPath,
						fallbackName = draft.title,
						resolveFile = resolveFile,
						size = Dimens.avatarLarge,
					)
					Column {
						TextButton(onClick = onPickImage, enabled = !draft.saving) {
							Text(if (draft.avatarPath == null) "从相册选图" else "换一张图")
						}
						if (draft.avatarPath != null) {
							TextButton(onClick = onClearImage, enabled = !draft.saving) { Text("清除图片") }
						}
					}
				}

				OutlinedTextField(
					value = draft.title,
					onValueChange = onTitleChange,
					label = { Text("群名称") },
					singleLine = true,
					modifier = Modifier.fillMaxWidth(),
				)

				Text(
					if (draft.avatarPath == null) "挑个 emoji 当头像" else "挑个 emoji（当前有图片，图片优先显示）",
					style = MaterialTheme.typography.labelMedium,
					color = MaterialTheme.colorScheme.outline,
				)
				LazyRow(horizontalArrangement = Arrangement.spacedBy(Dimens.spaceSm)) {
					// 第一格是"不用 emoji"，选它就退回名字首字那层兜底
					item {
						EmojiChoice(
							label = "无",
							selected = draft.avatarEmoji.isBlank(),
							onClick = { onEmojiChange("") },
						)
					}
					items(GROUP_AVATAR_EMOJI) { emoji ->
						EmojiChoice(
							label = emoji,
							selected = draft.avatarEmoji == emoji,
							onClick = { onEmojiChange(emoji) },
						)
					}
				}
			}
		},
		confirmButton = {
			TextButton(
				onClick = onConfirm,
				enabled = !draft.saving && draft.title.isNotBlank(),
			) { Text(if (draft.saving) "正在存图…" else "确定") }
		},
		dismissButton = {
			TextButton(onClick = onDismiss) { Text("取消") }
		},
	)
}

@Composable
private fun EmojiChoice(label: String, selected: Boolean, onClick: () -> Unit) {
	Surface(
		shape = CircleShape,
		color = if (selected) {
			MaterialTheme.colorScheme.primaryContainer
		} else {
			MaterialTheme.colorScheme.surfaceContainerHighest
		},
	) {
		Row(
			modifier = Modifier
				// 40dp 没换成 Dimens.touchTargetMin（48）：这一排是横着划着挑的快选，
				// 撑到 48 一屏能看到的 emoji 明显变少，划的次数反而更多
				.size(40.dp)
				.clickable(onClick = onClick),
			horizontalArrangement = Arrangement.Center,
			verticalAlignment = Alignment.CenterVertically,
		) {
			Text(label, style = MaterialTheme.typography.titleMedium)
		}
	}
}

// 群头像的 emoji 快选。不做全量 emoji 键盘：真要精挑细选的人会去选图，
// 这里只求够快够顺手，给一屏能横着划完的量
private val GROUP_AVATAR_EMOJI = listOf(
	"👥", "💬", "🎈", "🌙", "🔥", "🍰", "🎧", "🐾",
	"🌊", "🧩", "☕", "✨", "🎮", "📚", "🏔", "🎬",
)

/**
 * 长按气泡后的操作菜单。
 *
 * 用对话框而不是 DropdownMenu：菜单要贴着长按位置弹，而 Compose 里拿长按坐标
 * 得自己接 pointerInput 算偏移，为三个选项做这些不值得。对话框居中弹出虽然朴素，
 * 但一眼能看清操作对象是哪条消息。
 */
@Composable
private fun MessageActionDialog(
	message: MessageEntity,
	displayText: String,
	onCopy: () -> Unit,
	onDelete: () -> Unit,
	onDismiss: () -> Unit,
) {
	AlertDialog(
		onDismissRequest = onDismiss,
		title = { Text("这条消息") },
		text = {
			Column(verticalArrangement = Arrangement.spacedBy(Dimens.spaceSm)) {
				// 给一小段预览，长按完隔两秒才看清弹窗时还能认出选中的是哪条
				Text(
					text = displayText.take(80).replace('\n', ' ').ifEmpty { "(没有文字内容)" },
					style = MaterialTheme.typography.bodyMedium,
					maxLines = 2,
					overflow = TextOverflow.Ellipsis,
				)
				Text(
					text = MessageTime.formatFull(message.createdAt),
					style = MaterialTheme.typography.labelSmall,
					color = MaterialTheme.colorScheme.outline,
				)
			}
		},
		confirmButton = {
			TextButton(onClick = onCopy, enabled = displayText.isNotEmpty()) { Text("复制文字") }
		},
		dismissButton = {
			Row {
				TextButton(onClick = onDelete) {
					Text("删除", color = MaterialTheme.colorScheme.error)
				}
				TextButton(onClick = onDismiss) { Text("取消") }
			}
		},
	)
}

// 放 */* 而不是枚举一堆 mime：.kt/.gradle 这类文件系统压根不给 mime，
// 枚举反而会让它们在选择器里变灰。选错格式由 attachFile 拦下来给提示。
private val FILE_PICKER_MIME_TYPES = arrayOf("*/*")
