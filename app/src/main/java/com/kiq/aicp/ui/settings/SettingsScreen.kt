// app/src/main/java/com/kiq/aicp/ui/settings/SettingsScreen.kt
// 设置页：接口配置、联网搜索、记忆与压缩调优、表情包、真人模拟、主动搭话、外观、备份与恢复、说明。
//
// 接口那三项走草稿 + 保存；压缩参数是即时生效的滑块（松手才写盘，见 SettingsComponents）。
// API Key 输入框永远不回填已保存的值，只在 supportingText 里给脱敏提示 ——
// 免得截图或者旁边有人时把整串 Key 亮出来。
//
// 真人模拟和主动搭话的细项都藏在各自总开关后面：关着的时候那些滑块调了也不生效，
// 摆出来只会让人以为自己调过了。后台推送那一项额外挂了通知权限申请，见 ProactiveSection。
//
// 尺寸一律走 Dimens，别在这儿写字面 dp —— 设置页是全应用卡片最多的一页，
// 这里一旦松口，其他页照着抄就全乱了。
// 文件末尾还放了个跨页共用的 EmptyState，原因写在它自己的注释里。

package com.kiq.aicp.ui.settings

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kiq.aicp.BuildConfig
import com.kiq.aicp.R
import com.kiq.aicp.data.backup.BackupManager
import com.kiq.aicp.domain.config.ConfigCodec
import com.kiq.aicp.ui.common.UpdateDialog
import com.kiq.aicp.ui.sticker.StickerViewModel
import com.kiq.aicp.ui.theme.Dimens
import kotlinx.coroutines.launch

/** 单行输入框走胶囊圆角，跟同一行里的按钮看着是一套东西 */
private val PillShape = RoundedCornerShape(Dimens.radiusPill)

/** 转圈指示器的直径。它是控件尺寸不是间距，不归 Dimens 那五档管 */
private val InlineSpinner = 16.dp

/**
 * 配置码正文和变化清单的最大高度。
 * 不限高的话，一段长码或者十几条变化会把对话框顶到超出屏幕，按钮直接被挤出可视区。
 */
private val CodeBoxMaxHeight = 200.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
	onOpenMemory: () -> Unit,
	onOpenStickers: () -> Unit,
	/**
	 * 非空表示这次是从别的页面（比如聊天页那句"还没配置接口"）压栈进来的，
	 * 标题栏要给一个返回箭头。tab 里进来时传 null —— tab 页没有"上一页"可回。
	 */
	onBack: (() -> Unit)? = null,
	viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory),
) {
	val state by viewModel.uiState.collectAsStateWithLifecycle()

	// 只给配置码那块用：复制成功需要一个不打断操作的反馈，
	// 而分区里那行 notice 是"结果"，复制这种一瞬间的动作用它反而要多点一次"知道了"
	val snackbarHostState = remember { SnackbarHostState() }

	// 手动检查更新的回音走 snackbar：它是"查完了"的一次性结果，
	// 而分区里挂一行常驻文字会让人以为那是当前状态
	LaunchedEffect(state.update.notice) {
		state.update.notice?.let {
			snackbarHostState.showSnackbar(it)
			viewModel.dismissUpdateNotice()
		}
	}

	state.update.available?.let { info ->
		UpdateDialog(info = info, onDismiss = viewModel::dismissUpdateDialog)
	}

	Scaffold(
		topBar = {
			TopAppBar(
				title = { Text("设置") },
				navigationIcon = {
					onBack?.let { back ->
						IconButton(onClick = back) {
							Icon(
								painter = painterResource(R.drawable.ic_back),
								contentDescription = "返回",
							)
						}
					}
				},
			)
		},
		snackbarHost = { SnackbarHost(snackbarHostState) },
	) { innerPadding ->
		LazyColumn(
			modifier = Modifier
				.fillMaxWidth()
				.padding(innerPadding),
			contentPadding = PaddingValues(Dimens.screenPadding),
			verticalArrangement = Arrangement.spacedBy(Dimens.spaceLg),
		) {
			item { EndpointSection(state, viewModel) }
			item { WebSearchSection(state, viewModel) }
			item { MemorySection(state, viewModel, onOpenMemory) }
			item { StickerSection(onOpenStickers) }
			item { AppearanceSection(state, viewModel) }
			// 新分区排在"关于数据"前面 —— 那段隐私说明是页脚性质的，后面再跟可调项会显得没收尾
			item { HumanizeSection(state, viewModel) }
			item { ProactiveSection(state, viewModel) }
			item { BackupSection(state, viewModel) }
			item { ConfigCodeSection(state, viewModel, snackbarHostState) }
			item { AboutSection(state, viewModel) }
		}
	}
}

@Composable
private fun EndpointSection(state: SettingsUiState, viewModel: SettingsViewModel) {
	var keyVisible by remember { mutableStateOf(false) }

	SectionCard(
		title = "模型接口",
		subtitle = "任何 OpenAI 兼容的服务都能接：DeepSeek、智谱、Kimi、OpenRouter，或者局域网里的 ollama",
	) {
		OutlinedTextField(
			value = state.baseUrlField,
			onValueChange = viewModel::onBaseUrlChange,
			label = { Text("Base URL") },
			placeholder = { Text("https://api.deepseek.com") },
			supportingText = { Text("填到域名或 /v1 都行，会自动补成 /v1/chat/completions") },
			singleLine = true,
			shape = PillShape,
			keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
			modifier = Modifier.fillMaxWidth(),
		)

		OutlinedTextField(
			value = state.apiKeyField,
			onValueChange = viewModel::onApiKeyChange,
			label = { Text("API Key") },
			placeholder = {
				Text(if (state.settings.apiKey.isEmpty()) "sk-..." else "留空则保持不变")
			},
			supportingText = {
				Text(
					if (state.settings.rememberApiKey) "当前：${state.savedKeyHint}　加密后存在本机，不上传"
					else "当前：${state.savedKeyHint}　仅保留在内存，不会写入本机存储",
				)
			},
			visualTransformation = if (keyVisible) {
				VisualTransformation.None
			} else {
				PasswordVisualTransformation()
			},
			trailingIcon = {
				TextButton(onClick = { keyVisible = !keyVisible }) {
					Text(if (keyVisible) "隐藏" else "显示")
				}
			},
			singleLine = true,
			shape = PillShape,
			modifier = Modifier.fillMaxWidth(),
		)

		Row(
			modifier = Modifier.fillMaxWidth(),
			horizontalArrangement = Arrangement.SpaceBetween,
			verticalAlignment = Alignment.CenterVertically,
		) {
			Column(modifier = Modifier.weight(1f)) {
				Text("记住 API Key")
				Text(
					if (state.settings.rememberApiKey) "加密保存在本机，不会上传"
					else "只在本次运行期间有效，应用重启后需要重新填写",
					style = MaterialTheme.typography.bodySmall,
				)
			}
			Switch(
				checked = state.settings.rememberApiKey,
				onCheckedChange = viewModel::requestRememberApiKey,
			)
		}

		OutlinedTextField(
			value = state.modelField,
			onValueChange = viewModel::onModelChange,
			label = { Text("模型名") },
			placeholder = { Text("deepseek-chat") },
			singleLine = true,
			shape = PillShape,
			modifier = Modifier.fillMaxWidth(),
		)

		Row(
			horizontalArrangement = Arrangement.spacedBy(Dimens.spaceSm),
			verticalAlignment = Alignment.CenterVertically,
		) {
			Button(onClick = viewModel::saveEndpoint, enabled = state.canSave) { Text("保存") }
			OutlinedButton(onClick = viewModel::discardDraft, enabled = state.canSave) { Text("放弃修改") }
			OutlinedButton(onClick = viewModel::testConnection, enabled = state.canTest) { Text("测试连接") }
		}

		// 关掉开关是个不可逆动作（密文当场删掉），所以拦一道确认
		if (state.confirmForgetApiKey) {
			AlertDialog(
				onDismissRequest = viewModel::dismissRememberApiKeyConfirmation,
				title = { Text("不再记住 API Key？") },
				text = { Text("已保存的 Key 会立即从本机删除，之后只在本次运行期间有效；应用重启后需要重新填写。") },
				confirmButton = {
					TextButton(onClick = viewModel::confirmRememberApiKeyOff) { Text("关闭并删除") }
				},
				dismissButton = {
					TextButton(onClick = viewModel::dismissRememberApiKeyConfirmation) { Text("取消") }
				},
			)
		}

		if (state.settings.apiKey.isNotEmpty()) {
			TextButton(onClick = viewModel::clearApiKey) { Text("清除当前 Key") }
		}

		if (state.savedHint) {
			Text(
				"已保存",
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.primary,
			)
		}

		when (val test = state.test) {
			ConnectionTest.Running -> Row(
				horizontalArrangement = Arrangement.spacedBy(Dimens.spaceSm),
				verticalAlignment = Alignment.CenterVertically,
			) {
				CircularProgressIndicator(modifier = Modifier.size(InlineSpinner), strokeWidth = 2.dp)
				Text("正在连接…", style = MaterialTheme.typography.bodySmall)
			}

			is ConnectionTest.Ok -> Text(
				"连通了，模型回了：${test.reply}",
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.primary,
			)

			is ConnectionTest.Fail -> Column(
				verticalArrangement = Arrangement.spacedBy(Dimens.spaceXs),
			) {
				Text(
					test.message,
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.error,
				)
				if (test.retryable) {
					Text(
						"这类错误过一会儿重试通常就好了",
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.outline,
					)
				}
				TextButton(onClick = viewModel::dismissTest) { Text("知道了") }
			}

			null -> Unit
		}
	}
}

