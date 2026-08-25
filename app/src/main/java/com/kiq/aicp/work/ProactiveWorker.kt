// app/src/main/java/com/kiq/aicp/work/ProactiveWorker.kt
// 后台主动搭话。WorkManager 定期唤醒，满足条件就让某个性格发一条消息 + 弹通知。
//
// 这个 Worker 会在用户看不见的时候真的发起模型请求，也就是真的花钱。所以设计上处处从严：
// - 默认关闭，用户在设置里两道开关（proactiveEnabled + proactivePushEnabled）都打开才注册
// - 免打扰时段内直接放弃，不是延后（延后会攒成一堆，早上八点连着弹五条）
// - 每天次数上限，用 proactive_logs 记账
// - 走 complete() 而不是 streamChat()：后台没有 UI 要打字机效果，一次拿完整段话最省事
//
// 最小间隔 15 分钟是 WorkManager 的硬限制（PeriodicWorkRequest 的 MIN_PERIODIC_INTERVAL_MILLIS），
// 且系统会按 Doze / 电池优化再往后推，实际触发时刻不可控。所以"空闲三小时就搭话"实际表现是
// "空闲三小时之后的某次唤醒时搭话"，这一点在设置页要跟用户说清楚。
//
// Result.retry() 只在网络类错误时返回。参数错、Key 错重试多少次都是错，
// 白耗电还可能反复扣费，直接 success 收工（success 表示"这次调度结束"，不代表搭话成功）。

package com.kiq.aicp.work

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.kiq.aicp.AicpApplication
import com.kiq.aicp.data.remote.LlmException
import com.kiq.aicp.data.remote.LlmMessage
import com.kiq.aicp.data.remote.LlmParams
import com.kiq.aicp.domain.humanize.ProactiveContext
import com.kiq.aicp.domain.humanize.ProactiveDecider
import com.kiq.aicp.domain.model.ChatRole
import java.time.LocalTime
import java.util.concurrent.TimeUnit

class ProactiveWorker(
	context: Context,
	params: WorkerParameters,
) : CoroutineWorker(context, params) {

	override suspend fun doWork(): Result {
		val container = AicpApplication.container()
		val settings = container.settingsStore.current()

		if (!settings.proactiveEnabled || !settings.proactivePushEnabled) {
			return Result.success()
		}
		if (!settings.hasEndpoint) return Result.success()

		// 免打扰直接放弃而不是延后：延后会攒成一堆，八点整连弹好几条
		if (settings.isQuietAt(LocalTime.now().hour)) return Result.success()

		val proactive = container.proactiveRepository
		if (proactive.remainingQuota(settings.proactiveDailyLimit) <= 0) return Result.success()

		val target = proactive.pickTarget() ?: return Result.success()

		val messages = container.chatRepository.recentRaw(target.conversationId, RECENT_FOR_DECISION)
		val decision = ProactiveDecider.decide(
			settings = settings,
			ctx = ProactiveContext(
				participantCount = 1,
				trailingAssistantCount = ProactiveDecider.trailingAssistantCount(
					messages.asReversed().map { it.role == ChatRole.ASSISTANT },
				),
				idleMillis = System.currentTimeMillis() - (messages.firstOrNull()?.createdAt ?: 0L),
				todayProactiveCount = settings.proactiveDailyLimit -
					proactive.remainingQuota(settings.proactiveDailyLimit),
				hourOfDay = LocalTime.now().hour,
			),
			respectQuietHours = true,
		)
		if (!decision.shouldSpeak) return Result.success()

		val persona = container.personaRepository.getById(target.personaId) ?: return Result.success()
		if (!persona.proactiveEnabled) return Result.success()

		return try {
			val context = container.contextBuilder.build(
				conversationId = target.conversationId,
				speaker = persona,
				settings = settings,
			)

			val reply = container.llmProvider.complete(
				messages = context.messages + LlmMessage(ChatRole.SYSTEM, ProactiveDecider.INSTRUCTION),
				params = LlmParams(
					model = persona.modelOverride?.takeIf { it.isNotBlank() } ?: settings.model,
					temperature = persona.temperature,
					topP = persona.topP,
					maxTokens = ProactiveDecider.MAX_TOKENS,
				),
			).trim()

			if (reply.isEmpty()) return Result.success()

			container.chatRepository.appendAssistantSegment(
				convId = target.conversationId,
				personaId = persona.id,
				text = reply,
			)
			proactive.recordGlobalProactive()

			ProactiveNotifier.notifyMessage(
				context = applicationContext,
				conversationId = target.conversationId,
				personaName = persona.name,
				body = reply,
			)
			Result.success()
		} catch (e: LlmException) {
			// 只有网络类值得重试；参数或 Key 错误重试多少次都一样，还可能反复计费
			if (e.kind.retryable) Result.retry() else Result.success()
		} catch (e: Exception) {
			Log.w(TAG, "后台主动搭话失败", e)
			Result.success()
		}
	}

	companion object {
		private const val TAG = "ProactiveWorker"
		private const val UNIQUE_NAME = "aicp_proactive"
		private const val RECENT_FOR_DECISION = 5

		/**
		 * 注册或更新周期任务。
		 * UPDATE 策略保证改了间隔后新配置生效，而不是被旧的排程一直占着。
		 */
		fun schedule(context: Context, intervalMinutes: Long) {
			val request = PeriodicWorkRequestBuilder<ProactiveWorker>(
				intervalMinutes.coerceAtLeast(MIN_INTERVAL_MINUTES),
				TimeUnit.MINUTES,
			)
				.setConstraints(
					Constraints.Builder()
						.setRequiredNetworkType(NetworkType.CONNECTED)
						// 电量低的时候别为了闲聊耗电
						.setRequiresBatteryNotLow(true)
						.build(),
				)
				.build()

			WorkManager.getInstance(context).enqueueUniquePeriodicWork(
				UNIQUE_NAME,
				ExistingPeriodicWorkPolicy.UPDATE,
				request,
			)
		}

		fun cancel(context: Context) {
			WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_NAME)
		}

		/** WorkManager 的硬下限，比这个小的间隔会被它自己抬上来 */
		const val MIN_INTERVAL_MINUTES = 15L
	}
}
