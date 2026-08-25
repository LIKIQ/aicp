// app/src/main/java/com/kiq/aicp/ui/chat/ChatComponents.kt
// 聊天页的气泡、输入栏、打字指示器。
//
// 气泡配色不用 primary：那是按钮色，聊天两侧都用它会跟操作元素撞在一起，
// 所以单独在 Theme 里开了 LocalBubbleColors。
//
// 尺寸一律走 Dimens，页面里不再手写 dp。原来这里 12/8/4 混着来，单看每处都正常，
// 一屏滑下来就是"说不清哪里别扭"。唯一两个例外（气泡尖角、附件缩略图）在文件末尾，
// 各自写了为什么没进全局标尺。
//
// AI 那侧的头像可点，弹角色资料卡；卡片本体在 ChatScreen 里，这里只负责把点击抛出去。

package com.kiq.aicp.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.FilledIconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kiq.aicp.R
import com.kiq.aicp.data.attach.AttachmentStore
import com.kiq.aicp.data.db.entity.MessageAttachmentEntity
import com.kiq.aicp.data.db.entity.MessageEntity
import com.kiq.aicp.data.db.entity.PersonaEntity
import com.kiq.aicp.data.db.entity.StickerEntity
import com.kiq.aicp.domain.model.AttachmentKind
import com.kiq.aicp.domain.model.ChatRole
import com.kiq.aicp.domain.model.MessageStatus
import com.kiq.aicp.ui.common.Avatar
import com.kiq.aicp.ui.theme.Dimens
import com.kiq.aicp.ui.theme.LocalBubbleColors
import java.io.File

