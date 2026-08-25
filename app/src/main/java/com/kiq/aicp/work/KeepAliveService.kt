// app/src/main/java/com/kiq/aicp/work/KeepAliveService.kt
// 保活前台服务：只为"进程别被回收"而存在。
//
// ================== 这里永远不要放业务逻辑 ==================
// 不发消息、不调模型、不轮询、不读库。主动搭话仍然由 ProactiveWorker 触发，
// 那边有免打扰时段、每日额度、空闲判定一整套闸门；在这个服务里动手就等于绕开全部闸门
// 偷偷花钱，而且用户从"我关了主动搭话"这件事上完全推不出还有别的地方在发请求。
// 以后有新的后台活儿要干，去写新的 Worker，别往这儿塞。
// ==========================================================
//
// 为什么需要它：Android 8 起的后台限制叠上国产 ROM 的清理策略，进程一被回收，
// WorkManager 的周期任务就排不上号，"到点不搭话"是常态。挂一个前台服务能把进程留在
// 系统眼里"有可见通知、不该回收"的那一档，周期任务才有机会准时醒。
// 它只提高存活概率，不是保证 —— 内存紧张时该杀还是杀，所以设置页还配了一条
// 引导用户关闭电池优化的路，两手一起用才有意义。
//
// 类型选 specialUse：Android 14 起前台服务必须声明类型，而"什么都不干，只为活着"
// 不属于 dataSync / mediaPlayback / location 里的任何一种。挑个语义不符的类型看着能过编译，
// 却会在 Play 审核和系统的类型行为约束上双重埋雷。用途说明写在 manifest 的
// PROPERTY_SPECIAL_USE_FGS_SUBTYPE 里，那是给审核看的正式声明。
//
// 通知渠道单开一个 IMPORTANCE_LOW 的，不复用主动搭话那个（它是 IMPORTANCE_DEFAULT，会响）：
// 保活通知一挂就是一整天，响一声都算骚扰。

package com.kiq.aicp.work

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.kiq.aicp.MainActivity
import com.kiq.aicp.R

class KeepAliveService : Service() {

