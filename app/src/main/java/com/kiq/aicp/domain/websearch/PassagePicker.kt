// app/src/main/java/com/kiq/aicp/domain/websearch/PassagePicker.kt
// 从 HtmlTextExtractor 拆出来的行里，挑出跟检索词相关的那几行拼成一段。
//
// 为什么是"打分挑行"而不是"截取开头 N 字"：
// 实测 tianqi.com，去标签后 2545 字里大约 80% 是导航菜单、城市列表和无关新闻标题
// （"宫保鸡丁做法""如何破解wifi密码"这种），真正有用的就一两行，而且位置不固定。
// 从开头截 500 字抽到的全是菜单，正文一个字都没有。
// 换成按相关性打分之后，那一页 118 个候选行里排最前的正好是
// "今天北京仍有降雨最高气温仅14℃..." 和 "空气质量：优 湿度：75% 风向：北风 2级..."，
// 噪声行全被甩到后面 —— 所以这套分值是量出来的，不是拍的，调之前先拿真实页面比一遍。
//
// 三条分值的来历：
// - 含阿拉伯数字、含冒号/百分号/温度符号：真正回答问题的行几乎都带这些，导航菜单不带
// - 行长 12~200 加分、小于 8 扣分：短行基本都是导航链接和城市名
// - 版权页脚要单独扣 6 分。这是打分法唯一明显的漏网之鱼：
//   "Copyright © 2009-2026 www.tianqi.com 天气网" 有数字、长度合适，一度排到很前面
//
// 剩下两个刻意的选择：
// - 入选不足 minLines 行就整段作废返回空串。SPA 抓空（百度百科去标签后 0 字）、
//   纯导航页都会落到这里，宁可只给搜索摘要，也不要往上下文里灌半屏垃圾。
// - 挑的时候按分数排，输出的时候按原文顺序排。按分数输出读起来是跳着的，
//   而同一页面前后抓两次结果必须一致，所以排序要显式带上原文下标兜稳定性。
//
// 一条实测记下来的局限，别指望这套规则能解决：
// 搜"北京 今天 天气"抓 weather.com.cn 首页，它本身就是新闻聚合页，正经预报数据以
// "25 / 22℃" 这种短行呈现（不含任何检索词），而"未来三天陕西多地迎强降雨天气"这类
// 外地新闻又长又带"天气"。去重和元数据降分之后温度行能挤进来了，但无关新闻仍占掉
// 一部分预算。想再往上走得做 DOM 结构分析（Readability 那套），不是调分值能解决的。
// 打分法真正擅长的是有明确正文的页面：新闻详情、文档页、百科条目。

package com.kiq.aicp.domain.websearch

object PassagePicker {

	private const val KEYWORD_HIT = 3

	/**
	 * 命中两个以上不同检索词的额外奖励。
	 *
	 * 拿真实页面量出来的：搜"北京 今天 天气"抓 weather.com.cn，只按命中个数算的话
	 * "未来三天陕西多地迎强降雨天气"这种只沾了个"天气"的无关新闻跟正经预报同分，
	 * 结果筛出来一屏外地新闻。检索词凑齐两个以上的行才更可能是在回答这个问题。
	 */
	private const val MULTI_KEYWORD_BONUS = 4

	private const val HAS_DIGIT = 2
	private const val HAS_DATA_MARK = 2
	private const val GOOD_LENGTH = 2
	private const val TOO_SHORT = -3
	private const val FOOTER_NOISE = -6

	/**
	 * 新闻列表里的"站名 + 日期时间"那种元数据行。
	 * 同一个页面能刷出十几行"中国天气网 2026-08-26 11:16"，它们有数字有冒号，
	 * 按基础分能排得很前，但对回答问题毫无用处。
	 */
	private const val LIST_META = -5

	private const val MIN_GOOD_LENGTH = 12
	private const val MAX_GOOD_LENGTH = 200
	private const val SHORT_LENGTH = 8

	/** 元数据行的长度上限。真正带数据的行（"空气质量：优 湿度：75%…"）都比这长 */
	private const val META_MAX_LENGTH = 30

	private val DATE_LIKE = Regex("""\d{4}-\d{1,2}-\d{1,2}|\d{1,2}:\d{2}""")

