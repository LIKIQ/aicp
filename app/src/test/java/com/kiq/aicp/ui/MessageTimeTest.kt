// app/src/test/java/com/kiq/aicp/ui/MessageTimeTest.kt
// 时间分割线的判断与格式化。纯 JVM。
//
// 这块看着简单，但"跨天"和"跨年"的分支很容易写反，而且一旦写错要到第二天
// 或者过年才能发现——正是那种必须靠单测钉住的逻辑。

package com.kiq.aicp.ui

import com.kiq.aicp.ui.chat.MessageTime
import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageTimeTest {

	/** 固定一个基准时刻：2026-08-25 14:30 */
	private val now = Calendar.getInstance().apply {
		set(2026, Calendar.AUGUST, 25, 14, 30, 0)
		set(Calendar.MILLISECOND, 0)
	}.timeInMillis

	private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
		Calendar.getInstance().apply {
			set(year, month, day, hour, minute, 0)
			set(Calendar.MILLISECOND, 0)
		}.timeInMillis

	// ---------------- 该不该插分割线 ----------------

	@Test
	fun `第一条消息一定显示时间`() {
		assertTrue(MessageTime.shouldShowDivider(now, previousAt = 0L))
	}

	@Test
	fun `间隔不到五分钟不插`() {
		val fourMinutesAgo = now - 4 * 60 * 1000L

		assertFalse(MessageTime.shouldShowDivider(now, fourMinutesAgo))
	}

	@Test
	fun `刚好五分钟就插`() {
		val fiveMinutesAgo = now - 5 * 60 * 1000L

		assertTrue(MessageTime.shouldShowDivider(now, fiveMinutesAgo))
	}

	@Test
	fun `隔了几小时当然插`() {
		assertTrue(MessageTime.shouldShowDivider(now, now - 3 * 60 * 60 * 1000L))
	}

	// ---------------- 格式化 ----------------

	@Test
	fun `今天只显示时分`() {
		val morning = at(2026, Calendar.AUGUST, 25, 9, 5)

		assertEquals("09:05", MessageTime.formatDivider(morning, now))
	}

	@Test
	fun `昨天带昨天二字`() {
		val yesterday = at(2026, Calendar.AUGUST, 24, 22, 15)

		assertEquals("昨天 22:15", MessageTime.formatDivider(yesterday, now))
	}

	@Test
	fun `今年内的更早日期给月日`() {
		val earlier = at(2026, Calendar.JULY, 3, 8, 0)

		assertEquals("7月3日 08:00", MessageTime.formatDivider(earlier, now))
	}

	@Test
	fun `跨年要带上年份，不然分不清是哪年的今天`() {
		val lastYear = at(2025, Calendar.DECEMBER, 31, 23, 59)

		assertEquals("2025年12月31日 23:59", MessageTime.formatDivider(lastYear, now))
	}

	@Test
	fun `月初和跨月边界不会错位`() {
		// 8 月 1 日 00:00，相对基准是今年内的更早日期
		val monthStart = at(2026, Calendar.AUGUST, 1, 0, 0)

		assertEquals("8月1日 00:00", MessageTime.formatDivider(monthStart, now))
	}

	@Test
	fun `长按菜单里的完整时间带年月日和时分`() {
		val t = at(2026, Calendar.AUGUST, 25, 7, 8)

		assertEquals("2026-08-25 07:08", MessageTime.formatFull(t))
	}

	@Test
	fun `个位数月日在完整时间里补零`() {
		val t = at(2026, Calendar.JANUARY, 2, 3, 4)

		assertEquals("2026-01-02 03:04", MessageTime.formatFull(t))
	}
}