/**
 * 联网搜索分区。
 *
 * 细项照 HumanizeSection 的做法藏在总开关后面：关掉之后 WebSearchService 直接返回 Empty，
 * 那几个滑块调了也不生效。
 */
@Composable
private fun WebSearchSection(state: SettingsUiState, viewModel: SettingsViewModel) {
	val s = state.settings

	SectionCard(
		title = "联网搜索",
		subtitle = "模型自己判断这句话要不要查资料，要查就走必应的免 key 接口搜一下。" +
			"搜到的东西只作为背景塞进上下文，聊天界面上看不出来",
	) {
		SwitchRow(
			title = "让模型自己决定要不要搜",
			subtitle = "关掉之后完全不联网。开着的代价是每条消息多一次判定调用，走的是压缩模型",
			checked = s.webSearchEnabled,
			onCheckedChange = viewModel::setWebSearchEnabled,
		)

		if (s.webSearchEnabled) {
			SliderRow(
				title = "取几条结果",
				subtitle = "只取搜索页给的标题和摘要，条数多了主要是把预算吃光，不一定更准",
				value = s.webSearchResultCount,
				valueRange = 1..10,
				step = 1,
				valueLabel = { "$it 条" },
				onValueSettled = { viewModel.setWebSearchResultCount(it) },
			)

			SliderRow(
				title = "抓几篇正文",
				subtitle = "抓正文更准但更慢，每篇都是一次额外请求；抓不到就退回用摘要，" +
					"设成 0 则完全不抓",
				value = s.webSearchFetchPages,
				valueRange = 0..2,
				step = 1,
				valueLabel = { if (it == 0) "只用摘要" else "$it 篇" },
				onValueSettled = { viewModel.setWebSearchFetchPages(it) },
			)

			SliderRow(
				title = "每篇留多少字",
				subtitle = "正文按相关段落截取，留太少会把结论切掉",
				value = s.webSearchPageChars,
				valueRange = 200..2_000,
				step = 100,
				valueLabel = { "$it 字" },
				onValueSettled = { viewModel.setWebSearchPageChars(it) },
			)

			SliderRow(
				title = "这段占多少预算",
				subtitle = "搜索结果最多占上下文的这么多 token，超出的部分会被裁掉，" +
					"给得越多留给对话历史的就越少",
				value = s.webSearchBudgetTokens,
				valueRange = 300..4_000,
				step = 100,
				valueLabel = { "$it token" },
				onValueSettled = { viewModel.setWebSearchBudgetTokens(it) },
			)
		}
	}
}

@Composable
private fun MemorySection(
	state: SettingsUiState,
	viewModel: SettingsViewModel,
	onOpenMemory: () -> Unit,
) {
	val s = state.settings

	SectionCard(
		title = "记忆与压缩",
		subtitle = "聊久了会自动把旧对话总结成记忆摘要，原文不会删，只是不再占上下文",
	) {
		OutlinedButton(onClick = onOpenMemory, modifier = Modifier.fillMaxWidth()) {
			Text("管理已记住的内容")
		}

		SwitchRow(
			title = "自动语义压缩",
			subtitle = "关掉之后只保留最近若干条，旧对话不再进上下文也不生成摘要",
			checked = s.autoCompressEnabled,
			onCheckedChange = viewModel::setAutoCompress,
		)

		OutlinedTextField(
			value = state.compressModelField,
			onValueChange = viewModel::onCompressModelChange,
			label = { Text("压缩专用模型（可留空）") },
			placeholder = { Text("留空则用上面的主模型") },
			supportingText = { Text("摘要用便宜的小模型就够，能省不少钱") },
			singleLine = true,
			shape = PillShape,
			modifier = Modifier.fillMaxWidth(),
		)

		SliderRow(
			title = "上下文预算",
			subtitle = "一次请求最多塞多少 token（含人设、记忆、历史）",
			value = s.contextBudgetTokens,
			valueRange = 1_000..32_000,
			step = 500,
			valueLabel = { "$it token" },
			onValueSettled = { viewModel.updateTuning(contextBudgetTokens = it) },
		)

		SliderRow(
			title = "保留最近原文",
			subtitle = "这些消息永远以原文进上下文，压缩不碰它们",
			value = s.keepRecentMessages,
			valueRange = 2..50,
			step = 2,
			valueLabel = { "$it 条" },
			onValueSettled = { viewModel.updateTuning(keepRecentMessages = it) },
		)

		SliderRow(
			title = "压缩触发（token）",
			subtitle = "未压缩部分超过这个量就开始总结",
			value = s.compressTriggerTokens,
			valueRange = 500..20_000,
			step = 250,
			valueLabel = { "$it token" },
			onValueSettled = { viewModel.updateTuning(compressTriggerTokens = it) },
		)

		SliderRow(
			title = "压缩触发（条数）",
			subtitle = "或者未压缩条数超过这个数，两个条件谁先到算谁",
			value = s.compressTriggerCount,
			valueRange = 4..200,
			step = 2,
			valueLabel = { "$it 条" },
			onValueSettled = { viewModel.updateTuning(compressTriggerCount = it) },
		)

		SliderRow(
			title = "摘要合并阈值",
			subtitle = "段摘要攒够这么多条，就再压一层成长期记忆",
			value = s.summaryMergeThreshold,
			valueRange = 2..30,
			step = 1,
			valueLabel = { "$it 条" },
			onValueSettled = { viewModel.updateTuning(summaryMergeThreshold = it) },
		)

		SliderRow(
			title = "记忆卡片上限",
			subtitle = "每次最多带多少条稳定事实进上下文，0 表示不带",
			value = s.memoryCardLimit,
			valueRange = 0..40,
			step = 2,
			valueLabel = { if (it == 0) "不带" else "$it 张" },
			onValueSettled = { viewModel.updateTuning(memoryCardLimit = it) },
		)

		SliderRow(
			title = "群聊一轮发言人数",
			subtitle = "多性格同场时，一次最多几个角色接话",
			value = s.groupMaxSpeakersPerTurn,
			valueRange = 1..5,
			step = 1,
			valueLabel = { "$it 人" },
			onValueSettled = { viewModel.updateTuning(groupMaxSpeakersPerTurn = it) },
		)

		OutlinedButton(onClick = viewModel::resetTuning) { Text("恢复默认调优参数") }
	}
}

