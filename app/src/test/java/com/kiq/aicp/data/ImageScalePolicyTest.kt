// app/src/test/java/com/kiq/aicp/data/ImageScalePolicyTest.kt
// 图片预处理的算术策略测试。
// 这几个数字直接决定上传流量和识别效果：缩太狠小字就糊，缩不够纯烧流量还可能顶爆 body 上限。

package com.kiq.aicp.data

import com.kiq.aicp.data.attach.ImageScalePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageScalePolicyTest {

	@Test
	fun `长边没超限时不做任何缩放`() {
		assertEquals(1, ImageScalePolicy.sampleSizeFor(800, 600, maxEdge = 1024))
		assertEquals(800 to 600, ImageScalePolicy.targetSizeFor(800, 600, maxEdge = 1024))
	}

	@Test
	fun `sampleSize 只取 2 的幂，且解出来不会小于目标尺寸`() {
		// 4000 / 2 = 2000 >= 1024 可以再降；4000 / 4 = 1000 < 1024 就不能了
		assertEquals(2, ImageScalePolicy.sampleSizeFor(4000, 3000, maxEdge = 1024))
		// 正好两倍：降一档后刚好等于目标
		assertEquals(2, ImageScalePolicy.sampleSizeFor(2048, 1024, maxEdge = 1024))
		// 八千多的长边可以降三档
		assertEquals(8, ImageScalePolicy.sampleSizeFor(8192, 8192, maxEdge = 1024))
	}

	@Test
	fun `sampleSize 对异常输入不会返回 0 或负数`() {
		assertEquals(1, ImageScalePolicy.sampleSizeFor(0, 0, maxEdge = 1024))
		assertEquals(1, ImageScalePolicy.sampleSizeFor(-5, 100, maxEdge = 1024))
		assertEquals(1, ImageScalePolicy.sampleSizeFor(4000, 3000, maxEdge = 0))
	}

	@Test
	fun `targetSize 等比缩放且长边正好落在上限`() {
		val (w, h) = ImageScalePolicy.targetSizeFor(4000, 3000, maxEdge = 1024)
		assertEquals(1024, w)
		assertEquals(768, h)

		val (w2, h2) = ImageScalePolicy.targetSizeFor(1080, 1920, maxEdge = 1024)
		assertEquals(1024, h2)
		assertTrue("宽应该按比例缩到 576 左右，实际 $w2", w2 in 570..580)
	}

	@Test
	fun `极端长条图的短边至少保留 1 像素`() {
		val (w, h) = ImageScalePolicy.targetSizeFor(10_000, 3, maxEdge = 1024)
		assertEquals(1024, w)
		assertTrue("短边不能变成 0，实际 $h", h >= 1)
	}

	@Test
	fun `带文字的截图走更大长边和更高质量`() {
		assertEquals(1024, ImageScalePolicy.maxEdgeFor(textHeavy = false))
		assertEquals(1568, ImageScalePolicy.maxEdgeFor(textHeavy = true))
		assertEquals(82, ImageScalePolicy.qualityFor(textHeavy = false))
		assertEquals(90, ImageScalePolicy.qualityFor(textHeavy = true))
	}

	@Test
	fun `质量逐档递降，到底之后返回 null 让调用方报错`() {
		assertEquals(70, ImageScalePolicy.nextQuality(90))
		assertEquals(70, ImageScalePolicy.nextQuality(82))
		assertEquals(55, ImageScalePolicy.nextQuality(70))
		assertEquals(45, ImageScalePolicy.nextQuality(55))
		assertNull(ImageScalePolicy.nextQuality(45))
		assertNull(ImageScalePolicy.nextQuality(30))
	}
}
