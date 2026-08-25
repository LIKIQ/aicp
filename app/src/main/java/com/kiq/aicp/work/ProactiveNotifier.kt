// app/src/main/java/com/kiq/aicp/work/ProactiveNotifier.kt
// 主动搭话的通知。
//
// 渠道在发通知前就地创建（createNotificationChannel 是幂等的），不放 Application.onCreate：
// 大部分用户永远不会打开后台推送，没必要为一个用不到的功能在冷启动时多做一次系统调用。
//
// 点通知直接进 MainActivity。没做"直达该会话"的深链：那要给 MainActivity 加
// intent 解析和 Compose 侧的导航跳转，而当前只有一个会话列表入口，收益不值那个复杂度。
// 真要加的话在这里挂 extra，MainActivity 里读出来传给 AicpApp 即可。
//
// Android 13+ 没给 POST_NOTIFICATIONS 时 notify 会被系统静默丢弃，不抛异常。
// 但这里仍然显式查一次权限：一是 lint 的 MissingPermission 要求，二是能留一行日志 ——
// "开了后台推送却收不到消息"这种问题，日志里有没有这行是能不能查下去的分界。

package com.kiq.aicp.work

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.app.PendingIntent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.kiq.aicp.MainActivity
import com.kiq.aicp.R

object ProactiveNotifier {

	private const val TAG = "ProactiveNotifier"
	private const val CHANNEL_ID = "aicp_proactive"

	/** 用会话 id 当通知 id：同一个会话的新消息覆盖旧的，不堆一串 */
	fun notifyMessage(context: Context, conversationId: Long, personaName: String, body: String) {
		if (!hasPermission(context)) {
			Log.i(TAG, "没有通知权限，主动消息已入库但不弹通知")
			return
		}

		ensureChannel(context)

		val intent = Intent(context, MainActivity::class.java).apply {
			flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
		}
		val pending = PendingIntent.getActivity(
			context,
			conversationId.toInt(),
			intent,
			PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
		)

		val notification = NotificationCompat.Builder(context, CHANNEL_ID)
			.setSmallIcon(R.drawable.ic_tab_chat)
			.setContentTitle(personaName)
			.setContentText(body.take(120).replace('\n', ' '))
			// 长文本展开可读，短的不受影响
			.setStyle(NotificationCompat.BigTextStyle().bigText(body))
			.setContentIntent(pending)
			.setAutoCancel(true)
			.setPriority(NotificationCompat.PRIORITY_DEFAULT)
			.build()

		// 显式 catch SecurityException 而不只是预检：
		// lint 的数据流分析跨不过上面那个 SDK 版本判断，光有 hasPermission() 它仍然报 MissingPermission。
		// 而且权限可能在预检之后被用户从系统设置里撤掉，catch 住才是真的安全。
		try {
			NotificationManagerCompat.from(context).notify(conversationId.toInt(), notification)
		} catch (e: SecurityException) {
			Log.i(TAG, "通知权限被拒，主动消息已入库但没弹出来", e)
		}
	}

	private fun hasPermission(context: Context): Boolean =
		Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
			ContextCompat.checkSelfPermission(
				context,
				Manifest.permission.POST_NOTIFICATIONS,
			) == PackageManager.PERMISSION_GRANTED

	private fun ensureChannel(context: Context) {
		val manager = context.getSystemService(NotificationManager::class.java) ?: return
		val channel = NotificationChannel(
			CHANNEL_ID,
			context.getString(R.string.proactive_channel_name),
			// DEFAULT 会响一声。闲聊消息用 LOW 太容易被忽略，用 HIGH 又太吵
			NotificationManager.IMPORTANCE_DEFAULT,
		).apply {
			description = context.getString(R.string.proactive_channel_desc)
		}
		manager.createNotificationChannel(channel)
	}
}
