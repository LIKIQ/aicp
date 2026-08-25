// app/src/main/java/com/kiq/aicp/ui/persona/PersonaListScreen.kt
// 性格库列表。内置性格能改不能删，删除按钮点了会给出解释而不是静默失败。

package com.kiq.aicp.ui.persona

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kiq.aicp.data.db.entity.PersonaEntity

@Composable
fun PersonaListScreen(
	onEditPersona: (Long?) -> Unit,
	viewModel: PersonaListViewModel = viewModel(factory = PersonaListViewModel.Factory),
) {
	val personas by viewModel.personas.collectAsStateWithLifecycle()
	val message by viewModel.message.collectAsStateWithLifecycle()
	val snackbar = remember { SnackbarHostState() }
	var pendingDelete by remember { mutableStateOf<PersonaEntity?>(null) }

	LaunchedEffect(message) {
		message?.let {
			snackbar.showSnackbar(it)
			viewModel.dismissMessage()
		}
	}

	Scaffold(
		snackbarHost = { SnackbarHost(snackbar) },
		floatingActionButton = {
			ExtendedFloatingActionButton(
				onClick = { onEditPersona(null) },
				text = { Text("新建性格") },
				icon = {},
			)
		},
	) { innerPadding ->
		LazyColumn(
			modifier = Modifier
				.fillMaxSize()
				.padding(innerPadding),
			contentPadding = androidx.compose.foundation.layout.PaddingValues(
				start = 12.dp,
				end = 12.dp,
				top = 12.dp,
				bottom = 88.dp,
			),
			verticalArrangement = Arrangement.spacedBy(8.dp),
		) {
			items(personas, key = { it.id }) { persona ->
				Card(
					modifier = Modifier
						.fillMaxWidth()
						.clickable { onEditPersona(persona.id) },
					colors = CardDefaults.cardColors(
						containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
					),
				) {
					Row(
						modifier = Modifier.padding(14.dp),
						verticalAlignment = Alignment.CenterVertically,
					) {
						Text(persona.avatarEmoji, style = MaterialTheme.typography.headlineSmall)
						Column(
							modifier = Modifier
								.weight(1f)
								.padding(horizontal = 12.dp),
						) {
							Row(verticalAlignment = Alignment.CenterVertically) {
								Text(persona.name, style = MaterialTheme.typography.titleMedium)
								if (persona.isBuiltIn) {
									Text(
										"  内置",
										style = MaterialTheme.typography.labelSmall,
										color = MaterialTheme.colorScheme.outline,
									)
								}
								if (persona.generatedFromPrompt != null) {
									Text(
										"  AI 生成",
										style = MaterialTheme.typography.labelSmall,
										color = MaterialTheme.colorScheme.primary,
									)
								}
							}
							Text(
								persona.tagline.ifBlank { persona.systemPrompt },
								style = MaterialTheme.typography.bodySmall,
								color = MaterialTheme.colorScheme.outline,
								maxLines = 2,
								overflow = TextOverflow.Ellipsis,
							)
						}
						TextButton(onClick = { pendingDelete = persona }) { Text("删除") }
					}
				}
			}
		}
	}

	pendingDelete?.let { persona ->
		AlertDialog(
			onDismissRequest = { pendingDelete = null },
			title = { Text("删除 ${persona.name}？") },
			text = {
				Text(
					if (persona.isBuiltIn) {
						"这是内置性格，删不掉。你可以直接改它的人设，改动只影响你自己这台设备。"
					} else {
						"用它聊过的会话和记忆不会被删，但以后这些消息就找不到对应性格了。"
					},
				)
			},
			confirmButton = {
				TextButton(
					onClick = {
						viewModel.delete(persona)
						pendingDelete = null
					},
				) { Text("确认删除") }
			},
			dismissButton = {
				TextButton(onClick = { pendingDelete = null }) { Text("取消") }
			},
		)
	}
}
