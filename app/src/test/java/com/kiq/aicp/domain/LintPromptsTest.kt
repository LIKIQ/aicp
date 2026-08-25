// app/src/test/java/com/kiq/aicp/domain/LintPromptsTest.kt
// 记忆体检的提示词与解析测试。纯 JVM。
//
// 体检建议会真的删条目、并条目，所以解析层的每道闸都得有测试压着：
// 序号越界、自己吞自己、两组建议抢同一条、正文为空 —— 任一没挡住，
// 用户点一下"合并"就可能丢掉一条本该留着的记忆。
//
// 提示词那几条断言同理：宁少勿多、不许删持久事实、编号不许编，
// 这三句是体检不乱提建议的全部约束，被谁"顺手精简"掉这里会红。

package com.kiq.aicp.domain

import com.kiq.aicp.data.db.entity.MemoryEntryEntity
import com.kiq.aicp.domain.memory.LintPrompts
import com.kiq.aicp.domain.model.MemoryCardType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LintPromptsTest {

	private var seq = 0L

	private fun entry(
		title: String,
		aliases: String = "",
		importance: Int = 3,
		hitCount: Int = 0,
		lastHitAt: Long = 0,
		pinned: Boolean = false,
		sourceCount: Int = 1,
		conflictNote: String? = null,
		category: MemoryCardType = MemoryCardType.FACT,
		updatedAt: Long = 1_000L,
	) = MemoryEntryEntity(
		id = ++seq,
		scopeKey = "c:-|p:-",
		category = category,
		title = title,
		aliases = aliases,
		oneLiner = "$title 的摘要",
		body = "$title 的正文",
		importance = importance,
		hitCount = hitCount,
		lastHitAt = lastHitAt,
		pinned = pinned,
		sourceCount = sourceCount,
		conflictNote = conflictNote,
		createdAt = 0,
		updatedAt = updatedAt,
	)

	@Test
	fun `四类建议都能解析出来`() {
		val raw = """
			{
			  "merges": [{"keep": 1, "absorb": [2], "body": "并完的正文", "reason": "同一件事"}],
			  "conflicts": [{"target": 3, "question": "你现在住哪？", "reason": "前后两个地方"}],
			  "stale": [{"target": 4, "reason": "事情过去了"}],
			  "notes": ["猫一直没有自己的条目"]
			}
		""".trimIndent()

		val parsed = LintPrompts.parseLint(raw, entryCount = 5)

		assertTrue(parsed.strict)
		assertEquals(1, parsed.merges.size)
		assertEquals(1, parsed.merges[0].keep)
		assertEquals(listOf(2), parsed.merges[0].absorb)
		assertEquals("并完的正文", parsed.merges[0].body)
		assertEquals(3, parsed.conflicts[0].target)
		assertEquals("你现在住哪？", parsed.conflicts[0].question)
		assertEquals(4, parsed.stale[0].target)
		assertEquals(listOf("猫一直没有自己的条目"), parsed.notes)
	}

	@Test
	fun `套了代码块也能解析`() {
		val raw = "```json\n{\"stale\":[{\"target\":1,\"reason\":\"过期了\"}]}\n```"

		val parsed = LintPrompts.parseLint(raw, entryCount = 3)

		assertTrue(parsed.strict)
		assertEquals(1, parsed.stale.size)
	}

	@Test
	fun `JSON 前后加了解释文字也能解析`() {
		val raw = "我检查了一遍，结果如下：\n{\"notes\":[\"没什么问题\"]}\n希望有帮助。"

		val parsed = LintPrompts.parseLint(raw, entryCount = 3)

		assertTrue(parsed.strict)
		assertEquals(listOf("没什么问题"), parsed.notes)
	}

	@Test
	fun `空回复和坏 JSON 都不算有效体检`() {
		assertFalse(LintPrompts.parseLint("", entryCount = 5).strict)
		assertFalse(LintPrompts.parseLint("   ", entryCount = 5).strict)
		assertFalse(LintPrompts.parseLint("我觉得挺好的，没什么要改", entryCount = 5).strict)
	}

	@Test
	fun `没有条目时不接受任何建议`() {
		val raw = """{"stale":[{"target":1,"reason":"删了吧"}]}"""

		val parsed = LintPrompts.parseLint(raw, entryCount = 0)

		assertFalse(parsed.strict)
		assertTrue(parsed.stale.isEmpty())
	}

	@Test
	fun `全空的合法 JSON 是有效结果只是没建议`() {
		val parsed = LintPrompts.parseLint("{}", entryCount = 5)

		assertTrue(parsed.strict)
		assertTrue(parsed.merges.isEmpty())
		assertTrue(parsed.notes.isEmpty())
	}

	@Test
	fun `序号越界的建议一律丢掉`() {
		val raw = """
			{
			  "merges": [{"keep": 9, "absorb": [1], "body": "正文", "reason": "越界的 keep"}],
			  "conflicts": [{"target": 0, "question": "问句", "reason": "序号是 0"}],
			  "stale": [{"target": -1, "reason": "负数"}, {"target": 99, "reason": "超范围"}]
			}
		""".trimIndent()

		val parsed = LintPrompts.parseLint(raw, entryCount = 3)

		assertTrue(parsed.strict)
		assertTrue(parsed.merges.isEmpty())
		assertTrue(parsed.conflicts.isEmpty())
		assertTrue(parsed.stale.isEmpty())
	}

	@Test
	fun `keep 出现在 absorb 里时那个序号被剔掉`() {
		val raw = """{"merges":[{"keep":1,"absorb":[1,2],"body":"正文","reason":"自己也在里面"}]}"""

		val parsed = LintPrompts.parseLint(raw, entryCount = 3)

		assertEquals(listOf(2), parsed.merges[0].absorb)
	}

	@Test
	fun `absorb 剔干净之后整条合并作废`() {
		val raw = """{"merges":[{"keep":1,"absorb":[1],"body":"正文","reason":"只想吞自己"}]}"""

		val parsed = LintPrompts.parseLint(raw, entryCount = 3)

		assertTrue(parsed.merges.isEmpty())
	}

	@Test
	fun `合并没给正文就不能执行`() {
		val raw = """{"merges":[{"keep":1,"absorb":[2],"body":"   ","reason":"忘了写正文"}]}"""

		val parsed = LintPrompts.parseLint(raw, entryCount = 3)

		assertTrue(parsed.merges.isEmpty())
	}

	@Test
	fun `矛盾没给问句就没有用`() {
		val raw = """{"conflicts":[{"target":1,"question":"","reason":"只给了理由"}]}"""

		val parsed = LintPrompts.parseLint(raw, entryCount = 3)

		assertTrue(parsed.conflicts.isEmpty())
	}

	@Test
	fun `同一条目被两组合并抢时只认第一组`() {
		val raw = """
			{"merges":[
			  {"keep":1,"absorb":[2],"body":"第一组","reason":"先来的"},
			  {"keep":2,"absorb":[3],"body":"第二组","reason":"keep 已经被上面并掉了"},
			  {"keep":4,"absorb":[5],"body":"第三组","reason":"没冲突"}
			]}
		""".trimIndent()

		val parsed = LintPrompts.parseLint(raw, entryCount = 6)

		assertEquals(2, parsed.merges.size)
		assertEquals("第一组", parsed.merges[0].body)
		assertEquals("第三组", parsed.merges[1].body)
	}

	@Test
	fun `已经参与合并的条目不再列进可删`() {
		val raw = """
			{
			  "merges":[{"keep":1,"absorb":[2],"body":"正文","reason":"合并"}],
			  "stale":[{"target":2,"reason":"顺手也删了"},{"target":3,"reason":"这条可以删"}]
			}
		""".trimIndent()

		val parsed = LintPrompts.parseLint(raw, entryCount = 4)

		assertEquals(listOf(3), parsed.stale.map { it.target })
	}

	@Test
	fun `同一条目重复出现在矛盾或可删里只留一条`() {
		val raw = """
			{
			  "conflicts":[{"target":1,"question":"问句A","reason":""},{"target":1,"question":"问句B","reason":""}],
			  "stale":[{"target":2,"reason":"理由A"},{"target":2,"reason":"理由B"}]
			}
		""".trimIndent()

		val parsed = LintPrompts.parseLint(raw, entryCount = 3)

		assertEquals(1, parsed.conflicts.size)
		assertEquals("问句A", parsed.conflicts[0].question)
		assertEquals(1, parsed.stale.size)
		assertEquals("理由A", parsed.stale[0].reason)
	}

	@Test
	fun `每类建议最多八条`() {
		val stale = (1..20).joinToString(",") { """{"target":$it,"reason":"理由$it"}""" }
		val notes = (1..20).joinToString(",") { """"观察$it"""" }
		val raw = """{"stale":[$stale],"notes":[$notes]}"""

		val parsed = LintPrompts.parseLint(raw, entryCount = 30)

		assertEquals(8, parsed.stale.size)
		assertEquals(8, parsed.notes.size)
	}

	@Test
	fun `合并正文超长按条目上限截断`() {
		val long = "字".repeat(MemoryEntryEntity.MAX_BODY + 50)
		val raw = """{"merges":[{"keep":1,"absorb":[2],"body":"$long","reason":"太长了"}]}"""

		val parsed = LintPrompts.parseLint(raw, entryCount = 3)

		assertEquals(MemoryEntryEntity.MAX_BODY, parsed.merges[0].body.length)
	}

	@Test
	fun `空白观察被过滤掉`() {
		val raw = """{"notes":["  ","有内容的观察",""]}"""

		val parsed = LintPrompts.parseLint(raw, entryCount = 3)

		assertEquals(listOf("有内容的观察"), parsed.notes)
	}

	@Test
	fun `系统提示词保住三条关键约束`() {
		val prompt = LintPrompts.lintSystem()

		// 宁少勿多：话痨 linter 会让用户疲于确认，最后干脆不看
		assertTrue(prompt.contains("宁少勿多"))
		assertTrue(prompt.contains("零条建议"))
		// 不许删持久信息：这是记忆库的底线，删错了没法找回
		assertTrue(prompt.contains("不许建议删除"))
		// 编号不许编：序号是唯一的定位手段
		assertTrue(prompt.contains("不许编新编号"))
	}

	@Test
	fun `系统提示词说明了矛盾要由用户裁决`() {
		val prompt = LintPrompts.lintSystem()

		assertTrue(prompt.contains("不要自己裁决"))
		assertTrue(prompt.contains("让用户来定"))
	}

	@Test
	fun `清单编号从一开始并带上分类`() {
		val entries = listOf(entry("职业"), entry("宠物", category = MemoryCardType.PREFERENCE))

		val text = LintPrompts.lintUser(entries, nowMillis = 0)

		assertTrue(text.contains("1. [FACT] 职业"))
		assertTrue(text.contains("2. [PREFERENCE] 宠物"))
		assertTrue(text.contains("共 2 条"))
	}

	@Test
	fun `别名和钉住状态都送给模型`() {
		val entries = listOf(entry("职业", aliases = "工作|上班", pinned = true))

		val text = LintPrompts.lintUser(entries, nowMillis = 0)

		assertTrue(text.contains("又叫：工作、上班"))
		assertTrue(text.contains("已钉住"))
	}

	@Test
	fun `已有的疑点标记要带上去否则模型会重复提同一个矛盾`() {
		val entries = listOf(entry("住处", conflictNote = "先说北京后说上海"))

		val text = LintPrompts.lintUser(entries, nowMillis = 0)

		assertTrue(text.contains("已标记的疑点：先说北京后说上海"))
	}

	@Test
	fun `用户写了记忆规则就一并送去对照`() {
		val entries = listOf(entry("体重"))

		val withRules = LintPrompts.lintUser(entries, nowMillis = 0, schema = "不要记我的体重")
		val withoutRules = LintPrompts.lintUser(entries, nowMillis = 0, schema = "   ")

		assertTrue(withRules.contains("不要记我的体重"))
		assertTrue(withRules.contains("记忆规则"))
		assertFalse(withoutRules.contains("记忆规则"))
	}

	@Test
	fun `时间说成人话而不是时间戳`() {
		val day = 86_400_000L
		val now = 100 * day

		val text = LintPrompts.lintUser(
			listOf(
				entry("从没用过", lastHitAt = 0),
				entry("今天用过", lastHitAt = now),
				entry("昨天用过", lastHitAt = now - day),
				entry("十天前", lastHitAt = now - 10 * day),
				entry("两个月前", lastHitAt = now - 65 * day),
			),
			nowMillis = now,
		)

		assertTrue(text.contains("还没用到过"))
		assertTrue(text.contains("今天"))
		assertTrue(text.contains("昨天"))
		assertTrue(text.contains("10 天前"))
		assertTrue(text.contains("2 个月前"))
	}

	@Test
	fun `只出现一次的条目不啰嗦确认次数`() {
		val text = LintPrompts.lintUser(
			listOf(entry("只提过一次", sourceCount = 1), entry("反复提过", sourceCount = 4)),
			nowMillis = 0,
		)

		assertTrue(text.contains("来源 1 次"))
		assertTrue(text.contains("来源 4 次"))
	}

	@Test
	fun `候选按重要度和命中排序`() {
		val low = entry("低", importance = 1)
		val high = entry("高", importance = 5)
		val mid = entry("中", importance = 3, hitCount = 10)

		val sorted = LintPrompts.lintCandidates(listOf(low, mid, high))

		assertEquals(listOf("高", "中", "低"), sorted.map { it.title })
	}

	@Test
	fun `条目太多时截断到上限`() {
		val many = (1..60).map { entry("条目$it", importance = 3) }

		val sorted = LintPrompts.lintCandidates(many)

		assertEquals(LintPrompts.MAX_ENTRIES_PER_LINT, sorted.size)
	}

	@Test
	fun `钉住的条目照样送去体检`() {
		val pinned = entry("钉住的", pinned = true, importance = 1)
		val normal = entry("普通的", importance = 5)

		val sorted = LintPrompts.lintCandidates(listOf(pinned, normal))

		assertEquals(2, sorted.size)
		assertTrue(sorted.any { it.title == "钉住的" })
	}
}
