// app/src/main/java/com/kiq/aicp/ui/memory/MemoryScreen.kt
// 记忆管理页：看得见、改得动、删得掉。
// 作用域标签直接把 scopeKey 翻译成人话，否则用户看到 "c:3|p:-" 只会一脸问号。

package com.kiq.aicp.ui.memory

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kiq.aicp.R
import com.kiq.aicp.data.db.entity.MemoryCardEntity
import com.kiq.aicp.domain.model.MemoryCardType
import com.kiq.aicp.ui.settings.SliderRow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryScreen(
	onBack: () -> Unit,
	viewModel: MemoryViewModel = viewModel(factory = MemoryViewModel.Factory),
) {
	val cards by viewModel.cards.collectAsStateWithLifecycle()
	val message by viewModel.message.collectAsStateWithLifecycle()
	val snackbar = remember { SnackbarHostState() }
	var editing by remember { mutableStateOf<MemoryCardEntity?>(null) }

	LaunchedEffect(message) {
		message?.let {
			snackbar.showSnackbar(it)
			viewModel.dismissMessage()
		}
	}

	Scaffold(
		topBar = {
			TopAppBar(
				title = { Text("记忆（${cards.size}）") },
				navigationIcon = {
					IconButton(onClick = onBack) {
						Icon(painterResource(R.drawable.ic_back), contentDescription = "返回")
					}
				},
				actions = {
					TextButton(onClick = viewModel::pruneCold) { Text("清理冷记忆") }
				},
			)
		},
		snackbarHost = { SnackbarHost(snackbar) },
	) { innerPadding ->
		if (cards.isEmpty()) {
			Column(
				modifier = Modifier
					.fillMaxSize()
					.padding(innerPadding)
					.padding(32.dp),
				verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
				horizontalAlignment = Alignment.CenterHorizontally,
			) {
				Text("还没有记忆", style = MaterialTheme.typography.titleMedium)
				Text(
					"聊到一定长度后会自动把旧对话压成摘要，并从里面抽出稳定的事实存在这里",
					style = MaterialTheme.typography.bodyMedium,
					color = MaterialTheme.colorScheme.outline,
				)
			}
			return@Scaffold
		}

		LazyColumn(
			modifier = Modifier
				.fillMaxSize()
				.padding(innerPadding),
			contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
			verticalArrangement = Arrangement.spacedBy(8.dp),
		) {
			items(cards, key = { it.id }) { card ->
				Card(
					modifier = Modifier.fillMaxWidth(),
					colors = CardDefaults.cardColors(
						containerColor = if (card.pinned) {
							MaterialTheme.colorScheme.secondaryContainer
						} else {
							MaterialTheme.colorScheme.surfaceContainerLow
						},
					),
				) {
					Column(modifier = Modifier.padding(14.dp)) {
						Row(verticalAlignment = Alignment.CenterVertically) {
							Text(
								"${typeLabel(card.type)} · ${card.keyword}",
								style = MaterialTheme.typography.titleSmall,
								modifier = Modifier.weight(1f),
							)
							Text(
								"重要度 ${card.importance}",
								style = MaterialTheme.typography.labelSmall,
								color = MaterialTheme.colorScheme.outline,
							)
						}

						Text(
							card.content,
							style = MaterialTheme.typography.bodyMedium,
							modifier = Modifier.padding(vertical = 6.dp),
						)

						Text(
							"${scopeLabel(card)} · 用过 ${card.hitCount} 次" +
								if (card.pinned) " · 已锁定" else "",
							style = MaterialTheme.typography.labelSmall,
							color = MaterialTheme.colorScheme.outline,
						)

						Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
							TextButton(onClick = { editing = card }) { Text("编辑") }
							TextButton(onClick = { viewModel.setPinned(card, !card.pinned) }) {
								Text(if (card.pinned) "解锁" else "锁定")
							}
							TextButton(onClick = { viewModel.delete(card) }) { Text("删除") }
						}
					}
				}
			}
		}
	}

	editing?.let { card ->
		var content by remember(card.id) { mutableStateOf(card.content) }
		var importance by remember(card.id) { mutableIntStateOf(card.importance) }

		AlertDialog(
			onDismissRequest = { editing = null },
			title = { Text("编辑「${card.keyword}」") },
			text = {
				Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
					OutlinedTextField(
						value = content,
						onValueChange = { content = it },
						modifier = Modifier.fillMaxWidth(),
						minLines = 3,
					)
					SliderRow(
						title = "重要度",
						subtitle = "越高越优先进上下文，1 最低 5 最高",
						value = importance,
						valueRange = 1..5,
						step = 1,
						onValueSettled = { importance = it },
					)
				}
			},
			confirmButton = {
				TextButton(
					enabled = content.isNotBlank(),
					onClick = {
						viewModel.edit(card, content, importance)
						editing = null
					},
				) { Text("保存") }
			},
			dismissButton = {
				TextButton(onClick = { editing = null }) { Text("取消") }
			},
		)
	}
}

private fun typeLabel(type: MemoryCardType): String = when (type) {
	MemoryCardType.FACT -> "事实"
	MemoryCardType.PREFERENCE -> "喜好"
	MemoryCardType.EVENT -> "事件"
	MemoryCardType.RELATION -> "关系"
	MemoryCardType.IMPRESSION -> "印象"
}

/** 把 scopeKey 翻成人话，别让用户直接看 "c:3|p:-" */
private fun scopeLabel(card: MemoryCardEntity): String = when {
	card.conversationId == null && card.personaId == null -> "所有会话共享"
	card.conversationId != null && card.personaId == null -> "仅某个会话"
	card.conversationId == null && card.personaId != null -> "仅某个性格"
	else -> "某会话中的某性格"
}
