// app/src/test/java/com/kiq/aicp/domain/ProactiveDeciderTest.kt
// 主动搭话决策的测试。这块逻辑在真机上极难复现（要等几小时空闲、要跨免打扰时段），
// 所以每一条放行/拦截规则都在这里钉死。

package com.kiq.aicp.domain

import com.kiq.aicp.domain.humanize.ProactiveContext
import com.kiq.aicp.domain.humanize.ProactiveDecider
import com.kiq.aicp.domain.model.AicpSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProactiveDeciderTest {

	private val enabled = AicpSettings(
		proactiveEnabled = true,
		proactiveIdleMinutes = 180,
		proactiveDailyLimit = 3,
		quietHoursStart = 23,
		quietHoursEnd = 8,
	)

	/** 一个默认放行的场景：用户最后说话、空闲够久、白天、次数没超 */
	private fun okContext() = ProactiveContext(
		participantCount = 1,
		trailingAssistantCount = 0,
		idleMillis = 4 * 60 * 60 * 1000L,
		todayProactiveCount = 0,
		hourOfDay = 15,
	)

	private fun decide(
		ctx: ProactiveContext,
		settings: AicpSettings = enabled,
		respectQuiet: Boolean = false,
	) = ProactiveDecider.decide(settings, ctx, respectQuiet)

	@Test
	fun `条件都满足时放行`() {
		assertTrue(decide(okContext()).shouldSpeak)
	}

	@Test
	fun `总开关关着一律不搭话`() {
		val off = enabled.copy(proactiveEnabled = false)

		assertFalse(decide(okContext(), off).shouldSpeak)
	}

	@Test
	fun `会话里没有性格不搭话`() {
		assertFalse(decide(okContext().copy(participantCount = 0)).shouldSpeak)
	}

	@Test
	fun `末尾已经连着两条 AI 消息就闭嘴，不刷屏`() {
		assertFalse(decide(okContext().copy(trailingAssistantCount = 2)).shouldSpeak)
		// 一条还可以再补
		assertTrue(decide(okContext().copy(trailingAssistantCount = 1)).shouldSpeak)
	}

	@Test
	fun `空闲时长不够不打扰`() {
		val justSpoke = okContext().copy(idleMillis = 5 * 60 * 1000L)

		assertFalse(decide(justSpoke).shouldSpeak)
	}

	@Test
	fun `刚好到空闲阈值放行`() {
		val exactly = okContext().copy(idleMillis = 180 * 60 * 1000L)

		assertTrue(decide(exactly).shouldSpeak)
	}

	@Test
	fun `今天次数用完就不再搭话`() {
		assertFalse(decide(okContext().copy(todayProactiveCount = 3)).shouldSpeak)
		assertTrue(decide(okContext().copy(todayProactiveCount = 2)).shouldSpeak)
	}

	@Test
	fun `后台推送要守免打扰，深夜不发`() {
		val midnight = okContext().copy(hourOfDay = 3)

		assertFalse("后台凌晨 3 点不该推送", decide(midnight, respectQuiet = true).shouldSpeak)
	}

	@Test
	fun `前台不守免打扰，用户自己开着 App 半夜也能搭话`() {
		val midnight = okContext().copy(hourOfDay = 3)

		assertTrue("前台是用户自己看着的，不算打扰", decide(midnight, respectQuiet = false).shouldSpeak)
	}

	@Test
	fun `拦截时带得出原因`() {
		val decision = decide(okContext().copy(trailingAssistantCount = 2))

		assertFalse(decision.shouldSpeak)
		require(decision is com.kiq.aicp.domain.humanize.ProactiveDecision.Skip)
		assertTrue(decision.reason.isNotBlank())
	}

	// ---------------- trailingAssistantCount ----------------

	@Test
	fun `从尾部数连续 AI 条数`() {
		// [user, ai, user, ai, ai] → 末尾连续 2 条 ai
		val roles = listOf(false, true, false, true, true)

		assertEquals(2, ProactiveDecider.trailingAssistantCount(roles))
	}

	@Test
	fun `末尾是用户消息时连续数为 0`() {
		val roles = listOf(true, true, false)

		assertEquals(0, ProactiveDecider.trailingAssistantCount(roles))
	}

	@Test
	fun `全是 AI 消息时数满`() {
		assertEquals(3, ProactiveDecider.trailingAssistantCount(listOf(true, true, true)))
	}

	@Test
	fun `空列表数为 0`() {
		assertEquals(0, ProactiveDecider.trailingAssistantCount(emptyList()))
	}
}
