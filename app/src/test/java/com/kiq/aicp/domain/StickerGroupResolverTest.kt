// app/src/test/java/com/kiq/aicp/domain/StickerGroupResolverTest.kt
// [情绪] → [具体标记] 的替换。纯 JVM。
//
// 这一层最要紧的性质是幂等：流式回复里同一段文本会被 resolve 几十遍，
// 换过的部分必须钉死不动，否则用户会看着一条消息里的表情不停变脸。
// 所以下面用"每次调用都返回不同标记"的 pick 来测，真有重复替换会立刻现形。

package com.kiq.aicp.domain

import com.kiq.aicp.domain.sticker.StickerGroupResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StickerGroupResolverTest {

	private val emotions = setOf("开心", "无语", "伤心")

	/** 三张图分属两条来源：熊猫开心 在名为"开心"的组里，猫猫无语 是识图标出来的 */
	private val known = setOf("熊猫开心", "猫猫无语", "开心")

	private val pick: (String) -> String? = { emotion ->
		when (emotion) {
			"开心" -> "熊猫开心"
			"无语" -> "猫猫无语"
			else -> null
		}
	}

	@Test
	fun `情绪被换成具体标记`() {
		val out = StickerGroupResolver.resolve("今天真不错[开心]", emotions, setOf("熊猫开心"), pick)

		assertEquals("今天真不错[熊猫开心]", out)
	}

	@Test
	fun `已经是具体标记的不动`() {
		val text = "看这个[熊猫开心]"

		assertEquals(text, StickerGroupResolver.resolve(text, emotions, setOf("熊猫开心"), pick))
	}

	@Test
	fun `情绪跟某张图撞名时按标记处理，不再随机挑`() {
		// known 里有一张图的标记就叫"开心"，这时候 [开心] 是确定的那一张，不该被换掉
		val out = StickerGroupResolver.resolve("嗨[开心]", emotions, known, pick)

		assertEquals("嗨[开心]", out)
	}

	@Test
	fun `挑不到图时原样保留，不要吞掉这段文字`() {
		val out = StickerGroupResolver.resolve("好难过[伤心]", emotions, setOf("熊猫开心"), pick)

		assertEquals("好难过[伤心]", out)
	}

	@Test
	fun `一段里多个情绪各换各的`() {
		val out = StickerGroupResolver.resolve(
			"先[开心]后来[无语]",
			emotions,
			setOf("熊猫开心", "猫猫无语"),
			pick,
		)

		assertEquals("先[熊猫开心]后来[猫猫无语]", out)
	}

	@Test
	fun `同一个情绪出现两次会各挑一次，两处未必是同一张图`() {
		var seq = 0
		val out = StickerGroupResolver.resolve("[开心][开心]", emotions, emptySet()) { "图${++seq}" }

		assertEquals("[图1][图2]", out)
	}

	@Test
	fun `没有可用情绪或空文本时原样返回`() {
		assertEquals("[开心]", StickerGroupResolver.resolve("[开心]", emptySet(), known, pick))
		assertEquals("", StickerGroupResolver.resolve("", emotions, known, pick))
	}

	@Test
	fun `清单外的名字不碰`() {
		val out = StickerGroupResolver.resolve("这个[随便写的]呢", emotions, emptySet(), pick)

		assertEquals("这个[随便写的]呢", out)
	}

	@Test
	fun `markdown 链接不会被当成情绪`() {
		val text = "看[开心](https://example.com/a)"

		assertEquals(text, StickerGroupResolver.resolve(text, emotions, emptySet(), pick))
	}

	@Test
	fun `流式里反复 resolve，先前换好的不会再变`() {
		// 模拟增量累积：每个新片段进来都对全文跑一遍，pick 每次给不同答案
		var seq = 0
		val nextLabel: (String) -> String? = { "图${++seq}" }
		val chunks = listOf("今天", "真好[开", "心]", "，", "走吧")

		var accumulated = ""
		val snapshots = mutableListOf<String>()
		chunks.forEach { chunk ->
			accumulated = StickerGroupResolver.resolve(
				accumulated + chunk,
				emotions,
				// 已经换进去的标记这时候是"已知标记"，跟真实流程一致
				setOf("图1", "图2", "图3"),
				nextLabel,
			)
			snapshots += accumulated
		}

		assertEquals("今天真好[图1]，走吧", accumulated)
		// 只挑过一次图：多挑一次就说明重复替换了
		assertEquals(1, seq)
		assertTrue("换好之后每一帧都该带着同一个标记", snapshots.takeLast(3).all { it.contains("[图1]") })
	}

	@Test
	fun `hasEmotionMarker 只在真有活儿时返回 true`() {
		assertTrue(StickerGroupResolver.hasEmotionMarker("嗨[开心]", emotions, setOf("熊猫开心")))
		assertFalse("已经是标记了", StickerGroupResolver.hasEmotionMarker("嗨[熊猫开心]", emotions, known))
		assertFalse("撞名时按标记算", StickerGroupResolver.hasEmotionMarker("嗨[开心]", emotions, known))
		assertFalse(StickerGroupResolver.hasEmotionMarker("纯文字", emotions, known))
		assertFalse(StickerGroupResolver.hasEmotionMarker("[开心]", emptySet(), known))
	}

	@Test
	fun `emotionsIn 按出现顺序去重，只留还没换掉的`() {
		val found = StickerGroupResolver.emotionsIn(
			"[无语][开心][无语][熊猫开心][没这个]",
			emotions,
			setOf("熊猫开心"),
		)

		assertEquals(listOf("无语", "开心"), found)
	}

	@Test
	fun `emotionsIn 在没情绪可换时给空列表`() {
		assertTrue(StickerGroupResolver.emotionsIn("[熊猫开心]", emotions, known).isEmpty())
		assertTrue(StickerGroupResolver.emotionsIn("", emotions, known).isEmpty())
		assertTrue(StickerGroupResolver.emotionsIn("[开心]", emptySet(), known).isEmpty())
	}
}