/**
 * 表情包分区。
 *
 * 这里刻意用 StickerViewModel 而不是往 SettingsViewModel 上加两个 setter：
 * 表情包的开关跟"已导入多少张"是同一件事的两面，共用一个 ViewModel 才能顺手把数量显示出来，
 * 而 SettingsViewModel 那一整套接口草稿逻辑跟表情包毫无关系，掺进去以后谁改都得两头翻。
 * 代价是设置页停留期间多订阅两条 Room Flow，量级可以忽略。
 */
@Composable
private fun StickerSection(
	onOpenStickers: () -> Unit,
	stickerViewModel: StickerViewModel = viewModel(factory = StickerViewModel.Factory),
) {
	val sticker by stickerViewModel.uiState.collectAsStateWithLifecycle()

	SectionCard(
		title = "表情包",
		subtitle = "自己从相册导入的图。AI 回复里写 [标记] 的地方会换成对应那张，应用没有预置表情",
	) {
		OutlinedButton(onClick = onOpenStickers, modifier = Modifier.fillMaxWidth()) {
			Text(
				if (sticker.totalCount == 0) {
					"管理表情包（还没导入）"
				} else {
					"管理表情包（${sticker.groups.size} 组 ${sticker.totalCount} 张）"
				},
			)
		}

		SwitchRow(
			title = "让 AI 用表情包",
			subtitle = "关掉后不再把表情清单写进提示词，模型也就不会主动发；" +
				"你自己手打 [标记] 照样能显示成图",
			checked = sticker.stickersEnabled,
			onCheckedChange = stickerViewModel::setStickersEnabled,
		)

		if (sticker.stickersEnabled) {
			SliderRow(
				title = "告诉模型多少个表情",
				subtitle = "按常用度取前几个写进提示词。一个标记约 4~6 token，给太多会挤掉记忆的位置",
				value = sticker.promptLimit,
				valueRange = 0..120,
				step = 5,
				valueLabel = { if (it == 0) "不告诉" else "$it 个" },
				onValueSettled = stickerViewModel::setPromptLimit,
			)
		}
	}
}

@Composable
private fun AppearanceSection(state: SettingsUiState, viewModel: SettingsViewModel) {
	SectionCard(title = "外观") {
		SwitchRow(
			title = "跟随系统取色",
			subtitle = "Android 12 及以上生效，关掉则用应用自带的紫色主题",
			checked = state.settings.dynamicColor,
			onCheckedChange = viewModel::setDynamicColor,
		)
	}
}

/**
 * 真人模拟分区。
 *
 * 三项细调只在总开关打开时才画出来。这不是为了好看：关掉之后 humanizeConfig() 返回 Disabled，
 * 分段和停顿全都不走了，滑块还留在那儿等于骗人。
 */
@Composable
private fun HumanizeSection(state: SettingsUiState, viewModel: SettingsViewModel) {
	val s = state.settings

	SectionCard(
		title = "真人模拟",
		subtitle = "让回复像对面真有个人在打字，而不是憋半天甩出一整篇",
	) {
		SwitchRow(
			title = "像真人一样发消息",
			subtitle = "长回复会拆成几条陆续发出，回复前也会有短暂停顿；关掉就退回一次一整条的老样子",
			checked = s.humanizeEnabled,
			onCheckedChange = { viewModel.updateHumanizeTuning(enabled = it) },
		)

		if (s.humanizeEnabled) {
			SliderRow(
				title = "最多分几段",
				subtitle = "只是上限。短回复照样一条发完，不会为了凑数硬切",
				value = s.humanizeMaxSegments,
				valueRange = 1..5,
				step = 1,
				valueLabel = { if (it == 1) "不分段" else "$it 段" },
				onValueSettled = { viewModel.updateHumanizeTuning(maxSegments = it) },
			)

			SliderRow(
				title = "打字速度",
				subtitle = "值越大打得越慢，段间停顿按这一段的字数乘它算 —— " +
					"当前速度下，20 个字的一段大约要等 ${typingPauseText(s.humanizeMsPerChar)} 秒",
				value = s.humanizeMsPerChar,
				valueRange = 10..200,
				step = 5,
				valueLabel = { "$it ms/字" },
				onValueSettled = { viewModel.updateHumanizeTuning(msPerChar = it) },
			)

			SliderRow(
				title = "已读延迟",
				subtitle = "看到消息后先愣一下再开始打字，给 0 就是秒回",
				value = s.humanizeReadDelayMs.toInt(),
				valueRange = 0..5_000,
				step = 100,
				valueLabel = ::delayText,
				onValueSettled = { viewModel.updateHumanizeTuning(readDelayMs = it.toLong()) },
			)
		}
	}
}

/**
 * 主动搭话分区。
 *
 * 通知权限这块是整页最容易出错的地方：Android 13+ 没拿到 POST_NOTIFICATIONS 时，
 * NotificationManager.notify 不抛异常，就是静默丢掉。所以用户拒绝权限后绝不能把开关留在打开状态 ——
 * 那样他会以为配好了，然后抱怨"开了推送但一条都没收到"。拒绝就写回 false 并明说原因。
 *
 * 排程本身不在这里碰，AicpApplication 订阅着设置自己会去注册/取消 WorkManager。
 *
 * 保活那两行（前台服务开关 + 电池优化引导）拆到 KeepAliveRows 里，
 * 但仍然摆在这张卡片内：它不是一项独立功能，是"让主动搭话真的能按时触发"的手段，
 * 单独立一个分区反而会让人以为开了它就能收到消息。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProactiveSection(state: SettingsUiState, viewModel: SettingsViewModel) {
	val s = state.settings
	val context = LocalContext.current
	var permissionDenied by remember { mutableStateOf(false) }

	val notificationPermission = rememberLauncherForActivityResult(
		ActivityResultContracts.RequestPermission(),
	) { granted ->
		viewModel.updateProactiveTuning(pushEnabled = granted)
		permissionDenied = !granted
	}

	fun togglePush(enable: Boolean) {
		if (!enable) {
			viewModel.updateProactiveTuning(pushEnabled = false)
			permissionDenied = false
			return
		}
		// 13 以下不存在这个运行时权限，checkSelfPermission 也会直接给 GRANTED，但显式判版本更好读
		val needsGrant = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
			ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
			PackageManager.PERMISSION_GRANTED
		if (needsGrant) {
			notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
		} else {
			viewModel.updateProactiveTuning(pushEnabled = true)
			permissionDenied = false
		}
	}

	/**
	 * 回到这个页面时复查一次权限。
	 * 用户完全可以在开了推送之后跑到系统设置里把通知权限撤掉，
	 * 那之后开关还显示"开着"就是在骗人 —— 通知已经发不出来了，钱照花。
	 */
	LifecycleResumeEffect(s.proactivePushEnabled) {
		val revoked = s.proactivePushEnabled &&
			Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
			ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
			PackageManager.PERMISSION_GRANTED
		if (revoked) {
			viewModel.updateProactiveTuning(pushEnabled = false)
			permissionDenied = true
		}
		onPauseOrDispose { }
	}

	SectionCard(
		title = "主动搭话",
		subtitle = "隔太久没说话时，让 AI 自己开个话头。默认关着，因为它会自己花钱",
	) {
		SwitchRow(
			title = "很久没聊时主动找我",
			subtitle = "这只是总闸；具体哪个性格能主动开口，还得在它自己的设置里单独允许",
			checked = s.proactiveEnabled,
			onCheckedChange = { viewModel.updateProactiveTuning(enabled = it) },
		)

		if (s.proactiveEnabled) {
			// 空闲阈值给档位不给连续滑块：这个量级上"137 分钟"和"120 分钟"没有区别，
			// 而且后台唤醒间隔取的是它的四分之一，精调出来的零头也会被 WorkManager 抹平。
			// 标题/数值故意照抄 SliderRow 的 bodyLarge + labelLarge：它在用户眼里就是一行滑块的变体，
			// 换成别的字级会显得这一行不属于这张卡
			Column(verticalArrangement = Arrangement.spacedBy(Dimens.spaceXs)) {
				Row(verticalAlignment = Alignment.CenterVertically) {
					Text(
						"空闲多久才开口",
						style = MaterialTheme.typography.bodyLarge,
						modifier = Modifier.weight(1f),
					)
					Text(
						idleLabel(s.proactiveIdleMinutes),
						style = MaterialTheme.typography.labelLarge,
						color = MaterialTheme.colorScheme.primary,
					)
				}
				Text(
					"从最后一条消息算起，超过这个时长才允许主动搭话",
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.outline,
				)
				FlowRow(
					horizontalArrangement = Arrangement.spacedBy(Dimens.spaceSm),
					verticalArrangement = Arrangement.spacedBy(Dimens.spaceSm),
				) {
					IDLE_PRESETS.forEach { preset ->
						FilterChip(
							selected = s.proactiveIdleMinutes == preset,
							onClick = { viewModel.updateProactiveTuning(idleMinutes = preset) },
							label = { Text(idleLabel(preset)) },
						)
					}
				}
			}

			SliderRow(
				title = "每天最多几次",
				subtitle = "所有会话共用这个额度，按自然日重置",
				value = s.proactiveDailyLimit,
				valueRange = 1..20,
				step = 1,
				valueLabel = { "$it 次" },
				onValueSettled = { viewModel.updateProactiveTuning(dailyLimit = it) },
			)

			SliderRow(
				title = "免打扰开始",
				value = s.quietHoursStart,
				valueRange = 0..23,
				step = 1,
				valueLabel = ::hourLabel,
				onValueSettled = { viewModel.updateProactiveTuning(quietStart = it) },
			)

			SliderRow(
				title = "免打扰结束",
				value = s.quietHoursEnd,
				valueRange = 0..23,
				step = 1,
				valueLabel = ::hourLabel,
				onValueSettled = { viewModel.updateProactiveTuning(quietEnd = it) },
			)

			Text(
				"跨午夜是正常填法：23:00 到 08:00 表示晚上 11 点到次日早上 8 点之间不打扰。" +
					"两个值填成一样则整天都不打扰。\n当前：${quietRangeLabel(s.quietHoursStart, s.quietHoursEnd)}",
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.outline,
			)

			SwitchRow(
				title = "后台推送",
				subtitle = "应用没打开时也检查，够条件就发一条系统通知",
				checked = s.proactivePushEnabled,
				onCheckedChange = { togglePush(it) },
			)

			Text(
				"开这项之前先看清三件事：\n" +
					"· 它会在你没打开应用的时候自己发起模型请求，账单上是实打实的花费\n" +
					"· 需要通知权限，不给就收不到\n" +
					"· 系统最短只允许 15 分钟检查一次，而且不保证准时 —— 手机进 Doze 会往后拖，" +
					"晚个把小时都属于正常",
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.error,
			)

			if (permissionDenied) {
				Text(
					"刚才没给通知权限，已经把后台推送关回去了：没这个权限系统会把通知直接丢掉，" +
						"开着只是白花钱。要用就去系统设置里放开本应用的通知，再回来打开开关。",
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.error,
				)
			}
		}

		// 放在 if 外面：主动搭话关着的时候这一行也要露出来，只是变成禁用态。
		// 藏起来的话，"到点了却不搭话"的用户永远找不到这个能救他的开关
		KeepAliveRows(state, viewModel)
	}
}

