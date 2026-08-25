// app/src/main/java/com/kiq/aicp/ui/memory/MemoryScreen.kt
// 记忆管理页：看得见、改得动、删得掉。v6 起看的是 wiki 条目，不再是旧的扁平卡片。
//
// 页面从上到下：
// 1. 记忆规则（schema）—— 折叠在最顶上。它是"写一次管很久"的东西，默认展开会天天占掉半屏，
//    但它决定了下面所有条目怎么长，所以位置必须在最前。改动走显式「保存」按钮，理由写在 SchemaSection 上。
// 2. 记忆体检 —— 让模型给整库提意见，产出的是建议不是既成事实，用户逐条点头才改库。
//    结果做成全屏 Dialog，理由写在 LintReportDialog 上。
// 3. 条目 —— 主体，按五个分类分段。带 conflictNote 的条目描一圈 error 并把矛盾内容摊开显示：
//    Karpathy 那份 llm-wiki 里刻意保留这个信号，新旧信息冲突时不许悄悄覆盖，得让人看见再定。
// 4. 操作日志 —— 同一页的下半部分，用分区标题隔开，没做成第二个 tab。
//    理由是日志的价值全在于跟条目对照着看（"这条怎么变成现在这样的"），往下滑一屏就能对上；
//    做成 tab 就得让用户在两屏之间来回切，还要多维护一份 tab 状态和第二套空状态，
//    换来的只是"首屏短一点"。等日志需要筛选和翻页时再拆页才划算。
//
// 列表卡片只摆标题、一行摘要、正文和矛盾提示。别名、用了多少次、被几轮对话确认过这些内部结构
// 全部收进编辑框顶部的只读区 —— 前台要能一眼扫完，追细节的人点开就有。
//
// 所有会删东西的操作（删条目、清理冷记忆、合并）都挂了二次确认，而且确认文案说的是后果
// （"以后模型不会再记得它"）而不是动作（"确定吗"）：记忆删了没有回收站。
//
// 一个条目就是一个列表项，所以标题吃 titleMedium（跟会话列表、性格列表的主标题同一级），
// 作用域这类元信息统一 labelSmall + outline。卡片配色规则见 ConversationListScreen：
// 普通条目 surfaceContainerLow，钉住的走 secondaryContainer；冲突提示用 errorContainer，
// 那是语义色不是"第三种卡片色"。


package com.kiq.aicp.ui.memory

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kiq.aicp.R
import com.kiq.aicp.data.db.entity.MemoryEntryEntity
import com.kiq.aicp.data.db.entity.MemoryLogEntity
import com.kiq.aicp.data.db.entity.MemoryLogKind
import com.kiq.aicp.data.prefs.SettingsStore
import com.kiq.aicp.domain.memory.ConflictItem
import com.kiq.aicp.domain.memory.LintReport
import com.kiq.aicp.domain.memory.MergeItem
import com.kiq.aicp.domain.memory.StaleItem
import com.kiq.aicp.domain.model.MemoryCardType
import com.kiq.aicp.ui.chat.MessageTime
import com.kiq.aicp.ui.settings.EmptyState
import com.kiq.aicp.ui.settings.SectionCard
import com.kiq.aicp.ui.settings.SliderRow
import com.kiq.aicp.ui.theme.Dimens

/** 多行文本域跟卡片同圆角，理由见 PersonaEditScreen 里同名的那个 */
private val TextAreaShape = RoundedCornerShape(Dimens.radiusCard)

/** 冲突条目的描边宽度。它是控件线宽不是间距，不归 Dimens 那五档管 */
private val ConflictBorder = 1.dp

/** 按钮旁边那个转圈的直径，跟设置页同名的那个一致 */
private val InlineSpinner = 16.dp

/** 正文折叠时露几行。两行够看出这条在讲什么，又不至于让整页变成文字墙 */
private const val BODY_COLLAPSED_LINES = 2

/**
 * 分区顺序：先客观后主观。
 * 事实和喜好是用户最常来核对的（"它是不是记错了我的职业"），
 * IMPRESSION 最接近模型自己的推断，放最后 —— 翻到那儿的人本来就是想看"它怎么看我"。
 */
