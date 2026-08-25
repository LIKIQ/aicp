// app/src/main/java/com/kiq/aicp/ui/settings/SettingsScreen.kt
// 设置页：接口配置、记忆与压缩调优、表情包、真人模拟、主动搭话、外观、说明。
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
import android.content.pm.PackageManager
import android.os.Build
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kiq.aicp.BuildConfig
import com.kiq.aicp.ui.sticker.StickerViewModel
import com.kiq.aicp.ui.theme.Dimens

/** 单行输入框走胶囊圆角，跟同一行里的按钮看着是一套东西 */
private val PillShape = RoundedCornerShape(Dimens.radiusPill)

/** 转圈指示器的直径。它是控件尺寸不是间距，不归 Dimens 那五档管 */
private val InlineSpinner = 16.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
	onOpenMemory: () -> Unit,
	onOpenStickers: () -> Unit,
	viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory),
) {
	val state by viewModel.uiState.collectAsStateWithLifecycle()

	Scaffold(
		topBar = { TopAppBar(title = { Text("设置") }) },
	) { innerPadding ->
		LazyColumn(
			modifier = Modifier
				.fillMaxWidth()
				.padding(innerPadding),
			contentPadding = PaddingValues(Dimens.screenPadding),
			verticalArrangement = Arrangement.spacedBy(Dimens.spaceLg),
		) {
			item { EndpointSection(state, viewModel) }
			item { MemorySection(state, viewModel, onOpenMemory) }
			item { StickerSection(onOpenStickers) }
			item { AppearanceSection(state, viewModel) }
			// 新分区排在"关于数据"前面 —— 那段隐私说明是页脚性质的，后面再跟可调项会显得没收尾
			item { HumanizeSection(state, viewModel) }
			item { ProactiveSection(state, viewModel) }
			item { AboutSection() }
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
			supportingText = { Text("已保存：${state.savedKeyHint}　加密后存在本机，不上传") },
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

		if (state.settings.apiKey.isNotEmpty()) {
			TextButton(onClick = viewModel::clearApiKey) { Text("清除已保存的 Key") }
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
	}
}

@Composable
private fun AboutSection() {
	SectionCard(title = "关于数据") {
		Text(
			"所有会话、记忆摘要和记忆卡片都只存在这台设备的应用私有目录里，不会上传到任何服务器。" +
				"API Key 经 Android Keystore 加密后落盘 —— 这能防别人翻你的文件和系统备份，" +
				"但防不住把 APK 拉走逆向的人，端上没有绝对藏得住的密钥。\n\n" +
				"明文 http 只允许连本机和局域网地址（10./172.16-31./192.168./*.local），" +
				"公网接口一律要求 https，避免 Key 在路上被截。",
			style = MaterialTheme.typography.bodySmall,
			color = MaterialTheme.colorScheme.outline,
		)

		// 版本号摆在这里是为了能核对"手上装的到底是哪一版"。
		// 只看 versionName 不够，同一个 versionName 我可能出好几个包，所以把 versionCode 也带上。
		// 额外顶开一档，让它看着像页脚而不是上面那段说明的第四句
		Text(
			"版本 ${BuildConfig.VERSION_NAME}（构建 ${BuildConfig.VERSION_CODE}）· ${BuildConfig.BUILD_DATE}",
			style = MaterialTheme.typography.labelSmall,
			color = MaterialTheme.colorScheme.outline,
			modifier = Modifier.padding(top = Dimens.spaceSm),
		)
	}
}

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
