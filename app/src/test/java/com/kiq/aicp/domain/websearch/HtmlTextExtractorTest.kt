// app/src/test/java/com/kiq/aicp/domain/websearch/HtmlTextExtractorTest.kt
// HTML 拆行的测试。纯 JVM，不碰 Robolectric。
//
// 最要紧的一条是 `nav 里的内容仍然保留`：删 nav/header/footer 整块这个"优化"
// 已经在 tianqi.com 上把整页删空过一次，这里钉死它，别让人再好心改回去。

package com.kiq.aicp.domain.websearch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HtmlTextExtractorTest {

	@Test
	fun `script 与 style 整块被剔除，注释也不留`() {
		val html = """
			<body>
				<script>var city = "beijing"; document.write("脚本里的字不要");</script>
				<style>.hd { color: red; } /* 样式里的字也不要 */</style>
				<!-- 注释里的字同样不要 -->
				<p>正文这一行要留下来</p>
			</body>
		""".trimIndent()

		val lines = HtmlTextExtractor.toLines(html)

		assertEquals(listOf("正文这一行要留下来"), lines)
	}

	@Test
	fun `noscript 与 svg 也一起清掉`() {
		val html = "<body><noscript>请开启脚本</noscript><svg><text>图标文字</text></svg><p>真正的正文</p></body>"

		assertEquals(listOf("真正的正文"), HtmlTextExtractor.toLines(html))
	}

	@Test
	fun `有 body 就只认 body 里的内容`() {
		val html = "<html><head><title>页面标题</title></head>" +
			"<body><p>body 里的正文</p></body><div>body 外面的脚注</div></html>"

		val lines = HtmlTextExtractor.toLines(html)

		assertEquals(listOf("body 里的正文"), lines)
	}

	@Test
	fun `没有 body 时按全文处理，不能直接返回空`() {
		val html = "<div><p>这是一段被截断的片段</p></div>"

		assertEquals(listOf("这是一段被截断的片段"), HtmlTextExtractor.toLines(html))
	}

	@Test
	fun `块级标签边界变成换行`() {
		val html = "<body><div>第一段</div><p>第二段</p>第三段<br>第四段" +
			"<ul><li>列表项一</li><li>列表项二</li></ul>" +
			"<table><tr><td>单元格</td></tr></table></body>"

		val lines = HtmlTextExtractor.toLines(html)

		assertEquals(
			listOf("第一段", "第二段", "第三段", "第四段", "列表项一", "列表项二", "单元格"),
			lines,
		)
	}

	@Test
	fun `标题标签 h1 到 h6 都算块级边界`() {
		val html = "<body><h1>大标题</h1><h3>小标题</h3><h6>更小的标题</h6></body>"

		assertEquals(listOf("大标题", "小标题", "更小的标题"), HtmlTextExtractor.toLines(html))
	}

	@Test
	fun `行内标签换成空格，前后的字不能粘在一起`() {
		// a<b>b</b>c 直接删标签会变成 abc，那就分不清原文了
		val lines = HtmlTextExtractor.toLines("<body><p>a<b>b</b>c</p></body>")

		assertEquals(listOf("a b c"), lines)
	}

	@Test
	fun `命名实体被解码`() {
		val html = "<body><p>&lt;p&gt; 标签 &amp; 实体 &quot;引号&quot; &apos;单引号&apos; " +
			"&middot; &hellip; &mdash;</p></body>"

		val lines = HtmlTextExtractor.toLines(html)

		assertEquals(listOf("<p> 标签 & 实体 \"引号\" '单引号' · … —"), lines)
	}

	@Test
	fun `nbsp 解成普通空格而不是留下不可见字符`() {
		val lines = HtmlTextExtractor.toLines("<body><p>湿度&nbsp;75%</p></body>")

		assertEquals(listOf("湿度 75%"), lines)
		assertTrue("残留了 U+00A0", lines[0].none { it == '\u00A0' })
	}

	@Test
	fun `十进制和十六进制的数字实体都能解`() {
		val html = "<body><p>&#39;引号&#39; &#123;花括号&#125; &#x4E2D;&#x6587; &#x1F600;</p></body>"

		val lines = HtmlTextExtractor.toLines(html)

		assertEquals(listOf("'引号' {花括号} 中文 \uD83D\uDE00"), lines)
	}

	@Test
	fun `解不出来的实体保留原文，不静悄悄丢字`() {
		val lines = HtmlTextExtractor.toLines("<body><p>价格 &fooo; 未知 &#x999999999;</p></body>")

		assertTrue("原文被吞了：${lines.firstOrNull()}", lines[0].contains("&fooo;"))
		assertTrue(lines[0].contains("&#x999999999;"))
	}

	@Test
	fun `实体不会被二次解码`() {
		// &amp;lt; 应该出来是 &lt; 这五个字符，而不是继续解成 <
		val lines = HtmlTextExtractor.toLines("<body><p>写法是 &amp;lt;</p></body>")

		assertEquals(listOf("写法是 &lt;"), lines)
	}

	@Test
	fun `连续空白压成一个空格并且去掉首尾`() {
		val html = "<body><p>   前后都有空白    中间\t有\n\n制表符和换行   　全角空格 </p></body>"

		val lines = HtmlTextExtractor.toLines(html)

		assertEquals(listOf("前后都有空白 中间 有", "制表符和换行 全角空格"), lines)
	}

	@Test
	fun `空输入返回空列表`() {
		assertEquals(emptyList<String>(), HtmlTextExtractor.toLines(""))
		assertEquals(emptyList<String>(), HtmlTextExtractor.toLines("   \n\t "))
	}

	@Test
	fun `SPA 空壳返回空列表`() {
		// 百度百科就是这样：去标签后 0 字，正文全靠 JS 渲染
		val html = """
			<html><head><title>词条</title></head>
			<body><div id="app"></div><script>window.__INITIAL__ = {"a":1};</script></body></html>
		""".trimIndent()

		assertEquals(emptyList<String>(), HtmlTextExtractor.toLines(html))
	}

	@Test
	fun `nav 里的内容仍然保留`() {
		// 防回归：删 nav 整块看着像去导航的捷径，实测能把整页删空
		val html = "<body><nav>导航里的这行文字必须留下</nav><p>正文</p></body>"

		val lines = HtmlTextExtractor.toLines(html)

		assertTrue("nav 被误删了：$lines", lines.contains("导航里的这行文字必须留下"))
		assertTrue(lines.contains("正文"))
	}

	@Test
	fun `header footer aside form 的内容同样保留`() {
		val html = "<body><header>站点头部文字</header><aside>侧栏文字</aside>" +
			"<form><label>搜索框标签</label></form><footer>页脚文字</footer><p>正文</p></body>"

		val lines = HtmlTextExtractor.toLines(html)

		listOf("站点头部文字", "侧栏文字", "搜索框标签", "页脚文字", "正文").forEach {
			assertTrue("$it 被误删了：$lines", lines.contains(it))
		}
	}

	@Test
	fun `不闭合的 nav 也不会把后面的正文带走`() {
		// 真实页面里这种写法很常见，正是它让"删整块"的方案翻车的
		val html = "<body><nav><a href='/'>首页</a></div><p>这一行绝对不能丢</p></body>"

		assertTrue(HtmlTextExtractor.toLines(html).contains("这一行绝对不能丢"))
	}

	@Test
	fun `大小写混写的标签一样处理`() {
		val html = "<BODY><SCRIPT>不要我</SCRIPT><DIV>要我这行</DIV></BODY>"

		assertEquals(listOf("要我这行"), HtmlTextExtractor.toLines(html))
	}

	@Test
	fun `超长输入先截断，不把内存吃穿`() {
		val html = "<body><p>开头这一行要保住</p>" + "凑".repeat(1_100_000) + "</body>"

		val lines = HtmlTextExtractor.toLines(html)

		assertEquals("开头这一行要保住", lines.first())
		assertTrue("截断没生效", lines.sumOf { it.length } <= 1_000_000)
	}
}