	/**
	 * intent 为 null 表示这次是系统按 START_STICKY 把服务自己拉回来的
	 * （进程被杀之后的自恢复主要就靠这条路）。我们不读 intent 里的任何东西，
	 * 所以两种来源一样处理：建渠道、进前台、继续待着。
	 */
	override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
		ensureChannel()
		enterForeground()
		return START_STICKY
	}

	/** 不提供跨进程接口：外面只该 start/stop 它，没有任何可调用的东西 */
	override fun onBind(intent: Intent?): IBinder? = null

	override fun onDestroy() {
		// 留一行日志：保活到底是"用户关了"还是"被系统清了"，排查时全靠这条线索
		Log.i(TAG, "保活服务已停止")
		super.onDestroy()
	}

	/**
	 * 进前台。
	 *
	 * 34 以下走不带类型的重载：specialUse 这个类型值是 API 34 才有的，
	 * 在 29~33 上把它传给 startForeground 属于"声明了系统不认识的类型"，
	 * 不同 ROM 的表现从忽略到直接抛 IllegalArgumentException 都有，不值得赌。
	 *
	 * 失败就地收摊：没有前台通知的服务在系统眼里跟普通后台服务没区别，保不了活，
	 * 而 Android 14 还会因为"startForegroundService 之后没在 5 秒内进前台"判违规。
	 * 赖着不走既没收益又有风险，不如停掉，等下次用户打开应用时再试。
	 */
	private fun enterForeground() {
		val notification = buildNotification()
		val started = runCatching {
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
				startForeground(
					NOTIFICATION_ID,
					notification,
					ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
				)
			} else {
				startForeground(NOTIFICATION_ID, notification)
			}
		}.onFailure { Log.w(TAG, "没能进前台，这次保活不生效", it) }.isSuccess

		if (!started) stopSelf()
	}

	/**
	 * 常驻通知。
	 *
	 * 文案必须回答用户看到它时的第一个问题——"你为什么在这儿"，
	 * 所以正文直接写清用途和关掉它的地方，而不是一句"服务正在运行中"。
	 * 点一下进应用：一条不能划掉的通知如果连点都点不动，只会更让人烦。
	 */
	private fun buildNotification(): Notification {
		val pending = PendingIntent.getActivity(
			this,
			NOTIFICATION_ID,
			Intent(this, MainActivity::class.java).apply {
				flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
			},
			PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
		)

		return NotificationCompat.Builder(this, CHANNEL_ID)
			.setSmallIcon(R.drawable.ic_tab_chat)
			.setContentTitle(NOTIFICATION_TITLE)
			.setContentText(NOTIFICATION_TEXT)
			// 正文一行放不下，展开能看全"为什么在这儿、去哪关"
			.setStyle(NotificationCompat.BigTextStyle().bigText(NOTIFICATION_TEXT))
			.setContentIntent(pending)
			.setOngoing(true)
			.setSilent(true)
			.setPriority(NotificationCompat.PRIORITY_LOW)
			.setCategory(NotificationCompat.CATEGORY_SERVICE)
			// 不显示时间：它不是某一刻发生的事，挂上时间戳只会让人以为刚有新消息
			.setShowWhen(false)
			.build()
	}

	/**
	 * 渠道就地创建（createNotificationChannel 幂等）。
	 * 跟 ProactiveNotifier 一个路子：没开这个功能的用户不该为它在冷启动时多付一次系统调用。
	 */
	private fun ensureChannel() {
		val manager = getSystemService(NotificationManager::class.java) ?: return
		val channel = NotificationChannel(
			CHANNEL_ID,
			CHANNEL_NAME,
			// LOW 不响不震。这条通知会一直挂着，任何提示音都是骚扰
			NotificationManager.IMPORTANCE_LOW,
		).apply {
			description = CHANNEL_DESC
			// LOW 本身已经静音，这两行是给那些把渠道默认值改过的 ROM 兜底
			setSound(null, null)
			enableVibration(false)
			// 桌面图标不挂小红点：它表达的是"我还活着"，不是"有新东西"
			setShowBadge(false)
		}
		manager.createNotificationChannel(channel)
	}

	companion object {
		private const val TAG = "KeepAliveService"

		private const val CHANNEL_ID = "aicp_keep_alive"
		private const val CHANNEL_NAME = "后台保活"
		private const val CHANNEL_DESC = "一条常驻通知，AICP 靠它留在后台，好在你闲着的时候来找你聊天"

		/**
		 * 通知 id 兼作 PendingIntent 的 requestCode，取一个不会跟别处撞的大数：
		 * 主动搭话那边用会话 id 当通知 id（自增主键，实际都是很小的整数），
		 * 撞上就会互相覆盖 —— 保活通知被一条聊天通知顶掉，前台服务就失去了它的通知。
		 */
		private const val NOTIFICATION_ID = 91_001

		private const val NOTIFICATION_TITLE = "AICP 在后台待着"
		private const val NOTIFICATION_TEXT =
			"保持后台运行，这样才能在你闲着的时候主动找你聊天。" +
				"这条通知撤不掉是系统的规定；不想要就去设置的主动搭话分区里关掉「保持后台运行」。"

		/**
		 * 拉起服务。
		 *
		 * runCatching 不是走过场：Android 12 起从后台调 startForegroundService 会抛
		 * ForegroundServiceStartNotAllowedException，而调用点之一是 AicpApplication.onCreate ——
		 * 那次冷启动完全可能是 WorkManager 在后台唤起的。这种时候启动注定失败，
		 * 但绝不该让整个冷启动崩在一个"锦上添花"的功能上。
		 *
		 * 真正的自恢复靠 START_STICKY（系统重启服务不受后台启动限制），
		 * 而进了电池优化白名单之后，后台启动前台服务这条限制本身也会被豁免 ——
		 * 这也是设置页那个引导按钮的第二层价值，它不只防清理。
		 */
		fun start(context: Context) {
			runCatching {
				ContextCompat.startForegroundService(
					context,
					Intent(context, KeepAliveService::class.java),
				)
			}.onFailure { Log.w(TAG, "保活服务启动失败，多半是从后台启前台服务被系统拦了", it) }
		}

		fun stop(context: Context) {
			runCatching {
				context.stopService(Intent(context, KeepAliveService::class.java))
			}.onFailure { Log.w(TAG, "保活服务停止失败", it) }
		}
	}
}