@Composable
fun MessageRow(
	message: MessageEntity,
	displayText: String,
	persona: PersonaEntity?,
	showSpeakerName: Boolean,
	attachments: List<MessageAttachmentEntity> = emptyList(),
	resolveAttachment: (String) -> File = { File(it) },
	stickerIndex: Map<String, String> = emptyMap(),
	/** 点了 AI 头像。性格已被删的老消息压根不会触发，所以这里拿到的一定是活着的性格 */
	onPersonaClick: (PersonaEntity) -> Unit = {},
	/** 长按气泡。复制、删除这类操作都藏在这后面，气泡上不挂常驻按钮 */
	onLongPress: () -> Unit = {},
	onRetry: () -> Unit,
	onDelete: () -> Unit,
) {
	val fromUser = message.role == ChatRole.USER
	val bubbles = LocalBubbleColors.current
	val failed = message.status == MessageStatus.FAILED
	val streaming = message.status == MessageStatus.STREAMING

	Row(
		modifier = Modifier
			.fillMaxWidth()
			.padding(horizontal = Dimens.spaceMd, vertical = Dimens.spaceXs),
		horizontalArrangement = if (fromUser) Arrangement.End else Arrangement.Start,
		verticalAlignment = Alignment.Top,
	) {
		if (!fromUser) {
			// 性格配了图片头像就显示图，否则回落到 emoji，两者都没有才用名字首字。
			// 这里跟顶栏、会话列表共用同一个组件，免得出现"列表里是照片、气泡里还是 emoji"
			//
			// 点头像看资料卡：性格被删掉的老消息（persona == null）就不给点了，
			// 没内容可展示，弹个空卡片只会让人以为坏了。clip 必须在 clickable 前面，
			// 不然水波纹是个方块，圆头像上特别扎眼
			Avatar(
				emoji = persona?.avatarEmoji.orEmpty(),
				imagePath = persona?.avatarPath,
				fallbackName = persona?.name ?: "?",
				resolveFile = resolveAttachment,
				size = Dimens.avatarBubble,
				modifier = if (persona == null) {
					Modifier
				} else {
					Modifier
						.clip(CircleShape)
						.clickable { onPersonaClick(persona) }
				},
			)
		}

		Column(
			modifier = Modifier
				.padding(horizontal = Dimens.spaceSm)
				.widthIn(max = Dimens.bubbleMaxWidth),
			horizontalAlignment = if (fromUser) Alignment.End else Alignment.Start,
			verticalArrangement = Arrangement.spacedBy(Dimens.spaceXs),
		) {
			if (!fromUser && showSpeakerName) {
				Text(
					text = persona?.name ?: "已删除的性格",
					style = MaterialTheme.typography.labelMedium,
					color = MaterialTheme.colorScheme.outline,
					modifier = Modifier.padding(start = Dimens.spaceXs),
				)
			}

			// 图片不套气泡背景，跟主流 IM 一致：图就是图，气泡会让它显得被框住
			attachments.filter { it.kind == AttachmentKind.IMAGE }.forEach { image ->
				SentImage(file = resolveAttachment(image.localPath), entity = image)
			}

			attachments.filter { it.kind == AttachmentKind.FILE }.forEach { doc ->
				SentFileCard(doc)
			}

			// 只发了一个表情就单独出图，不套气泡 —— 被小气泡框住的表情很局促
			val solo = if (!failed && !streaming) soloStickerOf(displayText, stickerIndex) else null

			// 只发图不打字时没必要再挂一个空气泡
			val hasAttachment = attachments.isNotEmpty()
			if (solo != null) {
				SoloSticker(solo.localPath, solo.label, resolveAttachment)
			} else if (displayText.isNotEmpty() || !hasAttachment) {
				Box(
					modifier = Modifier
						.clip(
							// 三个角走标尺，靠说话人那一侧收成尖角 —— 气泡的朝向全靠这一个角，
							// 四个角一样圆就分不出谁在说话了
							RoundedCornerShape(
								topStart = if (fromUser) Dimens.radiusBubble else BUBBLE_TAIL_RADIUS,
								topEnd = if (fromUser) BUBBLE_TAIL_RADIUS else Dimens.radiusBubble,
								bottomStart = Dimens.radiusBubble,
								bottomEnd = Dimens.radiusBubble,
							),
						)
						.background(
							when {
								failed -> MaterialTheme.colorScheme.errorContainer
								fromUser -> bubbles.user
								else -> bubbles.ai
							},
						)
						// 长按才出操作菜单：气泡上挂常驻的复制/删除按钮会把界面塞满，
						// 而这两个操作的使用频率远低于"读消息"本身。
						// 流式输出中的消息不给长按 —— 那时文本还在变，复制到的是半截话
						.then(
							if (streaming) {
								Modifier
							} else {
								Modifier.combinedClickable(
									onClick = {},
									onLongClick = onLongPress,
								)
							},
						)
						.padding(horizontal = Dimens.spaceMd, vertical = Dimens.spaceSm),
				) {
					val body = displayText.ifEmpty { if (streaming) "…" else "(空)" }
					StickerText(
						text = body,
						// 流式中途不渲染表情：半截的 "[开" 会先被当文字画出来再突然变成图，看着像闪屏
						stickerIndex = if (streaming) emptyMap() else stickerIndex,
						resolveFile = resolveAttachment,
						style = MaterialTheme.typography.bodyLarge,
						color = if (failed) {
							MaterialTheme.colorScheme.onErrorContainer
						} else {
							MaterialTheme.colorScheme.onSurface
						},
					)
				}
			}

			if (failed) {
				Row(verticalAlignment = Alignment.CenterVertically) {
					message.errorMessage?.let {
						Text(
							text = it,
							style = MaterialTheme.typography.labelSmall,
							color = MaterialTheme.colorScheme.error,
							modifier = Modifier.widthIn(max = 180.dp),
						)
					}
					TextButton(onClick = onRetry) { Text("重试") }
					TextButton(onClick = onDelete) { Text("删除") }
				}
			}
		}
	}
}

/** 已发出的图片。宽度固定 200dp，高度按落盘时记下的原始比例还原，避免加载完跳一下 */
@Composable
private fun SentImage(file: File, entity: MessageAttachmentEntity) {
	val ratio = remember(entity.id) {
		val w = entity.width
		val h = entity.height
		if (w > 0 && h > 0) (w.toFloat() / h).coerceIn(0.5f, 2f) else 1f
	}
	val widthDp = 200.dp
	val bitmap by rememberLocalImage(file, targetWidthPx = 600)

	Box(
		modifier = Modifier
			.width(widthDp)
			.height(widthDp / ratio)
			.clip(RoundedCornerShape(Dimens.radiusCard))
			.background(MaterialTheme.colorScheme.surfaceVariant),
		contentAlignment = Alignment.Center,
	) {
		val image = bitmap
		if (image != null) {
			Image(
				bitmap = image,
				contentDescription = "图片附件",
				modifier = Modifier.fillMaxWidth(),
				contentScale = ContentScale.Crop,
			)
		} else {
			CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
		}
	}
}

