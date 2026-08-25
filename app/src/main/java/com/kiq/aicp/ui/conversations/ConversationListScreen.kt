// app/src/main/java/com/kiq/aicp/ui/conversations/ConversationListScreen.kt
// 会话列表。新建入口做成一个对话框：先勾性格，勾一个是单聊，勾多个直接开群聊。
//
// 卡片左边那个头像：群聊用会话自己设的，单聊借对面性格的，两者都没配就落到名字首字。
// 具体怎么算见 ConversationListUiState.avatarEmojiOf / avatarPathOf。
//
// ---- 全应用的卡片配色规则，从这里开始，其他页照这个来 ----
// 普通卡片：surfaceContainerLow。它比 surface 高一档，能从背景里浮起来，又不抢内容的注意力。
// 强调态卡片：secondaryContainer。只用在"用户自己标出来的那几条"上 —— 置顶的会话、锁定的记忆卡。
// 一律显式写 containerColor，不吃 Card 的默认值：默认色跟 surfaceContainerLow 差一档，
// 混着用的结果就是同一屏里有两种白，说不出哪里不对但看着脏。
//
// 卡片内边距也在这里定个调：内容卡（设置页那种 SectionCard）四边都是 spaceLg；
// 列表卡垂直收到 spaceMd、右边收到 spaceXs —— 右边挂着的 IconButton / TextButton 自带十几 dp 内缩，
// 再给 16 的话图标会离卡边差不多 30dp，看着像右边少画了一列东西。

package com.kiq.aicp.ui.conversations

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kiq.aicp.R
import com.kiq.aicp.data.db.entity.ConversationEntity
import com.kiq.aicp.domain.model.ConversationMode
import com.kiq.aicp.ui.common.Avatar
import com.kiq.aicp.ui.settings.EmptyState
import com.kiq.aicp.ui.theme.Dimens
import java.io.File

/**
 * 列表底部要给 FAB 让出的高度：FAB 本体 56 + Scaffold 给它的 16 边距 + 再留 16 让最后一项不贴着它。
 * 不留够的话最后一条会话永远被 FAB 压住半边，用户得先划一下才能点。
 */
private val FabClearance = 88.dp

/** 性格选择列表的高度上限。性格多了对话框会顶到屏幕边，卡住让它内部滚 */
private val PickerMaxHeight = 380.dp

@Composable
fun ConversationListScreen(
	onOpenConversation: (Long) -> Unit,
	viewModel: ConversationListViewModel = viewModel(factory = ConversationListViewModel.Factory),
) {
	val state by viewModel.uiState.collectAsStateWithLifecycle()
	val pendingOpen by viewModel.pendingOpen.collectAsStateWithLifecycle()
	var pickerOpen by remember { mutableStateOf(false) }

	LaunchedEffect(pendingOpen) {
		pendingOpen?.let {
			viewModel.consumeOpen()
			pickerOpen = false
			onOpenConversation(it)
		}
	}

	Scaffold(
		floatingActionButton = {
			// 用不带 icon 槽的那个重载：传 icon = {} 时 M3 照样会插一段图标与文字的间隔，
			// 文字就被顶得偏右，跟性格页、表情包页的 FAB 对不齐
			ExtendedFloatingActionButton(onClick = { pickerOpen = true }) {
				Text("开始新对话")
			}
		},
	) { innerPadding ->
		Box(
			modifier = Modifier
				.fillMaxSize()
				.padding(innerPadding),
		) {
			if (state.isEmpty) {
				EmptyState(
					emoji = "💬",
					title = "还没有会话",
					description = "点右下角挑一个性格开聊；勾多个就是群聊",
				)
			} else {
				LazyColumn(
					contentPadding = PaddingValues(
						start = Dimens.screenPadding,
						end = Dimens.screenPadding,
						top = Dimens.screenPadding,
						bottom = FabClearance,
					),
					verticalArrangement = Arrangement.spacedBy(Dimens.spaceSm),
				) {
					items(state.conversations, key = { it.id }) { conversation ->
						ConversationCard(
							conversation = conversation,
							avatarEmoji = state.avatarEmojiOf(conversation),
							avatarPath = state.avatarPathOf(conversation),
							resolveFile = viewModel::resolveAvatar,
							onClick = { onOpenConversation(conversation.id) },
							onTogglePin = { viewModel.togglePin(conversation) },
							onArchive = { viewModel.archive(conversation) },
							onDelete = { viewModel.delete(conversation) },
						)
					}
				}
			}
		}
	}

	if (pickerOpen) {
		val selected = remember { mutableStateListOf<Long>() }
		AlertDialog(
			onDismissRequest = { pickerOpen = false },
			title = { Text("选择性格") },
			text = {
				Column(
					modifier = Modifier.heightIn(max = PickerMaxHeight),
					verticalArrangement = Arrangement.spacedBy(Dimens.spaceSm),
				) {
					Text(
						"勾一个是一对一，勾多个开群聊",
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.outline,
					)
					LazyColumn {
						items(state.personas, key = { it.id }) { persona ->
							Row(
								modifier = Modifier
									.fillMaxWidth()
									.heightIn(min = Dimens.touchTargetMin)
									.clickable {
										if (selected.contains(persona.id)) {
											selected.remove(persona.id)
										} else {
											selected.add(persona.id)
										}
									},
								verticalAlignment = Alignment.CenterVertically,
							) {
								Checkbox(
									checked = selected.contains(persona.id),
									onCheckedChange = { checked ->
										if (checked) selected.add(persona.id) else selected.remove(persona.id)
									},
								)
								Column(modifier = Modifier.weight(1f)) {
									Text(
										"${persona.avatarEmoji} ${persona.name}",
										style = MaterialTheme.typography.bodyLarge,
									)
									if (persona.tagline.isNotBlank()) {
										Text(
											persona.tagline,
											style = MaterialTheme.typography.bodySmall,
											color = MaterialTheme.colorScheme.outline,
											maxLines = 1,
											overflow = TextOverflow.Ellipsis,
										)
									}
								}
							}
						}
					}
				}
			},
			confirmButton = {
				TextButton(
					enabled = selected.isNotEmpty(),
					onClick = {
						if (selected.size == 1) {
							viewModel.startSingle(selected.first())
						} else {
							viewModel.startGroup(selected.toList())
						}
					},
				) { Text(if (selected.size > 1) "开群聊" else "开聊") }
			},
			dismissButton = {
				TextButton(onClick = { pickerOpen = false }) { Text("取消") }
			},
		)
	}
}

