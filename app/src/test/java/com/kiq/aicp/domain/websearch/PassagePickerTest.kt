// app/src/test/java/com/kiq/aicp/domain/websearch/PassagePickerTest.kt
// 段落挑选的测试。纯 JVM，不碰 Robolectric。
//
// 分值是拿真实页面量出来的，所以这里的行文本刻意照着 tianqi.com 的形态写：
// 一堆两到四个字的导航链接、一行带冒号和百分号的数据、几条无关新闻标题、一行版权。
// 最后那条集成味的用例是整条链路（拆行 + 挑段）一起跑的，动分值前先看它还绿不绿。

package com.kiq.aicp.domain.websearch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PassagePickerTest {

	@Test
	fun `命中关键词的行优先入选`() {
		val lines = listOf(
			"这是一段跟检索词毫无关系的普通文字内容",
			"北京今天最高气温14℃，夜间有小雨",
		)

		// 预算只够一行，谁被选中就说明谁排在前面
		val picked = PassagePicker.pick(lines, listOf("北京", "今天"), maxChars = 20, minLines = 1)

		assertEquals("北京今天最高气温14℃，夜间有小雨", picked)
	}

	@Test
	fun `带数字冒号百分号的数据行会被选中`() {
		val lines = listOf("导航", "空气质量：优 湿度：75% 风向：北风 2级", "更多")

		// 关键词一个都不命中，靠数据特征照样能捞出来
		val picked = PassagePicker.pick(lines, listOf("天气"), maxChars = 200, minLines = 1)

		assertEquals("空气质量：优 湿度：75% 风向：北风 2级", picked)
	}

	@Test
	fun `版权页脚行被降分排除`() {
		val lines = listOf(
			"Copyright © 2009-2026 www.example.com All Rights Reserved",
			"今天白天多云，最高气温 26℃，风力 3 级",
			"空气质量：良 湿度：60% 紫外线：中等",
		)

		val picked = PassagePicker.pick(lines, listOf("今天", "气温"), maxChars = 300)

		assertFalse("版权行混进来了：$picked", picked.contains("Copyright"))
		assertEquals(2, picked.lines().size)
	}

	@Test
	fun `备案号与联系我们这类页脚同样被压掉`() {
		val noise = listOf(
			"京ICP备12345678号-1 增值电信业务经营许可证",
			"联系我们：service@example.com 电话 010-12345678",
			"版权所有 2026 违法和不良信息举报电话",
		)
		val lines = noise + listOf("今天白天多云，最高气温 26℃", "空气质量：良 湿度：60%")

		val picked = PassagePicker.pick(lines, listOf("今天"), maxChars = 300)

		noise.forEach { assertFalse("$it 混进来了", picked.contains(it)) }
	}

	@Test
	fun `入选行数不足 minLines 时整段作废`() {
		// 纯导航页：每行都是两到四个字的短链接，全被短行惩罚打到 0 分以下
		val nav = listOf("首页", "天气", "城市", "更多", "空气")

		assertEquals("", PassagePicker.pick(nav, listOf("天气"), maxChars = 200))
	}

	@Test
	fun `只捞到一行时默认也返回空串`() {
		val lines = listOf("导航", "北京今天最高气温14℃有小雨")

		assertEquals("", PassagePicker.pick(lines, listOf("北京"), maxChars = 200))
		// 明确把门槛降到 1 才拿得到这一行
		assertEquals(
			"北京今天最高气温14℃有小雨",
			PassagePicker.pick(lines, listOf("北京"), maxChars = 200, minLines = 1),
		)
	}

	@Test
	fun `maxChars 生效，返回的串不会超长`() {
		val lines = (1..10).map { "第${it}行数据：温度 2${it}℃ 湿度 5${it}% 风力 ${it} 级" }

		val picked = PassagePicker.pick(lines, listOf("温度"), maxChars = 80)

		assertTrue("超长了：${picked.length}", picked.length <= 80)
		assertTrue(picked.isNotEmpty())
		assertTrue("应该装不下全部 10 行", picked.lines().size < lines.size)
	}

	@Test
	fun `装不下就停手，不会把一行截成半句`() {
		val lines = listOf(
			"今天白天多云转晴，最高气温 26℃，最低气温 15℃",
			"空气质量：良 湿度：60% 紫外线：中等 日出: 05:36",
		)

		val picked = PassagePicker.pick(lines, listOf("今天"), maxChars = 26, minLines = 1)

		assertEquals(lines[0], picked)
	}

	@Test
	fun `一行都装不下时才截断，总比交白卷好`() {
		val long = "北京今天最高气温14℃夜间有小雨明天转晴气温回升到20℃以上"

		val picked = PassagePicker.pick(listOf(long), listOf("北京"), maxChars = 10, minLines = 1)

		assertEquals(long.take(10), picked)
	}

	@Test
	fun `输出按原文顺序，不按分数顺序`() {
		val lines = listOf(
			"这一行只是长度合适的普通描述文字",
			"北京今天最高气温14℃：夜间有小雨",
		)

		// 第二行分数明显更高，但读起来必须还是原文的先后
		val picked = PassagePicker.pick(lines, listOf("北京", "今天"), maxChars = 300)

		assertEquals("${lines[0]}\n${lines[1]}", picked)
	}

	@Test
	fun `同分行按原文顺序排，反复跑结果一致`() {
		val lines = listOf("北京今天白天多云气温14℃", "上海今天白天晴朗气温22℃")

		val results = (1..5).map { PassagePicker.pick(lines, listOf("今天"), maxChars = 13, minLines = 1) }

		assertEquals(setOf(lines[0]), results.toSet())
	}

	@Test
	fun `空输入与非法预算都返回空串`() {
		assertEquals("", PassagePicker.pick(emptyList(), listOf("北京"), maxChars = 200))
		assertEquals("", PassagePicker.pick(listOf("北京今天气温14℃"), listOf("北京"), maxChars = 0, minLines = 1))
		assertEquals("", PassagePicker.pick(listOf("北京今天气温14℃"), listOf("北京"), maxChars = -1, minLines = 1))
	}

	@Test
	fun `关键词为空时不炸，靠数据特征照样能挑`() {
		val lines = listOf("首页", "空气质量：优 湿度：75% 风向：北风 2级", "今天白天多云，最高气温 26℃")

		val picked = PassagePicker.pick(lines, emptyList(), maxChars = 200)

		assertEquals(2, picked.lines().size)
		assertFalse(picked.contains("首页"))
	}

	@Test
	fun `关键词大小写不敏感`() {
		val lines = listOf("iPhone 17 Pro 售价 7999 元起", "另一行长度足够的无关描述文字内容")

		val picked = PassagePicker.pick(lines, listOf("IPHONE"), maxChars = 26, minLines = 1)

		assertEquals(lines[0], picked)
	}

	// ---------------- 整条链路 ----------------

	@Test
	fun `照着天气站结构走一遍，挑出数据行并甩掉菜单与版权`() {
		val html = """
			<!DOCTYPE html>
			<html>
			<head><title>北京天气预报</title><script>var city = "beijing";</script>
			<style>.nav { color: red; }</style></head>
			<body>
			<nav>
				<a href="/">首页</a><a href="/beijing/">北京</a><a href="/tianqi/">天气预报</a>
				<a href="/kongqi/">空气质量</a><a href="/shanghai/">上海</a><a href="/guangzhou/">广州</a>
			</nav>
			<div class="today">
				<h2>今天北京仍有降雨最高气温仅14℃ 明后天雨水停歇气温回升</h2>
				<p>空气质量：优 湿度：75% 风向：北风 2级 紫外线：很弱 日出: 05:36 日落: 18:56</p>
			</div>
			<ul class="news">
				<li><a href="#">宫保鸡丁的最简单做法</a></li>
				<li><a href="#">如何破解wifi密码</a></li>
				<li><a href="#">十二星座本周运势</a></li>
			</ul>
			<footer>Copyright © 2009-2026 www.tianqi.com All Rights Reserved</footer>
			</body>
			</html>
		""".trimIndent()

		val lines = HtmlTextExtractor.toLines(html)
		val picked = PassagePicker.pick(lines, listOf("北京", "天气", "今天"), maxChars = 400)

		assertTrue("导航被整块删掉了，拆行这步出问题了", lines.size > 5)
		assertTrue("丢了数据行：\n$picked", picked.contains("空气质量：优 湿度：75%"))
		assertTrue("丢了天气标题：\n$picked", picked.contains("最高气温仅14℃"))
		assertFalse("无关新闻混进来了：\n$picked", picked.contains("宫保鸡丁"))
		assertFalse("星座新闻混进来了：\n$picked", picked.contains("星座"))
		assertFalse("版权行混进来了：\n$picked", picked.contains("Copyright"))
		// 原文里标题在数据行之前，输出也得是这个顺序
		assertTrue(picked.indexOf("最高气温仅14℃") < picked.indexOf("空气质量：优"))
	}

	@Test
	fun `SPA 空壳走完整条链路拿到空串`() {
		val html = "<html><body><div id=\"app\"></div><script>window.__D__ = 1;</script></body></html>"

		val picked = PassagePicker.pick(
			HtmlTextExtractor.toLines(html),
			listOf("北京", "天气"),
			maxChars = 400,
		)

		assertEquals("", picked)
	}

	/** 实测 weather.com.cn 一页里"天气较好，适合擦洗汽车。"按天列了三遍 */
	@Test
	fun `完全相同的行只留第一次出现的`() {
		val lines = listOf(
			"北京今天多云，最高气温 31℃",
			"天气较好，适合擦洗汽车。",
			"天气较好，适合擦洗汽车。",
			"天气较好，适合擦洗汽车。",
			"空气质量：优 湿度：75% 风向：北风 2级",
		)

		val picked = PassagePicker.pick(lines, listOf("北京", "今天", "天气"), maxChars = 400)

		assertEquals(1, Regex("适合擦洗汽车").findAll(picked).count())
		assertTrue(picked.contains("空气质量：优"))
	}

	/** 新闻列表的"站名 + 发布时间"能刷十几行，有数字有冒号，光按基础分能排很前 */
	@Test
	fun `新闻列表的发布时间行被压下去`() {
		val lines = listOf(
			"中国天气网 2026-08-26 11:28",
			"中国天气网 2026-08-26 11:16",
			"北京今天白天多云，最高气温 31℃，东南风 2 级",
			"空气质量：优 湿度：75% 紫外线：很弱 日出: 05:36 日落: 18:56",
		)

		val picked = PassagePicker.pick(lines, listOf("北京", "今天", "天气"), maxChars = 120)

		assertTrue("正经数据行没进来：\n$picked", picked.contains("最高气温 31℃"))
		assertFalse("时间戳行挤进来了：\n$picked", picked.contains("11:28"))
	}

	/** 长度超过阈值的行即使带时刻也不算元数据，"日出: 05:36" 那种不能被误杀 */
	@Test
	fun `带时刻的长数据行不算元数据`() {
		val lines = listOf(
			"空气质量：优 湿度：75% 风向：北风 2级 紫外线：很弱 日出: 05:36 日落: 18:56",
			"北京今天白天多云，最高气温 31℃",
		)

		val picked = PassagePicker.pick(lines, listOf("北京", "天气"), maxChars = 400)

		assertTrue(picked.contains("日出: 05:36"))
	}

	/** 只沾一个泛词的无关新闻，不该跟检索词凑齐的正经内容同分 */
	@Test
	fun `命中多个检索词的行排在只命中一个的前面`() {
		val lines = listOf(
			"未来三天陕西多地迎强降雨天气 西安等地仍有高温",
			"北京今天白天多云，最高气温 31℃",
			"江南一带高温天气持续发展",
		)

		// 预算只够装两行，逼它做选择
		val picked = PassagePicker.pick(lines, listOf("北京", "今天", "天气"), maxChars = 50)

		assertTrue("三词齐全的那行没排上：\n$picked", picked.contains("北京今天白天多云"))
	}
}
