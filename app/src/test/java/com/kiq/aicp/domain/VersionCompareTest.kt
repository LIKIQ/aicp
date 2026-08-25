// app/src/test/java/com/kiq/aicp/domain/VersionCompareTest.kt
// 版本号比较的边界测试。纯 JVM，不碰 Android。
//
// 这里守的是三类会让用户直接看见的错：
// - 字典序误判（0.10.0 vs 0.9.0）：一进两位数就会天天提示"有新版本"，而其实没有
// - 预发布方向搞反：装了正式版还被推去装 beta
// - tag 写得随意时乱提示：宁可漏提示，也不能一开 App 就弹一次

package com.kiq.aicp.domain

import com.kiq.aicp.domain.update.VersionCompare
import com.kiq.aicp.domain.update.VersionCompare.Order
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionCompareTest {

	@Test
	fun `远端 tag 更高时判为有新版本`() {
		assertEquals(Order.NEWER, VersionCompare.compare("v0.5.0", "0.4.0"))
		assertTrue(VersionCompare.isNewer("v0.5.0", "0.4.0"))
	}

	@Test
	fun `版本号相同时判为一样新`() {
		assertEquals(Order.SAME, VersionCompare.compare("v0.4.0", "0.4.0"))
		assertFalse(VersionCompare.isNewer("v0.4.0", "0.4.0"))
	}

	@Test
	fun `本地版本更高时判为本地更新，不提示`() {
		assertEquals(Order.OLDER, VersionCompare.compare("v0.3.0", "0.4.0"))
		assertFalse(VersionCompare.isNewer("v0.3.0", "0.4.0"))
	}

	@Test
	fun `两位数段按数值比而不是字典序`() {
		assertEquals(Order.NEWER, VersionCompare.compare("0.10.0", "0.9.0"))
		assertEquals(Order.OLDER, VersionCompare.compare("0.9.0", "0.10.0"))
		assertEquals(Order.NEWER, VersionCompare.compare("1.0.0", "0.99.99"))
		assertEquals(Order.NEWER, VersionCompare.compare("0.4.10", "0.4.9"))
	}

	@Test
	fun `段数不一样时短的一边补零`() {
		assertEquals(Order.SAME, VersionCompare.compare("1.0", "1.0.0"))
		assertEquals(Order.SAME, VersionCompare.compare("1.0.0", "1.0"))
		assertEquals(Order.SAME, VersionCompare.compare("1", "1.0.0"))
		assertEquals(Order.NEWER, VersionCompare.compare("1.0.1", "1.0"))
		assertEquals(Order.NEWER, VersionCompare.compare("2.0", "1.9.9"))
		assertEquals(Order.OLDER, VersionCompare.compare("1.0", "1.0.1"))
	}

	@Test
	fun `多出来的第四段也参与比较`() {
		assertEquals(Order.NEWER, VersionCompare.compare("1.2.3.4", "1.2.3"))
		assertEquals(Order.OLDER, VersionCompare.compare("1.2.3", "1.2.3.4"))
	}

	@Test
	fun `大小写 v 前缀和两侧空格都不影响比较`() {
		assertEquals(Order.SAME, VersionCompare.compare("v1.2.3", "1.2.3"))
		assertEquals(Order.SAME, VersionCompare.compare("V1.2.3", "1.2.3"))
		assertEquals(Order.SAME, VersionCompare.compare("  v1.2.3  ", "1.2.3"))
		assertEquals(Order.NEWER, VersionCompare.compare("v1.2.4", "v1.2.3"))
	}

	@Test
	fun `预发布后缀排在同号正式版之前`() {
		assertEquals(Order.OLDER, VersionCompare.compare("1.0.0-beta", "1.0.0"))
		assertEquals(Order.OLDER, VersionCompare.compare("1.0.0rc1", "1.0.0"))
		assertEquals(Order.OLDER, VersionCompare.compare("v1.0.0-SNAPSHOT", "1.0.0"))
		// 本地装的是 beta，远端出了正式版，这时候该提示
		assertEquals(Order.NEWER, VersionCompare.compare("1.0.0", "1.0.0-beta"))
	}

	@Test
	fun `预发布版本自己更新时该提示的还是要提示`() {
		assertEquals(Order.NEWER, VersionCompare.compare("1.0.1-beta", "1.0.0"))
		assertEquals(Order.NEWER, VersionCompare.compare("1.0.0-beta", "0.9.9"))
		assertFalse(VersionCompare.isNewer("1.0.0-beta", "1.0.0"))
	}

	@Test
	fun `两边同一段都是预发布时不猜谁大`() {
		// 各家 alpha beta rc 的排法都不一样，猜错方向不如当作相等——结果是不提示
		assertEquals(Order.SAME, VersionCompare.compare("1.0.0-beta", "1.0.0-alpha"))
		assertEquals(Order.SAME, VersionCompare.compare("1.0.0-beta.2", "1.0.0-beta.1"))
	}

	@Test
	fun `段里压根没有数字时排在正式版之前`() {
		assertEquals(Order.OLDER, VersionCompare.compare("1.beta", "1.0.0"))
		assertEquals(Order.NEWER, VersionCompare.compare("1.0.0", "1.beta"))
	}

	@Test
	fun `认不出来的输入一律返回 UNKNOWN`() {
		listOf(null, "", "   ", "v", "latest", "nightly", "release-1.0", "发布版", "-1.0").forEach {
			assertEquals("认不出的 tag：$it", Order.UNKNOWN, VersionCompare.compare(it, "0.4.0"))
		}
		// 本地版本读不出来也一样是 UNKNOWN，不能只查远端那一边
		assertEquals(Order.UNKNOWN, VersionCompare.compare("0.5.0", ""))
		assertEquals(Order.UNKNOWN, VersionCompare.compare("0.5.0", null))
	}

	@Test
	fun `无法判断时按不提示更新处理`() {
		assertFalse(VersionCompare.isNewer("latest", "0.4.0"))
		assertFalse(VersionCompare.isNewer(null, "0.4.0"))
		assertFalse(VersionCompare.isNewer("0.9.9", "认不出来的本地版本"))
	}

	@Test
	fun `位数离谱的段当认不出来处理，不许悄悄溢出`() {
		assertEquals(Order.UNKNOWN, VersionCompare.compare("99999999999999999999.0", "0.4.0"))
		assertFalse(VersionCompare.isNewer("99999999999999999999.0", "0.4.0"))
	}

	@Test
	fun `尾随点和空段按补零处理`() {
		assertEquals(Order.SAME, VersionCompare.compare("1.0.", "1.0.0"))
		assertEquals(Order.SAME, VersionCompare.compare("1..0", "1.0.0"))
	}
}
