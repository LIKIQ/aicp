// app/src/main/java/com/kiq/aicp/AicpApplication.kt
// 应用入口：先处理待恢复的备份，再建全局依赖容器，并在后台把内置性格灌进空库。
//
// 种子灌入放在 applicationScope 而不是 onCreate 主线程：它要开库、写四行，
// 放主线程会实打实拖慢冷启动，而首页拿到数据是靠 Flow 推的，晚几十毫秒无感。
//
// 备份恢复反过来 —— 必须同步、必须在容器之前，理由写在 onCreate 里那段注释。

package com.kiq.aicp

import android.app.Application
import android.util.Log
import com.kiq.aicp.data.backup.BackupManager
import com.kiq.aicp.work.ProactiveWorker
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
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

		// 待恢复的备份必须在这一行搬完，位置和同步性都不能挪：
		// AppContainer 里的 database 是 by lazy，谁先碰它谁就先把 Room 连接开起来了，
		// 那之后再换库文件就是"连接握着旧页、磁盘上是新文件"，轻则读到半旧半新，重则写坏新库。
		// 扔进 applicationScope 也不行 —— 协程调度上去的那一刻主线程已经往下跑了。
		// 代价是刚点过恢复的那一次冷启动会被阻塞几十到几百毫秒；没有待恢复标记时它只做一次
		// 目录 stat 就返回。用一次可感知的启动卡顿换"要么完整恢复、要么原样不动"，这笔账划得来。
		BackupManager.applyPendingRestore(this)

		container = AppContainer(this)

		applicationScope.launch {
			runCatching { container.personaRepository.ensureSeeded() }
				.onFailure { Log.e(TAG, "灌入内置性格失败", it) }
		}

		applicationScope.launch {
			runCatching { importBuiltInStickersOnce() }
				.onFailure { Log.e(TAG, "灌入内置表情失败", it) }
		}

		applicationScope.launch {
			runCatching { settleRestoreFlag() }
				.onFailure { Log.e(TAG, "清理待恢复标记失败", it) }
		}

		observeProactiveSchedule()

		// 补跑一次表情识图：上次可能导入完就被杀进程了，或者当时没网。
		// 没有待识别的图时 Worker 查一次库就退出，代价可以忽略，所以无条件排
		runCatching { container.scheduleStickerVision() }
			.onFailure { Log.e(TAG, "表情识图排程失败", it) }
	}

	/**
	 * 把 DataStore 里的待恢复标记跟磁盘上的实际情况对齐。
	 *
	 * 标记是给设置页看的"等重启"状态，搬运本身却发生在 DataStore 还用不上的时候（见上面 onCreate），
	 * 所以只能等容器起来之后补这一刀：暂存目录已经没了就说明这轮搬运已经收尾（成功或已回滚），
	 * 标记该落下去，否则那句"重启后完成恢复"会一直挂在设置页上骗人。
	 */
	private suspend fun settleRestoreFlag() {
		if (!container.settingsStore.restorePending.first()) return
		if (BackupManager.hasPendingRestore(this)) return
		container.settingsStore.setRestorePending(false)
	}

	/**
	 * 内置表情只在首次启动灌一次。
	 * 判据是 DataStore 里的标记，不是"表情表空不空"——
	 * 用户把内置表情删干净之后，按后者判断的话每次重启都会重新冒出来。
	 */
	private suspend fun importBuiltInStickersOnce() {
		importPresetEmojiStickers()

		if (container.settingsStore.builtInStickersImported()) return
		val count = container.builtInStickers.importIfNeeded()
		// assets 里没放素材时 count 是 0，这时候也标记成已处理：
		// 否则每次冷启动都要白跑一遍 assets 列目录
		container.settingsStore.markBuiltInStickersImported()
		if (count > 0) {
			Log.i(TAG, "内置表情导入 $count 张")
			// 刚灌进来的图还没有情绪，赶紧排一次识图。
			// onCreate 里那次排程发生在这个协程之前，可能已经把空批次跑完了
			runCatching { container.scheduleStickerVision() }
				.onFailure { Log.e(TAG, "内置表情识图排程失败", it) }
		}
	}

	/**
	 * 预设 emoji 表情。
	 *
	 * 跟 assets 那套分开跑，而且不排识图 —— 它的分组名本身就是情绪词，
	 * 装上就能被模型选中，没有待分类的图，排识图纯属白跑一趟视觉调用。
	 * 版本号判据在 importIfNeeded 里面，这里不重复判。
	 */
	private suspend fun importPresetEmojiStickers() {
		val count = runCatching { container.builtInEmojiStickers.importIfNeeded() }
			.onFailure { Log.e(TAG, "预设 emoji 表情导入失败", it) }
			.getOrDefault(0)
		if (count > 0) Log.i(TAG, "预设 emoji 表情导入 $count 张")
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
