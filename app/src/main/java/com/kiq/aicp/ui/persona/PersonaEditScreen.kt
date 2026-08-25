// app/src/main/java/com/kiq/aicp/ui/persona/PersonaEditScreen.kt
// 性格编辑页。顶上那个"用一句话生成"是 KIQ 点名要的功能：
// 描述一句 → 调模型扩写成完整人设 → 填进草稿 → 用户还能手改再保存。
//
// 采样参数复用了设置页的 SliderRow（Int 版），temperature / topP 用百分比整数承载，
// 免得再写一套 Float 滑块。
//
// 头像那一栏原来是个 96dp 的 emoji 输入框，加了图片头像后横着摆不下三样东西，
// 于是收成"点头像出菜单"：emoji 输入挪进对话框，能力一个没少。
// 备注单独一张卡放最后，标题下面必须写清"不发给 AI" —— 不写用户就会拿它当提示词使。

package com.kiq.aicp.ui.persona

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kiq.aicp.R
import com.kiq.aicp.ui.common.Avatar
import com.kiq.aicp.ui.settings.SectionCard
import com.kiq.aicp.ui.settings.SliderRow
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PersonaEditScreen(
	personaId: Long?,
	onBack: () -> Unit,
) {
	val viewModel: PersonaEditViewModel = viewModel(
		factory = PersonaEditViewModel.factoryFor(personaId),
	)
	val state by viewModel.uiState.collectAsStateWithLifecycle()
	val snackbar = remember { SnackbarHostState() }
	var description by remember { mutableStateOf("") }
	var emojiDialogOpen by remember { mutableStateOf(false) }

	// PickVisualMedia 不要任何存储权限，系统相册直接给一个临时可读 uri，
	// 所以回调里立刻扔给 ViewModel 落盘 —— 拖到点保存时这个 uri 早就读不动了
	val avatarPicker = rememberLauncherForActivityResult(
		ActivityResultContracts.PickVisualMedia(),
	) { uri -> uri?.let(viewModel::pickAvatar) }

	LaunchedEffect(state.saved) {
		if (state.saved) onBack()
	}

	LaunchedEffect(state.message) {
		state.message?.let {
			snackbar.showSnackbar(it)
			viewModel.dismissMessage()
		}
	}

	Scaffold(
		topBar = {
			TopAppBar(
				title = { Text(if (state.isNew) "新建性格" else "编辑性格") },
				navigationIcon = {
					IconButton(onClick = onBack) {
						Icon(painterResource(R.drawable.ic_back), contentDescription = "返回")
					}
				},
				actions = {
					Button(
						onClick = viewModel::save,
						// 头像还在压缩时先别让存：这会儿存下去的是旧路径，
						// 压完那张就成了没人引用的孤儿文件
						enabled = state.draft.valid && !state.generating && !state.avatarSaving,
						modifier = Modifier.padding(end = 12.dp),
					) { Text("保存") }
				},
			)
		},
		snackbarHost = { SnackbarHost(snackbar) },
	) { innerPadding ->
		Column(
			modifier = Modifier
				.fillMaxSize()
				.padding(innerPadding)
				.verticalScroll(rememberScrollState())
				.padding(16.dp),
			verticalArrangement = Arrangement.spacedBy(16.dp),
		) {
			SectionCard(
				title = "用一句话生成",
				subtitle = "描述你想要的角色，让模型把人设补全，生成完还能手改",
			) {
				OutlinedTextField(
					value = description,
					onValueChange = { description = it },
					placeholder = { Text("例如：一个傲娇但很靠谱的猫娘程序员") },
					modifier = Modifier.fillMaxWidth(),
					minLines = 2,
					maxLines = 4,
				)
				Row(
					horizontalArrangement = Arrangement.spacedBy(8.dp),
					verticalAlignment = Alignment.CenterVertically,
				) {
					OutlinedButton(
						onClick = { viewModel.generateFrom(description) },
						enabled = description.isNotBlank() && !state.generating,
					) { Text(if (state.generating) "生成中…" else "生成人设") }

					if (state.generating) {
						CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
					}
				}
			}

			SectionCard(title = "基本信息") {
				Row(
					horizontalArrangement = Arrangement.spacedBy(12.dp),
					verticalAlignment = Alignment.CenterVertically,
				) {
					AvatarPicker(
						emoji = state.draft.avatarEmoji,
						imagePath = state.draft.avatarPath,
						name = state.draft.name,
						saving = state.avatarSaving,
						resolveFile = viewModel::resolveFile,
						onEditEmoji = { emojiDialogOpen = true },
						onPickImage = {
							avatarPicker.launch(
								PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
							)
						},
						onClearImage = viewModel::clearAvatar,
					)
					OutlinedTextField(
						value = state.draft.name,
						onValueChange = { value -> viewModel.update { it.copy(name = value) } },
						label = { Text("名字") },
						singleLine = true,
						modifier = Modifier.weight(1f),
					)
				}

				Text(
					"点头像可以换图或改 emoji。有图就显示图，没图显示 emoji，emoji 也空着就显示名字首字",
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.outline,
				)

				OutlinedTextField(
					value = state.draft.tagline,
					onValueChange = { value -> viewModel.update { it.copy(tagline = value) } },
					label = { Text("一句话简介") },
					supportingText = { Text("只在列表里给你自己看，不会进提示词") },
					singleLine = true,
					modifier = Modifier.fillMaxWidth(),
				)

				OutlinedTextField(
					value = state.draft.greeting,
					onValueChange = { value -> viewModel.update { it.copy(greeting = value) } },
					label = { Text("开场白") },
					supportingText = { Text("新建会话时它会先说这句；留空则不主动开口") },
					modifier = Modifier.fillMaxWidth(),
					maxLines = 3,
				)
			}

			SectionCard(
				title = "人设提示词",
				subtitle = if (state.isBuiltIn) {
					"这是内置性格，改动只影响你这台设备，删不掉但随便改"
				} else {
					"写清身份、说话风格，最好带一条明确的禁忌，不然容易变成客服腔"
				},
			) {
				OutlinedTextField(
					value = state.draft.systemPrompt,
					onValueChange = { value -> viewModel.update { it.copy(systemPrompt = value) } },
					modifier = Modifier.fillMaxWidth(),
					minLines = 8,
					placeholder = { Text("你叫…，说话…，不要…") },
				)
			}

			SectionCard(title = "采样参数", subtitle = "不确定就别动，默认值对大多数模型都够用") {
				SliderRow(
					title = "temperature",
					subtitle = "越高越发散，越低越稳",
					value = (state.draft.temperature * 100).toInt(),
					valueRange = 0..200,
					step = 5,
					valueLabel = { "%.2f".format(it / 100f) },
					onValueSettled = { v -> viewModel.update { it.copy(temperature = v / 100f) } },
				)
				SliderRow(
					title = "top_p",
					value = (state.draft.topP * 100).toInt(),
					valueRange = 10..100,
					step = 5,
					valueLabel = { "%.2f".format(it / 100f) },
					onValueSettled = { v -> viewModel.update { it.copy(topP = v / 100f) } },
				)
				SliderRow(
					title = "单条回复上限",
					subtitle = "这个角色一次最多能说多长",
					value = state.draft.maxTokens,
					valueRange = 256..8_192,
					step = 128,
					valueLabel = { "$it token" },
					onValueSettled = { v -> viewModel.update { it.copy(maxTokens = v) } },
				)
				OutlinedTextField(
					value = state.draft.modelOverride,
					onValueChange = { value -> viewModel.update { it.copy(modelOverride = value) } },
					label = { Text("专用模型（可留空）") },
					supportingText = { Text("留空跟随设置里的全局模型") },
					singleLine = true,
					modifier = Modifier.fillMaxWidth(),
				)
			}

			SectionCard(
				title = "备注",
				subtitle = "只给你自己看，不会发给 AI。想让它影响回复请写进上面的人设提示词",
			) {
				OutlinedTextField(
					value = state.draft.note,
					onValueChange = { value -> viewModel.update { it.copy(note = value) } },
					placeholder = { Text("例如：这套人设是深夜写代码时用的，白天太吵") },
					modifier = Modifier.fillMaxWidth(),
					minLines = 3,
					maxLines = 5,
				)
			}
		}

		if (emojiDialogOpen) {
			EmojiInputDialog(
				initial = state.draft.avatarEmoji,
				onDismiss = { emojiDialogOpen = false },
				onConfirm = { value -> viewModel.update { it.copy(avatarEmoji = value) } },
			)
		}
	}
}