private val CATEGORY_ORDER = listOf(
	MemoryCardType.FACT,
	MemoryCardType.PREFERENCE,
	MemoryCardType.EVENT,
	MemoryCardType.RELATION,
	MemoryCardType.IMPRESSION,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryScreen(
	onBack: () -> Unit,
	viewModel: MemoryViewModel = viewModel(factory = MemoryViewModel.Factory),
) {
	val state by viewModel.uiState.collectAsStateWithLifecycle()
	val snackbar = remember { SnackbarHostState() }
	var editing by remember { mutableStateOf<MemoryEntryEntity?>(null) }
	// 删除确认纯属界面状态，没必要绕一趟 ViewModel
	var deleting by remember { mutableStateOf<MemoryEntryEntity?>(null) }

	LaunchedEffect(state.message) {
		state.message?.let {
			snackbar.showSnackbar(it)
			viewModel.dismissMessage()
		}
	}

	Scaffold(
		topBar = {
			TopAppBar(
				title = { Text("记忆（${state.entries.size}）") },
				navigationIcon = {
					IconButton(onClick = onBack) {
						Icon(painterResource(R.drawable.ic_back), contentDescription = "返回")
					}
				},
				actions = {
					TextButton(onClick = viewModel::requestPruneCold) { Text("清理冷记忆") }
				},
			)
		},
		snackbarHost = { SnackbarHost(snackbar) },
	) { innerPadding ->
		LazyColumn(
			modifier = Modifier
				.fillMaxSize()
				.padding(innerPadding),
			contentPadding = PaddingValues(Dimens.screenPadding),
			verticalArrangement = Arrangement.spacedBy(Dimens.spaceSm),
		) {
			item(key = "schema") { SchemaSection(state, viewModel) }
			item(key = "lint") { LintSection(state, viewModel) }

			if (state.conflictCount > 0) {
				item(key = "conflicts") { ConflictBanner(state.conflictCount) }
			}

			if (state.isEmpty) {
				item(key = "empty") {
					// EmptyState 内部是 fillMaxSize，放进 LazyColumn 的 item 里高度约束是无限的，
					// 它撑不满也不会报错，只会按内容高度居中 —— 上下补一档间距就不显得贴着上下文了
					EmptyState(
						emoji = "🧠",
						title = "还没有记忆",
						description = "聊到一定长度后会自动把旧对话压成摘要，" +
							"再从里面整理出条目存在这里。上面的记忆规则可以先写好，它会影响整理的取向",
						modifier = Modifier.padding(vertical = Dimens.spaceXl),
					)
				}
			} else {
				CATEGORY_ORDER.forEach { category ->
					val group = state.entriesOf(category)
					if (group.isEmpty()) return@forEach

					item(key = "cat-$category") {
						SectionHeader("${categoryLabel(category)}（${group.size}）")
					}
					// key 加前缀：条目 id 和日志 id 都是自增 Long，同一个 LazyColumn 里会撞
					items(group, key = { "entry-${it.id}" }) { entry ->
						EntryCard(
							entry = entry,
							onEdit = { editing = entry },
							onTogglePin = { viewModel.setPinned(entry, !entry.pinned) },
							onDelete = { deleting = entry },
						)
					}
				}
			}

			item(key = "log-header") {
				SectionHeader("记忆日志")
			}
			item(key = "log-hint") {
				Text(
					"每次自动整理、每次你手改，都会在这里留一行。记错了想查是哪一轮记的，从这儿翻",
					style = MaterialTheme.typography.labelSmall,
					color = MaterialTheme.colorScheme.outline,
				)
			}
			if (state.logs.isEmpty()) {
				item(key = "log-empty") {
					Text(
						"还没有记录",
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.outline,
					)
				}
			} else {
				items(state.logs, key = { "log-${it.id}" }) { log -> LogRow(log) }
			}
		}
	}

	editing?.let { entry ->
		EntryEditDialog(
			entry = entry,
			onDismiss = { editing = null },
			onSave = { body, importance ->
				viewModel.edit(entry, body, importance)
				editing = null
			},
		)
	}

	deleting?.let { entry ->
		DeleteEntryDialog(
			entry = entry,
			onDismiss = { deleting = null },
			onConfirm = {
				viewModel.delete(entry)
				deleting = null
			},
		)
	}

	state.pendingPrune?.let { cold ->
		PruneConfirmDialog(
			cold = cold,
			onDismiss = viewModel::cancelPruneCold,
			onConfirm = viewModel::confirmPruneCold,
		)
	}

	// 报告开着时编辑框可以叠在它上面：用户从"需要你确认"点进去改完，退回来还能接着处理下一条
	state.report?.let { report ->
		LintReportDialog(
			report = report,
			onClose = viewModel::dismissReport,
			onMerge = viewModel::applyMerge,
			onIgnoreMerge = viewModel::ignoreMerge,
			onConflictAck = viewModel::dismissConflict,
			onConflictEdit = { editing = it.entry },
			onStaleDelete = viewModel::applyDelete,
			onStaleKeep = viewModel::keepStale,
		)
	}
}

/**
 * 记忆规则（wiki 第三层 schema）。
 *
 * 默认折叠，收起时只露已存内容的头两行 —— 常来这一页的人是来看条目的，
 * 规则天天摊开只会把主体挤下去；但它排在最顶上，因为下面所有条目都是按它整理出来的。
 *
 * 保存走显式按钮而不是失焦即存：失焦的时机在移动端很不可靠（切后台、弹出输入法、点到别的卡都算），
 * 用户会分不清自己到底存上没存上。加上这段文字每次整理都要拼进提示词，
 * 半截句子被存下来会立刻影响下一次整理，宁可让他自己按一下。
 */
@Composable
private fun SchemaSection(state: MemoryUiState, viewModel: MemoryViewModel) {
	var expanded by remember { mutableStateOf(false) }

	SectionCard(
		title = "记忆规则",
		subtitle = "整理记忆时会把这段话一起交给模型，比内置约定优先",
	) {
		if (!expanded) {
			Row(
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.spacedBy(Dimens.spaceSm),
			) {
				Text(
					state.savedSchema.ifBlank { "还没写，现在只按内置约定整理" },
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.outline,
					maxLines = 2,
					overflow = TextOverflow.Ellipsis,
					modifier = Modifier.weight(1f),
				)
				if (state.schemaDirty) {
					Text(
						"改了没存",
						style = MaterialTheme.typography.labelSmall,
						color = MaterialTheme.colorScheme.primary,
					)
				}
				TextButton(onClick = { expanded = true }) {
					Text(if (state.savedSchema.isBlank()) "写一条" else "修改")
				}
			}
			return@SectionCard
		}

		OutlinedTextField(
			value = state.schemaField,
			onValueChange = viewModel::onSchemaChange,
			modifier = Modifier.fillMaxWidth(),
			shape = TextAreaShape,
			minLines = 3,
			maxLines = 8,
			placeholder = {
				Text("例如：重点记我的健康数据；别记工作细节；我说过的话优先于你的推断")
			},
			supportingText = {
				Text(
					"还能写 ${SettingsStore.MAX_MEMORY_SCHEMA - state.schemaField.length} 字。" +
						"留空就只用内置规则",
				)
			},
		)
		Row(
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(Dimens.spaceXs),
		) {
			TextButton(onClick = viewModel::saveSchema, enabled = state.schemaDirty) { Text("保存") }
			TextButton(onClick = { expanded = false }) { Text("收起") }
		}
	}
}

/**
 * 冲突汇总条。
 *
 * errorContainer 在这儿是语义色，不是文件头那条"普通卡 / 强调卡"规则的第三种选项：
 * 有条目前后打脸了本身就是要报给用户的异常，而且不摆在顶上的话，
 * 条目一多用户得一路滑到底才知道有东西等他处理。
 */
@Composable
private fun ConflictBanner(count: Int) {
	Card(
		modifier = Modifier.fillMaxWidth(),
		shape = RoundedCornerShape(Dimens.radiusCard),
		colors = CardDefaults.cardColors(
			containerColor = MaterialTheme.colorScheme.errorContainer,
		),
	) {
		Column(
			modifier = Modifier.padding(Dimens.spaceLg),
			verticalArrangement = Arrangement.spacedBy(Dimens.spaceXs),
		) {
			Text("⚠️ 有 $count 条记忆前后说法不一致", style = MaterialTheme.typography.titleSmall)
			Text(
				"新说法跟旧记录打架时不会悄悄覆盖，两边都留着等你定。" +
					"往下找带 ⚠️ 的条目，改一次就算你认可当前这版",
				style = MaterialTheme.typography.bodySmall,
			)
		}
	}
}

/**
 * 分区标题。
 * top padding 自己带着：LazyColumn 的统一间距只有 spaceSm，加上这 16 刚好凑成 Dimens.spaceXl 的
 * 分区呼吸感，比给每张卡单独算边距省事，也不会因为漏算某一处而参差。
 */
@Composable
private fun SectionHeader(text: String) {
	Text(
		text,
		style = MaterialTheme.typography.titleSmall,
		color = MaterialTheme.colorScheme.primary,
		modifier = Modifier.padding(top = Dimens.spaceLg, bottom = Dimens.spaceXs),
	)
}

@Composable
private fun EntryCard(
	entry: MemoryEntryEntity,
	onEdit: () -> Unit,
	onTogglePin: () -> Unit,
	onDelete: () -> Unit,
) {
	// key 用 id：LazyColumn 复用 composable 时不能把上一条的展开状态带到下一条身上
	var bodyExpanded by remember(entry.id) { mutableStateOf(false) }
	var bodyOverflow by remember(entry.id) { mutableStateOf(false) }

	val conflict = entry.conflictNote?.takeIf { it.isNotBlank() }
	// 展开过就一直允许收回去：展开状态下 onTextLayout 不会再报 overflow，光看它会让"收起"消失
	val foldable = bodyOverflow || bodyExpanded

	Card(
		modifier = Modifier
			.fillMaxWidth()
			// 整卡当折叠开关：只给"展开"两个字做热区的话手指点不准（Dimens.touchTargetMin 那条规矩），
			// 而这张卡上除了看全文没有别的主动作要抢这个手势 —— 改和删都在下面的按钮上
			.clickable(enabled = foldable) { bodyExpanded = !bodyExpanded },
		shape = RoundedCornerShape(Dimens.radiusCard),
		colors = CardDefaults.cardColors(
			// 钉住是用户自己标的，跟置顶会话一样走强调色
			containerColor = if (entry.pinned) {
				MaterialTheme.colorScheme.secondaryContainer
			} else {
				MaterialTheme.colorScheme.surfaceContainerLow
			},
		),
		// 有未处理矛盾的条目描一圈 error：一屏十几张卡的时候，颜色比图标更容易被扫到
		border = if (conflict != null) {
			BorderStroke(ConflictBorder, MaterialTheme.colorScheme.error)
		} else {
			null
		},
	) {
		Column(
			// 底部只给 spaceSm：下面那排 TextButton 自带垂直内缩，
			// 再给 16 的话卡底会空出一大条，看着像少画了一行东西
			modifier = Modifier.padding(
				start = Dimens.spaceLg,
				end = Dimens.spaceLg,
				top = Dimens.spaceLg,
				bottom = Dimens.spaceSm,
			),
			verticalArrangement = Arrangement.spacedBy(Dimens.spaceSm),
		) {
			Row(
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.spacedBy(Dimens.spaceSm),
			) {
				Text(
					if (conflict != null) "⚠️ ${entry.title}" else entry.title,
					style = MaterialTheme.typography.titleMedium,
					modifier = Modifier.weight(1f),
				)
				Text(
					"重要度 ${entry.importance}",
					style = MaterialTheme.typography.labelSmall,
					color = MaterialTheme.colorScheme.outline,
				)
			}

			Text(entry.oneLiner, style = MaterialTheme.typography.bodyMedium)

			Text(
				entry.body,
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
				maxLines = if (bodyExpanded) Int.MAX_VALUE else BODY_COLLAPSED_LINES,
				overflow = TextOverflow.Ellipsis,
				// 折得住才值得给"点一下看全文"的提示，短正文给了反而是骗人
				onTextLayout = { if (!bodyExpanded) bodyOverflow = it.hasVisualOverflow },
			)

			conflict?.let { ConflictNote(it) }

			Text(
				metaLine(entry, foldable, bodyExpanded),
				style = MaterialTheme.typography.labelSmall,
				color = MaterialTheme.colorScheme.outline,
			)

			Row(horizontalArrangement = Arrangement.spacedBy(Dimens.spaceXs)) {
				TextButton(onClick = onEdit) { Text("编辑") }
				TextButton(onClick = onTogglePin) {
					Text(if (entry.pinned) "取消钉住" else "钉住")
				}
				TextButton(onClick = onDelete) { Text("删除") }
			}
		}
	}
}

/**
 * 矛盾内容本体。
 * 摊开显示而不是折起来：这条信息的全部价值就在于"到底哪儿不一致"，
 * 藏起来就退化成一个没用的红点了。
 */
@Composable
private fun ConflictNote(note: String) {
	Card(
		modifier = Modifier.fillMaxWidth(),
		shape = RoundedCornerShape(Dimens.radiusSmall),
		colors = CardDefaults.cardColors(
			containerColor = MaterialTheme.colorScheme.errorContainer,
		),
	) {
		Column(
			modifier = Modifier.padding(Dimens.spaceSm),
			verticalArrangement = Arrangement.spacedBy(Dimens.spaceXs),
		) {
			Text("前后说法不一致", style = MaterialTheme.typography.labelMedium)
			Text(note, style = MaterialTheme.typography.bodySmall)
			Text(
				"编辑一次就当你认可当前这版，这块提示会消失",
				style = MaterialTheme.typography.labelSmall,
			)
		}
	}
}

/**
 * 日志一行。
 *
 * 刻意不套卡片：日志跟上面的条目摆在同一列，都做成卡的话两者会互相抢注意力，
 * 而它本来就是附属信息。靠字号层级和一条分隔线区分就够了。
 */
@Composable
private fun LogRow(log: MemoryLogEntity) {
	Column(
		modifier = Modifier
			.fillMaxWidth()
			.padding(vertical = Dimens.spaceXs),
		verticalArrangement = Arrangement.spacedBy(Dimens.spaceXs),
	) {
		Row(
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(Dimens.spaceSm),
		) {
			Text(
				logKindLabel(log.kind),
				style = MaterialTheme.typography.labelMedium,
				color = MaterialTheme.colorScheme.primary,
			)
			// 时间格式复用聊天页那套：今天只给时分，跨天才补日期，读起来跟消息时间线是一致的
			Text(
				MessageTime.formatDivider(log.createdAt),
				style = MaterialTheme.typography.labelSmall,
				color = MaterialTheme.colorScheme.outline,
			)
		}
		Text(
			log.summary,
			style = MaterialTheme.typography.bodySmall,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
		)
		touchedLabel(log.touchedTitles)?.let {
			Text(
				it,
				style = MaterialTheme.typography.labelSmall,
				color = MaterialTheme.colorScheme.outline,
			)
		}
		HorizontalDivider(modifier = Modifier.padding(top = Dimens.spaceXs))
	}
}

/**
 * 编辑框。
 *
 * 只开正文和重要度，标题、一行摘要、别名都不给改：这三样参与唯一索引和关键词检索，
 * 在这儿改掉等于把条目换成另一条，下一轮整理还会把原来那条建回来，用户会以为自己的修改被吞了。
 *
 * 顶上那块只读信息是列表卡片让出来的：分类、作用域、别名、用了多少次、被几轮对话确认过，
 * 都是"想追细节时才需要"的内部结构，摊在列表上是噪音，藏在这儿又刚好够用。
 */
@Composable
private fun EntryEditDialog(
	entry: MemoryEntryEntity,
	onDismiss: () -> Unit,
	onSave: (String, Int) -> Unit,
) {
	var body by remember(entry.id) { mutableStateOf(entry.body) }
	var importance by remember(entry.id) { mutableIntStateOf(entry.importance) }

	AlertDialog(
		onDismissRequest = onDismiss,
		title = { Text("编辑「${entry.title}」") },
		text = {
			Column(verticalArrangement = Arrangement.spacedBy(Dimens.spaceSm)) {
				Text(
					entry.oneLiner,
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
				EntryFacts(entry)
				OutlinedTextField(
					// 输入时就截断到 MAX_BODY，别等保存完才发现后面几十个字被 editEntry 切了
					value = body,
					onValueChange = { body = it.take(MemoryEntryEntity.MAX_BODY) },
					modifier = Modifier.fillMaxWidth(),
					shape = TextAreaShape,
					minLines = 3,
					supportingText = {
						Text("还能写 ${MemoryEntryEntity.MAX_BODY - body.length} 字")
					},
				)
				SliderRow(
					title = "重要度",
					subtitle = "越高越优先进上下文，1 最低 5 最高",
					value = importance,
					valueRange = 1..5,
					step = 1,
					onValueSettled = { importance = it },
				)
				if (!entry.conflictNote.isNullOrBlank()) {
					Text(
						"保存后这条的冲突提示会一起清掉",
						style = MaterialTheme.typography.labelSmall,
						color = MaterialTheme.colorScheme.primary,
					)
				}
			}
		},
		confirmButton = {
			TextButton(
				enabled = body.isNotBlank(),
				onClick = { onSave(body, importance) },
			) { Text("保存") }
		},
		dismissButton = {
			TextButton(onClick = onDismiss) { Text("取消") }
		},
	)
}

/**
 * 编辑框顶部的只读事实区。
 * 全部压到 labelSmall + outline：视觉上一眼就能看出"这几行不是可改项"，
 * 跟下面能编辑的正文和滑块分得开。
 */
@Composable
private fun EntryFacts(entry: MemoryEntryEntity) {
	Column(verticalArrangement = Arrangement.spacedBy(Dimens.spaceXs)) {
		Text(
			"${categoryLabel(entry.category)} · ${scopeLabel(entry)}",
			style = MaterialTheme.typography.labelSmall,
			color = MaterialTheme.colorScheme.outline,
		)
		aliasLabel(entry.aliases)?.let {
			Text(
				it,
				style = MaterialTheme.typography.labelSmall,
				color = MaterialTheme.colorScheme.outline,
			)
		}
		Text(
			usageLine(entry),
			style = MaterialTheme.typography.labelSmall,
			color = MaterialTheme.colorScheme.outline,
		)
	}
}

/**
 * 用量行。
 * sourceCount 只在大于 1 时说："被 N 轮对话确认过"是加分项，
 * 每条都写"被 1 轮确认过"就成了废话，还把真正反复出现的那几条淹掉了。
 */
private fun usageLine(entry: MemoryEntryEntity): String = buildString {
	append("用过 ${entry.hitCount} 次")
	if (entry.sourceCount > 1) append(" · 被 ${entry.sourceCount} 轮对话确认过")
}

/**
 * 删除确认。
 * 正文说的是后果而不是动作 —— 用户要判断的是"以后它还记不记得"，
 * 不是"你确定吗"。
 */
@Composable
private fun DeleteEntryDialog(
	entry: MemoryEntryEntity,
	onDismiss: () -> Unit,
	onConfirm: () -> Unit,
) {
	AlertDialog(
		onDismissRequest = onDismiss,
		title = { Text("删掉「${entry.title}」？") },
		text = { Text("这条记忆会消失，以后对话里模型不会再记得它，也没法撤回。") },
		confirmButton = {
			TextButton(onClick = onConfirm) {
				Text("删除", color = MaterialTheme.colorScheme.error)
			}
		},
		dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
	)
}

/**
 * 批量清理确认。
 * 一次删好几条，所以把标题全列出来给他核对 —— 只说"清掉 5 条"的话，
 * 用户没法判断这 5 条里有没有他其实还想留的。
 */
@Composable
private fun PruneConfirmDialog(
	cold: List<MemoryEntryEntity>,
	onDismiss: () -> Unit,
	onConfirm: () -> Unit,
) {
	AlertDialog(
		onDismissRequest = onDismiss,
		title = { Text("清掉 ${cold.size} 条冷记忆？") },
		text = {
			Column(verticalArrangement = Arrangement.spacedBy(Dimens.spaceSm)) {
				Text("重要度不高、而且 60 天没被用到的记忆。钉住的不在里面，删掉之后没法撤回。")
				Text(
					cold.joinToString("、") { it.title },
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
			}
		},
		confirmButton = {
			TextButton(onClick = onConfirm) {
				Text("删除 ${cold.size} 条", color = MaterialTheme.colorScheme.error)
			}
		},
		dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
	)
}

/**
 * 体检入口。
 * 单独占一块而不是塞进顶栏：这个动作要把整库送给模型，费时也费 token，
 * 得有地方把"它到底做什么、结果要不要你点头"写清楚，顶栏一个按钮装不下这句话。
 */
@Composable
private fun LintSection(state: MemoryUiState, viewModel: MemoryViewModel) {
	SectionCard(
		title = "记忆体检",
		subtitle = "让模型检查记忆库里有没有重复、矛盾、过时的内容。改不改由你逐条点头",
	) {
		Row(
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(Dimens.spaceSm),
		) {
			OutlinedButton(onClick = viewModel::runLint, enabled = !state.linting) {
				Text(if (state.linting) "体检中…" else "开始体检")
			}
			if (state.linting) {
				CircularProgressIndicator(
					modifier = Modifier.size(InlineSpinner),
					strokeWidth = 2.dp,
				)
			}
		}
	}
}

/**
 * 体检报告。
 *
 * 做成全屏 Dialog 而不是页内区块：一份报告可能十几条建议，用户得逐条处理完才算收工。
 * 塞进主列表里既会把条目挤到看不见，又要跟主列表抢滚动；全屏覆盖等于给他一个
 * "处理完再回去"的明确工作流。报告本身是内存态，关掉就没了，所以顶栏只留关闭。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LintReportDialog(
	report: LintReport,
	onClose: () -> Unit,
	onMerge: (MergeItem) -> Unit,
	onIgnoreMerge: (MergeItem) -> Unit,
	onConflictAck: (ConflictItem) -> Unit,
	onConflictEdit: (ConflictItem) -> Unit,
	onStaleDelete: (StaleItem) -> Unit,
	onStaleKeep: (StaleItem) -> Unit,
) {
	// 合并和清理都会真的删条目，报告里再挡一层确认。状态放在报告这一层而不是每张卡自己管：
	// 建议被摘掉之后那张卡会消失，确认框跟着卡走的话就成了挂在半空的孤儿
	var mergeConfirm by remember { mutableStateOf<MergeItem?>(null) }
	var staleConfirm by remember { mutableStateOf<StaleItem?>(null) }

	Dialog(
		onDismissRequest = onClose,
		properties = DialogProperties(usePlatformDefaultWidth = false),
	) {
		Surface(modifier = Modifier.fillMaxSize()) {
			Column(modifier = Modifier.fillMaxSize()) {
				TopAppBar(
					title = { Text("体检结果") },
					navigationIcon = {
						IconButton(onClick = onClose) {
							Icon(painterResource(R.drawable.ic_close), contentDescription = "关闭")
						}
					},
				)

				if (report.isEmpty) {
					EmptyState(
						emoji = "✅",
						title = "没发现问题",
						description = "体检了 ${report.checkedCount} 条记忆，" +
							"重复、矛盾、过时都没查到。记忆库现在是干净的",
					)
					return@Column
				}

				LintReportList(
					report = report,
					onMergeRequest = { mergeConfirm = it },
					onIgnoreMerge = onIgnoreMerge,
					onConflictAck = onConflictAck,
					onConflictEdit = onConflictEdit,
					onStaleRequest = { staleConfirm = it },
					onStaleKeep = onStaleKeep,
				)
			}
		}
	}

	mergeConfirm?.let { item ->
		AlertDialog(
			onDismissRequest = { mergeConfirm = null },
			title = { Text("合并进「${item.keep.title}」？") },
			text = {
				Text(
					"另外 ${item.absorb.size} 条会被删掉，正文换成合并后的版本，没法撤回。",
				)
			},
			confirmButton = {
				TextButton(
					onClick = {
						onMerge(item)
						mergeConfirm = null
					},
				) { Text("合并", color = MaterialTheme.colorScheme.error) }
			},
			dismissButton = {
				TextButton(onClick = { mergeConfirm = null }) { Text("取消") }
			},
		)
	}

	staleConfirm?.let { item ->
		DeleteEntryDialog(
			entry = item.entry,
			onDismiss = { staleConfirm = null },
			onConfirm = {
				onStaleDelete(item)
				staleConfirm = null
			},
		)
	}
}

/**
 * 报告正文。
 * "需要你确认"排最前：那一段是唯一非人不可判断的，合并和清理用户不表态也不会出错，
 * 但矛盾放着不管，模型下一轮还会拿着两个互相打脸的说法继续编。
 */
@Composable
private fun LintReportList(
	report: LintReport,
	onMergeRequest: (MergeItem) -> Unit,
	onIgnoreMerge: (MergeItem) -> Unit,
	onConflictAck: (ConflictItem) -> Unit,
	onConflictEdit: (ConflictItem) -> Unit,
	onStaleRequest: (StaleItem) -> Unit,
	onStaleKeep: (StaleItem) -> Unit,
) {
	LazyColumn(
		modifier = Modifier.fillMaxSize(),
		contentPadding = PaddingValues(Dimens.screenPadding),
		verticalArrangement = Arrangement.spacedBy(Dimens.spaceSm),
	) {
		item {
			Text(
				"这次体检了 ${report.checkedCount} 条记忆",
				style = MaterialTheme.typography.labelSmall,
				color = MaterialTheme.colorScheme.outline,
			)
		}

		if (report.conflicts.isNotEmpty()) {
			item { SectionHeader("需要你确认（${report.conflicts.size}）") }
			items(report.conflicts) { item ->
				ConflictSuggestionCard(
					item = item,
					onAck = { onConflictAck(item) },
					onEdit = { onConflictEdit(item) },
				)
			}
		}

		if (report.merges.isNotEmpty()) {
			item { SectionHeader("可以合并（${report.merges.size}）") }
			items(report.merges) { item ->
				MergeSuggestionCard(
					item = item,
					onMerge = { onMergeRequest(item) },
					onIgnore = { onIgnoreMerge(item) },
				)
			}
		}

		if (report.stale.isNotEmpty()) {
			item { SectionHeader("建议清理（${report.stale.size}）") }
			items(report.stale) { item ->
				StaleSuggestionCard(
					item = item,
					onDelete = { onStaleRequest(item) },
					onKeep = { onStaleKeep(item) },
				)
			}
		}

		if (report.notes.isNotEmpty()) {
			item { SectionHeader("顺便记下的观察") }
			items(report.notes) { note ->
				Text(
					note,
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
			}
		}
	}
}

/** 建议卡的外壳。三类建议共用一个容器，配色和内边距只写这一处 */
@Composable
private fun SuggestionCard(content: @Composable ColumnScope.() -> Unit) {
	Card(
		modifier = Modifier.fillMaxWidth(),
		shape = RoundedCornerShape(Dimens.radiusCard),
		colors = CardDefaults.cardColors(
			containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
		),
	) {
		Column(
			modifier = Modifier.padding(
				start = Dimens.spaceLg,
				end = Dimens.spaceLg,
				top = Dimens.spaceLg,
				bottom = Dimens.spaceSm,
			),
			verticalArrangement = Arrangement.spacedBy(Dimens.spaceSm),
			content = content,
		)
	}
}

/**
 * 矛盾建议。
 * question 用最大的字号，因为它是一句要用户回答的问话（"你现在到底住哪"），
 * reason 只是模型解释自己为什么问，属于背景。
 */
@Composable
private fun ConflictSuggestionCard(
	item: ConflictItem,
	onAck: () -> Unit,
	onEdit: () -> Unit,
) {
	SuggestionCard {
		Text(
			item.entry.title,
			style = MaterialTheme.typography.labelSmall,
			color = MaterialTheme.colorScheme.outline,
		)
		Text("⚠️ ${item.question}", style = MaterialTheme.typography.titleSmall)
		Text(
			item.reason,
			style = MaterialTheme.typography.bodySmall,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
		)
		Row(horizontalArrangement = Arrangement.spacedBy(Dimens.spaceXs)) {
			TextButton(onClick = onAck) { Text("我知道了") }
			TextButton(onClick = onEdit) { Text("去编辑") }
		}
	}
}

/** 合并建议。正文预览一定要给：用户是拿它跟脑子里的事实对，不是信模型的一句"可以合" */
@Composable
private fun MergeSuggestionCard(
	item: MergeItem,
	onMerge: () -> Unit,
	onIgnore: () -> Unit,
) {
	SuggestionCard {
		Text(
			"「${item.keep.title}」← ${item.absorb.joinToString("、") { "「${it.title}」" }}",
			style = MaterialTheme.typography.titleSmall,
		)
		Text(
			item.reason,
			style = MaterialTheme.typography.bodySmall,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
		)
		Text(
			"合并后的正文",
			style = MaterialTheme.typography.labelSmall,
			color = MaterialTheme.colorScheme.outline,
		)
		Text(item.mergedBody, style = MaterialTheme.typography.bodySmall)
		Row(horizontalArrangement = Arrangement.spacedBy(Dimens.spaceXs)) {
			TextButton(onClick = onMerge) { Text("合并") }
			TextButton(onClick = onIgnore) { Text("忽略") }
		}
	}
}

/** 清理建议。带上 oneLiner，让用户不用回列表翻就知道这条记的是什么 */
@Composable
private fun StaleSuggestionCard(
	item: StaleItem,
	onDelete: () -> Unit,
	onKeep: () -> Unit,
) {
	SuggestionCard {
		Text(item.entry.title, style = MaterialTheme.typography.titleSmall)
		Text(item.entry.oneLiner, style = MaterialTheme.typography.bodySmall)
		Text(
			item.reason,
			style = MaterialTheme.typography.bodySmall,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
		)
		Row(horizontalArrangement = Arrangement.spacedBy(Dimens.spaceXs)) {
			TextButton(onClick = onDelete) {
				Text("删除", color = MaterialTheme.colorScheme.error)
			}
			TextButton(onClick = onKeep) { Text("保留") }
		}
	}
}

/** 分类的中文名。用户要看的是"客观事实"，不是 FACT */
private fun categoryLabel(category: MemoryCardType): String = when (category) {
	MemoryCardType.FACT -> "客观事实"
	MemoryCardType.PREFERENCE -> "喜好与忌讳"
	MemoryCardType.EVENT -> "发生过的事"
	MemoryCardType.RELATION -> "关系与约定"
	MemoryCardType.IMPRESSION -> "对你的印象"
}

/**
 * 卡底那行元信息。
 *
 * 刻意只留"这条记忆管到哪儿"和折叠提示：别名、命中次数、被几轮确认过都是内部结构，
 * 摊在列表上会把每张卡撑成四行小字，扫一眼找不到重点。它们挪进了编辑框顶部的只读区，
 * 想追细节的人点开就能看到。
 */
private fun metaLine(entry: MemoryEntryEntity, foldable: Boolean, expanded: Boolean): String =
	buildList {
		add(scopeLabel(entry))
		if (entry.pinned) add("已钉住")
		if (foldable) add(if (expanded) "点一下收起" else "点一下看全文")
	}.joinToString(" · ")

/** 把 scopeKey 背后那两个 id 翻成人话，别让用户直接看 "c:3|p:-" */
private fun scopeLabel(entry: MemoryEntryEntity): String = when {
	entry.conversationId == null && entry.personaId == null -> "所有会话共享"
	entry.conversationId != null && entry.personaId == null -> "仅某个会话"
	entry.conversationId == null && entry.personaId != null -> "仅某个性格"
	else -> "某会话中的某性格"
}

/** 别名行。别名是"模型换个说法也能对上这条"的关键，值得让用户看见它认了哪些叫法 */
private fun aliasLabel(aliases: String): String? = splitTitles(aliases)
	?.joinToString("、", prefix = "也叫：")

private fun touchedLabel(titles: String): String? = splitTitles(titles)
	?.joinToString("、", prefix = "涉及：")

/** "|" 分隔的串拆成列表，全空返回 null 让调用方整行不显示 */
private fun splitTitles(raw: String): List<String>? = raw
	.split(MemoryEntryEntity.ALIAS_SEPARATOR)
	.map { it.trim() }
	.filter { it.isNotEmpty() }
	.takeIf { it.isNotEmpty() }

/** 日志类型中文化。kind 在库里是字符串，将来后台加了新类型这里没跟上也不能显示成空白 */
private fun logKindLabel(kind: String): String = when (kind) {
	MemoryLogKind.INGEST -> "整理"
	MemoryLogKind.LINT -> "体检"
	MemoryLogKind.MANUAL -> "手动"
	MemoryLogKind.MIGRATE -> "迁移"
	else -> kind
}
