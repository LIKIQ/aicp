// app/src/main/java/com/kiq/aicp/domain/websearch/WebSearchModels.kt
// 联网搜索这条链路上传递的几种数据。
//
// 分三段是照着链路的三个阶段来的：
// 判定（要不要搜、搜什么）→ 命中（搜到了什么）→ 产出（拼给模型的那段文字）。
// 中间任何一段失败都退化成"这轮不搜"，所以每种都有一个明确的"空"表示。

package com.kiq.aicp.domain.websearch

/** 模型对"这轮要不要联网"的判定结果 */
data class SearchDecision(
	val shouldSearch: Boolean,
	/** 检索词。shouldSearch 为 false 时无意义 */
	val query: String = "",
) {
	companion object {
		/** 不搜。判定失败、解析不出来、模型说不用搜，都落到这里 */
		val No = SearchDecision(shouldSearch = false)
	}
}

/** 一条搜索结果。title/link/snippet 直接来自 Bing RSS 的 item */
data class SearchHit(
	val title: String,
	val link: String,
	/** RSS 的 description，通常是一两句跟检索词相关的片段 */
	val snippet: String,
	/** RSS 的 pubDate 原文，解析不出来就留空。展示用，不参与排序 */
	val publishedAt: String = "",
	/**
	 * 抓正文抽出来的相关段落。空串表示没抓、抓失败，或者筛出来的相关行太少
	 * 被整篇丢掉了 —— 宁可只给摘要，也不把一屏导航菜单塞进上下文。
	 */
	val passage: String = "",
) {
	/** 链接的域名，注入时标来源用。取不到就返回空串 */
	val host: String
		get() = runCatching {
			link.substringAfter("://").substringBefore('/').removePrefix("www.")
		}.getOrDefault("")
}

/** 一次完整检索的产出 */
data class WebSearchOutcome(
	val query: String,
	val hits: List<SearchHit>,
) {
	val isEmpty: Boolean get() = hits.isEmpty()

	companion object {
		val Empty = WebSearchOutcome(query = "", hits = emptyList())
	}
}