/**
 * 保活：前台服务开关 + 电池优化引导。
 *
 * 这两样必须一起出现。前台服务只能把进程放进"系统眼里不该回收"的那一档，
 * 挡不住部分 ROM 在内存紧张时的清理；电池优化白名单管的是省电策略那一路。
 * 只做一半，用户还是会撞上"开了保活照样不搭话"，然后得出"这功能是假的"的结论。
 *
 * 开关自己画一行而不复用 SwitchRow：这一项在主动搭话关着时必须是禁用态，
 * 而 SwitchRow 没有 enabled 参数 —— 能点开却什么都不发生，比压根不给点更让人困惑。
 */
@Composable
private fun KeepAliveRows(state: SettingsUiState, viewModel: SettingsViewModel) {
	val s = state.settings
	val context = LocalContext.current

	// 电池优化的当前状态。只在页面重新可见时查一次：用户是跑到系统设置里改这个的，
	// 那个页面既不回调也不返回结果，resume 是唯一能保证"他改完回来这里就跟着变"的时机
	var ignoringBattery by remember { mutableStateOf(false) }

	// 两个系统页面都打不开的机器。按钮按下去毫无反应最容易被当成 bug，得留一句话交代
	var noSystemPage by remember { mutableStateOf(false) }

	LifecycleResumeEffect(Unit) {
		ignoringBattery = isIgnoringBatteryOptimizations(context)
		onPauseOrDispose { }
	}

	Row(
		modifier = Modifier.fillMaxWidth(),
		horizontalArrangement = Arrangement.spacedBy(Dimens.spaceMd),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Column(
			modifier = Modifier.weight(1f),
			verticalArrangement = Arrangement.spacedBy(Dimens.spaceXs),
		) {
			Text(
				"保持后台运行",
				style = MaterialTheme.typography.bodyLarge,
				// 禁用态连标题一起变灰，不然只有开关是灰的，看着像开关坏了
				color = if (s.proactiveEnabled) {
					MaterialTheme.colorScheme.onSurface
				} else {
					MaterialTheme.colorScheme.outline
				},
			)
			Text(
				if (s.proactiveEnabled) {
					"挂一条常驻通知把 AICP 留在后台，让到点的后台检查更可能真的跑起来"
				} else {
					"先打开上面的主动搭话 —— 它关着的时候，保活没有任何东西要保"
				},
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.outline,
			)
		}
		Switch(
			checked = s.keepAliveEnabled,
			onCheckedChange = { viewModel.setKeepAlive(it) },
			enabled = s.proactiveEnabled,
		)
	}

	// 只在真的开着的时候往下展开：保活关着时讲电池优化，等于在解释一个用户还没选择的东西
	if (s.proactiveEnabled && s.keepAliveEnabled) {
		Text(
			"代价先说清楚：通知栏会一直挂一条 AICP 的通知，划不掉也撤不掉 —— " +
				"那是系统对前台服务的强制要求，不是这里漏了个关闭按钮。" +
				"换来的是进程不容易被回收，到点的检查更可能真的执行。",
			style = MaterialTheme.typography.bodySmall,
			color = MaterialTheme.colorScheme.error,
		)

		Text(
			if (ignoringBattery) {
				"电池优化：已经放行，系统不会再为了省电把 AICP 从后台清掉"
			} else {
				"电池优化：还管着 AICP。省电策略照样会在内存紧张时把它清掉，这一路前台服务挡不住"
			},
			style = MaterialTheme.typography.bodySmall,
			color = if (ignoringBattery) {
				MaterialTheme.colorScheme.outline
			} else {
				MaterialTheme.colorScheme.error
			},
		)

		if (!ignoringBattery) {
			OutlinedButton(onClick = { noSystemPage = !openBatteryWhitelistRequest(context) }) {
				Text("去放行电池优化")
			}
			Text(
				"按下去会弹一个系统对话框问你要不要允许后台运行。这个请求只在你按这里的时候发 —— " +
					"应用一启动就自己弹系统弹窗，那是流氓软件的做法。",
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.outline,
			)
		}

		if (noSystemPage) {
			Text(
				"你这台设备把这两个系统页面都藏了起来，只能手动找：系统设置 - 应用管理 - AICP - " +
					"耗电管理（有的 ROM 叫省电策略或自启动管理），把它设成不受限制。",
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.error,
			)
		}
	}
}

/**
 * 当前有没有被放进电池优化白名单。
 *
 * 取不到 PowerManager 时按"没放行"算：这个判断只用来决定要不要显示引导，
 * 宁可多显示一次引导，也不能在真被限制着的时候告诉用户"已经放行了"。
 */
private fun isIgnoringBatteryOptimizations(context: Context): Boolean {
	val manager = context.getSystemService(PowerManager::class.java) ?: return false
	return manager.isIgnoringBatteryOptimizations(context.packageName)
}

