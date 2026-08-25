// app/src/main/java/com/kiq/aicp/ui/chat/MessageTimeDivider.kt
// 消息时间分割线，还有"这条消息该不该显示时间"的判断。
//
// 为什么不给每条消息都挂一个时间戳：连着发的五条消息时间几乎一样，
// 五个"14:32"叠在一起纯属噪音，还把气泡挤窄了。主流 IM 都是攒够间隔才插一行。
//
// 间隔阈值定 5 分钟：比这短的间隔说明还在同一轮对话里，插一行时间是打断；
// 再长就该让用户知道"这中间隔了一会儿"了。
//
// 跨天时显示日期而不只是时分。不然翻旧消息会看到一串没有上下文的"09:15"，
// 分不清是今天还是上周。

package com.kiq.aicp.ui.chat

import java.util.Calendar
import java.util.Locale

object MessageTime {

	/** 超过这个间隔就在两条消息之间插一行时间 */
	private const val GAP_THRESHOLD_MS = 5 * 60 * 1000L

	/**
	 * @param previousAt 上一条消息的时间，0 表示这是第一条
	 */
	fun shouldShowDivider(currentAt: Long, previousAt: Long): Boolean {
		if (previousAt <= 0L) return true
		return currentAt - previousAt >= GAP_THRESHOLD_MS
	}

	/**
	 * 分割线上显示的文字。
	 * 今天只给时分，昨天加"昨天"，今年内给月日，跨年才带上年份 ——
	 * 显示的信息量刚好够定位，不多写一个字。
	 */
	fun formatDivider(timestamp: Long, now: Long = System.currentTimeMillis()): String {
		val time = Calendar.getInstance().apply { timeInMillis = timestamp }
		val today = Calendar.getInstance().apply { timeInMillis = now }

		val hhmm = String.format(
			Locale.getDefault(),
			"%02d:%02d",
			time.get(Calendar.HOUR_OF_DAY),
			time.get(Calendar.MINUTE),
		)

		return when {
			isSameDay(time, today) -> hhmm

			isYesterday(time, today) -> "昨天 $hhmm"

			time.get(Calendar.YEAR) == today.get(Calendar.YEAR) ->
				"${time.get(Calendar.MONTH) + 1}月${time.get(Calendar.DAY_OF_MONTH)}日 $hhmm"

			else ->
				"${time.get(Calendar.YEAR)}年${time.get(Calendar.MONTH) + 1}月" +
					"${time.get(Calendar.DAY_OF_MONTH)}日 $hhmm"
		}
	}

	/** 长按菜单里显示的完整时间，要能精确到分且带日期 */
	fun formatFull(timestamp: Long): String {
		val t = Calendar.getInstance().apply { timeInMillis = timestamp }
		return String.format(
			Locale.getDefault(),
			"%d-%02d-%02d %02d:%02d",
			t.get(Calendar.YEAR),
			t.get(Calendar.MONTH) + 1,
			t.get(Calendar.DAY_OF_MONTH),
			t.get(Calendar.HOUR_OF_DAY),
			t.get(Calendar.MINUTE),
		)
	}

	private fun isSameDay(a: Calendar, b: Calendar): Boolean =
		a.get(Calendar.YEAR) == b.get(Calendar.YEAR) &&
			a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)

	private fun isYesterday(time: Calendar, today: Calendar): Boolean {
		val yesterday = (today.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }
		return isSameDay(time, yesterday)
	}
}
