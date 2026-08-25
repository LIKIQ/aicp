// app/src/main/java/com/kiq/aicp/ui/sticker/StickerScreen.kt
// 表情包管理页：分组、导入、看/改情绪、改标记、移到别的组、删除。
//
// 这一页的主角是情绪，不是标记。模型手上只有一份情绪清单 —— 它写 [开心]，代码再从开心的那些图
// 里随机挑一张换进去，单张图的标记它根本看不到。所以分组头部和每张卡片都得先回答
// "这张图在什么情绪下会被发出去"，标记退成第二行的内部标识（只在用户自己手打 [标记] 时还有用）。
//
// 情绪的两条来源在分组头部分开讲清楚：组名认得出情绪就整组共用它，一张图都不用识；
// 认不出（「我的收藏」这种）才靠后台识图逐张标。识图是 WorkManager 自动排队的，
// 所以这页刻意不放"开始识别"按钮 —— 摆个按钮用户就会以为不点就不跑，白多一件要操心的事。
//
// 网格没用 LazyVerticalGrid。整页是一个 LazyColumn，组内表情 chunked 成每行四个再交给
// items 铺出来：垂直 LazyColumn 里嵌垂直 LazyVerticalGrid 会直接抛"无限高度约束"，
// 给它写死高度又会变成内层能滚外层不动的怪手感。按行发 item 一样是懒加载，两个坑一起绕开。
//
// 空状态的文案写得比别的页面啰嗦，是因为这里最容易被误解：
// APK 里一张预置表情都没有（那些图有版权，不敢打包进来），必须说清"要自己从相册导入"，
// 以及"分组名写成情绪词就不用等识图"这层因果 —— 不然用户根本不知道导进来能干什么。
//
// 动图只会画出第一帧：缩略图走 BitmapFactory，管理页认得出是哪张就够了，
// 真正发到聊天里也是同一套渲染，这一点跟 KIQ 确认过可以接受。
//
// 空状态借的是 ui.settings 里那个共用 EmptyState，三页的"还没有内容"这才长得一样；
// 这页要摆两个按钮加一行小字，所以走它的 actions 槽。顶部那段机制说明同理复用 SectionCard。

package com.kiq.aicp.ui.sticker

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material3.FilterChip
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
import com.kiq.aicp.domain.sticker.StickerEmotion
import com.kiq.aicp.ui.chat.rememberLocalImage
import com.kiq.aicp.ui.settings.EmptyState
import com.kiq.aicp.ui.settings.SectionCard
import com.kiq.aicp.ui.theme.Dimens
import java.io.File

/** 每行四张：手机宽度下再多字就挤到看不清标记了 */
private const val GRID_COLUMNS = 4

/** 跟会话列表同一个值，理由见 ConversationListScreen */
private val FabClearance = 88.dp

