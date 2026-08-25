// app/src/main/java/com/kiq/aicp/ui/sticker/StickerScreen.kt
// 表情包管理页：分组、导入、改标记、移到别的组、删除。
//
// 网格没用 LazyVerticalGrid。整页是一个 LazyColumn，组内表情 chunked 成每行四个再交给
// items 铺出来：垂直 LazyColumn 里嵌垂直 LazyVerticalGrid 会直接抛"无限高度约束"，
// 给它写死高度又会变成内层能滚外层不动的怪手感。按行发 item 一样是懒加载，两个坑一起绕开。
//
// 空状态的文案写得比别的页面啰嗦，是因为这里最容易被误解：
// APK 里一张预置表情都没有（那些图有版权，不敢打包进来），必须说清"要自己从相册导入"，
// 以及"标记就是 AI 回复里写的 [标记]"这层因果 —— 不然用户根本不知道导进来能干什么。
//
// 动图只会画出第一帧：缩略图走 BitmapFactory，管理页认得出是哪张就够了，
// 真正发到聊天里也是同一套渲染，这一点跟 KIQ 确认过可以接受。

package com.kiq.aicp.ui.sticker

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kiq.aicp.R
import com.kiq.aicp.data.db.entity.StickerEntity
import com.kiq.aicp.data.db.entity.StickerPackEntity
import com.kiq.aicp.data.repo.StickerRepository
import com.kiq.aicp.ui.chat.rememberLocalImage
import java.io.File

