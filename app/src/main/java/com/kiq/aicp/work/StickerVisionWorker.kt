// app/src/main/java/com/kiq/aicp/work/StickerVisionWorker.kt
// 后台表情识图。导入完表情、应用启动时各排一次，把还没分类的图逐张问过视觉模型。
//
// 做成后台任务而不是让用户点一下的理由是 KIQ 明确要求的："不需要用户点击，后台自动识别分类"。
// 由此定下几条：
//
// - 不发通知。这是整理性质的活儿，用户没要求知道进度，弹通知只是打扰
// - 唯一工作名 + KEEP：连着导入三批图只会有一个任务在排队，后来的并入已有那个。
//   这不是省事，是防止三个任务同时对同一批图各发一遍请求（识图按张计费）
// - 视觉模型没配 → success 不 retry。重试一百次也不会自己长出一个模型来，
//   写成 retry 的话没配模型的用户会被 WorkManager 无限退避重试，纯耗电池
// - 单张失败不影响整批：一张坏图（读不出、模型认不出）跳过就是，它的 emotion 留空，
//   下次触发时自然又会排到它
//
// "该 retry 还是该收工"这个判断刻意不写在这里，抽到了 StickerVisionPolicy：
// Worker 本身要靠 WorkManager 的测试库才跑得起来，而这个判断错了是会实际耗用户电池的，
// 值得单独用纯函数测。这里只做编排。

package com.kiq.aicp.work

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkRequest
import androidx.work.WorkerParameters
import com.kiq.aicp.AicpApplication
import com.kiq.aicp.domain.sticker.StickerVisionNext
import com.kiq.aicp.domain.sticker.StickerVisionPolicy
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

class StickerVisionWorker(
	context: Context,
	params: WorkerParameters,
) : CoroutineWorker(context, params) {

	override suspend fun doWork(): Result {
		val container = AicpApplication.container()
		val settings = container.settingsStore.current()

		// 接口或视觉模型没配齐时连库都不用查。这里就返回 success，别占着退避链
		if (!settings.hasEndpoint || settings.effectiveVisionModel().isBlank()) {
			return Result.success()
		}

		val report = try {
			container.stickerVision.classifyPending(settings)
		} catch (e: CancellationException) {
			throw e
		} catch (e: Exception) {
			// 库读不出来、磁盘满这类问题重试也是同样结果，留个日志收工
			Log.w(TAG, "表情识图这批没跑成", e)
			return Result.success()
		}

		if (report.total == 0) return Result.success()

		Log.i(TAG, "表情识图：${report.ok}/${report.total} 张认出来了")

		return when (StickerVisionPolicy.next(report)) {
			StickerVisionNext.RETRY -> Result.retry()
			StickerVisionNext.DONE -> Result.success()
		}
	}

	companion object {
		private const val TAG = "StickerVisionWorker"
		private const val UNIQUE_NAME = "aicp_sticker_vision"

		/**
		 * 排一次识图。重复调用会被 KEEP 挡掉，所以调用方不用自己判"是不是已经排过了"。
		 *
		 * 没活干的时候 Worker 会立刻 success 退出（一次查库的代价），
		 * 所以"不确定要不要排"的时候排一次比漏掉划算。
		 */
		fun enqueue(context: Context) {
			val request = OneTimeWorkRequestBuilder<StickerVisionWorker>()
				.setConstraints(
					Constraints.Builder()
						// 识图必须联网，没网时让 WorkManager 自己等，别醒过来白跑一趟
						.setRequiredNetworkType(NetworkType.CONNECTED)
						.build(),
				)
				// 网络类失败会 retry，退避按指数拉长：连着断网时不要每 30 秒撞一次
				.setBackoffCriteria(
					BackoffPolicy.EXPONENTIAL,
					WorkRequest.MIN_BACKOFF_MILLIS,
					TimeUnit.MILLISECONDS,
				)
				.build()

			WorkManager.getInstance(context).enqueueUniqueWork(
				UNIQUE_NAME,
				ExistingWorkPolicy.KEEP,
				request,
			)
		}
	}
}
