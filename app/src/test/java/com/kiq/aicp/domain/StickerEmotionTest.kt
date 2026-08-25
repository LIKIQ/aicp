// app/src/test/java/com/kiq/aicp/domain/StickerEmotionTest.kt
// 情绪词表的判断与模型回复解析。纯 JVM，不碰 Android。
//
// 守两处最容易出问题的地方：
// - 组名识别的"包含匹配"。用户建组习惯叫"开心的图""伤心表情包"，
//   这几种必须被认成情绪，否则整组会被当成未分类，白白让他再跑一次识图
// - parseReply 的宽容度。识图模型不一定听话，回一整句是常态，
//   但也不能宽到把不含任何情绪词的回答硬凑一个答案出来

package com.kiq.aicp.domain

import com.kiq.aicp.domain.sticker.StickerEmotion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StickerEmotionTest {

	@Test
	fun `词表里的词才算情绪`() {
		assertTrue(StickerEmotion.isEmotion("开心"))
		assertTrue(StickerEmotion.isEmotion("无语"))
		assertFalse(StickerEmotion.isEmotion("超开心"))
		assertFalse(StickerEmotion.isEmotion("我的收藏"))
		assertFalse(StickerEmotion.isEmotion(""))
	}

	@Test
	fun `判断前先去空格，免得识图回复带空白就判失败`() {
		assertTrue(StickerEmotion.isEmotion("  开心 "))
		assertTrue(StickerEmotion.isEmotion("开心\n"))
	}

	@Test
	fun `组名精确等于情绪时直接命中`() {
		assertEquals("开心", StickerEmotion.emotionOf("开心"))
		assertEquals("伤心", StickerEmotion.emotionOf("  伤心  "))
	}

	@Test
	fun `组名包含情绪也算，用户建组就爱这么起名`() {
		assertEquals("开心", StickerEmotion.emotionOf("开心的图"))
		assertEquals("伤心", StickerEmotion.emotionOf("伤心表情包"))
		assertEquals("生气", StickerEmotion.emotionOf("我的生气合集"))
	}

	@Test
	fun `组名跟情绪无关时返回 null，那种组要靠识图`() {
		assertNull(StickerEmotion.emotionOf("我的收藏"))
		assertNull(StickerEmotion.emotionOf("熊猫头"))
		assertNull(StickerEmotion.emotionOf(""))
		assertNull(StickerEmotion.emotionOf("   "))
	}

	@Test
	fun `组名同时撞上两个情绪时取词表里靠前的那个`() {
		// ALL 里"开心"在"哭"前面，顺序即优先级
		assertEquals("开心", StickerEmotion.emotionOf("开心到哭"))
	}

	@Test
	fun `模型回一个干净的词`() {
		assertEquals("开心", StickerEmotion.parseReply("开心"))
		assertEquals("无语", StickerEmotion.parseReply(" 无语 "))
	}

	@Test
	fun `模型带解释也要能抠出来`() {
		assertEquals("开心", StickerEmotion.parseReply("这张图表达的是开心的情绪"))
		assertEquals("伤心", StickerEmotion.parseReply("情绪：伤心。"))
		assertEquals("委屈", StickerEmotion.parseReply("我觉得是委屈"))
	}

	@Test
	fun `词表外的说法一律认不出，不许硬凑一个`() {
		assertNull(StickerEmotion.parseReply("略带忧郁的欣喜"))
		assertNull(StickerEmotion.parseReply("看不出来是什么"))
		assertNull(StickerEmotion.parseReply("这是一只猫"))
		assertNull(StickerEmotion.parseReply(""))
		assertNull(StickerEmotion.parseReply("   "))
	}

	@Test
	fun `识图提示词把全部候选词都列给模型，并禁止它造词`() {
		val system = StickerEmotion.visionSystem()

		StickerEmotion.ALL.forEach { assertTrue("词表里的 $it 没出现在提示词里", system.contains(it)) }
		assertTrue(system.contains("不要自己造词"))
	}

	@Test
	fun `聊天提示词要说清写的是情绪而不是图片名字`() {
		val prompt = StickerEmotion.promptFor(listOf("开心", "无语"))

		assertTrue(prompt.contains("[开心]"))
		assertTrue(prompt.contains("开心、无语"))
		assertTrue("必须点明填的是情绪不是图名", prompt.contains("不是图片名字"))
		assertTrue("要明确排除圆括号写法", prompt.contains("圆括号"))
		assertTrue("要告诉它哪张图不用它管", prompt.contains("自动挑"))
	}

	@Test
	fun `没有可用情绪时提示词为空，不给模型塞废话`() {
		assertEquals("", StickerEmotion.promptFor(emptyList()))
	}
}