/** 每行四张：手机宽度下再多字就挤到看不清标记了 */
private const val GRID_COLUMNS = 4

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StickerScreen(
	onBack: () -> Unit,
	viewModel: StickerViewModel = viewModel(factory = StickerViewModel.Factory),
) {
	val state by viewModel.uiState.collectAsStateWithLifecycle()
	val snackbar = remember { SnackbarHostState() }

	// 折叠状态只活在这一页：库里没有这个字段，也不值得为它加一列
	var collapsed by remember { mutableStateOf(emptySet<Long>()) }

	var importTarget by remember { mutableStateOf<Long?>(null) }
	var choosingImportPack by remember { mutableStateOf(false) }
	var creatingPack by remember { mutableStateOf(false) }
	var renamingPack by remember { mutableStateOf<StickerPackEntity?>(null) }
	var deletingPack by remember { mutableStateOf<StickerPackGroup?>(null) }
	var acting by remember { mutableStateOf<StickerEntity?>(null) }
	var renamingSticker by remember { mutableStateOf<StickerEntity?>(null) }
	var movingSticker by remember { mutableStateOf<StickerEntity?>(null) }

	// OpenMultipleDocuments 不要任何存储权限，回调里拿到的 uri 当场读盘拷走。
	// importTarget 是 State，回调执行时读到的是最新值，所以点哪个组的"导入"就进哪个组。
	val picker = rememberLauncherForActivityResult(
		ActivityResultContracts.OpenMultipleDocuments(),
	) { uris -> viewModel.importInto(importTarget, uris) }

	fun startImport(packId: Long?) {
		importTarget = packId
		picker.launch(arrayOf("image/*"))
	}

	LaunchedEffect(state.notice) {
		state.notice?.let {
			snackbar.showSnackbar(it)
			viewModel.dismissNotice()
		}
	}

	LaunchedEffect(state.error) {
		state.error?.let {
			snackbar.showSnackbar(it)
			viewModel.dismissError()
		}
	}

	Scaffold(
		topBar = {
			TopAppBar(
				title = { Text("表情包（${state.totalCount}）") },
				navigationIcon = {
					IconButton(onClick = onBack) {
						Icon(painterResource(R.drawable.ic_back), contentDescription = "返回")
					}
				},
				actions = {
					TextButton(onClick = { creatingPack = true }) { Text("新建分组") }
				},
			)
		},
		snackbarHost = { SnackbarHost(snackbar) },
		floatingActionButton = {
			// 一张表情都没有时不摆 FAB：那种状态下引导区里的大按钮才是主角
			if (!state.noStickers) {
				ExtendedFloatingActionButton(
					onClick = {
						if (state.groups.size > 1) {
							choosingImportPack = true
						} else {
							startImport(state.groups.firstOrNull()?.pack?.id)
						}
					},
					text = { Text(if (state.importing) "正在导入…" else "导入表情") },
					icon = {},
				)
			}
		},
	) { innerPadding ->
		if (state.noStickers) {
			EmptyGuide(
				hasPack = state.groups.isNotEmpty(),
				importing = state.importing,
				onImport = { startImport(state.groups.firstOrNull()?.pack?.id) },
				onCreatePack = { creatingPack = true },
				modifier = Modifier.padding(innerPadding),
			)
			return@Scaffold
		}

		LazyColumn(
			modifier = Modifier
				.fillMaxSize()
				.padding(innerPadding),
			contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 88.dp),
			verticalArrangement = Arrangement.spacedBy(8.dp),
		) {
			state.groups.forEachIndexed { index, group ->
				if (index > 0) {
					item(key = "divider-${group.pack.id}") { HorizontalDivider() }
				}

				item(key = "pack-${group.pack.id}") {
					PackHeader(
						group = group,
						collapsed = group.pack.id in collapsed,
						onToggle = {
							val id = group.pack.id
							collapsed = if (id in collapsed) collapsed - id else collapsed + id
						},
						onImport = { startImport(group.pack.id) },
						onRename = { renamingPack = group.pack },
						onDelete = { deletingPack = group },
					)
				}

				if (group.pack.id in collapsed) return@forEachIndexed

				if (group.stickers.isEmpty()) {
					item(key = "blank-${group.pack.id}") {
						PackEmptyHint(onImport = { startImport(group.pack.id) })
					}
				} else {
					items(
						items = group.stickers.chunked(GRID_COLUMNS),
						key = { row -> "row-${row.first().id}" },
					) { row ->
						StickerRow(
							row = row,
							resolveFile = viewModel::resolveFile,
							onClick = { acting = it },
						)
					}
				}
			}
		}
	}

	if (creatingPack) {
		NameDialog(
			title = "新建分组",
			fieldLabel = "分组名",
			initial = "",
			hint = "只是给自己看的归类，比如「熊猫头」「猫猫虫」",
			onDismiss = { creatingPack = false },
			onConfirm = viewModel::createPack,
		)
	}

	renamingPack?.let { pack ->
		NameDialog(
			title = "重命名分组",
			fieldLabel = "分组名",
			initial = pack.name,
			hint = null,
			onDismiss = { renamingPack = null },
			onConfirm = { viewModel.renamePack(pack, it) },
		)
	}

	deletingPack?.let { group ->
		AlertDialog(
			onDismissRequest = { deletingPack = null },
			title = { Text("删除分组「${group.pack.name}」？") },
			text = {
				Text(
					if (group.stickers.isEmpty()) {
						"这个组是空的，删了不影响别的表情。"
					} else {
						"组里 ${group.stickers.size} 张表情会跟着一起删掉，图片文件也会从手机上抹掉，恢复不了。" +
							"只想留着图的话，先把它们移到别的组去。"
					},
				)
			},
			confirmButton = {
				TextButton(
					onClick = {
						viewModel.deletePack(group)
						deletingPack = null
					},
				) { Text("确认删除") }
			},
			dismissButton = {
				TextButton(onClick = { deletingPack = null }) { Text("取消") }
			},
		)
	}

	if (choosingImportPack) {
		PackChooserDialog(
			title = "导入到哪个组",
			groups = state.groups,
			disabledPackId = null,
			onDismiss = { choosingImportPack = false },
			onPick = { pack ->
				choosingImportPack = false
				startImport(pack.id)
			},
		)
	}

	acting?.let { sticker ->
		StickerActionDialog(
			sticker = sticker,
			canMove = state.groups.size > 1,
			onRename = {
				acting = null
				renamingSticker = sticker
			},
			onMove = {
				acting = null
				movingSticker = sticker
			},
			onDelete = {
				acting = null
				viewModel.deleteSticker(sticker)
			},
			onDismiss = { acting = null },
		)
	}

	renamingSticker?.let { sticker ->
		NameDialog(
			title = "改标记",
			fieldLabel = "标记",
			initial = sticker.label,
			hint = "AI 回复里写 [标记] 就会换成这张图，你在输入框里这么写也一样。" +
				"标记全局唯一，跟别的表情撞名会有提示。",
			onDismiss = { renamingSticker = null },
			onConfirm = { viewModel.renameSticker(sticker, it) },
		)
	}

	movingSticker?.let { sticker ->
		PackChooserDialog(
			title = "把 [${sticker.label}] 移到",
			groups = state.groups,
			disabledPackId = sticker.packId,
			onDismiss = { movingSticker = null },
			onPick = { pack ->
				movingSticker = null
				viewModel.moveSticker(sticker, pack)
			},
		)
	}
}

