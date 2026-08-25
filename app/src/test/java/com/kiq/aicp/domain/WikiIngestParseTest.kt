// app/src/test/java/com/kiq/aicp/domain/WikiIngestParseTest.kt
// wiki ingest 的提示词与解析测试。纯 JVM。
//
// 解析必须扛住模型的各种不听话：套代码块、JSON 前后加解释、字段缺失、分类写错。
// 卡片时代已经踩过一轮，条目化之后字段更多，能出错的地方也更多。
//
// 提示词那几条断言不是走形式：index 复用已有 title、正文要增补而不是重写、
// 矛盾要写进 conflict —— 这三句话是条目不裂成碎片的全部保障，
// 哪天有人"顺手精简一下提示词"把它们删了，这里会立刻红。

package com.kiq.aicp.domain

import com.kiq.aicp.domain.memory.CompressionPrompts
import com.kiq.aicp.domain.memory.IndexLine
import com.kiq.aicp.domain.memory.RelatedEntry
import com.kiq.aicp.domain.model.MemoryCardType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WikiIngestParseTest {

	// ---------------- 解析 ----------------

	@Test
	fun `标准输出能解析出摘要和条目`() {
		val raw = """
			{"summary":"用户在做安卓开发",
			 "entries":[{"category":"FACT","title":"职业","aliases":["工作"],
			 "oneLiner":"安卓开发","body":"在做安卓开发，写一个陪伴 App","importance":4,"conflict":null}]}
		""".trimIndent()

		val parsed = CompressionPrompts.parseWiki(raw)

		assertTrue(parsed.strict)
		assertEquals("用户在做安卓开发", parsed.summary)
		val entry = parsed.entries.single()
		assertEquals(MemoryCardType.FACT, entry.category)
		assertEquals("职业", entry.title)
		assertEquals(listOf("工作"), entry.aliases)
		assertEquals("安卓开发", entry.oneLiner)
		assertNull(entry.conflictNote)
	}

	@Test
	fun `套了 json 代码块也能解析`() {
		val raw = "```json\n{\"summary\":\"摘要\",\"entries\":[]}\n```"

		val parsed = CompressionPrompts.parseWiki(raw)

		assertTrue(parsed.strict)
		assertEquals("摘要", parsed.summary)
	}

	@Test
	fun `JSON 前后带解释文字也能掐出来`() {
		val raw = "好的，这是结果：\n{\"summary\":\"摘要\",\"entries\":[]}\n希望有帮助"

		assertEquals("摘要", CompressionPrompts.parseWiki(raw).summary)
	}

	@Test
	fun `压根不给 JSON 时整段当摘要并标记不严格`() {
		val raw = "用户今天聊了工作，情绪不错。"

		val parsed = CompressionPrompts.parseWiki(raw)

		assertFalse(parsed.strict)
		assertEquals(raw, parsed.summary)
		assertTrue("没结构的文本不能硬塞进条目", parsed.entries.isEmpty())
	}

	@Test
	fun `空回复不炸`() {
		val parsed = CompressionPrompts.parseWiki("   ")

		assertFalse(parsed.strict)
		assertTrue(parsed.entries.isEmpty())
	}

	@Test
	fun `分类写错的条目被丢掉，其余照收`() {
		val raw = """
			{"summary":"摘要","entries":[
			 {"category":"UNKNOWN","title":"甲","oneLiner":"x","body":"内容甲","importance":3},
			 {"category":"PREFERENCE","title":"乙","oneLiner":"y","body":"内容乙","importance":3}]}
		""".trimIndent()

		val entries = CompressionPrompts.parseWiki(raw).entries

		assertEquals(1, entries.size)
		assertEquals("乙", entries.single().title)
	}

	@Test
	fun `标题或正文为空的条目被丢掉`() {
		val raw = """
			{"summary":"摘要","entries":[
			 {"category":"FACT","title":"","oneLiner":"x","body":"有正文没标题","importance":3},
			 {"category":"FACT","title":"有标题没正文","oneLiner":"y","body":"","importance":3}]}
		""".trimIndent()

		assertTrue(CompressionPrompts.parseWiki(raw).entries.isEmpty())
	}

	@Test
	fun `oneLiner 缺失时用正文开头兜底`() {
		val raw = """
			{"summary":"摘要","entries":[
			 {"category":"FACT","title":"职业","body":"在做安卓开发","importance":3}]}
		""".trimIndent()

		assertEquals("在做安卓开发", CompressionPrompts.parseWiki(raw).entries.single().oneLiner)
	}

	@Test
	fun `重要度超范围会被夹回 1 到 5`() {
		val raw = """
			{"summary":"摘要","entries":[
			 {"category":"FACT","title":"甲","oneLiner":"x","body":"内容","importance":99},
			 {"category":"PREFERENCE","title":"乙","oneLiner":"y","body":"内容","importance":-3}]}
		""".trimIndent()

		val entries = CompressionPrompts.parseWiki(raw).entries.associateBy { it.title }

		assertEquals(5, entries["甲"]!!.importance)
		assertEquals(1, entries["乙"]!!.importance)
	}

	@Test
	fun `同分类同标题的重复条目只保留第一个`() {
		val raw = """
			{"summary":"摘要","entries":[
			 {"category":"FACT","title":"职业","oneLiner":"x","body":"第一份","importance":3},
			 {"category":"FACT","title":"职业","oneLiner":"y","body":"第二份","importance":3}]}
		""".trimIndent()

		val entries = CompressionPrompts.parseWiki(raw).entries

		assertEquals(1, entries.size)
		assertEquals("第一份", entries.single().body)
	}

	@Test
	fun `跟标题重复的别名被剔掉`() {
		val raw = """
			{"summary":"摘要","entries":[
			 {"category":"FACT","title":"职业","aliases":["职业","工作"],
			  "oneLiner":"x","body":"内容","importance":3}]}
		""".trimIndent()

		assertEquals(listOf("工作"), CompressionPrompts.parseWiki(raw).entries.single().aliases)
	}

	@Test
	fun `矛盾字段被保留下来`() {
		val raw = """
			{"summary":"摘要","entries":[
			 {"category":"FACT","title":"职业","oneLiner":"x","body":"内容","importance":3,
			  "conflict":"之前记的是前端，这次说是安卓"}]}
		""".trimIndent()

		assertEquals(
			"之前记的是前端，这次说是安卓",
			CompressionPrompts.parseWiki(raw).entries.single().conflictNote,
		)
	}

	// ---------------- 提示词 ----------------

	@Test
	fun `系统提示词里保留着防碎片的三条硬要求`() {
		val system = CompressionPrompts.wikiIngestSystem()

		assertTrue("必须要求复用已有 title", system.contains("复用清单里那个 title"))
		assertTrue("必须要求增补而不是重写", system.contains("增补改写"))
		assertTrue("必须要求标注矛盾", system.contains("conflict"))
	}

	@Test
	fun `用户写的记忆规则会被注入且声明优先级更高`() {
		val system = CompressionPrompts.wikiIngestSystem("重点记我的健康数据")

		assertTrue(system.contains("重点记我的健康数据"))
		assertTrue(system.contains("以这里为准"))
	}

	@Test
	fun `没写记忆规则时不注入那一段`() {
		assertFalse(CompressionPrompts.wikiIngestSystem("").contains("以这里为准"))
		assertFalse(CompressionPrompts.wikiIngestSystem("   ").contains("以这里为准"))
	}

	@Test
	fun `用户消息里 index 只带标题和摘要，相关条目才带正文`() {
		val user = CompressionPrompts.wikiIngestUser(
			index = listOf(IndexLine(MemoryCardType.FACT, "职业", "工作", "在做安卓开发")),
			related = listOf(RelatedEntry(MemoryCardType.PREFERENCE, "咖啡", "只喝手冲，不加糖")),
			transcript = "用户：今天喝了咖啡",
		)

		assertTrue(user.contains("已有条目清单"))
		assertTrue(user.contains("职业"))
		assertTrue("别名要一起给，模型才对得上号", user.contains("工作"))
		assertTrue(user.contains("现有正文"))
		assertTrue(user.contains("只喝手冲，不加糖"))
		assertTrue(user.contains("用户：今天喝了咖啡"))
	}

	@Test
	fun `没有已有条目时不写那两段标题，省 token`() {
		val user = CompressionPrompts.wikiIngestUser(
			index = emptyList(),
			related = emptyList(),
			transcript = "用户：在吗",
		)

		assertFalse(user.contains("已有条目清单"))
		assertFalse(user.contains("现有正文"))
		assertTrue(user.contains("用户：在吗"))
	}
}
