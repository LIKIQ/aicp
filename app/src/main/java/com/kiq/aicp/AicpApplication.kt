// app/src/main/java/com/kiq/aicp/AicpApplication.kt
// 应用入口：建全局依赖容器，并在后台把内置性格灌进空库。
//
// 种子灌入放在 applicationScope 而不是 onCreate 主线程：它要开库、写四行，
// 放主线程会实打实拖慢冷启动，而首页拿到数据是靠 Flow 推的，晚几十毫秒无感。

package com.kiq.aicp

import android.app.Application
import android.util.Log
import com.kiq.aicp.work.ProactiveWorker
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class AicpApplication : Application() {

	lateinit var container: AppContainer
		private set

	/** 跟应用同生命周期的作用域，给"不该被页面销毁打断"的活儿用（种子灌入、后台压缩） */
	val applicationScope: CoroutineScope by lazy {
		CoroutineScope(
			SupervisorJob() + Dispatchers.Default +
				CoroutineExceptionHandler { _, e -> Log.e(TAG, "应用级协程未捕获异常", e) },
		)
	}

	override fun onCreate() {
		super.onCreate()
		instance = this
		container = AppContainer(this)

		applicationScope.launch {
			runCatching { container.personaRepository.ensureSeeded() }
				.onFailure { Log.e(TAG, "灌入内置性格失败", it) }
		}

		applicationScope.launch {
			runCatching { importBuiltInStickersOnce() }
				.onFailure { Log.e(TAG, "灌入内置表情失败", it) }
		}

		observeProactiveSchedule()
	}

	/**
	 * 内置表情只在首次启动灌一次。
	 * 判据是 DataStore 里的标记，不是"表情表空不空"——
	 * 用户把内置表情删干净之后，按后者判断的话每次重启都会重新冒出来。
	 */
	private suspend fun importBuiltInStickersOnce() {
		if (container.settingsStore.builtInStickersImported()) return
		val count = container.builtInStickers.importIfNeeded()
		// assets 里没放素材时 count 是 0，这时候也标记成已处理：
		// 否则每次冷启动都要白跑一遍 assets 列目录
		container.settingsStore.markBuiltInStickersImported()
		if (count > 0) Log.i(TAG, "内置表情导入 $count 张")
	}

	/**
	 * 后台主动搭话的调度跟着设置走。
	 *
	 * 放在这里而不是设置页的原因：WorkManager 的排程是进程级的，
	 * 而设置页可能压根没被打开过（用户上次开了推送，这次冷启动直接进聊天页）。
	 * 在 Application 里订阅能保证"设置是什么状态，排程就是什么状态"。
	 *
	 * 唤醒间隔取空闲阈值的四分之一：阈值 180 分钟就 45 分钟醒一次。
	 * 直接用阈值当间隔的话，最坏情况要等两个周期（6 小时）才碰上那个判定点。
	 */
	private fun observeProactiveSchedule() {
		applicationScope.launch {
			container.settingsStore.settings
				.map { it.proactivePushEnabled to it.proactiveIdleMinutes }
				.distinctUntilChanged()
				.collect { (pushEnabled, idleMinutes) ->
					runCatching {
						if (pushEnabled) {
							ProactiveWorker.schedule(
								context = this@AicpApplication,
								intervalMinutes = (idleMinutes / 4).toLong(),
							)
						} else {
							ProactiveWorker.cancel(this@AicpApplication)
						}
					}.onFailure { Log.e(TAG, "更新主动搭话排程失败", it) }
				}
		}
	}

	companion object {
		private const val TAG = "AICP"

		@Volatile
		private var instance: AicpApplication? = null

		/** 给 ViewModel 取容器用。取不到就是有人在 onCreate 之前调了 */
		fun app(): AicpApplication = requireNotNull(instance) {
			"AicpApplication 还没初始化，检查 manifest 里 android:name 是否指向了它"
		}

		fun container(): AppContainer = app().container
	}
}
