// app/src/test/java/com/kiq/aicp/data/BuiltInEmojiPresetTest.kt
// 预设 emoji 表情清单的结构测试。纯 JVM（只碰清单，不碰 Canvas 渲染）。
//
// 渲染那部分要 Android 的 Paint/Bitmap，纯 JVM 跑不了，所以这里守的是清单本身：
// 数量、情绪覆盖、有没有重复。这三条错了的表现都是"装上之后 AI 少一类表情可发"，
// 而那种缺失在真机上要聊很久才可能碰到一次。

package com.kiq.aicp.data

import com.kiq.aicp.data.attach.BuiltInEmojiStickers
import com.kiq.aicp.domain.sticker.StickerEmotion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BuiltInEmojiPresetTest {

	@Test
	fun `预设一共 32 张`() {
		assertEquals(32, BuiltInEmojiStickers.PRESET_EMOJI_COUNT)
	}

	@Test
	fun `每个分组名都是词表里的情绪`() {
		val unknown = BuiltInEmojiStickers.PRESET_EMOTIONS
			.filterNot { StickerEmotion.isEmotion(it) }

		assertTrue("这些分组名不在情绪词表里，装上之后模型拿不到它们：$unknown", unknown.isEmpty())
	}

	@Test
	fun `情绪词表里的每一项都有预设表情`() {
		val missing = StickerEmotion.ALL - BuiltInEmojiStickers.PRESET_EMOTIONS.toSet()

		assertTrue("这些情绪没有预设表情，模型选了就发不出东西：$missing", missing.isEmpty())
	}

	@Test
	fun `分组名不重复`() {
		val names = BuiltInEmojiStickers.PRESET_EMOTIONS

		assertEquals("有重复的分组名，第二次 ensurePack 会并进同一组", names.size, names.toSet().size)
	}

	@Test
	fun `同一个 emoji 不出现在两个情绪里`() {
		val all = BuiltInEmojiStickers.PRESET.flatMap { it.second }

		assertEquals(
			"有 emoji 被复用到多个情绪，表情库里会出现两张一样的图：" +
				all.groupBy { it }.filterValues { it.size > 1 }.keys,
			all.size,
			all.toSet().size,
		)
	}

	@Test
	fun `每个情绪至少一张最多三张`() {
		val bad = BuiltInEmojiStickers.PRESET.filter { it.second.isEmpty() || it.second.size > 3 }

		assertTrue("这些情绪的张数不合理（空组或过多）：${bad.map { it.first }}", bad.isEmpty())
	}

	@Test
	fun `emoji 都不是空串也不含空白`() {
		val bad = BuiltInEmojiStickers.PRESET
			.flatMap { it.second }
			.filter { it.isBlank() || it.any { ch -> ch.isWhitespace() } }

		assertTrue("这些 emoji 有空白字符，渲染出来会是空图：$bad", bad.isEmpty())
	}
}
