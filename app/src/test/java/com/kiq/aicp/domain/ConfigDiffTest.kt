// app/src/test/java/com/kiq/aicp/domain/ConfigDiffTest.kt
// 配置码导入前差异预览的测试。纯 JVM。
//
// 这一层的作用是让用户在覆盖前看清哪几项会变，所以两件事必须成立：
// 1. 没变的项不能出现在清单里（噪音一多用户就不看了，那预览就白做）
// 2. 变了的项一项都不能漏（漏报比误报危险：他以为不会变的东西被悄悄改了）
//
// 最后那条字段数比对钉的是第 2 点：给 AicpSettings 加了设置项却忘了往比对清单里补一行，
// 预览会永远漏报那一项，而这种漏是静默的。

package com.kiq.aicp.domain

import com.kiq.aicp.domain.config.ConfigDiff
import com.kiq.aicp.domain.model.AicpSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigDiffTest {

	private val base = AicpSettings(
		baseUrl = "https://a.example.com/v1",
		model = "gpt-4o",
		humanizeEnabled = true,
		humanizeMsPerChar = 55,
		proactiveDailyLimit = 3,
	)

	@Test
	fun `一模一样时清单是空的`() {
		assertTrue(ConfigDiff.describe(base, base.copy()).isEmpty())
	}

	@Test
	fun `只列出真的变了的项`() {
		val incoming = base.copy(model = "claude-opus-4")

		val lines = ConfigDiff.describe(base, incoming)

		assertEquals(1, lines.size)
		assertEquals("主模型：gpt-4o → claude-opus-4", lines[0])
	}

	@Test
	fun `开关变化说成开和关而不是 true false`() {
		val lines = ConfigDiff.describe(base, base.copy(humanizeEnabled = false))

		assertEquals("真人模拟：开 → 关", lines[0])
	}

	@Test
	fun `空值说成人话而不是留白`() {
		val lines = ConfigDiff.describe(base.copy(baseUrl = ""), base)

		assertEquals("接口地址：未填 → https://a.example.com/v1", lines[0])
	}

	@Test
	fun `跟随主模型的空值不说成未填`() {
		// compressModel 留空的语义是"跟随主模型"，说"未填"会让人以为坏了
		val lines = ConfigDiff.describe(base, base.copy(compressModel = "gpt-4o-mini"))

		assertEquals("压缩模型：跟随主模型 → gpt-4o-mini", lines[0])
	}

	@Test
	fun `多项变化按清单顺序给出`() {
		val incoming = base.copy(
			model = "claude-opus-4",
			humanizeMsPerChar = 90,
			proactiveDailyLimit = 8,
		)

		val lines = ConfigDiff.describe(base, incoming)

		assertEquals(3, lines.size)
		assertTrue(lines[0].startsWith("主模型："))
		assertTrue(lines.any { it == "每字打字耗时：55 毫秒 → 90 毫秒" })
		assertTrue(lines.any { it == "每天最多搭话：3 次 → 8 次" })
	}

	@Test
	fun `记忆规则太长时预览截断`() {
		val long = "规".repeat(100)

		val lines = ConfigDiff.describe(base, base.copy(memorySchema = long))

		assertEquals(1, lines.size)
		// 20 字够看出是哪段规则，整段 600 字塞进确认框会把变化清单挤没
		assertTrue(lines[0].length < 60)
	}

	@Test
	fun `apiKey 不出现在预览文案里`() {
		val incoming = base.copy(apiKey = "sk-super-secret")

		val lines = ConfigDiff.describe(base, incoming)

		assertTrue(lines.none { it.contains("sk-super-secret") })
		assertTrue(ConfigDiff.overwritesApiKey(incoming))
	}

	@Test
	fun `没带 Key 的配置码不会覆盖现有 Key`() {
		assertFalse(ConfigDiff.overwritesApiKey(base.copy(apiKey = "")))
		assertFalse(ConfigDiff.overwritesApiKey(base.copy(apiKey = "   ")))
	}

	@Test
	fun `比对清单必须覆盖除 apiKey 之外的每一项设置`() {
		val settingsFields = instanceFieldsOf(AicpSettings::class.java) - "apiKey"

		assertEquals(
			"设置项和比对清单数量不一致：给 AicpSettings 加字段时 ConfigDiff.fields 也要补一行，" +
				"否则导入预览会永远漏报那一项。当前设置项 ${settingsFields.size} 个",
			settingsFields.size,
			ConfigDiff.fieldCount,
		)
	}
}
