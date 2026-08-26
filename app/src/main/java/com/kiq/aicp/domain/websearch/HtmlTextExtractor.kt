// app/src/main/java/com/kiq/aicp/domain/websearch/HtmlTextExtractor.kt
// 把抓回来的 HTML 拆成一行一行的纯文本，供 PassagePicker 挑相关段落。
//
// 这一层只负责"拆行"，不判断哪行有用。两件事分开是因为它们坏掉的样子不一样：
// 拆行坏了整页变空，选段坏了是把一屏导航菜单塞进上下文，出问题时得能分开定位。
//
// 几条实测结论，改这个文件之前先读完：
//
// 1. 只删 script/style/noscript/svg/head/iframe。这几个必然不含正文，
//    而且实际页面里都是规规矩矩成对闭合的，非贪婪匹配不会越界。
//
// 2. 绝对不要顺手加上 <nav>/<header>/<footer>/<aside>/<form>。
//    看着像是"一步去掉导航"的捷径，实测在 tianqi.com 上把去标签后的 2545 字
//    直接删成了 0 字 —— 这些标签在真实页面里经常嵌套，或者干脆不闭合，
//    遇到 <nav>...</div> 这种写法，非贪婪匹配会从第一个 <nav> 一路吞到
//    页面末尾那个唯一的 </nav>，正文跟着一起消失。
//    导航噪声是 PassagePicker 用相关性压下去的，不在这一层硬删。
//
// 3. 先去标签、再解实体，顺序不能反。反过来的话 &lt;p&gt; 解出来的假标签
//    会在下一步被当成真标签抹掉，页面里讲 HTML 的内容就全花了。
//
// 4. 输入上限 1_000_000 字符。天气页才 47KB，但难保哪个站丢回来一个几十 MB 的
//    单页大礼包，正则在那种输入上跑一遍就够把内存吃穿。

package com.kiq.aicp.domain.websearch

object HtmlTextExtractor {

	/** 超过这个长度先截断再处理。宁可少抽几行，也不能让一个页面拖死进程 */
	private const val MAX_INPUT_CHARS = 1_000_000

	private val DOTALL = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)

	/** 必然不含正文、且必然成对闭合的整块。名单只能减不能加，理由见文件头第 2 条 */
	private val INVISIBLE_BLOCK = Regex(
		"""<(script|style|noscript|svg|head|iframe)\b[^>]*>.*?</\1\s*>""",
		DOTALL,
	)

	private val COMMENT = Regex("""<!--.*?-->""", DOTALL)

	private val BODY = Regex("""<body\b[^>]*>(.*?)</body\s*>""", DOTALL)

	/**
	 * 该换行的地方：<br> 加一批块级元素的两侧边界。
	 * 开标签也要算 —— `第四段<ul><li>列表项` 只认闭合标签的话，
	 * 这两段会粘成一行，后面打分时长度和标点特征全乱。
	 */
	private val LINE_BREAK = Regex(
		"""<br\s*/?>|</?\s*(?:p|div|li|ul|ol|dl|dd|dt|h[1-6]|tr|td|th|table|thead|tbody""" +
			"""|section|article|main|header|footer|nav|aside|form|label|option|blockquote|pre|figure)\b[^>]*>""",
		RegexOption.IGNORE_CASE,
	)

	/**
	 * 剩下的标签一律换成空格而不是直接删。
	 * `a<b>b</b>c` 直接删会粘成 `abc`，多一个空格顶多后面被压掉，粘起来的字就找不回来了。
	 */
	private val ANY_TAG = Regex("""<[^>]*>""")

	/** \u00A0 是 &nbsp; 的本体，\u3000 是中文全角空格，两个都不在 \s 里，得手动带上 */
	private val WHITESPACE = Regex("""[\s\u00A0\u3000\u200B\uFEFF]+""")

	/** 数字实体和命名实体一次扫完，避免 &amp;lt; 被解成 < 这种二次解码 */
	private val ENTITY = Regex("""&(#[xX][0-9a-fA-F]{1,6}|#\d{1,7}|[a-zA-Z][a-zA-Z0-9]{1,9});""")

	private val NAMED = mapOf(
		"amp" to "&",
		"lt" to "<",
		"gt" to ">",
		"quot" to "\"",
		"apos" to "'",
		"nbsp" to " ",
		"middot" to "·",
		"hellip" to "…",
		"mdash" to "—",
		"ndash" to "–",
		"ldquo" to "“",
		"rdquo" to "”",
		"copy" to "©",
		"reg" to "®",
		"times" to "×",
		"deg" to "°",
	)

	fun toLines(html: String): List<String> {
		if (html.isBlank()) return emptyList()

		val capped = if (html.length > MAX_INPUT_CHARS) html.take(MAX_INPUT_CHARS) else html
		val cleaned = COMMENT.replace(INVISIBLE_BLOCK.replace(capped, " "), " ")

		return toPlainText(bodyOf(cleaned))
			.split('\n')
			.map { normalize(it) }
			.filter { it.isNotEmpty() }
	}

	/**
	 * 有 body 就只认 body 里的东西，没有就整篇拿来用。
	 * 取不到 body 的情况比想象中多：搜索结果里混着 RSS 片段、被截断的 HTML，
	 * 那些直接按全文处理反而抽得到东西。
	 */
	private fun bodyOf(html: String): String =
		BODY.find(html)?.groupValues?.get(1) ?: html

	private fun toPlainText(fragment: String): String {
		val broken = LINE_BREAK.replace(fragment, "\n")
		return decodeEntities(ANY_TAG.replace(broken, " "))
	}

	private fun decodeEntities(text: String): String {
		if (!text.contains('&')) return text

		return ENTITY.replace(text) { match ->
			val body = match.groupValues[1]
			when {
				body.startsWith("#x") || body.startsWith("#X") ->
					codePointOf(body.drop(2), radix = 16) ?: match.value

				body.startsWith("#") -> codePointOf(body.drop(1), radix = 10) ?: match.value
				else -> NAMED[body.lowercase()] ?: match.value
			}
		}
	}

	/** 解不出来或者码位越界就返回 null，让调用方保留原文 —— 显示成 &#xZZ 比丢字好排查 */
	private fun codePointOf(digits: String, radix: Int): String? {
		val value = digits.toIntOrNull(radix) ?: return null
		if (value !in 1..0x10FFFF) return null
		return runCatching { String(Character.toChars(value)) }.getOrNull()
	}

	private fun normalize(line: String): String =
		WHITESPACE.replace(line, " ").trim()
}