/**
 * 拉起电池优化白名单请求，返回有没有成功打开某个页面。
 *
 * 两级兜底：ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS 在不少国产 ROM 上压根不存在，
 * startActivity 会抛 ActivityNotFoundException；有的 ROM 存在但不接三方调用，抛 SecurityException。
 * 抛了就退回应用详情页 —— 那一页几乎每台机器都有，用户从那儿点进耗电管理也能到目的地。
 * 连详情页都打不开时返回 false，由调用方把话说明白，而不是让按钮按下去毫无反应。
 *
 * BatteryLife 那条 lint 警告提醒的是"Play 对这个权限有政策限制"，这里明知故用：
 * 保活就是这个功能的诉求本身，而且请求由用户在设置页主动按，不是启动时偷偷弹的。
 */
@SuppressLint("BatteryLife")
private fun openBatteryWhitelistRequest(context: Context): Boolean {
	val self = "package:${context.packageName}".toUri()
	// NEW_TASK 是给"LocalContext 拿到的不是 Activity"那种情况兜底的，从 Activity 起也不受影响
	val opened = runCatching {
		context.startActivity(
			Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, self)
				.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
		)
	}.isSuccess
	if (opened) return true

	return runCatching {
		context.startActivity(
			Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, self)
				.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
		)
	}.isSuccess
}

/**
 * 备份与恢复。
 *
 * 恢复这条路刻意走得比导出啰嗦：选文件 → 看具体数字 → 确认。
 * 确认框上写的是"多少个会话、多少条记忆会被替换"，不是"确定吗" —— 后者拦不住任何误操作。
 *
 * 换文件的动作发生在下次冷启动（数据库正被占用着，运行中换不了），所以按下确认之后
 * 这里能给的最强承诺只是"已经解好，等重启"。这一层预期必须在文案里说清楚，
 * 不然用户点完确认发现数据没变，只会以为功能是坏的。
 */
@Composable
private fun BackupSection(state: SettingsUiState, viewModel: SettingsViewModel) {
	val backup = state.backup

	val exportLauncher = rememberLauncherForActivityResult(
		ActivityResultContracts.CreateDocument(BackupManager.MIME_ZIP),
	) { uri -> uri?.let(viewModel::onExportTargetChosen) }

	// 用 OpenDocument 而不是 GetContent：前者能拿到真正的文件，后者在某些文件管理器上
	// 会给一个转了一手的临时副本，大文件容易半路失效
	val pickLauncher = rememberLauncherForActivityResult(
		ActivityResultContracts.OpenDocument(),
	) { uri -> uri?.let(viewModel::onBackupZipPicked) }

	SectionCard(
		title = "备份与恢复",
		subtitle = "换手机、刷机、清数据之前自己导一份。这个应用不进系统云备份，数据只在本机，所以这是唯一的路",
	) {
		OutlinedButton(
			onClick = { exportLauncher.launch(BackupManager.suggestedFileName()) },
			enabled = !backup.busy,
			modifier = Modifier.fillMaxWidth(),
		) {
			Text("导出全部数据")
		}

		Text(
			"打包会话与消息原文、记忆摘要与记忆卡片、表情包和头像，存成一个 zip 放到你选的位置。" +
				"不含 API Key —— 它是用只存在这台设备里的密钥加密的，换机也解不开，带出去纯属多余风险。\n" +
				"下一步可以给这份备份设个口令：设了就只有你能打开，不设就是明文 zip，" +
				"谁拿到文件就等于拿到全部聊天记录。",
			style = MaterialTheme.typography.bodySmall,
			color = MaterialTheme.colorScheme.outline,
		)

		OutlinedButton(
			onClick = { pickLauncher.launch(arrayOf(BackupManager.MIME_ZIP)) },
			enabled = !backup.busy && !backup.restorePending,
			modifier = Modifier.fillMaxWidth(),
		) {
			Text("从备份恢复")
		}

		Text(
			"恢复是整体替换，动手前看清三件事：\n" +
				"· 当前设备上的会话、记忆、表情、头像会被备份里的内容覆盖，覆盖掉的找不回来\n" +
				"· 必须完全退出应用再打开才能完成 —— 数据库正被占用着，运行中换文件会把库写坏\n" +
				"· 只认本版或更早版本导出的备份，更新版本的备份会被挡下来",
			style = MaterialTheme.typography.bodySmall,
			color = MaterialTheme.colorScheme.error,
		)

		if (backup.restorePending) {
			Text(
				"有一份备份已经解好，等下次启动生效。把 AICP 从任务列表里划掉再打开就会开始恢复；" +
					"从现在到重启之间新聊的内容会被备份里的内容覆盖。",
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.error,
			)
			TextButton(onClick = viewModel::cancelStagedRestore, enabled = !backup.busy) {
				Text("取消这次恢复")
			}
		}

		backup.job?.let { job ->
			Row(
				horizontalArrangement = Arrangement.spacedBy(Dimens.spaceSm),
				verticalAlignment = Alignment.CenterVertically,
			) {
				CircularProgressIndicator(modifier = Modifier.size(InlineSpinner), strokeWidth = 2.dp)
				Text(backupJobLabel(job), style = MaterialTheme.typography.bodySmall)
			}
		}

		// 成功和失败共用一处出口：两条各配一个"知道了"的话，卡片底部会同时挂两个按钮
		val message = backup.error ?: backup.notice
		if (message != null) {
			Text(
				message,
				style = MaterialTheme.typography.bodySmall,
				color = if (backup.error != null) {
					MaterialTheme.colorScheme.error
				} else {
					MaterialTheme.colorScheme.primary
				},
			)
			TextButton(onClick = viewModel::dismissBackupMessage) { Text("知道了") }
		}
	}

	backup.confirm?.let { confirm ->
		RestoreConfirmDialog(
			confirm = confirm,
			onConfirm = viewModel::confirmRestore,
			onDismiss = viewModel::dismissRestoreConfirm,
		)
	}

	if (backup.exportPrompt != null) {
		ExportPasswordDialog(
			onConfirm = viewModel::confirmExport,
			onDismiss = viewModel::dismissExportPrompt,
		)
	}

	backup.restorePrompt?.let { prompt ->
		RestorePasswordDialog(
			prompt = prompt,
			busy = backup.busy,
			onConfirm = viewModel::submitRestorePassword,
			onDismiss = viewModel::dismissRestorePrompt,
		)
	}
}

@Composable
private fun RestoreConfirmDialog(
	confirm: RestoreConfirm,
	onConfirm: () -> Unit,
	onDismiss: () -> Unit,
) {
	val s = confirm.summary
	AlertDialog(
		onDismissRequest = onDismiss,
		title = { Text("用备份覆盖现有数据？") },
		text = {
			Text(
				"当前这台设备上的 ${s.conversations} 个会话、${s.messages} 条消息、" +
					"${s.memories} 条记忆和 ${s.stickers} 张表情，会被这份备份里的内容整体替换掉。" +
					"替换掉的部分没有任何找回办法。\n\n" +
					"点「覆盖」之后备份会先校验并解压好，等你完全退出应用再打开时才真正生效。",
				style = MaterialTheme.typography.bodyMedium,
			)
		},
		confirmButton = { Button(onClick = onConfirm) { Text("覆盖") } },
		dismissButton = { TextButton(onClick = onDismiss) { Text("算了") } },
	)
}

/**
 * 导出前的口令框。
 *
 * 两条路都得当面说清，因为它们的代价方向正好相反：不设口令是"别人拿到文件就全看得见"，
 * 设了口令是"自己忘了就永久打不开"。哪条更要命取决于用户的处境，所以不替他默认，
 * 但两句话都摆到他眼前 —— 只写一句的话，另一条就成了事后才发现的坑。
 *
 * 明文可见（显示/隐藏）是刻意给的：打错一个字符的代价是整份备份报废，
 * 让他能核对自己到底输了什么，比"安全地看不见"重要。
 */
