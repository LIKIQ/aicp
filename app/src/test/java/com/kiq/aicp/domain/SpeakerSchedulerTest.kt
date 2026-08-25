// app/src/test/java/com/kiq/aicp/domain/SpeakerSchedulerTest.kt
// 群聊发言调度的规则测试。这套规则决定"我说一句话之后谁接",
// 错了的表现是同一个角色一直霸麦，或者被点名的那个反而不说话。

package com.kiq.aicp.domain

import com.kiq.aicp.domain.group.SpeakerCandidate
import com.kiq.aicp.domain.group.SpeakerScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeakerSchedulerTest {

	private fun candidate(
		id: Long,
		name: String,
		weight: Float = 1f,
		muted: Boolean = false,
		lastSpokeAt: Long = 0,
	) = SpeakerCandidate(id, name, weight, muted, lastSpokeAt)

	private val xiaoxue = candidate(1, "小雪", lastSpokeAt = 300)
	private val laodao = candidate(2, "老刀", lastSpokeAt = 200)
	private val ada = candidate(3, "Ada", lastSpokeAt = 100)

	@Test
	fun `只有一个参与者时直接是它`() {
		assertEquals(listOf(1L), SpeakerScheduler.pick(listOf(xiaoxue), "随便说点什么", maxSpeakers = 3))
	}

	@Test
	fun `静音的角色永远不开口`() {
		val picked = SpeakerScheduler.pick(
			listOf(xiaoxue.copy(muted = true), laodao),
			"大家在吗",
			maxSpeakers = 3,
		)

		assertEquals(listOf(2L), picked)
	}

	@Test
	fun `全员静音时返回空`() {
		val picked = SpeakerScheduler.pick(
			listOf(xiaoxue.copy(muted = true), laodao.copy(muted = true)),
			"在吗",
			maxSpeakers = 3,
		)

		assertTrue(picked.isEmpty())
	}

	@Test
	fun `被点名的优先，并按名字出现的先后排序`() {
		val picked = SpeakerScheduler.pick(
			listOf(xiaoxue, laodao, ada),
			"老刀你怎么看，小雪你也说说",
			maxSpeakers = 3,
		)

		assertEquals(listOf(2L, 1L), picked)
	}

	@Test
	fun `at 名字也算点名`() {
		val picked = SpeakerScheduler.pick(listOf(xiaoxue, laodao, ada), "@Ada 帮我看看", maxSpeakers = 2)

		assertEquals(listOf(3L), picked)
	}

	@Test
	fun `点名不区分大小写`() {
		val picked = SpeakerScheduler.pick(listOf(xiaoxue, ada), "ada 你说", maxSpeakers = 2)

		assertEquals(listOf(3L), picked)
	}

	@Test
	fun `没人被点名时让最久没说话的先开口`() {
		val picked = SpeakerScheduler.pick(listOf(xiaoxue, laodao, ada), "今天天气不错", maxSpeakers = 2)

		// lastSpokeAt 越小越久没说：Ada(100) → 老刀(200)
		assertEquals(listOf(3L, 2L), picked)
	}

	@Test
	fun `一样久没说话时权重高的先说`() {
		val a = candidate(10, "甲", weight = 0.5f, lastSpokeAt = 0)
		val b = candidate(11, "乙", weight = 2f, lastSpokeAt = 0)

		val picked = SpeakerScheduler.pick(listOf(a, b), "都没说过话", maxSpeakers = 1)

		assertEquals(listOf(11L), picked)
	}

	@Test
	fun `一轮发言人数受上限约束`() {
		val picked = SpeakerScheduler.pick(listOf(xiaoxue, laodao, ada), "都来说说", maxSpeakers = 1)

		assertEquals(1, picked.size)
	}

	@Test
	fun `上限传 0 也至少让一个人说话`() {
		val picked = SpeakerScheduler.pick(listOf(xiaoxue, laodao), "在吗", maxSpeakers = 0)

		assertEquals(1, picked.size)
	}

	@Test
	fun `点名人数超过上限时按上限截断`() {
		val picked = SpeakerScheduler.pick(
			listOf(xiaoxue, laodao, ada),
			"小雪 老刀 Ada 都说一句",
			maxSpeakers = 2,
		)

		assertEquals(listOf(1L, 2L), picked)
	}

	@Test
	fun `候选为空时返回空而不是崩`() {
		assertTrue(SpeakerScheduler.pick(emptyList(), "在吗", maxSpeakers = 3).isEmpty())
	}
}