@Composable
private fun PackHeader(
	group: StickerPackGroup,
	collapsed: Boolean,
	onToggle: () -> Unit,
	onImport: () -> Unit,
	onRename: () -> Unit,
	onDelete: () -> Unit,
) {
	var menuOpen by remember { mutableStateOf(false) }

	Row(
		modifier = Modifier
			.fillMaxWidth()
			.clickable(onClick = onToggle),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Column(modifier = Modifier.weight(1f)) {
			Text(group.pack.name, style = MaterialTheme.typography.titleMedium)
			Text(
				"${group.stickers.size} 张" + if (collapsed) " · 已折叠，点一下展开" else "",
				style = MaterialTheme.typography.labelSmall,
				color = MaterialTheme.colorScheme.outline,
			)
		}

		TextButton(onClick = onImport) { Text("导入") }

		Box {
			TextButton(onClick = { menuOpen = true }) { Text("…") }
			DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
				DropdownMenuItem(
					text = { Text("重命名") },
					onClick = {
						menuOpen = false
						onRename()
					},
				)
				DropdownMenuItem(
					text = { Text("删除分组") },
					onClick = {
						menuOpen = false
						onDelete()
					},
				)
			}
		}
	}
}

@Composable
private fun PackEmptyHint(onImport: () -> Unit) {
	Row(verticalAlignment = Alignment.CenterVertically) {
		Text(
			"这个组还是空的",
			style = MaterialTheme.typography.bodySmall,
			color = MaterialTheme.colorScheme.outline,
			modifier = Modifier.weight(1f),
		)
		TextButton(onClick = onImport) { Text("导入到这里") }
	}
}

@Composable
private fun StickerRow(
	row: List<StickerEntity>,
	resolveFile: (String) -> File,
	onClick: (StickerEntity) -> Unit,
) {
	Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
		row.forEach { sticker ->
			StickerCell(
				sticker = sticker,
				file = resolveFile(sticker.localPath),
				onClick = { onClick(sticker) },
				modifier = Modifier.weight(1f),
			)
		}
		// 最后一行不满就补空位撑住，否则剩下那两三张会被 weight 拉成大图
		repeat(GRID_COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
	}
}

@Composable
private fun StickerCell(
	sticker: StickerEntity,
	file: File,
	onClick: () -> Unit,
	modifier: Modifier = Modifier,
) {
	// 240px 够铺满四列格子里的一格，再大只是白解码
	val bitmap by rememberLocalImage(file, targetWidthPx = 240)

	Column(
		modifier = modifier.clickable(onClick = onClick),
		horizontalAlignment = Alignment.CenterHorizontally,
	) {
		Box(
			modifier = Modifier
				.fillMaxWidth()
				.aspectRatio(1f)
				.clip(RoundedCornerShape(10.dp))
				.background(MaterialTheme.colorScheme.surfaceVariant),
			contentAlignment = Alignment.Center,
		) {
			// 文件被外部清掉时 bitmap 一直是 null，留个占位比整格空白好认
			bitmap?.let {
				Image(
					bitmap = it,
					contentDescription = sticker.label,
					contentScale = ContentScale.Fit,
					modifier = Modifier.fillMaxSize(),
				)
			}
		}
		Text(
			sticker.label,
			style = MaterialTheme.typography.labelSmall,
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
			modifier = Modifier.padding(top = 2.dp),
		)
	}
}

