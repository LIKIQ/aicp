// app/src/test/java/com/kiq/aicp/data/SseParserTest.kt
// SSE 解析器的测试，覆盖各家服务商实测见过的不规范写法：
// 不发空行分隔、塞 ": keep-alive" 心跳、CRLF 换行、流断在半句上。

package com.kiq.aicp.data

import com.kiq.aicp.data.remote.SseParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SseParserTest {

	private fun feedAll(parser: SseParser, raw: String): List<SseParser.Event> {
		val events = mutableListOf<SseParser.Event>()
		raw.split("\n").forEach { line ->
			parser.feedLine(line)?.let { events += it }
		}
		parser.flush()?.let { events += it }
		return events
	}

	@Test
	fun `标准事件在空行处收尾`() {
		val events = feedAll(SseParser(), "data: {\"a\":1}\n\ndata: {\"a\":2}\n\n")

		assertEquals(2, events.size)
		assertEquals("{\"a\":1}", (events[0] as SseParser.Event.Data).payload)
		assertEquals("{\"a\":2}", (events[1] as SseParser.Event.Data).payload)
	}

	@Test
	fun `多行 data 按换行拼成一个事件`() {
		val events = feedAll(SseParser(), "data: 第一行\ndata: 第二行\n\n")

		assertEquals(1, events.size)
		assertEquals("第一行\n第二行", (events.single() as SseParser.Event.Data).payload)
	}

	@Test
	fun `冒号开头的心跳被忽略`() {
		val parser = SseParser()
		assertNull(parser.feedLine(": keep-alive"))
		assertNull(parser.feedLine(":"))
		val events = feedAll(parser, "data: hi\n\n")
		assertEquals("hi", (events.single() as SseParser.Event.Data).payload)
	}

	@Test
	fun `DONE 不必等空行就产出终止事件`() {
		val parser = SseParser()
		assertEquals(SseParser.Event.Done, parser.feedLine("data: [DONE]"))
	}

	@Test
	fun `DONE 前后有空行也只出一个终止事件`() {
		val events = feedAll(SseParser(), "data: {\"a\":1}\n\ndata: [DONE]\n\n")

		assertEquals(2, events.size)
		assertEquals(SseParser.Event.Done, events[1])
	}

	@Test
	fun `CRLF 换行不会把回车留在载荷里`() {
		val events = feedAll(SseParser(), "data: {\"a\":1}\r\n\r\n")

		assertEquals("{\"a\":1}", (events.single() as SseParser.Event.Data).payload)
	}

	@Test
	fun `流没有空行收尾时靠 flush 补出最后一段`() {
		val parser = SseParser()
		assertNull(parser.feedLine("data: 最后一句"))
		val last = parser.flush()
		assertEquals("最后一句", (last as SseParser.Event.Data).payload)
		// flush 过一次就空了，再 flush 不该重复吐
		assertNull(parser.flush())
	}

	@Test
	fun `冒号后只吃掉一个空格，其余空格保留`() {
		val parser = SseParser()
		parser.feedLine("data:  两个空格开头")
		val event = parser.flush() as SseParser.Event.Data
		assertEquals(" 两个空格开头", event.payload)
	}

	@Test
	fun `没有冒号的字段行不产出事件`() {
		val parser = SseParser()
		assertNull(parser.feedLine("event"))
		assertNull(parser.flush())
	}

	@Test
	fun `连续空行不会产出空事件`() {
		val events = feedAll(SseParser(), "\n\n\n")
		assertEquals(0, events.size)
	}

	@Test
	fun `event 和 id 字段被忽略，只认 data`() {
		val events = feedAll(SseParser(), "event: message\nid: 42\ndata: 正文\n\n")

		assertEquals(1, events.size)
		assertEquals("正文", (events.single() as SseParser.Event.Data).payload)
	}
}
