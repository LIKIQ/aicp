// app/src/main/java/com/kiq/aicp/ui/AicpApp.kt
// 应用外壳：底部三个 tab + 两个全屏页（聊天、性格编辑）。
//
// 系统栏 inset 的处理方式：外层 Scaffold 把 contentWindowInsets 置零，
// 只负责给 tab 页留出底栏高度；状态栏和导航栏由各页面自己的 Scaffold 处理。
// 不这么做的话，聊天页的 TopAppBar 会被外层和内层各推一次状态栏高度，白多一条空白。

package com.kiq.aicp.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.kiq.aicp.R
import com.kiq.aicp.ui.chat.ChatScreen
import com.kiq.aicp.ui.conversations.ConversationListScreen
import com.kiq.aicp.ui.memory.MemoryScreen
import com.kiq.aicp.ui.persona.PersonaEditScreen
import com.kiq.aicp.ui.persona.PersonaListScreen
import com.kiq.aicp.ui.settings.SettingsScreen
import com.kiq.aicp.ui.sticker.StickerScreen

/**
 * 底部 tab。route 一旦定下就不要改，深链和返回栈都靠它。
 * 图标走自绘 drawable —— icons-core 里没有 Chat，为一个图标背 material-icons-extended 不值。
 */
private enum class TopTab(
	val route: String,
	val iconRes: Int,
	val labelRes: Int,
) {
	Chats("chats", R.drawable.ic_tab_chat, R.string.nav_chats),
	Personas("personas", R.drawable.ic_tab_persona, R.string.nav_personas),
	Settings("settings", R.drawable.ic_tab_settings, R.string.nav_settings),
}

private object Routes {
	const val CHAT = "chat/{conversationId}"
	const val PERSONA_NEW = "persona/new"
	const val PERSONA_EDIT = "persona/edit/{personaId}"
	const val MEMORY = "memory"
	const val STICKER = "sticker"

	/**
	 * 压栈进来的设置页，跟底部 tab 里那个是两条不同的 route。
	 *
	 * 聊天页那句"还没配置接口"点过去时用这个：tab 的 route 一旦被 navigate 上去，
	 * 底部 tab 栏会跟着冒出来（onTab 判定为真），看着像是切了 tab，
	 * 可返回栈里还压着聊天页，此后点任何 tab 都会触发 popUpTo + restoreState，
	 * 行为对用户完全不可预测。独立 route 不进 tab 判定，返回就是干净的一次 pop。
	 */
	const val SETTINGS_MODAL = "settings/modal"

	fun chat(conversationId: Long) = "chat/$conversationId"
	fun personaEdit(personaId: Long) = "persona/edit/$personaId"
}

@Composable
fun AicpApp() {
	val navController = rememberNavController()
	val backStackEntry by navController.currentBackStackEntryAsState()
	val currentRoute = backStackEntry?.destination?.route
	val onTab = TopTab.entries.any { it.route == currentRoute }

	Scaffold(
		contentWindowInsets = WindowInsets(0, 0, 0, 0),
		bottomBar = {
			if (onTab) {
				NavigationBar {
					TopTab.entries.forEach { tab ->
						NavigationBarItem(
							selected = currentRoute == tab.route,
							onClick = {
								if (currentRoute != tab.route) {
									navController.navigate(tab.route) {
										// tab 之间来回切不该把返回栈堆到无限长
										popUpTo(navController.graph.findStartDestination().id) {
											saveState = true
										}
										launchSingleTop = true
										restoreState = true
									}
								}
							},
							icon = { Icon(painterResource(tab.iconRes), contentDescription = null) },
							label = { Text(stringResource(tab.labelRes)) },
						)
					}
				}
			}
		},
	) { innerPadding ->
		NavHost(
			navController = navController,
			startDestination = TopTab.Chats.route,
			modifier = Modifier
				.fillMaxSize()
				.padding(innerPadding),
		) {
			composable(TopTab.Chats.route) {
				ConversationListScreen(
					onOpenConversation = { navController.navigate(Routes.chat(it)) },
				)
			}

			composable(TopTab.Personas.route) {
				PersonaListScreen(
					onEditPersona = { personaId ->
						navController.navigate(
							if (personaId == null) Routes.PERSONA_NEW else Routes.personaEdit(personaId),
						)
					},
				)
			}

			composable(TopTab.Settings.route) {
				SettingsScreen(
					onOpenMemory = { navController.navigate(Routes.MEMORY) },
					onOpenStickers = { navController.navigate(Routes.STICKER) },
				)
			}

			// 从聊天页压栈进来的那一份。同一个 Composable，只是多给一个返回箭头，
			// 并且不算 tab（底部栏不显示），返回就回到刚才那个会话
			composable(Routes.SETTINGS_MODAL) {
				SettingsScreen(
					onOpenMemory = { navController.navigate(Routes.MEMORY) },
					onOpenStickers = { navController.navigate(Routes.STICKER) },
					onBack = { navController.popBackStack() },
				)
			}

			composable(Routes.MEMORY) {
				MemoryScreen(onBack = { navController.popBackStack() })
			}

			composable(Routes.STICKER) {
				StickerScreen(onBack = { navController.popBackStack() })
			}

			composable(Routes.CHAT) { entry ->
				val conversationId = entry.arguments?.getString("conversationId")?.toLongOrNull()
				if (conversationId == null) {
					navController.popBackStack()
				} else {
					ChatScreen(
						conversationId = conversationId,
						onBack = { navController.popBackStack() },
						onOpenSettings = { navController.navigate(Routes.SETTINGS_MODAL) },
						// 气泡头像点开的资料卡里那个「编辑」按钮，直接进这个性格的编辑页
						onEditPersona = { personaId ->
							navController.navigate(Routes.personaEdit(personaId))
						},
					)
				}
			}

			composable(Routes.PERSONA_NEW) {
				PersonaEditScreen(personaId = null, onBack = { navController.popBackStack() })
			}

			composable(Routes.PERSONA_EDIT) { entry ->
				PersonaEditScreen(
					personaId = entry.arguments?.getString("personaId")?.toLongOrNull(),
					onBack = { navController.popBackStack() },
				)
			}
		}
	}
}