@Composable
private fun EmptyGuide(
	hasPack: Boolean,
	importing: Boolean,
	onImport: () -> Unit,
	onCreatePack: () -> Unit,
	modifier: Modifier = Modifier,
) {
	Column(
		modifier = modifier
			.fillMaxSize()
			.padding(32.dp),
		verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
		horizontalAlignment = Alignment.CenterHorizontally,
	) {
		Text("还没有表情", style = MaterialTheme.typography.titleMedium)

		Text(
			"应用里没有预置任何表情 —— 那些图基本都有版权，不敢打包进来分发，" +
				"所以第一步得自己从相册导入。\n\n" +
				"每张表情都有一个「标记」，默认取文件名（去掉扩展名）。" +
				"AI 回复里写成 [标记] 的地方会被换成这张图，你自己在输入框里这么写也一样。" +
				"标记建议用短词，好打好记，比如 开心、无语、点赞。",
			style = MaterialTheme.typography.bodyMedium,
			color = MaterialTheme.colorScheme.outline,
		)

		Button(onClick = onImport, enabled = !importing) {
			Text(if (importing) "正在导入…" else "从相册导入表情")
		}

		if (!hasPack) {
			Text(
				"会自动建一个「${StickerRepository.DEFAULT_PACK}」分组放进去",
				style = MaterialTheme.typography.labelSmall,
				color = MaterialTheme.colorScheme.outline,
			)
		}

		TextButton(onClick = onCreatePack) { Text("先建个分组") }
	}
}

/** 新建分组、重命名分组、改标记三处都是"输入一个名字"，共用一个对话框 */
@Composable
private fun NameDialog(
	title: String,
	fieldLabel: String,
	initial: String,
	hint: String?,
	onDismiss: () -> Unit,
	onConfirm: (String) -> Unit,
) {
	var text by remember { mutableStateOf(initial) }

	AlertDialog(
		onDismissRequest = onDismiss,
		title = { Text(title) },
		text = {
			Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
				OutlinedTextField(
					value = text,
					onValueChange = { text = it },
					label = { Text(fieldLabel) },
					singleLine = true,
					modifier = Modifier.fillMaxWidth(),
				)
				hint?.let {
					Text(
						it,
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.outline,
					)
				}
			}
		},
		confirmButton = {
			TextButton(
				enabled = text.isNotBlank(),
				onClick = {
					onConfirm(text)
					onDismiss()
				},
			) { Text("确定") }
		},
		dismissButton = {
			TextButton(onClick = onDismiss) { Text("取消") }
		},
	)
}

@Composable
private fun StickerActionDialog(
	sticker: StickerEntity,
	canMove: Boolean,
	onRename: () -> Unit,
	onMove: () -> Unit,
	onDelete: () -> Unit,
	onDismiss: () -> Unit,
) {
	AlertDialog(
		onDismissRequest = onDismiss,
		title = { Text("[${sticker.label}]") },
		text = {
			Column {
				Text(
					"${sticker.width}×${sticker.height} · ${sticker.byteSize / 1024} KB · " +
						"发出去过 ${sticker.useCount} 次",
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.outline,
				)
				TextButton(
					onClick = onRename,
					modifier = Modifier.fillMaxWidth(),
				) { Text("改标记") }
				TextButton(
					onClick = onMove,
					enabled = canMove,
					modifier = Modifier.fillMaxWidth(),
				) { Text(if (canMove) "移到别的组" else "移到别的组（只有一个组）") }
				TextButton(
					onClick = onDelete,
					modifier = Modifier.fillMaxWidth(),
				) { Text("删除这张（图片一起抹掉）") }
			}
		},
		confirmButton = {
			TextButton(onClick = onDismiss) { Text("关闭") }
		},
	)
}

/**
 * 选分组：导入目标和"移到别的组"都用它。
 * 分组多了列表会顶出屏幕，所以套一层 verticalScroll —— AlertDialog 的内容区不自带滚动。
 */
@Composable
private fun PackChooserDialog(
	title: String,
	groups: List<StickerPackGroup>,
	disabledPackId: Long?,
	onDismiss: () -> Unit,
	onPick: (StickerPackEntity) -> Unit,
) {
	AlertDialog(
		onDismissRequest = onDismiss,
		title = { Text(title) },
		text = {
			Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
				groups.forEach { group ->
					val isCurrent = group.pack.id == disabledPackId
					TextButton(
						onClick = { onPick(group.pack) },
						enabled = !isCurrent,
						modifier = Modifier.fillMaxWidth(),
					) {
						Text(
							if (isCurrent) {
								"${group.pack.name}（当前）"
							} else {
								"${group.pack.name}（${group.stickers.size} 张）"
							},
						)
					}
				}
			}
		},
		confirmButton = {
			TextButton(onClick = onDismiss) { Text("取消") }
		},
	)
}
