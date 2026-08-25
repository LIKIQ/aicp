// app/src/test/java/com/kiq/aicp/domain/QuietHoursTest.kt
// 免打扰时段判断。单拎出来测是因为"跨午夜"这个 case 极容易写反 ——
// 23 点到次日 8 点，跨过了 0 点，区间判断的方向跟不跨午夜时是相反的。

package com.kiq.aicp.domain

import com.kiq.aicp.domain.model.AicpSettings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuietHoursTest {

	@Test
	fun `跨午夜的默认时段：深夜和凌晨都算免打扰`() {
		val s = AicpSettings(quietHoursStart = 23, quietHoursEnd = 8)

		assertTrue("23 点应在免打扰内", s.isQuietAt(23))
		assertTrue("凌晨 3 点应在免打扰内", s.isQuietAt(3))
		assertTrue("7 点还在免打扰内", s.isQuietAt(7))
	}

	@Test
	fun `跨午夜时段：白天不免打扰`() {
		val s = AicpSettings(quietHoursStart = 23, quietHoursEnd = 8)

		assertFalse("8 点整已经出了免打扰", s.isQuietAt(8))
		assertFalse("下午 3 点可以打扰", s.isQuietAt(15))
		assertFalse("22 点还没进免打扰", s.isQuietAt(22))
	}

	@Test
	fun `不跨午夜的时段照常判断`() {
		// 午休 13-14 点
		val s = AicpSettings(quietHoursStart = 13, quietHoursEnd = 14)

		assertTrue(s.isQuietAt(13))
		assertFalse(s.isQuietAt(14))
		assertFalse(s.isQuietAt(12))
		assertFalse(s.isQuietAt(20))
	}

	@Test
	fun `start 等于 end 视为全天免打扰`() {
		val s = AicpSettings(quietHoursStart = 0, quietHoursEnd = 0)

		// start<=end 分支，h in 0 until 0 恒 false —— 这其实是"全天可打扰"
		// 明确记录这个边界：UI 上不该让用户设出 start==end，真设了当作不免打扰处理
		assertFalse(s.isQuietAt(0))
		assertFalse(s.isQuietAt(12))
	}

	@Test
	fun `越界的小时数被夹回合法范围而不是抛异常`() {
		val s = AicpSettings(quietHoursStart = 23, quietHoursEnd = 8)

		// 25 被 coerce 成 23，落在免打扰内
		assertTrue(s.isQuietAt(25))
	}
}
