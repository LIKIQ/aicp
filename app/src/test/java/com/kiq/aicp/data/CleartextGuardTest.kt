// app/src/test/java/com/kiq/aicp/data/CleartextGuardTest.kt
// 明文放行规则的测试。这里的每一条都对应一个真实风险：
// 放宽了会把 API Key 明文发到公网，收紧了连不上局域网自建服务。
// 最要紧的是那几条"伪装成私网的域名"用例 —— 单纯 startsWith("192.168.") 会直接被绕过。

package com.kiq.aicp.data

import com.kiq.aicp.data.remote.CleartextGuard
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CleartextGuardTest {

	@Test
	fun `https 一律放行`() {
		assertTrue(CleartextGuard.isAllowed("https", "api.deepseek.com"))
		assertTrue(CleartextGuard.isAllowed("HTTPS", "api.openai.com"))
		assertTrue(CleartextGuard.isAllowed("https", "8.8.8.8"))
	}

	@Test
	fun `公网 http 一律拒绝`() {
		assertFalse(CleartextGuard.isAllowed("http", "api.deepseek.com"))
		assertFalse(CleartextGuard.isAllowed("http", "8.8.8.8"))
		assertFalse(CleartextGuard.isAllowed("http", "203.0.113.7"))
	}

	@Test
	fun `本机和模拟器宿主放行明文`() {
		listOf("localhost", "127.0.0.1", "::1", "[::1]", "10.0.2.2").forEach {
			assertTrue("应放行 $it", CleartextGuard.isAllowed("http", it))
		}
	}

	@Test
	fun `三段私有网段放行明文`() {
		listOf("10.1.2.3", "192.168.1.7", "172.16.0.1", "172.31.255.254", "169.254.1.1").forEach {
			assertTrue("应放行 $it", CleartextGuard.isAllowed("http", it))
		}
	}

	@Test
	fun `172 网段只放行 16 到 31`() {
		assertFalse(CleartextGuard.isAllowed("http", "172.15.0.1"))
		assertTrue(CleartextGuard.isAllowed("http", "172.16.0.1"))
		assertTrue(CleartextGuard.isAllowed("http", "172.31.0.1"))
		assertFalse(CleartextGuard.isAllowed("http", "172.32.0.1"))
	}

	@Test
	fun `伪装成私网前缀的域名不能放行`() {
		listOf(
			"192.168.1.1.evil.com",
			"10.0.0.1.attacker.net",
			"127.0.0.1.example.org",
			"localhost.evil.com",
			"notlocalhost",
			"local.evil.com",
		).forEach {
			assertFalse("不该放行 $it", CleartextGuard.isAllowed("http", it))
		}
	}

	@Test
	fun `越界或非十进制写法不算合法私网 IP`() {
		listOf("999.999.999.999", "256.1.1.1", "0177.0.0.1", "10.0.0", "10.0.0.1.2", "").forEach {
			assertFalse("不该放行 $it", CleartextGuard.isAllowed("http", it))
		}
	}

	@Test
	fun `mDNS 名字放行明文`() {
		assertTrue(CleartextGuard.isAllowed("http", "nas.local"))
		assertTrue(CleartextGuard.isAllowed("http", "local"))
		assertTrue(CleartextGuard.isAllowed("http", "my-mini-pc.LOCAL"))
	}

	@Test
	fun `既不是 http 也不是 https 的协议一律拒绝`() {
		assertFalse(CleartextGuard.isAllowed("ws", "127.0.0.1"))
		assertFalse(CleartextGuard.isAllowed("file", "localhost"))
		assertFalse(CleartextGuard.isAllowed("", "localhost"))
	}

	@Test
	fun `大小写和首尾空格不影响判定`() {
		assertTrue(CleartextGuard.isAllowed("HtTp", "  LocalHost  "))
		assertTrue(CleartextGuard.isAllowed("http", " 192.168.0.2 "))
	}

	@Test
	fun `拒绝原因文案带上目标主机但不带完整地址`() {
		val reason = CleartextGuard.rejectReason("http", "api.evil.com")
		assertTrue(reason.contains("api.evil.com"))
		assertTrue(reason.contains("https"))
		assertTrue(CleartextGuard.rejectReason("https", "api.deepseek.com").isEmpty())
		assertTrue(CleartextGuard.rejectReason("ws", "x").contains("不支持的协议"))
	}
}
