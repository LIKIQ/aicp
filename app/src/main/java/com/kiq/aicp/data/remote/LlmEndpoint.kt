// app/src/main/java/com/kiq/aicp/data/remote/LlmEndpoint.kt
// Base URL 的归一化。
//
// 用户填 Base URL 时的写法千奇百怪，实测常见这几种都得认：
//   https://api.deepseek.com
//   https://api.deepseek.com/
//   https://api.deepseek.com/v1
//   https://api.deepseek.com/v1/chat/completions
//   http://192.168.1.7:11434/v1        （ollama 兼容层）
// 归一化到 .../chat/completions，避免出现 /v1/v1/ 这种拼接事故。

package com.kiq.aicp.data.remote

object LlmEndpoint {

	private const val CHAT_PATH = "chat/completions"
	private const val V1 = "v1"

	/** 拼出 chat/completions 的完整地址。baseUrl 为空时返回空串，由调用方报 NO_CONFIG */
	fun chatCompletions(baseUrl: String): String {
		val base = baseUrl.trim().trimEnd('/')
		if (base.isEmpty()) return ""

		return when {
			base.endsWith("/$CHAT_PATH") -> base
			base.substringAfterLast('/') == V1 -> "$base/$CHAT_PATH"
			// 有的中转站把版本段写成 /v1beta、/openai/v1 之类，只要末段像版本号就不再补 v1
			looksLikeVersionSegment(base.substringAfterLast('/')) -> "$base/$CHAT_PATH"
			else -> "$base/$V1/$CHAT_PATH"
		}
	}

	/** 只取 scheme 和 host，给 CleartextGuard 判定用；解析不出来时返回 null */
	fun schemeAndHost(url: String): Pair<String, String>? {
		val trimmed = url.trim()
		val schemeEnd = trimmed.indexOf("://")
		if (schemeEnd <= 0) return null
		val scheme = trimmed.substring(0, schemeEnd)
		val rest = trimmed.substring(schemeEnd + 3)
		if (rest.isEmpty()) return null

		val authority = rest.substringBefore('/').substringBefore('?')
		if (authority.isEmpty()) return null

		// 去掉可能存在的 user:pass@ 与端口；IPv6 字面量用方括号包着，端口在方括号之后
		val hostPart = authority.substringAfterLast('@')
		val host = if (hostPart.startsWith("[")) {
			hostPart.substringBefore(']').removePrefix("[")
		} else {
			hostPart.substringBefore(':')
		}
		return if (host.isEmpty()) null else scheme to host
	}

	private fun looksLikeVersionSegment(segment: String): Boolean =
		segment.length in 2..12 && segment.startsWith("v") && segment[1].isDigit()
}