@Composable
private fun ConversationCard(
	conversation: ConversationEntity,
	avatarEmoji: String,
	avatarPath: String?,
	resolveFile: (String) -> File,
	onClick: () -> Unit,
	onTogglePin: () -> Unit,
	onArchive: () -> Unit,
	onDelete: () -> Unit,
) {
	var menuOpen by remember { mutableStateOf(false) }

	Card(
		modifier = Modifier
			.fillMaxWidth()
			.clickable(onClick = onClick),
		shape = RoundedCornerShape(Dimens.radiusCard),
		colors = CardDefaults.cardColors(
			// 置顶是用户自己标的，走强调色；其余走普通卡片色（规则见文件头）
			containerColor = if (conversation.pinned) {
				MaterialTheme.colorScheme.secondaryContainer
			} else {
				MaterialTheme.colorScheme.surfaceContainerLow
			},
		),
	) {
		Row(
			modifier = Modifier
				.fillMaxWidth()
				.heightIn(min = Dimens.touchTargetMin)
				.padding(
					start = Dimens.spaceLg,
					end = Dimens.spaceXs,
					top = Dimens.spaceMd,
					bottom = Dimens.spaceMd,
				),
			verticalAlignment = Alignment.CenterVertically,
		) {
			Avatar(
				emoji = avatarEmoji,
				imagePath = avatarPath,
				fallbackName = conversation.title,
				resolveFile = resolveFile,
				size = Dimens.avatarList,
			)
			Spacer(Modifier.width(Dimens.spaceMd))

			Column(modifier = Modifier.weight(1f)) {
				Row(
					verticalAlignment = Alignment.CenterVertically,
					horizontalArrangement = Arrangement.spacedBy(Dimens.spaceXs),
				) {
					Text(
						conversation.title,
						style = MaterialTheme.typography.titleMedium,
						maxLines = 1,
						overflow = TextOverflow.Ellipsis,
						modifier = Modifier.weight(1f, fill = false),
					)
					if (conversation.mode == ConversationMode.GROUP) {
						Text(
							"群聊",
							style = MaterialTheme.typography.labelSmall,
							color = MaterialTheme.colorScheme.primary,
						)
					}
					if (conversation.pinned) {
						Text(
							"置顶",
							style = MaterialTheme.typography.labelSmall,
							color = MaterialTheme.colorScheme.outline,
						)
					}
				}
				Text(
					conversation.lastMessagePreview.ifBlank { "还没说话" },
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.outline,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
				)
			}

			Box {
				// 换成 IconButton + 竖三点，跟聊天页顶栏的更多菜单是同一个图标；
				// 原来的 TextButton("…") 带着按钮的最小宽度，把标题区往里挤了一截
				IconButton(onClick = { menuOpen = true }) {
					Icon(painterResource(R.drawable.ic_more), contentDescription = "更多操作")
				}
				DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
					DropdownMenuItem(
						text = { Text(if (conversation.pinned) "取消置顶" else "置顶") },
						onClick = {
							menuOpen = false
							onTogglePin()
						},
					)
					DropdownMenuItem(
						text = { Text("归档") },
						onClick = {
							menuOpen = false
							onArchive()
						},
					)
					DropdownMenuItem(
						text = { Text("删除（连带记忆）") },
						onClick = {
							menuOpen = false
							onDelete()
						},
					)
				}
			}
		}
	}
}
