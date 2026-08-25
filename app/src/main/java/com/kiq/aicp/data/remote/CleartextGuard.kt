// app/src/main/java/com/kiq/aicp/data/remote/CleartextGuard.kt
// 明文 HTTP 的守门人。
//
// 背景：KIQ 要求能连局域网里自建的 ollama / LM Studio（那些是 http），
// 但 Android 的 network-security-config 只认精确域名和精确 IP，写不出 192.168.* 这种网段规则。
// 所以系统层只能整体放开明文，真正的白名单落在这里 —— 好处是它是纯函数，能被单测完整覆盖。
//
// 规则：https 一律放行；http 只放行本机和 RFC1918 私有网段与 .local；其余一律拒绝。
// 拒绝的意义很实在：公网 http 会把 API Key 明文发出去。

package com.kiq.aicp.data.remote

object CleartextGuard {

	private val IPV4 = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$""")

	private val LOOPBACK_HOSTS = setOf("localhost", "127.0.0.1", "::1", "[::1]")

	/** Android 模拟器里访问宿主机的固定地址 */
	private const val EMULATOR_HOST = "10.0.2.2"

	fun isAllowed(scheme: String, host: String): Boolean {
		val s = scheme.lowercase()
		val h = host.lowercase().trim()
		return when (s) {
			"https" -> true
			"http" -> isLocalOrPrivate(h)
			else -> false
		}
	}

	fun isLocalOrPrivate(host: String): Boolean {
		val h = host.lowercase().trim()
		if (h.isEmpty()) return false
		if (h in LOOPBACK_HOSTS || h == EMULATOR_HOST) return true
		// mDNS 名字，家里的 NAS / 小主机常见
		if (h == "local" || h.endsWith(".local")) return true

		val m = IPV4.matchEntire(h) ?: return false
		val parts = m.groupValues.drop(1).map { it.toIntOrNull() ?: return false }
		if (parts.any { it > 255 }) return false

		val (a, b) = parts[0] to parts[1]
		return when {
			a == 10 -> true                       // 10.0.0.0/8
			a == 127 -> true                       // 环回
			a == 192 && b == 168 -> true           // 192.168.0.0/16
			a == 172 && b in 16..31 -> true        // 172.16.0.0/12
			a == 169 && b == 254 -> true           // 链路本地
			else -> false
		}
	}

	/** 给 UI 用的拒绝原因，别在提示里回显完整 URL（可能带 query 里的 key） */
	fun rejectReason(scheme: String, host: String): String = when (scheme.lowercase()) {
		"http" -> "出于安全考虑，http 只允许连本机或局域网地址（当前目标：$host）。公网接口请改用 https。"
		"https" -> ""
		else -> "不支持的协议：$scheme，只接受 http/https。"
	}
}
