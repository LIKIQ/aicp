// app/src/main/java/com/kiq/aicp/domain/websearch/RssParser.kt
// Bing RSS 搜索结果的解析器：一段 XML 进，一串 SearchHit 出。
//
// 为什么手写而不用 XmlPullParser / jsoup：
// 一是不想为了搜一次结果就往依赖表里加东西，二是 XmlPullParser 在普通 JVM 单测里跑不起来
// （android.util.Xml 是 stub），而这个解析器恰恰最需要被一堆畸形输入喂着测。
//
// 关键取舍：
// - 只扫 <item>...</item> 里的字段。<channel> 自己带一份 title/link/description，
//   <image> 块里还有一份，按标签名全局搜的写法会把"必应：今天天气"当成第一条结果。
// - Bing 的返回不一定规矩：字段顺序会变、值可能裹 CDATA、检索词会被 <b> 加粗、
//   连接断了还会给半截 XML。所以一律"能读多少算多少"，任何一步出岔子只影响当前这条。
// - 清洗顺序是 CDATA → 去内联标签 → 解实体 → 压空白。实体放在去标签后面解，
//   这样正文里写的 &lt;b&gt; 会老老实实变成文字"<b>"，而不是被当标签抹掉。

package com.kiq.aicp.domain.websearch

object RssParser {

	/** 实体名再长也就 nbsp 这种量级，超了就当普通 & 字符，免得把半段正文吞进去 */
	private const val MAX_ENTITY_LEN = 12

	private val NAMED_ENTITIES = mapOf(
		"amp" to "&",
		"lt" to "<",
		"gt" to ">",
		"quot" to "\"",
		"apos" to "'",
		// 不换成 \u00a0，那玩意儿不算 \s，压不掉也没法 trim
		"nbsp" to " ",
	)

	private val WHITESPACE = Regex("[\\s\\u00a0\\u3000]+")

	/**
	 * @param limit 只取前 N 条，<= 0 直接返回空
	 */
	fun parse(xml: String, limit: Int = Int.MAX_VALUE): List<SearchHit> {
		if (limit <= 0 || xml.isBlank()) return emptyList()

		val hits = mutableListOf<SearchHit>()
		var cursor = 0
		while (hits.size < limit) {
			val slice = nextItem(xml, cursor) ?: break
			cursor = slice.next
			toHit(slice.body)?.let { hits += it }
		}
		return hits
	}

	private class ItemSlice(val body: String, val next: Int)

	/** 从 [from] 往后找下一个 item 的内容。找不到（含 XML 断在中途）返回 null */
	private fun nextItem(xml: String, from: Int): ItemSlice? {
		var search = from
		while (true) {
			val open = xml.indexOf("<item", search)
			if (open < 0) return null
			val nameEnd = open + "<item".length
			if (nameEnd >= xml.length) return null
			// <item/> 空节点、<items> 之类同名前缀，都从这儿滑过去
			if (!isTagNameEnd(xml[nameEnd])) {
				search = nameEnd
				continue
			}
			val bodyStart = xml.indexOf('>', nameEnd)
			if (bodyStart < 0) return null
			val close = xml.indexOf("</item", bodyStart)
			// 没闭合说明流断了，剩下多少读多少
			return if (close < 0) {
				ItemSlice(xml.substring(bodyStart + 1), xml.length)
			} else {
				ItemSlice(xml.substring(bodyStart + 1, close), close + "</item".length)
			}
		}
	}

	/** link 是唯一的硬要求 —— 没链接的结果注入上下文也没法给模型标来源，直接丢 */
	private fun toHit(body: String): SearchHit? {
		val link = clean(tagValue(body, "link").orEmpty())
		if (link.isEmpty()) return null
		return SearchHit(
			title = clean(tagValue(body, "title").orEmpty()),
			link = link,
			snippet = clean(tagValue(body, "description").orEmpty()),
			publishedAt = clean(tagValue(body, "pubDate").orEmpty()),
		)
	}

