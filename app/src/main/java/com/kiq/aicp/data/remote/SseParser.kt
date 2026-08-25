// app/src/main/java/com/kiq/aicp/data/remote/SseParser.kt
// Server-Sent Events 的增量行解析器。
//
// 为什么手写而不用 okhttp-sse：
// OpenAI 那套流并不完全守 SSE 规范（有的服务商不发空行、有的塞 ": keep-alive" 心跳、
// 有的把 [DONE] 写成 data: [DONE] 之后还跟一个空事件），自己解析才好逐条兜住这些差异，
// 而且纯函数能被单测覆盖到每种畸形输入。
//
// 用法：逐行 feedLine()，拿到非 null 事件就处理；流结束后调一次 flush() 收尾。

package com.kiq.aicp.data.remote

class SseParser {

	sealed interface Event {
		/** 一个完整事件的 data 载荷（多行 data 已按规范用 \n 拼好） */
		data class Data(val payload: String) : Event

		/** 收到 [DONE] 终止标记 */
		data object Done : Event
	}

	private val buffer = StringBuilder()

	fun feedLine(rawLine: String): Event? {
		// OkHttp 按 \n 切行，Windows 风格的服务会留下 \r
		val line = rawLine.removeSuffix("\r")

		// 空行 = 事件边界
		if (line.isEmpty()) return emit()

		// 以冒号开头的是注释/心跳，比如 ": keep-alive"
		if (line.startsWith(":")) return null

		val colon = line.indexOf(':')
		val field: String
		val rawValue: String
		if (colon < 0) {
			// 只有字段名没有值，规范里等价于空值
			field = line
			rawValue = ""
		} else {
			field = line.substring(0, colon)
			rawValue = line.substring(colon + 1)
		}
		// 规范：冒号后紧跟的一个空格要去掉，多余的空格保留
		val value = rawValue.removePrefix(" ")

		if (field == "data") {
			if (buffer.isNotEmpty()) buffer.append('\n')
			buffer.append(value)
			// 有些服务不发空行分隔，见到 [DONE] 就直接收
			if (value.trim() == DONE_TOKEN) return emit()
		}
		// event / id / retry 这几个字段对我们没用，忽略
		return null
	}

	/** 流读完了，把没被空行收尾的最后一段吐出来 */
	fun flush(): Event? = emit()

	private fun emit(): Event? {
		if (buffer.isEmpty()) return null
		val payload = buffer.toString()
		buffer.clear()
		return if (payload.trim() == DONE_TOKEN) Event.Done else Event.Data(payload)
	}

	companion object {
		private const val DONE_TOKEN = "[DONE]"
	}
}