	/** 数据行的特征符号：冒号（中英文）、百分号、温度和角度 */
	private val DATA_MARKS = charArrayOf(':', '：', '%', '℃', '°')

	/** 页脚和版权声明。命中就往下压，别让它挤掉真正的正文 */
	private val FOOTER_WORDS = listOf(
		"copyright",
		"all rights reserved",
		"版权",
		"备案",
		"icp",
		"关注我们",
		"联系我们",
		"免责声明",
		"隐私政策",
		"用户协议",
	)

	private data class Scored(val index: Int, val text: String, val score: Int)

	fun pick(lines: List<String>, keywords: List<String>, maxChars: Int, minLines: Int = 2): String {
		if (maxChars <= 0 || lines.isEmpty()) return ""

		val seen = mutableSetOf<String>()
		val ranked = lines.asSequence()
			.mapIndexed { index, line -> Scored(index, line, scoreOf(line, keywords)) }
			// 页脚行和新闻列表的时间戳行直接丢，不走分数。降分只能让它们沉底，
			// 可一旦关键词恰好命中站名（"Copyright © 2009-2026 www.tianqi.com 天气网"
			// 撞上关键词"天气"），它就能靠 +3 爬回正分；预算宽裕时更是照样挤进来。
			// 备案号、隐私政策、"中国天气网 2026-08-26 11:16" 对回答永远没用，
			// 没必要给它们留这个后门
			.filter { it.score > 0 && !isFooterNoise(it.text) && !isListMeta(it.text) }
			// 同文去重。实测 weather.com.cn 一页里"天气较好，适合擦洗汽车。"出现三次
			// （洗车指数按天列），同一句占三行预算纯属浪费，还会让模型以为这事很重要
			.filter { seen.add(it.text) }
			.sortedWith(compareByDescending<Scored> { it.score }.thenBy { it.index })
			.toList()

		val picked = takeWithinBudget(ranked, maxChars)
		if (picked.size < minLines) return ""

		return picked.sortedBy { it.index }.joinToString("\n") { it.text }
	}

	/**
	 * 按分数高低往里装，装不下就停手。
	 * 不做"截到半句"这种事 —— 半句数据比没有更容易让模型编，
	 * 唯一例外是一行都还没装进去，那时候截一刀总比交白卷好。
	 */
	private fun takeWithinBudget(ranked: List<Scored>, maxChars: Int): List<Scored> {
		val picked = mutableListOf<Scored>()
		var used = 0

		for (row in ranked) {
			// 拼接时行间会补一个 \n，预算里要把它算上，否则返回的串会比 maxChars 长
			val cost = row.text.length + if (picked.isEmpty()) 0 else 1
			if (used + cost <= maxChars) {
				picked += row
				used += cost
				continue
			}
			if (picked.isEmpty()) picked += row.copy(text = row.text.take(maxChars))
			break
		}
		return picked
	}

	private fun scoreOf(line: String, keywords: List<String>): Int {
		val lower = line.lowercase()
		val hits = keywords.count { it.isNotBlank() && lower.contains(it.lowercase()) }
		var score = hits * KEYWORD_HIT
		if (hits >= 2) score += MULTI_KEYWORD_BONUS

		if (line.any { it in '0'..'9' }) score += HAS_DIGIT
		if (line.any { it in DATA_MARKS }) score += HAS_DATA_MARK
		if (line.length in MIN_GOOD_LENGTH..MAX_GOOD_LENGTH) score += GOOD_LENGTH
		if (line.length < SHORT_LENGTH) score += TOO_SHORT
		if (isFooterNoise(line)) score += FOOTER_NOISE
		if (isListMeta(line)) score += LIST_META

		return score
	}

	/** 版权、备案、隐私政策这类页脚行。命中即出局，不参与打分 */
	private fun isFooterNoise(line: String): Boolean {
		val lower = line.lowercase()
		return FOOTER_WORDS.any { lower.contains(it) }
	}

	/** 新闻列表的"站名 + 发布时间"行：短、带日期或时刻，正文不会长这样 */
	private fun isListMeta(line: String): Boolean =
		line.length <= META_MAX_LENGTH && DATE_LIKE.containsMatchIn(line)
}