/** 单行输入框走胶囊圆角 */
private val PillShape = RoundedCornerShape(Dimens.radiusPill)

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
	var pickingEmotion by remember { mutableStateOf<StickerEntity?>(null) }

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
				// 不带 icon 槽的重载，跟另外两个 FAB 一致（理由见 ConversationListScreen）
				ExtendedFloatingActionButton(
					onClick = {
						if (state.groups.size > 1) {
							choosingImportPack = true
						} else {
							startImport(state.groups.firstOrNull()?.pack?.id)
						}
					},
				) {
					Text(if (state.importing) "正在导入…" else "导入表情")
				}
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
			contentPadding = PaddingValues(
				start = Dimens.screenPadding,
				end = Dimens.screenPadding,
				top = Dimens.screenPadding,
				bottom = FabClearance,
			),
			verticalArrangement = Arrangement.spacedBy(Dimens.spaceSm),
		) {
			// 机制说明摆在列表里而不是顶栏下方固定住：它是"看一次就懂"的东西，
			// 跟着内容滚走正好，钉在屏幕上只会一直占掉一块地方
			item(key = "how-it-works") {
				MechanismNote(state)
			}

			state.groups.forEachIndexed { index, group ->
				if (index > 0) {
					// 分割线自己再顶开 spaceXs，加上上下两侧的 spaceSm，组与组之间就攒到 spaceXl 那一档。
					// 分区之间要的就是这点呼吸感，光靠一条 1dp 的线是隔不开的
					item(key = "divider-${group.pack.id}") {
						HorizontalDivider(modifier = Modifier.padding(vertical = Dimens.spaceXs))
					}
				}

				item(key = "pack-${group.pack.id}") {
					PackHeader(
						group = group,
						visionReady = state.visionReady,
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
							group = group,
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
			hint = "组名直接写情绪词最省事：开心、无语、点赞……整组都按它发，一张图都不用识别。" +
				"写「熊猫头」这类归类名也行，组里的图交给后台看图分类。",
			onDismiss = { creatingPack = false },
			onConfirm = viewModel::createPack,
		)
	}

	renamingPack?.let { pack ->
		NameDialog(
			title = "重命名分组",
			fieldLabel = "分组名",
			initial = pack.name,
			hint = "改成情绪词之后整组都按这个情绪发，组里每张图自己识别出的情绪就不再起作用 —— " +
				"组名说话更大声。改回别的名字，又会退回按图片内容各算各的。",
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

	acting?.let { snapshot ->
		// 表情和所在分组都从最新的 state 里现找，而不是跟着点击存快照：
		// 对话框开着的时候后台识图可能刚把 emotion 写回来，组名也可能刚被改成情绪词，
		// 挂着旧值就会出现"弹窗说待分类、底下卡片已经写着开心"
		val group = state.groups.firstOrNull { it.pack.id == snapshot.packId }
		val sticker = group?.stickers?.firstOrNull { it.id == snapshot.id } ?: snapshot

		StickerActionDialog(
			sticker = sticker,
			group = group,
			canMove = state.groups.size > 1,
			onPickEmotion = {
				acting = null
				pickingEmotion = sticker
			},
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

	pickingEmotion?.let { snapshot ->
		val group = state.groups.firstOrNull { it.pack.id == snapshot.packId }
		val sticker = group?.stickers?.firstOrNull { it.id == snapshot.id } ?: snapshot

		EmotionPickerDialog(
			sticker = sticker,
			packEmotion = group?.packEmotion,
			onPick = { emotion ->
				pickingEmotion = null
				viewModel.setEmotion(sticker, emotion)
			},
			onDismiss = { pickingEmotion = null },
		)
	}

	renamingSticker?.let { sticker ->
		NameDialog(
			title = "改标记",
			fieldLabel = "标记",
			initial = sticker.label,
			hint = "标记是给你自己用的内部名字，AI 看不到它 —— 它挑表情看的是情绪。" +
				"你在输入框里手打 [标记] 还是会换成这张图。标记全局唯一，跟别的表情撞名会有提示。",
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

/**
 * 分组头部。
 *
 * 除了名字和张数，这里还得回答"这组图模型会在什么情绪下发"：
 * 组名认得出情绪就整组共用它（不识图），认不出就按图片内容各算各的。
 * 这行信息比张数重要，所以给情绪加了底色、张数压到最小号。
 */
@Composable
private fun PackHeader(
	group: StickerPackGroup,
	visionReady: Boolean,
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
			.heightIn(min = Dimens.touchTargetMin)
			.clickable(onClick = onToggle),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Column(
			modifier = Modifier.weight(1f),
			verticalArrangement = Arrangement.spacedBy(Dimens.spaceXs),
		) {
			Text(group.pack.name, style = MaterialTheme.typography.titleMedium)

			Row(
				horizontalArrangement = Arrangement.spacedBy(Dimens.spaceXs),
				verticalAlignment = Alignment.CenterVertically,
			) {
				group.packEmotion?.let { EmotionChip(emotion = it) }
				Text(
					packEmotionLine(group, visionReady),
					style = MaterialTheme.typography.labelSmall,
					color = if (group.unclassifiedCount > 0 && !visionReady) {
						MaterialTheme.colorScheme.error
					} else {
						MaterialTheme.colorScheme.outline
					},
					modifier = Modifier.weight(1f),
				)
			}

			Text(
				"${group.stickers.size} 张" + if (collapsed) " · 已折叠，点一下展开" else "",
				style = MaterialTheme.typography.labelSmall,
				color = MaterialTheme.colorScheme.outline,
			)

			// 组名一改成情绪词，组内那些识别过的情绪就静悄悄失效了。
			// 不说一句的话用户只会觉得"我明明标过开心，怎么不算了"
			if (group.shadowedCount > 0) {
				Text(
					"组里 ${group.shadowedCount} 张之前识别成了别的情绪，现在一律按组名「${group.packEmotion}」发",
					style = MaterialTheme.typography.labelSmall,
					color = MaterialTheme.colorScheme.outline,
				)
			}
		}

		TextButton(onClick = onImport) { Text("导入") }

		Box {
			// 跟会话列表、聊天页顶栏用同一个竖三点，别再留一个文字版的"…"
			IconButton(onClick = { menuOpen = true }) {
				Icon(painterResource(R.drawable.ic_more), contentDescription = "分组操作")
			}
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

/**
 * 分组头部那句情绪归属。
 *
 * 待识别的措辞刻意不带任何动作暗示（没有"去识别""点这里"）：识图是 WorkManager 自动排的，
 * 用户什么都不用做，写成催他的语气反而会让人以为漏了一步。
 *
 * 唯一例外是没配视觉模型：那种情况下识图根本跑不起来（Worker 判 notConfigured 就收工，不重试），
 * 这时候还说"等着就行"就是骗人，得把出路说出来。
 */
private fun packEmotionLine(group: StickerPackGroup, visionReady: Boolean): String = when {
	group.byPackName -> "组名就是情绪，整组共用，不用识图"
	group.stickers.isEmpty() -> "按图片内容自动分类"
	group.unclassifiedCount == 0 -> "按图片内容自动分类 · 都分好了"
	!visionReady -> "按图片内容自动分类 · ${group.unclassifiedCount} 张分不了，还没配能看图的模型"
	else -> "按图片内容自动分类 · 还有 ${group.unclassifiedCount} 张在后台排队，等着就行"
}

/**
 * 情绪徽标。情绪是这一页的主角，给它一块底色才能在缩略图和标记之间一眼扫到；
 * 还没分类的走弱化配色，视觉上就是"这里暂时空着"，不是错误。
 */
@Composable
private fun EmotionChip(
	emotion: String?,
	modifier: Modifier = Modifier,
) {
	val known = !emotion.isNullOrBlank()
	Text(
		text = if (known) emotion.orEmpty() else "待分类",
		style = MaterialTheme.typography.labelSmall,
		color = if (known) {
			MaterialTheme.colorScheme.onPrimaryContainer
		} else {
			MaterialTheme.colorScheme.outline
		},
		maxLines = 1,
		overflow = TextOverflow.Ellipsis,
		modifier = modifier
			.clip(RoundedCornerShape(Dimens.radiusSmall))
			.background(
				if (known) {
					MaterialTheme.colorScheme.primaryContainer
				} else {
					MaterialTheme.colorScheme.surfaceVariant
				},
			)
			.padding(horizontal = Dimens.spaceSm, vertical = Dimens.spaceXs),
	)
}

/**
 * 顶部机制说明。
 *
 * 只讲三件事：模型按情绪挑图、组名写成情绪词最省事、剩下的等后台识别。
 * 再多就没人读了，具体的坑留给各处 hint 在用户真的要改的时候说。
 *
 * 另外两句是"这页说的事现在到底生不生效"，都用醒目色：
 * 没配视觉模型时识图跑不起来（判 notConfigured 就收工，不会自己好），
 * 总开关关掉或"告诉模型几个"调成 0 时，模型根本拿不到情绪清单。
 * 不写出来的话，用户只会对着一页"待分类"和一个不发表情的 AI 各自纳闷。
 *
 * 收整个 state 而不是逐个字段：这几句判断迟早还要加，签名跟着改一次不如一次收全，
 * 跟设置页那些分区（AppearanceSection 等）的写法也是一路的。
 */
@Composable
private fun MechanismNote(state: StickerUiState) {
	SectionCard(
		title = "AI 是按情绪挑表情的",
		subtitle = "它看不到单张图的标记，只拿到一份情绪清单：写下 [开心]，应用就从开心的图里随机挑一张换上去。",
	) {
		Text(
			"分组名直接写成情绪词（${StickerEmotion.ALL.take(3).joinToString("、")}……）最省事，" +
				"整组共用这个情绪；组名不是情绪词的，后台会自动看图分类，你不用管。",
			style = MaterialTheme.typography.bodySmall,
			color = MaterialTheme.colorScheme.outline,
		)

		if (state.unclassifiedTotal > 0) {
			Text(
				if (state.visionReady) {
					"现在还有 ${state.unclassifiedTotal} 张在排队识别，识完会自己显示出来。"
				} else {
					"有 ${state.unclassifiedTotal} 张等着分类，但设置里还没有能看图的模型，识图跑不起来 —— " +
						"去配一个，或者把这些图所在的分组名直接改成情绪词。"
				},
				style = MaterialTheme.typography.bodySmall,
				color = if (state.visionReady) {
					MaterialTheme.colorScheme.primary
				} else {
					MaterialTheme.colorScheme.error
				},
			)
		}

		promptOffNote(state)?.let {
			Text(
				it,
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.error,
			)
		}
	}
}

/** 设置项把整套机制掐掉的两种情况。都没掐就返回 null，这块提示整段不画 */
private fun promptOffNote(state: StickerUiState): String? = when {
	!state.stickersEnabled ->
		"表情包总开关在设置里是关着的，AI 现在不会主动发表情 —— 上面这套挑图规则要打开它才生效。" +
			"你自己在输入框手打 [标记] 不受影响。"

	state.promptLimit == 0 ->
		"设置里「告诉模型多少个表情」调成了 0，情绪清单是空的，AI 也就无从挑起。"

	else -> null
}

@Composable
private fun PackEmptyHint(onImport: () -> Unit) {
	Row(
		modifier = Modifier.heightIn(min = Dimens.touchTargetMin),
		verticalAlignment = Alignment.CenterVertically,
	) {
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
	group: StickerPackGroup,
	resolveFile: (String) -> File,
	onClick: (StickerEntity) -> Unit,
) {
	Row(horizontalArrangement = Arrangement.spacedBy(Dimens.spaceSm)) {
		row.forEach { sticker ->
			StickerCell(
				sticker = sticker,
				// 生效的情绪由分组决定（组名是情绪就整组共用），单张自己算不出来
				emotion = group.effectiveEmotion(sticker),
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
	emotion: String?,
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
				.clip(RoundedCornerShape(Dimens.radiusSmall))
				.background(MaterialTheme.colorScheme.surfaceVariant),
			contentAlignment = Alignment.Center,
		) {
			// 文件被外部清掉时 bitmap 一直是 null，留个占位比整格空白好认
			bitmap?.let {
				Image(
					bitmap = it,
					// 读屏用户拿不到缩略图的信息，情绪和标记都念出来才知道点的是哪张
					contentDescription = "${emotion ?: "待分类"}，标记 ${sticker.label}",
					contentScale = ContentScale.Fit,
					modifier = Modifier.fillMaxSize(),
				)
			}
		}

		EmotionChip(emotion = emotion, modifier = Modifier.padding(top = Dimens.spaceXs))

		// 标记退成第二行的弱化小字：模型看不见它，只有用户自己在输入框手打 [标记] 时才用得上
		Text(
			"[${sticker.label}]",
			style = MaterialTheme.typography.labelSmall,
			color = MaterialTheme.colorScheme.outline,
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
		)
	}
}

/**
 * 表情包页的空状态。这里没自己画，转手交给 ui.settings 的 EmptyState，
 * 三页的空状态才会长一样；两个按钮和那行补充小字塞进它的 actions 槽。
 */
@Composable
private fun EmptyGuide(
	hasPack: Boolean,
	importing: Boolean,
	onImport: () -> Unit,
	onCreatePack: () -> Unit,
	modifier: Modifier = Modifier,
) {
	EmptyState(
		emoji = "🖼️",
		title = "还没有表情",
		description = "应用里没有预置任何表情 —— 那些图基本都有版权，不敢打包进来分发，" +
			"所以第一步得自己从相册导入。\n\n" +
			"AI 是按情绪挑表情的：分组名直接写成情绪词（开心、无语、点赞……），整组图就都归这个情绪，" +
			"一张都不用识别；组名写成「熊猫头」这类归类名的，导进去的图由后台自动看图分类，你不用操作。",
		modifier = modifier,
	) {
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
			Column(verticalArrangement = Arrangement.spacedBy(Dimens.spaceSm)) {
				OutlinedTextField(
					value = text,
					onValueChange = { text = it },
					label = { Text(fieldLabel) },
					singleLine = true,
					shape = PillShape,
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

/**
 * 单张表情的操作。
 *
 * 情绪摆在最前面：它才决定这张图会不会被发出去。改标记压到情绪下面 ——
 * 标记现在只是"手打 [标记] 时用的名字"，不再是模型看的东西。
 */
@Composable
private fun StickerActionDialog(
	sticker: StickerEntity,
	group: StickerPackGroup?,
	canMove: Boolean,
	onPickEmotion: () -> Unit,
	onRename: () -> Unit,
	onMove: () -> Unit,
	onDelete: () -> Unit,
	onDismiss: () -> Unit,
) {
	val byPackName = group?.byPackName == true
	val emotion = group?.effectiveEmotion(sticker) ?: sticker.emotion.takeIf { it.isNotBlank() }

	AlertDialog(
		onDismissRequest = onDismiss,
		title = { Text("[${sticker.label}]") },
		text = {
			Column(verticalArrangement = Arrangement.spacedBy(Dimens.spaceXs)) {
				Row(
					horizontalArrangement = Arrangement.spacedBy(Dimens.spaceSm),
					verticalAlignment = Alignment.CenterVertically,
				) {
					EmotionChip(emotion = emotion)
					Text(
						if (byPackName) "来自组名，整组共用" else "按这张图的内容分的",
						style = MaterialTheme.typography.labelSmall,
						color = MaterialTheme.colorScheme.outline,
					)
				}

				Text(
					"${sticker.width}×${sticker.height} · ${sticker.byteSize / 1024} KB · " +
						"发出去过 ${sticker.useCount} 次",
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.outline,
				)

				// 情绪组里改单张是允许的，但现在不生效。宁可说明白也不禁用按钮：
				// 用户可能正打算把组名改回去，先把情绪定好完全合理
				if (byPackName) {
					Text(
						"这组的组名本身是情绪，组内所有图都按它发。单独给这张定的情绪要等组名改成别的才会生效。",
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.outline,
					)
				}

				TextButton(
					onClick = onPickEmotion,
					modifier = Modifier.fillMaxWidth(),
				) { Text(if (byPackName) "改这张的情绪（当前按组名走）" else "改情绪") }
				TextButton(
					onClick = onRename,
					modifier = Modifier.fillMaxWidth(),
				) { Text("改标记（内部名字，AI 看不到）") }
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
 * 选情绪。词只能从 StickerEmotion.ALL 里点，不给自由输入：
 * 打成"有点开心"这种词表外的值，模型永远不会写出这个词，那张图就等于永久发不出去了。
 *
 * 清除走 onPick("")，跟 StickerViewModel.setEmotion 的空串语义对齐 —— 退回待分类，
 * 后台下一轮识图会重新捡起它（仓库那边是另一个方法，这层不用关心）。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EmotionPickerDialog(
	sticker: StickerEntity,
	packEmotion: String?,
	onPick: (String) -> Unit,
	onDismiss: () -> Unit,
) {
	AlertDialog(
		onDismissRequest = onDismiss,
		title = { Text("这张算什么情绪") },
		text = {
			// 二十个 chip 加说明在小屏上会顶出内容区，AlertDialog 又不自带滚动（同 PackChooserDialog）
			Column(
				modifier = Modifier.verticalScroll(rememberScrollState()),
				verticalArrangement = Arrangement.spacedBy(Dimens.spaceSm),
			) {
				Text(
					"选好之后，AI 写 [这个词] 的时候就有机会挑到这张图。选了会盖掉后台识别出来的结果。",
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.outline,
				)

				packEmotion?.let {
					Text(
						"这张图所在的组名已经是情绪「$it」了，整组按组名走 —— 这里选的要等组名改掉才生效。",
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.outline,
					)
				}

				FlowRow(
					horizontalArrangement = Arrangement.spacedBy(Dimens.spaceSm),
					verticalArrangement = Arrangement.spacedBy(Dimens.spaceSm),
				) {
					StickerEmotion.ALL.forEach { emotion ->
						FilterChip(
							selected = emotion == sticker.emotion,
							onClick = { onPick(emotion) },
							label = { Text(emotion) },
						)
					}
				}

				TextButton(
					onClick = { onPick("") },
					enabled = sticker.emotion.isNotBlank(),
					modifier = Modifier.fillMaxWidth(),
				) { Text("清除，退回待分类") }
			}
		},
		confirmButton = {
			TextButton(onClick = onDismiss) { Text("取消") }
		},
	)
}

/**
 * 选分组：导入目标和"移到别的组"都用它。
 * 分组多了列表会顶出屏幕，所以套一层 verticalScroll —— AlertDialog 的内容区不自带滚动。
 *
 * 每行带上情绪归属不是为了好看：把一张图移进名叫「开心」的组，等于一步给它定了情绪，
 * 这是比逐张点情绪更快的批量手段。看不到归属的话，用户根本想不到还能这么用。
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
								"${group.pack.name}（${group.stickers.size} 张${packChooserSuffix(group)}）"
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

/** 情绪组标出情绪，非情绪组标出还有几张没分类；都没有就只留张数 */
private fun packChooserSuffix(group: StickerPackGroup): String = when {
	group.packEmotion != null -> " · 情绪 ${group.packEmotion}"
	group.unclassifiedCount > 0 -> " · ${group.unclassifiedCount} 张待分类"
	else -> ""
}