@Composable
private fun ExportPasswordDialog(
	onConfirm: (CharArray?) -> Unit,
	onDismiss: () -> Unit,
) {
	var password by remember { mutableStateOf("") }
	var repeat by remember { mutableStateOf("") }
	var visible by remember { mutableStateOf(false) }

	val plain = password.isEmpty()
	val mismatch = !plain && repeat != password

	AlertDialog(
		onDismissRequest = onDismiss,
		title = { Text("给这份备份设个口令？") },
		text = {
			Column(verticalArrangement = Arrangement.spacedBy(Dimens.spaceMd)) {
				OutlinedTextField(
					value = password,
					onValueChange = { password = it },
					label = { Text("口令（留空就是不加密）") },
					singleLine = true,
					shape = PillShape,
					visualTransformation = passwordTransformation(visible),
					trailingIcon = {
						TextButton(onClick = { visible = !visible }) {
							Text(if (visible) "隐藏" else "显示")
						}
					},
					modifier = Modifier.fillMaxWidth(),
				)

				OutlinedTextField(
					value = repeat,
					onValueChange = { repeat = it },
					label = { Text("再输一遍") },
					enabled = !plain,
					isError = mismatch,
					singleLine = true,
					shape = PillShape,
					visualTransformation = passwordTransformation(visible),
					modifier = Modifier.fillMaxWidth(),
				)

				if (mismatch) {
					Text(
						"两次输入不一样",
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.error,
					)
				}

				if (plain) {
					Text(
						"不设口令：导出的是普通 zip，任何解压软件都能直接打开，方便你自己核对里面有什么；" +
							"代价是它一旦进了网盘、或者手机落到别人手上，全部聊天记录就一起给出去了。",
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.outline,
					)
				} else {
					// 这句用 bodyMedium 而不是 bodySmall：它是整个流程里唯一不可逆的风险，
					// 缩成灰色小字就等于没说
					Text(
						"口令只在你手里：我不会保存，也没有任何找回途径。忘了它，这份备份就永久打不开，" +
							"没有例外、没有客服。现在就把它记到你确定不会丢的地方。",
						style = MaterialTheme.typography.bodyMedium,
						color = MaterialTheme.colorScheme.error,
					)
				}
			}
		},
		confirmButton = {
			Button(
				onClick = { onConfirm(if (plain) null else password.toCharArray()) },
				enabled = !mismatch,
			) {
				Text(if (plain) "不加口令，直接导出" else "加密导出")
			}
		},
		dismissButton = { TextButton(onClick = onDismiss) { Text("算了") } },
	)
}

/**
 * 恢复时的口令框。
 *
 * 输错口令只把提示贴在输入框下面，对话框和已输入的内容都留着 ——
 * 把人打回文件选择那一步是最气人的做法：口令可能只错了一个字符，重选文件却要从头再走一遍。
 *
 * 解密期间对话框不关、按钮禁用、里面转圈：这一步要过 PBKDF2 再整份解密，
 * 秒级是常态，没有反馈的话用户只会以为点空了然后接着点。
 */
@Composable
private fun RestorePasswordDialog(
	prompt: RestorePasswordPrompt,
	busy: Boolean,
	onConfirm: (CharArray) -> Unit,
	onDismiss: () -> Unit,
) {
	// 刻意不把 prompt.error 当 remember 的 key：换 key 会重建 state 把输入清空，
	// 那正是这里最不想发生的事
	var password by remember { mutableStateOf("") }
	var visible by remember { mutableStateOf(false) }

	AlertDialog(
		onDismissRequest = { if (!busy) onDismiss() },
		title = { Text("这份备份有口令") },
		text = {
			Column(verticalArrangement = Arrangement.spacedBy(Dimens.spaceMd)) {
				OutlinedTextField(
					value = password,
					onValueChange = { password = it },
					label = { Text("口令") },
					enabled = !busy,
					isError = prompt.error != null,
					singleLine = true,
					shape = PillShape,
					visualTransformation = passwordTransformation(visible),
					trailingIcon = {
						TextButton(onClick = { visible = !visible }, enabled = !busy) {
							Text(if (visible) "隐藏" else "显示")
						}
					},
					modifier = Modifier.fillMaxWidth(),
				)

				prompt.error?.let { message ->
					Text(
						message,
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.error,
					)
				}

				Text(
					"填导出这份备份时你自己设的那个口令。输错不会有任何损失 —— " +
						"现有数据在你重启应用之前一直原样不动。",
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.outline,
				)

				if (busy) {
					Row(
						horizontalArrangement = Arrangement.spacedBy(Dimens.spaceSm),
						verticalAlignment = Alignment.CenterVertically,
					) {
						CircularProgressIndicator(
							modifier = Modifier.size(InlineSpinner),
							strokeWidth = 2.dp,
						)
						Text("正在解密并校验…", style = MaterialTheme.typography.bodySmall)
					}
				}
			}
		},
		confirmButton = {
			Button(
				onClick = { onConfirm(password.toCharArray()) },
				enabled = password.isNotEmpty() && !busy,
			) {
				Text("解密并继续")
			}
		},
		dismissButton = {
			TextButton(onClick = onDismiss, enabled = !busy) { Text("算了") }
		},
	)
}

/**
 * 配置码。
 *
 * 跟上面的文件备份是两件事，所以单开一张卡：备份搬的是"这台设备上积累的东西"（聊天、记忆、图），
 * 配置码搬的只是"我把 AICP 调成了什么样"。混在一张卡里，用户迟早会拿配置码去找他的聊天记录。
 *
 * 口令框这次只要一个 —— 配置码随时能重新生成，输错重来一次就行；
 * 备份文件那边要输两遍是因为它是一次性产物，口令错了就永久打不开，两者的代价不是一个量级。
 */
@Composable
private fun ConfigCodeSection(
	state: SettingsUiState,
	viewModel: SettingsViewModel,
	snackbarHostState: SnackbarHostState,
) {
	val config = state.configCode

	SectionCard(
		title = "配置码",
		subtitle = "一段文字，复制到另一台手机粘贴一下，设置就搬过去了。只搬设置 —— " +
			"聊天记录、记忆、表情走上面的文件备份",
	) {
		OutlinedButton(
			onClick = viewModel::openConfigExportPrompt,
			enabled = !config.busy,
			modifier = Modifier.fillMaxWidth(),
		) {
			Text("生成配置码")
		}

		OutlinedButton(
			onClick = viewModel::openConfigImportSheet,
			enabled = !config.busy,
			modifier = Modifier.fillMaxWidth(),
		) {
			Text("导入配置码")
		}

		Text(
			"带的是接口地址、模型名、记忆与压缩参数、真人模拟和主动搭话那些开关。" +
				"导入前会先列出哪几项要变，你看过再决定。",
			style = MaterialTheme.typography.bodySmall,
			color = MaterialTheme.colorScheme.outline,
		)

		config.job?.let { job ->
			Row(
				horizontalArrangement = Arrangement.spacedBy(Dimens.spaceSm),
				verticalAlignment = Alignment.CenterVertically,
			) {
				CircularProgressIndicator(modifier = Modifier.size(InlineSpinner), strokeWidth = 2.dp)
				Text(configJobLabel(job), style = MaterialTheme.typography.bodySmall)
			}
		}

		val message = config.error ?: config.notice
		if (message != null) {
			Text(
				message,
				style = MaterialTheme.typography.bodySmall,
				color = if (config.error != null) {
					MaterialTheme.colorScheme.error
				} else {
					MaterialTheme.colorScheme.primary
				},
			)
			TextButton(onClick = viewModel::dismissConfigMessage) { Text("知道了") }
		}
	}

	if (config.exportPrompt) {
		ConfigExportPasswordDialog(
			onConfirm = viewModel::generateConfigCode,
			onDismiss = viewModel::dismissConfigExportPrompt,
		)
	}

	config.generated?.let { generated ->
		GeneratedCodeDialog(
			generated = generated,
			snackbarHostState = snackbarHostState,
			onDismiss = viewModel::dismissGeneratedCode,
		)
	}

	config.importSheet?.let { sheet ->
		ConfigImportDialog(
			sheet = sheet,
			busy = config.busy,
			needsPassword = viewModel::configCodeNeedsPassword,
			onSubmit = viewModel::submitConfigCode,
			onDismiss = viewModel::dismissConfigImportSheet,
		)
	}

	config.confirm?.let { confirm ->
		ConfigImportConfirmDialog(
			confirm = confirm,
			onConfirm = viewModel::applyImportedConfig,
			onDismiss = viewModel::dismissConfigImportConfirm,
		)
	}
}

