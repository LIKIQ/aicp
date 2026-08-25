// app/src/test/java/com/kiq/aicp/domain/StickerVisionPolicyTest.kt
// 后台识图跑完之后该收工还是该退避重来。纯 JVM。
//
// 这个判断单拎出来测是因为它写错了用户是真会掉电的：
// 没配视觉模型的人如果被判成 retry，WorkManager 会带着指数退避一直重排这个任务，
// 而每次醒来的结果都一模一样。Worker 那几行编排不值得为它引 WorkManager 测试库，
// 但这段判断值得。

package com.kiq.aicp.domain

import com.kiq.aicp.domain.sticker.StickerVisionNext
import com.kiq.aicp.domain.sticker.StickerVisionPolicy
import com.kiq.aicp.domain.sticker.StickerVisionReport
import org.junit.Assert.assertEquals
import org.junit.Test

class StickerVisionPolicyTest {

	@Test
	fun `视觉模型没配置时收工，绝不能重试`() {
		val report = StickerVisionReport(
			total = 12,
			ok = 0,
			failed = 12,
			reasons = listOf("还没配置视觉模型"),
			notConfigured = true,
		)

		assertEquals(
			"没配模型还 retry 的话，WorkManager 会带着退避无限重排，纯耗电池",
			StickerVisionNext.DONE,
			StickerVisionPolicy.next(report),
		)
	}

	@Test
	fun `没配模型这件事优先于失败张数，即使全批失败也不重试`() {
		// retryable 被莫名填成非 0 时也不许翻盘：notConfigured 是最硬的那条
		val report = StickerVisionReport(
			total = 3,
			ok = 0,
			failed = 3,
			reasons = listOf("还没配置视觉模型"),
			retryable = 3,
			notConfigured = true,
		)

		assertEquals(StickerVisionNext.DONE, StickerVisionPolicy.next(report))
	}

	@Test
	fun `网络类失败值得退避后重来`() {
		val report = StickerVisionReport(
			total = 5,
			ok = 0,
			failed = 5,
			reasons = listOf("网络连不上"),
			retryable = 5,
		)

		assertEquals(StickerVisionNext.RETRY, StickerVisionPolicy.next(report))
	}

	@Test
	fun `跑成了一部分但剩下的是网络失败，仍然重来`() {
		val report = StickerVisionReport(
			total = 5,
			ok = 3,
			failed = 2,
			reasons = listOf("请求超时"),
			retryable = 2,
		)

		assertEquals(StickerVisionNext.RETRY, StickerVisionPolicy.next(report))
	}

	@Test
	fun `认不出和图读不出来这类失败直接收工`() {
		// 这两种重试一百次也是同样结果，那几张 emotion 留空，下次触发会再排到它
		val report = StickerVisionReport(
			total = 4,
			ok = 2,
			failed = 2,
			reasons = listOf("模型没给出词表里的情绪", "这张图读不出来"),
			retryable = 0,
		)

		assertEquals(StickerVisionNext.DONE, StickerVisionPolicy.next(report))
	}

	@Test
	fun `全部识别成功当然收工`() {
		val report = StickerVisionReport(total = 6, ok = 6, failed = 0, reasons = emptyList())

		assertEquals(StickerVisionNext.DONE, StickerVisionPolicy.next(report))
	}

	@Test
	fun `没图要识别时收工，不要空转出一个重试`() {
		val report = StickerVisionReport(total = 0, ok = 0, failed = 0, reasons = emptyList())

		assertEquals(StickerVisionNext.DONE, StickerVisionPolicy.next(report))
	}
}