/** 已发出的文件。只展示文件名和大小，正文已经进了上下文，没必要在气泡里铺开 */
@Composable
private fun SentFileCard(entity: MessageAttachmentEntity) {
	Row(
		modifier = Modifier
			.widthIn(max = 260.dp)
			.clip(RoundedCornerShape(Dimens.radiusCard))
			.background(MaterialTheme.colorScheme.surfaceVariant)
			.padding(horizontal = Dimens.spaceMd, vertical = Dimens.spaceSm),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(Dimens.spaceSm),
	) {
		Icon(
			painter = painterResource(R.drawable.ic_file),
			contentDescription = null,
			modifier = Modifier.size(28.dp),
			tint = MaterialTheme.colorScheme.primary,
		)
		Column {
			Text(
				text = entity.fileName,
				style = MaterialTheme.typography.bodyMedium,
				maxLines = 2,
				overflow = TextOverflow.Ellipsis,
			)
			Text(
				text = buildString {
					append(AttachmentStore.humanSize(entity.byteSize))
					if (entity.truncated) append(" · 内容过长已截断")
				},
				style = MaterialTheme.typography.labelSmall,
				color = MaterialTheme.colorScheme.outline,
			)
		}
	}
}

/**
 * 待发附件预览条。横向滚动，每项右上角一个叉。
 *
 * 图片左下角那个「截图」角标标的是走了截图档（更高分辨率、detail:high）的图。
 * 手动选截图的菜单项已经撤了，所以现在实际看不到它；角标先留着，
 * 等以后按图片内容自动判别接上，用户还是得知道这张走的是哪一档。
 */
@Composable
fun PendingAttachmentStrip(
	attachments: List<PendingAttachmentUi>,
	resolveAttachment: (String) -> File,
	onRemove: (String) -> Unit,
) {
	if (attachments.isEmpty()) return

	Row(
		modifier = Modifier
			.fillMaxWidth()
			.horizontalScroll(rememberScrollState())
			.padding(horizontal = Dimens.spaceMd, vertical = Dimens.spaceXs),
		horizontalArrangement = Arrangement.spacedBy(Dimens.spaceSm),
	) {
		attachments.forEach { item ->
			Box {
				if (item.isImage) {
					val bitmap by rememberLocalImage(resolveAttachment(item.saved.localPath), 240)
					Box(
						modifier = Modifier
							.size(ATTACHMENT_THUMB_SIZE)
							.clip(RoundedCornerShape(Dimens.radiusSmall))
							.background(MaterialTheme.colorScheme.surfaceVariant),
						contentAlignment = Alignment.Center,
					) {
						bitmap?.let {
							Image(
								bitmap = it,
								contentDescription = item.saved.fileName,
								modifier = Modifier.size(ATTACHMENT_THUMB_SIZE),
								contentScale = ContentScale.Crop,
							)
						}
						if (item.textHeavy) {
							Box(
								modifier = Modifier
									.align(Alignment.BottomStart)
									.background(MaterialTheme.colorScheme.primary)
									.padding(horizontal = Dimens.spaceXs),
							) {
								Text(
									text = "截图",
									style = MaterialTheme.typography.labelSmall,
									color = MaterialTheme.colorScheme.onPrimary,
								)
							}
						}
					}
				} else {
					Column(
						modifier = Modifier
							.widthIn(min = ATTACHMENT_THUMB_SIZE, max = 140.dp)
							.heightIn(min = ATTACHMENT_THUMB_SIZE)
							.clip(RoundedCornerShape(Dimens.radiusSmall))
							.background(MaterialTheme.colorScheme.surfaceVariant)
							.padding(Dimens.spaceSm),
						verticalArrangement = Arrangement.spacedBy(Dimens.spaceXs),
					) {
						Icon(
							painter = painterResource(R.drawable.ic_file),
							contentDescription = null,
							modifier = Modifier.size(20.dp),
							tint = MaterialTheme.colorScheme.primary,
						)
						Text(
							text = item.saved.fileName,
							style = MaterialTheme.typography.labelSmall,
							maxLines = 2,
							overflow = TextOverflow.Ellipsis,
						)
						item.extractedText?.let {
							Text(
								text = "${it.length} 字",
								style = MaterialTheme.typography.labelSmall,
								color = MaterialTheme.colorScheme.outline,
							)
						}
					}
				}

				IconButton(
					onClick = { onRemove(item.id) },
					modifier = Modifier
						.align(Alignment.TopEnd)
						.size(22.dp)
						// 原来这里写的是 RoundedCornerShape(11.dp)，也就是手算的"半径等于一半"，
						// 改 CircleShape 之后跟着尺寸走，以后调大小不会变成圆角方块
						.background(MaterialTheme.colorScheme.scrim, CircleShape),
				) {
					Icon(
						painter = painterResource(R.drawable.ic_close),
						contentDescription = "移除附件",
						modifier = Modifier.size(14.dp),
						tint = MaterialTheme.colorScheme.surface,
					)
				}
			}
		}
	}
}