/**
 * 生成配置码前的口令框。
 *
 * 两种模式的差别不是"安全等级"而是"带不带 Key"，所以文案必须点明这一点：
 * 留空得到的是能随便贴的明文码（Key 不跟着走，到那边要手填一次），
 * 填口令得到的是带 Key 的加密码。用户是按"这段字我打算发到哪儿"来选的，
 * 只写"要不要加密"他没法判断。
 */
@Composable
private fun ConfigExportPasswordDialog(
	onConfirm: (CharArray?) -> Unit,
	onDismiss: () -> Unit,
) {
	var password by remember { mutableStateOf("") }
	var visible by remember { mutableStateOf(false) }
	val plain = password.isEmpty()

	AlertDialog(
		onDismissRequest = onDismiss,
		title = { Text("生成配置码") },
		text = {
			Column(verticalArrangement = Arrangement.spacedBy(Dimens.spaceMd)) {
				OutlinedTextField(
					value = password,
					onValueChange = { password = it },
					label = { Text("口令（留空则不带 API Key）") },
					singleLine = true,
					shape = PillShape,
					visualTransformation = passwordTransformation(visible),
					trailingIcon = {
						TextButton(onClick = { visible = !visible }) {
							Text(if (visible) "隐藏" else "显示")
						}
					},
					modifier = Modifier.fillMaxWidth(),
				)

				Text(
					if (plain) {
						"不填口令：生成的是明文码，不含 API Key，贴进自己的笔记也不怕。" +
							"导入之后在那台手机上把 Key 手填一次就行。"
					} else {
						"填了口令：生成的码里带着 API Key，需要这个口令才能解开。" +
							"配置码随时能重新生成，所以口令输错了重来一次就好，不用输两遍。"
					},
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.outline,
				)
			}
		},
		confirmButton = {
			Button(onClick = { onConfirm(if (plain) null else password.toCharArray()) }) {
				Text(if (plain) "生成明文码" else "生成加密码")
			}
		},
		dismissButton = { TextButton(onClick = onDismiss) { Text("算了") } },
	)
}

/**
 * 生成结果。
 *
 * 码本身放在 SelectionContainer 里，让长按能选中——复制按钮解决的是常规路径，
 * 但总有人要"只复制中间那一段"或者拿它跟另一台手机上的对比，选不中就只能重打一遍。
 *
 * 用 LocalClipboardManager 而不是新的 LocalClipboard：项目里 ChatScreen 那处已经在用它，
 * 两处保持同一个 API 才好一起换。它的 deprecation 是"有更好的 suspend 版本"，不是要被删。
 */
@Composable
private fun GeneratedCodeDialog(
	generated: GeneratedConfigCode,
	snackbarHostState: SnackbarHostState,
	onDismiss: () -> Unit,
) {
	val clipboard = LocalClipboardManager.current
	val scope = rememberCoroutineScope()

	AlertDialog(
		onDismissRequest = onDismiss,
		title = { Text(if (generated.encrypted) "配置码（加密）" else "配置码（明文）") },
		text = {
			Column(verticalArrangement = Arrangement.spacedBy(Dimens.spaceMd)) {
				SelectionContainer {
					Text(
						generated.code,
						style = MaterialTheme.typography.bodySmall,
						modifier = Modifier
							.fillMaxWidth()
							.heightIn(max = CodeBoxMaxHeight)
							.verticalScroll(rememberScrollState()),
					)
				}

				if (generated.encrypted) {
					Text(
						"这段文字里有你的 API Key。发给别人、贴到群里，等于把 Key 一起给了 —— " +
							"哪怕有口令，也别往公开的地方贴。",
						style = MaterialTheme.typography.bodyMedium,
						color = MaterialTheme.colorScheme.error,
					)
				} else {
					Text(
						"这段码不含 API Key，导入之后要在那台手机上手填一次。",
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.outline,
					)
				}
			}
		},
		confirmButton = {
			Button(
				onClick = {
					clipboard.setText(AnnotatedString(generated.code))
					scope.launch { snackbarHostState.showSnackbar("配置码已复制") }
				},
			) {
				Text("复制")
			}
		},
		dismissButton = { TextButton(onClick = onDismiss) { Text("完成") } },
	)
}

/**
 * 导入面板。
 *
 * "从剪贴板读"这个按钮值得单独存在：用户刚在另一台手机上点了复制，
 * 到这边最自然的动作是"点一下就填上"，而不是长按输入框等系统菜单弹出来。
 *
 * 口令框跟着粘贴内容即时出现：加密码的前缀一眼可辨（纯字符串判断，没有任何 IO），
 * 所以不必等他点提交才告诉他"还要口令"。
 *
 * 解码失败时 sheet.error 贴在输入框下面，而这里的 code/password 是本地 state，不会被重置 ——
 * 那正是"输错一个字符就能改"的前提。
 */
@Composable
private fun ConfigImportDialog(
	sheet: ConfigImportSheet,
	busy: Boolean,
	needsPassword: (String) -> Boolean,
	onSubmit: (String, CharArray?) -> Unit,
	onDismiss: () -> Unit,
) {
	val clipboard = LocalClipboardManager.current
	var code by remember { mutableStateOf("") }
	var password by remember { mutableStateOf("") }
	var visible by remember { mutableStateOf(false) }

	val sealed = needsPassword(code)

	AlertDialog(
		onDismissRequest = { if (!busy) onDismiss() },
		title = { Text("导入配置码") },
		text = {
			Column(verticalArrangement = Arrangement.spacedBy(Dimens.spaceMd)) {
				OutlinedTextField(
					value = code,
					onValueChange = { code = it },
					label = { Text("把配置码粘贴到这儿") },
					placeholder = { Text("${ConfigCodec.PLAIN_PREFIX}… 或 ${ConfigCodec.SEALED_PREFIX}…") },
					enabled = !busy,
					isError = sheet.error != null,
					minLines = 3,
					maxLines = 6,
					modifier = Modifier.fillMaxWidth(),
				)

				OutlinedButton(
					onClick = { clipboard.getText()?.text?.let { code = it } },
					enabled = !busy,
				) {
					Text("从剪贴板读")
				}

				if (sealed) {
					OutlinedTextField(
						value = password,
						onValueChange = { password = it },
						label = { Text("口令") },
						supportingText = { Text("这段码是加密的，需要生成它时用的那个口令") },
						enabled = !busy,
						singleLine = true,
						shape = PillShape,
						visualTransformation = passwordTransformation(visible),
						trailingIcon = {
							TextButton(onClick = { visible = !visible }, enabled = !busy) {
								Text(if (visible) "隐藏" else "显示")
							}
						},
						modifier = Modifier.fillMaxWidth(),
					)
				}

				sheet.error?.let { message ->
					Text(
						message,
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.error,
					)
				}

				if (busy) {
					Row(
						horizontalArrangement = Arrangement.spacedBy(Dimens.spaceSm),
						verticalAlignment = Alignment.CenterVertically,
					) {
						CircularProgressIndicator(
							modifier = Modifier.size(InlineSpinner),
							strokeWidth = 2.dp,
						)
						Text("正在识别…", style = MaterialTheme.typography.bodySmall)
					}
				}
			}
		},
		confirmButton = {
			Button(
				onClick = {
					onSubmit(code, if (sealed && password.isNotEmpty()) password.toCharArray() else null)
				},
				enabled = code.isNotBlank() && !busy && (!sealed || password.isNotEmpty()),
			) {
				Text("识别并预览")
			}
		},
		dismissButton = {
			TextButton(onClick = onDismiss, enabled = !busy) { Text("算了") }
		},
	)
}

/**
 * 导入前的变化清单。
 *
 * 这一步不能省成"确定导入吗"：配置码是从别处贴进来的，用户其实不知道里面攒了什么，
 * 而它一次能改十几项。把真正会变的逐条摆出来，他才有机会发现"等等，我不想改主模型"。
 * 清单为空的情况不会走到这里 —— ViewModel 直接给了"跟现在完全一样"的提示。
 */
