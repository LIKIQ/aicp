// app/src/test/java/com/kiq/aicp/domain/EntryMatcherTest.kt
// 条目关键词检索的测试。纯 JVM。
//
// 这块是"不用 embedding 的检索"的全部实现，所以它的边界必须钉死：
// 单字词不能参与匹配（"猫"会在"猫腻"里误命中）、别名要跟标题一样有效、
// 命中多个词时按最长的算权重。这些规则一条写歪，检索就会开始带回不相干的记忆。

package com.kiq.aicp.domain

import com.kiq.aicp.data.db.entity.MemoryEntryEntity
import com.kiq.aicp.domain.memory.EntryMatcher
import com.kiq.aicp.domain.model.MemoryCardType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EntryMatcherTest {

	private var seq = 0L

	private fun entry(
		title: String,
		aliases: String = "",
		importance: Int = 3,
		updatedAt: Long = 1_000L,
	) = MemoryEntryEntity(
		id = ++seq,
		scopeKey = "c:-|p:-",
		category = MemoryCardType.FACT,
		title = title,
		aliases = aliases,
		oneLiner = "$title 的摘要",
		body = "$title 的正文",
		importance = importance,
		createdAt = 0,
		updatedAt = updatedAt,
	)

	@Test
	fun `标题出现在文本里就命中`() {
		val entries = listOf(entry("职业"), entry("宠物"))

		val hits = EntryMatcher.match(entries, "说说你的职业吧", limit = 5)

		assertEquals(1, hits.size)
		assertEquals("职业", hits.single().entry.title)
	}

	@Test
	fun `别名跟标题一样能命中`() {
		val entries = listOf(entry("职业", aliases = "工作|上班"))

		val hits = EntryMatcher.match(entries, "最近工作忙不忙", limit = 5)

		assertEquals(1, hits.size)
		assertEquals("工作", hits.single().term)
	}

	@Test
	fun `一个字的词不参与匹配，避免在别的词里误命中`() {
		val entries = listOf(entry("猫"))

		val hits = EntryMatcher.match(entries, "这里面有点猫腻", limit = 5)

		assertTrue("单字标题不该被匹配上", hits.isEmpty())
	}

	@Test
	fun `两个字起就正常匹配`() {
		val entries = listOf(entry("养猫"))

		assertEquals(1, EntryMatcher.match(entries, "我在养猫", limit = 5).size)
	}

	@Test
	fun `命中多个词时按最长的算权重`() {
		val entries = listOf(entry("安卓开发", aliases = "安卓"))

		val hit = EntryMatcher.match(entries, "在做安卓开发", limit = 5).single()

		assertEquals("安卓开发", hit.term)
		assertEquals(4, hit.weight)
	}

	@Test
	fun `权重高的排前面`() {
		val entries = listOf(entry("宠物"), entry("安卓开发"))

		val hits = EntryMatcher.match(entries, "我养宠物，也做安卓开发", limit = 5)

		assertEquals("安卓开发", hits.first().entry.title)
	}

	@Test
	fun `权重相同时重要度高的排前面`() {
		val entries = listOf(
			entry("宠物", importance = 2),
			entry("职业", importance = 5),
		)

		val hits = EntryMatcher.match(entries, "聊聊宠物和职业", limit = 5)

		assertEquals("职业", hits.first().entry.title)
	}

	@Test
	fun `权重和重要度都相同时最近更新的排前面`() {
		val entries = listOf(
			entry("宠物", updatedAt = 100),
			entry("职业", updatedAt = 900),
		)

		val hits = EntryMatcher.match(entries, "宠物 职业", limit = 5)

		assertEquals("职业", hits.first().entry.title)
	}

	@Test
	fun `limit 会截断结果，一句话不该把整个记忆库拖进上下文`() {
		val entries = listOf(entry("宠物"), entry("职业"), entry("住处"))

		val hits = EntryMatcher.match(entries, "宠物 职业 住处", limit = 2)

		assertEquals(2, hits.size)
	}

	@Test
	fun `一个条目只出一次，不会因为多个别名命中而重复`() {
		val entries = listOf(entry("职业", aliases = "工作|上班"))

		val hits = EntryMatcher.match(entries, "工作和上班都提到了职业", limit = 5)

		assertEquals(1, hits.size)
	}

	@Test
	fun `大小写不影响匹配`() {
		val entries = listOf(entry("Kotlin"))

		assertEquals(1, EntryMatcher.match(entries, "最近在写 kotlin", limit = 5).size)
	}

	@Test
	fun `空文本 空条目 零上限都安全返回空`() {
		val entries = listOf(entry("职业"))

		assertTrue(EntryMatcher.match(entries, "", limit = 5).isEmpty())
		assertTrue(EntryMatcher.match(entries, "   ", limit = 5).isEmpty())
		assertTrue(EntryMatcher.match(emptyList(), "职业", limit = 5).isEmpty())
		assertTrue(EntryMatcher.match(entries, "职业", limit = 0).isEmpty())
	}

	@Test
	fun `别名里的空段不会当成有效词`() {
		// "工作||上班" 中间那个空段被过滤掉，否则空串会匹配任何文本
		val entries = listOf(entry("职业", aliases = "工作||上班"))

		val hits = EntryMatcher.match(entries, "完全不相干的一句话", limit = 5)

		assertTrue(hits.isEmpty())
	}
}
