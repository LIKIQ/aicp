// app/src/test/java/com/kiq/aicp/domain/PersonaGeneratorTest.kt
// "一句话生成人设"的解析测试。
// 生成失败也必须给出能用的草稿 —— 用户已经等了几秒，弹个"解析失败"是最糟的结果，
// 所以兜底逻辑（整段回复当人设提示词）同样要有测试。

package com.kiq.aicp.domain

import com.kiq.aicp.domain.persona.PersonaGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonaGeneratorTest {

	@Test
	fun `标准 JSON 能解析出完整人设`() {
		val raw = """
			{"name":"小柚","avatarEmoji":"🍊","tagline":"傲娇但靠谱",
			 "systemPrompt":"你叫小柚，说话带刺但会把事办好。不要说客套话。",
			 "greeting":"哼，你终于来了。","temperature":0.95,"topP":0.9,"maxTokens":800}
		""".trimIndent()

		val p = PersonaGenerator.parse(raw, fallbackName = "描述")

		assertEquals("小柚", p.name)
		assertEquals("🍊", p.avatarEmoji)
		assertEquals("傲娇但靠谱", p.tagline)
		assertTrue(p.systemPrompt.contains("不要说客套话"))
		assertEquals("哼，你终于来了。", p.greeting)
		assertEquals(0.95f, p.temperature, 0.001f)
		assertEquals(800, p.maxTokens)
	}

	@Test
	fun `套代码块也能解析`() {
		val raw = "```json\n{\"name\":\"阿岩\",\"systemPrompt\":\"你叫阿岩，话少。不要解释太多。\"}\n```"

		val p = PersonaGenerator.parse(raw, fallbackName = "描述")

		assertEquals("阿岩", p.name)
		assertTrue(p.systemPrompt.contains("话少"))
	}

	@Test
	fun `JSON 前后有解释文字也能解析`() {
		val raw = "好的，这是人设：\n{\"name\":\"星野\",\"systemPrompt\":\"你叫星野。不要用敬语。\"}\n满意吗？"

		assertEquals("星野", PersonaGenerator.parse(raw, "描述").name)
	}

	@Test
	fun `缺字段时用默认值补齐`() {
		val p = PersonaGenerator.parse("{\"systemPrompt\":\"你叫某某。不要装懂。\"}", fallbackName = "一个话少的人")

		// name 缺失时用描述截前 6 字兜底
		assertEquals("一个话少的人", p.name)
		assertEquals("🙂", p.avatarEmoji)
		assertEquals(0.85f, p.temperature, 0.001f)
		assertEquals(0.95f, p.topP, 0.001f)
		assertEquals(1024, p.maxTokens)
	}

	@Test
	fun `完全不是 JSON 时整段当人设提示词`() {
		val raw = "你叫小林，说话慢条斯理，从不打断别人。不要给建议除非被问。"

		val p = PersonaGenerator.parse(raw, fallbackName = "慢性子")

		assertEquals(raw, p.systemPrompt)
		assertEquals("慢性子", p.name)
		assertEquals("🙂", p.avatarEmoji)
	}

	@Test
	fun `空回复也能兜出一个可用草稿`() {
		val p = PersonaGenerator.parse("", fallbackName = "")

		assertEquals("新角色", p.name)
		assertTrue(p.systemPrompt.isNotBlank())
	}

	@Test
	fun `emoji 字段给多个字符时只取第一个码点`() {
		val p = PersonaGenerator.parse(
			"{\"name\":\"某\",\"avatarEmoji\":\"🐱🐶abc\",\"systemPrompt\":\"你叫某。不要卖萌。\"}",
			fallbackName = "x",
		)

		// 猫脸是代理对，必须按码点取，按 Char 取会切出半个字符
		assertEquals("🐱", p.avatarEmoji)
	}

	@Test
	fun `越界的采样参数被夹回合法区间`() {
		val p = PersonaGenerator.parse(
			"{\"name\":\"某\",\"systemPrompt\":\"你叫某。不要跑题。\"," +
				"\"temperature\":9,\"topP\":5,\"maxTokens\":999999}",
			fallbackName = "x",
		)

		assertEquals(2f, p.temperature, 0.001f)
		assertEquals(1f, p.topP, 0.001f)
		assertEquals(8_192, p.maxTokens)
	}

	@Test
	fun `过长的名字和简介会被截断`() {
		val p = PersonaGenerator.parse(
			"{\"name\":\"${"名".repeat(40)}\",\"tagline\":\"${"简".repeat(80)}\"," +
				"\"systemPrompt\":\"你叫某。不要啰嗦。\"}",
			fallbackName = "x",
		)

		assertEquals(12, p.name.length)
		assertEquals(30, p.tagline.length)
	}

	@Test
	fun `提示词强制要求写禁忌并禁止提记忆机制`() {
		val system = PersonaGenerator.SYSTEM_PROMPT

		assertTrue(system.contains("禁忌"))
		assertTrue(system.contains("不要提记忆"))
		assertTrue(system.contains("temperature"))
	}
}