@Composable
fun ChatInputBar(
	input: String,
	sending: Boolean,
	canSend: Boolean,
	attaching: Boolean,
	stickerPanelOpen: Boolean,
	onInputChange: (String) -> Unit,
	onSend: () -> Unit,
	onStop: () -> Unit,
	/**
	 * 拉起相册。textHeavy 这一档现在恒为 false —— 菜单里那个「截图」项撤了，
	 * 参数留着是给"将来按图片内容自动判别"用的，别顺手删。
	 */
	onPickImage: (textHeavy: Boolean) -> Unit,
	onPickFile: () -> Unit,
	onToggleStickerPanel: () -> Unit,
) {
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.padding(horizontal = Dimens.spaceSm, vertical = Dimens.spaceSm),
		verticalAlignment = Alignment.Bottom,
		horizontalArrangement = Arrangement.spacedBy(Dimens.spaceXs),
	) {
		var menuOpen by remember { mutableStateOf(false) }

		Box {
			IconButton(onClick = { menuOpen = true }, enabled = !sending && !attaching) {
				if (attaching) {
					CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
				} else {
					Icon(painterResource(R.drawable.ic_attach), contentDescription = "添加附件")
				}
			}

			DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
				// 这里原来还夹着一项「截图（图里字多，看得更清）」，现在只剩图片和文件两项：
				// 让用户自己判断"这张算不算截图"本来就为难人，两档的差别他也看不出来。
				// 底层没动（AttachmentStore 的截图档、ImageScalePolicy 的 detail:high 都还在），
				// onPickImage 的 textHeavy 也留着，将来自动判别时直接从这里传值
				DropdownMenuItem(
					text = { Text("图片") },
					onClick = {
						menuOpen = false
						onPickImage(false)
					},
				)
				DropdownMenuItem(
					text = { Text("文件（文本 / 代码 / docx / xlsx）") },
					onClick = {
						menuOpen = false
						onPickFile()
					},
				)
			}
		}

		// 直接用 emoji 当图标，省一个 drawable：Compose 本身就能渲染它，
		// 而且面板开着的时候换成键盘图标，用户知道点回去是收起面板
		IconButton(onClick = onToggleStickerPanel, enabled = !sending) {
			Text(
				text = if (stickerPanelOpen) "⌨" else "😊",
				style = MaterialTheme.typography.titleMedium,
			)
		}

		OutlinedTextField(
			value = input,
			onValueChange = onInputChange,
			modifier = Modifier.weight(1f),
			placeholder = { Text("说点什么…") },
			maxLines = 5,
			shape = RoundedCornerShape(Dimens.radiusPill),
		)

		FilledIconButton(
			onClick = if (sending) onStop else onSend,
			enabled = sending || canSend,
		) {
			if (sending) {
				Icon(painterResource(R.drawable.ic_stop), contentDescription = "停止生成")
			} else {
				Icon(painterResource(R.drawable.ic_send), contentDescription = "发送")
			}
		}
	}
}