/**
 * 头像预览 + 点一下出来的操作菜单。预览直接吃草稿里的值，所以选完图、改完 emoji 立刻能看到效果。
 * "清除图片"只在真有图时才出现 —— 没图还摆一个清除项，用户会以为自己漏配了什么。
 */
@Composable
private fun AvatarPicker(
	emoji: String,
	imagePath: String?,
	name: String,
	saving: Boolean,
	resolveFile: (String) -> File,
	onEditEmoji: () -> Unit,
	onPickImage: () -> Unit,
	onClearImage: () -> Unit,
) {
	var menuOpen by remember { mutableStateOf(false) }

	Box {
		Avatar(
			emoji = emoji,
			imagePath = imagePath,
			fallbackName = name,
			resolveFile = resolveFile,
			size = 64.dp,
			// clip 放在 clickable 前面，不然按下去的水波纹是个方块
			modifier = Modifier
				.clip(CircleShape)
				.clickable(enabled = !saving) { menuOpen = true },
		)

		// 落盘要解码 + 缩放 + PNG 编码，选到几十兆的图会卡一下，盖个转圈免得用户以为没点上
		if (saving) {
			CircularProgressIndicator(
				modifier = Modifier
					.size(64.dp)
					.padding(20.dp),
				strokeWidth = 2.dp,
			)
		}

		DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
			DropdownMenuItem(
				text = { Text("输入 emoji") },
				onClick = {
					menuOpen = false
					onEditEmoji()
				},
			)
			DropdownMenuItem(
				text = { Text("从相册选图") },
				onClick = {
					menuOpen = false
					onPickImage()
				},
			)
			if (!imagePath.isNullOrBlank()) {
				DropdownMenuItem(
					text = { Text("清除图片") },
					onClick = {
						menuOpen = false
						onClearImage()
					},
				)
			}
		}
	}
}

/**
 * emoji 输入。原来这是"基本信息"里的一个小输入框，行为照搬：
 * 最多 4 个字符（不少 emoji 是多码点拼的，卡 1 会被截成乱码），允许清空回落到名字首字。
 */
@Composable
private fun EmojiInputDialog(
	initial: String,
	onDismiss: () -> Unit,
	onConfirm: (String) -> Unit,
) {
	var text by remember { mutableStateOf(initial) }

	AlertDialog(
		onDismissRequest = onDismiss,
		title = { Text("输入 emoji") },
		text = {
			Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
				OutlinedTextField(
					value = text,
					onValueChange = { text = it.take(4) },
					label = { Text("头像 emoji") },
					singleLine = true,
					modifier = Modifier.fillMaxWidth(),
				)
				Text(
					"配了图片时优先显示图片，清除图片后才会露出 emoji",
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.outline,
				)
			}
		},
		confirmButton = {
			TextButton(
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