@Composable
private fun ConfigImportConfirmDialog(
	confirm: ConfigImportConfirm,
	onConfirm: () -> Unit,
	onDismiss: () -> Unit,
) {
	AlertDialog(
		onDismissRequest = onDismiss,
		title = { Text("这些设置会被改掉") },
		text = {
			Column(
				verticalArrangement = Arrangement.spacedBy(Dimens.spaceXs),
				modifier = Modifier
					.heightIn(max = CodeBoxMaxHeight)
					.verticalScroll(rememberScrollState()),
			) {
				confirm.changes.forEach { line ->
					Text("· $line", style = MaterialTheme.typography.bodyMedium)
				}

				if (confirm.overwritesApiKey) {
					// 单独一行且用 error 色：其他项改错了随手能调回来，Key 被换掉是要去翻记录的
					Text(
						"· API Key 会被这段码里的那个覆盖",
						style = MaterialTheme.typography.bodyMedium,
						color = MaterialTheme.colorScheme.error,
					)
				}

				Text(
					"没列出来的项保持原样。聊天记录、记忆和表情不受影响。",
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.outline,
					modifier = Modifier.padding(top = Dimens.spaceSm),
				)
			}
		},
		confirmButton = { Button(onClick = onConfirm) { Text("应用") } },
		dismissButton = { TextButton(onClick = onDismiss) { Text("算了") } },
	)
}

/**
 * 关于。
 *
 * 原先这里摊了三段隐私说明，现在收成一句：那些话对第二次看到的人是噪音，
 * 想细看的去仓库读 README 更合适，页脚不该承担文档的活。
 * 版本号留着 —— 它是"手上装的到底是哪一版"唯一的答案，而 versionName 会重复，
 * 所以 versionCode 也一起带上。
 */
@Composable
private fun AboutSection(state: SettingsUiState, viewModel: SettingsViewModel) {
	val context = LocalContext.current
	val clipboard = LocalClipboardManager.current

	SectionCard(title = "关于") {
		Text(
			"聊天记录、记忆和表情都只存在这台设备上，不上传到任何服务器。" +
				"想了解实现细节或者提问题，去 GitHub 仓库。",
			style = MaterialTheme.typography.bodySmall,
			color = MaterialTheme.colorScheme.outline,
		)

		Row(
			horizontalArrangement = Arrangement.spacedBy(Dimens.spaceSm),
			verticalAlignment = Alignment.CenterVertically,
			modifier = Modifier.padding(top = Dimens.spaceSm),
		) {
			OutlinedButton(
				onClick = {
					// 没有浏览器、或者被限制了的机器上 startActivity 会抛，
					// 那种情况下把地址塞进剪贴板比弹一个崩溃有用
					val opened = runCatching {
						context.startActivity(Intent(Intent.ACTION_VIEW, REPO_URL.toUri()))
					}.isSuccess
					if (!opened) clipboard.setText(AnnotatedString(REPO_URL))
				},
			) {
				Text("GitHub 仓库")
			}

			OutlinedButton(
				onClick = { viewModel.checkUpdate(manual = true) },
				enabled = !state.update.checking,
			) {
				Text(if (state.update.checking) "检查中…" else "检查更新")
			}

			if (state.update.checking) {
				CircularProgressIndicator(
					modifier = Modifier.size(InlineSpinner),
					strokeWidth = 2.dp,
				)
			}
		}

		Text(
			"版本 ${BuildConfig.VERSION_NAME}（构建 ${BuildConfig.VERSION_CODE}）· ${BuildConfig.BUILD_DATE}",
			style = MaterialTheme.typography.labelSmall,
			color = MaterialTheme.colorScheme.outline,
			modifier = Modifier.padding(top = Dimens.spaceSm),
		)
	}
}

private const val REPO_URL = "https://github.com/LIKIQ/aicp"

/**
 * 跨页共用的空状态。
 *
 * 会话列表、记忆、表情包三处都要画"还没有内容"，原来各写一份：padding 32、间距 8/12、
 * 有的带按钮有的不带，字级也不一样。三页连着翻就能看出是三次分别写的。
 *
 * 落在这个文件里是权衡后的结果 —— 它本该待在 SettingsComponents.kt，但那个文件这轮不在改动范围内；
 * 而 ui.settings 已经是项目里事实上的共享组件包（SectionCard、SliderRow 都在这儿被
 * persona 页和 memory 页 import），放这儿至少跟现有的引用习惯是一路的。
 *
 * actions 留成 slot 而不是收一个 onAction + 按钮文案：表情包页那处要摆两个按钮外加一行补充小字，
 * 写死成"一个主按钮"的话它就只能退回去自己画，共用也就白共用了。
 */
@Composable
internal fun EmptyState(
	emoji: String,
	title: String,
	description: String,
	modifier: Modifier = Modifier,
	actions: @Composable ColumnScope.() -> Unit = {},
) {
	Column(
		modifier = modifier
			.fillMaxSize()
			.padding(Dimens.spaceXl),
		verticalArrangement = Arrangement.spacedBy(Dimens.spaceMd, Alignment.CenterVertically),
		horizontalAlignment = Alignment.CenterHorizontally,
	) {
		Text(emoji, style = MaterialTheme.typography.displaySmall)
		Text(title, style = MaterialTheme.typography.titleMedium)
		Text(
			description,
			style = MaterialTheme.typography.bodyMedium,
			color = MaterialTheme.colorScheme.outline,
			textAlign = TextAlign.Center,
		)
		actions()
	}
}

/** 空闲阈值的档位：半小时到半天，覆盖了常见的几种期待，比这更细的差别用户感知不到 */
private val IDLE_PRESETS = listOf(30, 60, 180, 360, 720)

/** 估算段间停顿时用的样本长度，20 字差不多是一句聊天的量 */
private const val SAMPLE_SEGMENT_CHARS = 20

private fun idleLabel(minutes: Int): String = when {
	minutes < 60 -> "$minutes 分钟"
	minutes % 60 == 0 -> "${minutes / 60} 小时"
	// 旧版本或以后改了档位可能留下带零头的值，别把它显示成整小时
	else -> "${minutes / 60} 小时 ${minutes % 60} 分"
}

private fun hourLabel(hour: Int): String = "%02d:00".format(hour)

/** 免打扰区间回显。跨午夜时点明"次日"，不然 23:00 - 08:00 看着像填反了 */
private fun quietRangeLabel(start: Int, end: Int): String = when {
	start == end -> "整天都不打扰"
	start < end -> "${hourLabel(start)} 到 ${hourLabel(end)}"
	else -> "${hourLabel(start)} 到次日 ${hourLabel(end)}"
}

/** ms/字 换成一句话的等待秒数，光看毫秒数没人能想象出那是多久 */
private fun typingPauseText(msPerChar: Int): String =
	"%.1f".format(msPerChar * SAMPLE_SEGMENT_CHARS / 1000f)

private fun delayText(ms: Int): String = when {
	ms == 0 -> "秒回"
	ms < 1000 -> "$ms ms"
	else -> "%.1f 秒".format(ms / 1000f)
}

/** 备份进度的说明文字。转圈本身只说明"在忙"，得配一句才知道在忙什么 */
private fun backupJobLabel(job: BackupJob): String = when (job) {
	BackupJob.EXPORTING -> "正在打包，先别离开这个页面…"
	BackupJob.CHECKING -> "正在核对当前有多少数据…"
	BackupJob.RESTORING -> "正在校验并解压备份…"
	BackupJob.CANCELLING -> "正在取消…"
}

/** 三个口令输入框共用。写成一处是为了别出现"这个框能看那个框不能看"的不一致 */
private fun passwordTransformation(visible: Boolean): VisualTransformation =
	if (visible) VisualTransformation.None else PasswordVisualTransformation()

/** 配置码那几步的进度文字 */
private fun configJobLabel(job: ConfigCodeJob): String = when (job) {
	ConfigCodeJob.ENCODING -> "正在生成…"
	ConfigCodeJob.DECODING -> "正在识别…"
	ConfigCodeJob.APPLYING -> "正在写入设置…"
}