/**
 * 表情选择面板。点一下把 [标记] 追加到输入框，不直接发送 ——
 * 用户经常是"表情 + 一句话"一起发的。
 *
 * 高度写死是必须的：LazyVerticalGrid 放在高度不受约束的容器里会抛异常，
 * 而 bottomBar 恰好就是这种容器。
 */
@Composable
fun StickerPanel(
	stickers: List<StickerEntity>,
	resolveFile: (String) -> File,
	onPick: (String) -> Unit,
) {
	Surface(
		modifier = Modifier
			.fillMaxWidth()
			.height(220.dp),
		color = MaterialTheme.colorScheme.surfaceVariant,
	) {
		if (stickers.isEmpty()) {
			Column(
				modifier = Modifier
					.fillMaxWidth()
					.padding(Dimens.spaceXl),
				horizontalAlignment = Alignment.CenterHorizontally,
				verticalArrangement = Arrangement.spacedBy(Dimens.spaceSm),
			) {
				Text("还没有表情", style = MaterialTheme.typography.titleSmall)
				Text(
					text = "去「设置 → 表情包」从相册导入。导入后给每张图起一个标记，" +
						"聊天时写 [标记] 就会变成图片，AI 也会知道有哪些表情可用。",
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
					textAlign = TextAlign.Center,
				)
			}
			return@Surface
		}

		LazyVerticalGrid(
			columns = GridCells.Fixed(5),
			modifier = Modifier.fillMaxWidth(),
			contentPadding = PaddingValues(Dimens.spaceSm),
			horizontalArrangement = Arrangement.spacedBy(Dimens.spaceSm),
			verticalArrangement = Arrangement.spacedBy(Dimens.spaceSm),
		) {
			items(stickers, key = { it.id }) { sticker ->
				val bitmap by rememberLocalImage(resolveFile(sticker.localPath), 180)
				Box(
					modifier = Modifier
						.aspectRatio(1f)
						.clip(RoundedCornerShape(Dimens.radiusSmall))
						.background(MaterialTheme.colorScheme.surface)
						.clickable { onPick(sticker.label) },
					contentAlignment = Alignment.Center,
				) {
					val image = bitmap
					if (image != null) {
						Image(
							bitmap = image,
							contentDescription = sticker.label,
							modifier = Modifier.fillMaxWidth(),
							contentScale = ContentScale.Fit,
						)
					} else {
						Text(
							text = sticker.label,
							style = MaterialTheme.typography.labelSmall,
							maxLines = 1,
							overflow = TextOverflow.Ellipsis,
						)
					}
				}
			}
		}
	}
}

/** 时间分割线。居中一行小字，不画横线 —— 横线会把消息流切得很碎 */
@Composable
fun TimeDivider(text: String) {
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.padding(vertical = Dimens.spaceSm),
		horizontalArrangement = Arrangement.Center,
	) {
		Text(
			text = text,
			style = MaterialTheme.typography.labelSmall,
			color = MaterialTheme.colorScheme.outline,
		)
	}
}

@Composable
fun ThinkingHint(personaName: String) {	Row(
		modifier = Modifier
			.fillMaxWidth()
			.padding(horizontal = Dimens.spaceLg, vertical = Dimens.spaceXs),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(Dimens.spaceSm),
	) {
		CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 2.dp)
		Text(
			text = "$personaName 正在想…",
			// 状态类小字跟附件大小、文件字数那些统一走 labelSmall + outline：
			// 它是过程提示，不该跟正文抢注意力
			style = MaterialTheme.typography.labelSmall,
			color = MaterialTheme.colorScheme.outline,
			textAlign = TextAlign.Start,
		)
	}
}

// 气泡尖角那一档故意没进 Dimens：全局最小圆角是 8dp，用在这儿已经圆到看不出朝向了，
// 而这个值只有气泡一处用得上，塞进标尺反而会被别处误用
private val BUBBLE_TAIL_RADIUS = 4.dp

// 待发附件的缩略图边长。跟 Dimens.avatarLarge 恰好都是 72dp，但那是巧合 ——
// 它是缩略图不是头像，哪天头像改大了这里不该跟着动
private val ATTACHMENT_THUMB_SIZE = 72.dp