	/** 取 item 里第一个同名标签的原文。标签没闭合就当这个字段不存在 */
	private fun tagValue(body: String, tag: String): String? {
		var search = 0
		while (true) {
			val open = body.indexOf("<$tag", search)
			if (open < 0) return null
			val nameEnd = open + tag.length + 1
			if (nameEnd >= body.length) return null
			// 防止 <link> 被 <linkTarget> 之类蹭上
			if (!isTagNameEnd(body[nameEnd])) {
				search = nameEnd
				continue
			}
			val valueStart = body.indexOf('>', nameEnd)
			if (valueStart < 0) return null
			val close = body.indexOf("</$tag", valueStart)
			if (close < 0) return null
			return body.substring(valueStart + 1, close)
		}
	}

	/** 标签名到这儿就结束了：<title> 收尾，或者 <title xxx="..."> 带属性 */
	private fun isTagNameEnd(c: Char): Boolean = c == '>' || c.isWhitespace()

	private fun clean(raw: String): String {
		if (raw.isEmpty()) return ""
		val text = decodeEntities(stripTags(unwrapCdata(raw)))
		return WHITESPACE.replace(text, " ").trim()
	}

	private fun unwrapCdata(raw: String): String {
		val open = raw.indexOf(CDATA_OPEN)
		if (open < 0) return raw
		val out = StringBuilder(raw.length)
		var i = 0
		while (i < raw.length) {
			val start = raw.indexOf(CDATA_OPEN, i)
			if (start < 0) {
				out.append(raw, i, raw.length)
				break
			}
			out.append(raw, i, start)
			val contentStart = start + CDATA_OPEN.length
			val end = raw.indexOf(CDATA_CLOSE, contentStart)
			// 截断的 CDATA：后面全算内容，别把它整段扔了
			if (end < 0) {
				out.append(raw, contentStart, raw.length)
				break
			}
			out.append(raw, contentStart, end)
			i = end + CDATA_CLOSE.length
		}
		return out.toString()
	}

	/** 干掉 <b>、<br> 这类内联标签，只留文字。收尾处半个标签一并丢掉 */
	private fun stripTags(text: String): String {
		if (!text.contains('<')) return text
		val out = StringBuilder(text.length)
		var i = 0
		while (i < text.length) {
			val open = text.indexOf('<', i)
			if (open < 0) {
				out.append(text, i, text.length)
				break
			}
			out.append(text, i, open)
			val close = text.indexOf('>', open)
			if (close < 0) break
			i = close + 1
		}
		return out.toString()
	}

	private fun decodeEntities(text: String): String {
		if (!text.contains('&')) return text
		val out = StringBuilder(text.length)
		var i = 0
		while (i < text.length) {
			val c = text[i]
			if (c != '&') {
				out.append(c)
				i++
				continue
			}
			val semi = text.indexOf(';', i + 1)
			val decoded = if (semi < 0 || semi - i > MAX_ENTITY_LEN) {
				null
			} else {
				decodeOne(text.substring(i + 1, semi))
			}
			if (decoded == null) {
				// 认不出来的就原样留着，页面标题里出现裸 & 的情况不少
				out.append(c)
				i++
			} else {
				out.append(decoded)
				i = semi + 1
			}
		}
		return out.toString()
	}

	/** [body] 是 & 和 ; 之间那段，可能是名字也可能是 #123 / #x1F600 */
	private fun decodeOne(body: String): String? {
		if (body.isEmpty()) return null
		if (!body.startsWith("#")) return NAMED_ENTITIES[body]
		val digits = body.substring(1)
		val code = if (digits.startsWith("x") || digits.startsWith("X")) {
			digits.substring(1).toIntOrNull(16)
		} else {
			digits.toIntOrNull()
		}
		if (code == null || code <= 0 || code > Character.MAX_CODE_POINT) return null
		// emoji 要走 appendCodePoint 补代理对，toChar() 会截成乱码
		return runCatching { StringBuilder().appendCodePoint(code).toString() }.getOrNull()
	}

	private const val CDATA_OPEN = "<![CDATA["
	private const val CDATA_CLOSE = "]]>"
}
