// app/src/main/java/com/kiq/aicp/MainActivity.kt
// 唯一 Activity。导航全部在 Compose 内部走 NavHost，不再开第二个 Activity。
//
// 这里直接订阅 settings 是为了让"跟随系统取色"开关能立刻生效 ——
// 主题是整棵树的根，交给某个页面的 ViewModel 管反而绕。
// edge-to-edge 是 targetSdk 35+ 的既定行为，显式开一次，免得不同厂商 ROM 表现不一致。

package com.kiq.aicp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kiq.aicp.domain.model.AicpSettings
import com.kiq.aicp.ui.AicpApp
import com.kiq.aicp.ui.theme.AicpTheme

class MainActivity : ComponentActivity() {

	override fun onCreate(savedInstanceState: Bundle?) {
		enableEdgeToEdge()
		super.onCreate(savedInstanceState)
		setContent {
			val settings by AicpApplication.container().settingsStore.settings
				.collectAsStateWithLifecycle(initialValue = AicpSettings())

			AicpTheme(dynamicColor = settings.dynamicColor) {
				AicpApp()
			}
		}
	}
}
