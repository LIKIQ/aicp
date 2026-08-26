// app/src/test/java/com/kiq/aicp/domain/websearch/RssParserTest.kt
// Bing RSS 解析器的测试。纯 JVM，不需要 Robolectric。
//
// 第一条测试是这里的主线：channel 和 image 各自都带一份 title/link/description，
// 按标签名全局搜的实现会把"必应：今天天气"当成第一条搜索结果，一眼看不出来但结果全歪。
// 其余用例都是实测遇到过或者迟早会遇到的返回：CDATA、加粗标签、实体、缺字段、流断在半句上。

package com.kiq.aicp.domain.websearch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RssParserTest {

	/** 拼一段带 channel 头和 image 块的完整回包，这两块就是干扰项 */
	private fun wrap(items: String): String =
		"<?xml version=\"1.0\" encoding=\"utf-8\" ?><rss version=\"2.0\"><channel>" +
			"<title>必应：今天天气</title>" +
			"<link>http://www.bing.com:80/search?q=x</link>" +
			"<description>搜索结果</description>" +
			"<image><url>http://www.bing.com:80/s/a/rsslogo.gif</url>" +
			"<title>今天天气</title><link>http://www.bing.com:80/search?q=x</link></image>" +
			"<copyright>版权所有 ...</copyright>" +
			items +
			"</channel></rss>"

	@Test
	fun `标准多 item 解析且第一条不是 channel 或 image 的标题`() {
		val xml = wrap(
			"<item><title>【北京今天天气预报】北京天气网</title>" +
				"<link>https://www.tianqi.com/beijing/today/</link>" +
				"<description>北京天气网为您提供北京天气预报24小时详情</description>" +
				"<pubDate>周三, 26 8月 2026 00:01:00 GMT</pubDate></item>" +
				"<item><title>中国天气网</title><link>https://www.weather.com.cn/</link>" +
				"<description>权威天气预报</description><pubDate>周四, 27 8月 2026 01:02:03 GMT</pubDate></item>",
		)

		val hits = RssParser.parse(xml)

		assertEquals(2, hits.size)
		val first = hits.first()
		assertEquals("【北京今天天气预报】北京天气网", first.title)
		assertEquals("https://www.tianqi.com/beijing/today/", first.link)
		assertEquals("北京天气网为您提供北京天气预报24小时详情", first.snippet)
		assertEquals("周三, 26 8月 2026 00:01:00 GMT", first.publishedAt)
		// channel / image 的那几份字段一条都不许漏进来
		assertTrue(hits.none { it.title == "必应：今天天气" || it.title == "今天天气" })
		assertTrue(hits.none { it.link.contains("bing.com") })
		assertEquals("中国天气网", hits[1].title)
		assertEquals("", hits[1].passage)
	}

	@Test
	fun `字段顺序打乱也能对上`() {
		val xml = wrap(
			"<item><pubDate>周一, 01 1月 2026 00:00:00 GMT</pubDate>" +
				"<description>先描述后标题</description>" +
				"<link>https://a.example.com/1</link><title>顺序反着写</title></item>",
		)

		val hit = RssParser.parse(xml).single()

		assertEquals("顺序反着写", hit.title)
		assertEquals("https://a.example.com/1", hit.link)
		assertEquals("先描述后标题", hit.snippet)
		assertEquals("周一, 01 1月 2026 00:00:00 GMT", hit.publishedAt)
	}

	@Test
	fun `CDATA 包裹的字段值被剥出来`() {
		val xml = wrap(
			"<item><title><![CDATA[带 <标记> 的标题 & 符号]]></title>" +
				"<link><![CDATA[https://b.example.com/?a=1&b=2]]></link>" +
				"<description>前半段<![CDATA[中间是 CDATA]]>后半段</description></item>",
		)

		val hit = RssParser.parse(xml).single()

		assertEquals("带 的标题 & 符号", hit.title)
		assertEquals("https://b.example.com/?a=1&b=2", hit.link)
		assertEquals("前半段中间是 CDATA后半段", hit.snippet)
	}

	@Test
	fun `HTML 实体含十进制和十六进制都能解出来`() {
		val xml = wrap(
			"<item><title>A&amp;B &lt;C&gt; &quot;D&quot; &apos;E&apos; &#39;F&#39;</title>" +
				"<link>https://c.example.com/?x=1&amp;y=2</link>" +
				"<description>十进制&#123;右&#125; 十六进制&#x1F600; 空格&nbsp;分隔 认不出的&foo;留着</description></item>",
		)

		val hit = RssParser.parse(xml).single()

		assertEquals("A&B <C> \"D\" 'E' 'F'", hit.title)
		assertEquals("https://c.example.com/?x=1&y=2", hit.link)
		assertEquals("十进制{右} 十六进制\uD83D\uDE00 空格 分隔 认不出的&foo;留着", hit.snippet)
	}

	@Test
	fun `字段里的加粗标签被剥掉只留文字`() {
		val xml = wrap(
			"<item><title>今天<b>天气</b>怎么样</title>" +
				"<link>https://d.example.com/</link>" +
				"<description>今日<b>北京</b>晴，<strong>气温</strong>25度<br/>明天转阴</description></item>",
		)

		val hit = RssParser.parse(xml).single()

		assertEquals("今天天气怎么样", hit.title)
		assertEquals("今日北京晴，气温25度明天转阴", hit.snippet)
	}

	@Test
	fun `一条 item 都没有时返回空列表`() {
		assertTrue(RssParser.parse(wrap("")).isEmpty())
	}

	@Test
	fun `缺 link 的 item 被丢掉，缺 title 的留着并用空标题`() {
		val xml = wrap(
			"<item><title>没有链接的结果</title><description>只有描述</description></item>" +
				"<item><description>只有描述和链接</description><link>https://e.example.com/</link></item>",
		)

		val hits = RssParser.parse(xml)

		assertEquals(1, hits.size)
		assertEquals("", hits.single().title)
		assertEquals("https://e.example.com/", hits.single().link)
		assertEquals("只有描述和链接", hits.single().snippet)
	}

	@Test
	fun `截断的 XML 不抛异常，能读多少读多少`() {
		val truncated = "<?xml version=\"1.0\"?><rss><channel><title>必应：x</title>" +
			"<item><title>第一条</title><link>https://f.example.com/1</link>" +
			"<description>完整的一条</description></item>" +
			"<item><title>第二条</title><link>https://f.example.com/2</link><description>断在这"

		val hits = RssParser.parse(truncated)

		assertEquals(2, hits.size)
		assertEquals("https://f.example.com/1", hits[0].link)
		assertEquals("第二条", hits[1].title)
		// description 的闭合标签没了，这个字段就当没给
		assertEquals("", hits[1].snippet)
	}

	@Test
	fun `空串和非 XML 输入都返回空列表`() {
		assertTrue(RssParser.parse("").isEmpty())
		assertTrue(RssParser.parse("   \n  ").isEmpty())
		assertTrue(RssParser.parse("这不是 XML，就是一句话").isEmpty())
		assertTrue(RssParser.parse("{\"error\":\"quota exceeded\"}").isEmpty())
		assertTrue(RssParser.parse("<html><body>403 Forbidden</body></html>").isEmpty())
	}

	@Test
	fun `limit 截断生效且 limit 为 0 时返回空列表`() {
		val xml = wrap(
			(1..5).joinToString("") { i ->
				"<item><title>第 $i 条</title><link>https://g.example.com/$i</link>" +
					"<description>描述 $i</description></item>"
			},
		)

		assertEquals(5, RssParser.parse(xml).size)
		val two = RssParser.parse(xml, limit = 2)
		assertEquals(2, two.size)
		assertEquals("第 1 条", two[0].title)
		assertEquals("第 2 条", two[1].title)
		assertTrue(RssParser.parse(xml, limit = 0).isEmpty())
		assertTrue(RssParser.parse(xml, limit = -3).isEmpty())
		assertEquals(5, RssParser.parse(xml, limit = 99).size)
	}

	@Test
	fun `连续空白压成单空格并去掉首尾`() {
		val xml = wrap(
			"<item>\n\t<title>\n\t\t标题   中间    好多空格\t\n</title>\n" +
				"\t<link>  https://h.example.com/  </link>\n" +
				"\t<description>描述\n\n换行\r\n也算空白  </description>\n</item>",
		)

		val hit = RssParser.parse(xml).single()

		assertEquals("标题 中间 好多空格", hit.title)
		assertEquals("https://h.example.com/", hit.link)
		assertEquals("描述 换行 也算空白", hit.snippet)
	}

	/**
	 * 真实回包的原样片段（2026-08-26 抓的 cn.bing.com）。
	 * 前面那些用例都是手搓的输入，这条是防"线上格式跟我们想的不一样"——
	 * 注意 pubDate 是中文星期加中文月份（"周二, 25 8月 2026"），
	 * 以前吃过按 RFC1123 硬解日期结果整条丢掉的亏。
	 */
	@Test
	fun `真实 Bing 回包片段解析正确`() {
		val xml = wrap(
			"<item><title>DeepSeek API 价格 2026：V4 Flash 与 Pro 高峰/闲时最新 ...</title>" +
				"<link>https://devtk.ai/zh/blog/deepseek-api-pricing-guide-2026/</link>" +
				"<description>2026年8月17日涨价后的 DeepSeek API 价格：V4 Flash、V4 Pro " +
				"峰谷 Token 费率、缓存成本、计费时段、模型 ID 与成本算例。</description>" +
				"<pubDate>周二, 25 8月 2026 21:09:00 GMT</pubDate></item>",
		)

		val hit = RssParser.parse(xml).single()

		assertEquals("DeepSeek API 价格 2026：V4 Flash 与 Pro 高峰/闲时最新 ...", hit.title)
		assertEquals("https://devtk.ai/zh/blog/deepseek-api-pricing-guide-2026/", hit.link)
		assertTrue(hit.snippet.contains("V4 Flash、V4 Pro"))
		assertEquals("周二, 25 8月 2026 21:09:00 GMT", hit.publishedAt)
		assertEquals("devtk.ai", hit.host)
	}
}
