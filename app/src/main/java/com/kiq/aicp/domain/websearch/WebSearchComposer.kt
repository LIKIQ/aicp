// app/src/main/java/com/kiq/aicp/domain/websearch/WebSearchComposer.kt
// 把搜索结果拼成塞进 system 提示词的那一段。
//
// 两处措辞是刻意的，别当成啰嗦删掉：
// 1. "这些不是你本来就知道的事" —— 不说这句，模型会把搜来的东西当成自己的记忆，
//    下一轮压缩就把它当既有事实写进记忆条目了，等于把网上的错话腌成角色的认知。
// 2. "不要说「根据搜索结果」" —— KIQ 要的是完全隐形。不压这一句，模型十次有八次
//    会先来一段"我查了一下，根据搜索结果显示"，角色感当场就破。

package com.kiq.aicp.domain.websearch

object WebSearchComposer {

	/** 段落标题。ContextBuilder 估算预算时也要认这个头，所以提出来共用 */
	const val SECTION_TITLE = "# 刚查到的网上信息"

	/**
	 * @param today 检索日期，形如 2026-08-26。写进标题是为了让模型知道这批信息的时效
	 * @return 可以直接接在 system 提示词后面的一段；没有任何结果时返回空串
	 */
	fun compose(outcome: WebSearchOutcome, today: String): String {
		if (outcome.isEmpty) return ""

		return buildString {
			appendLine("$SECTION_TITLE（$today 搜的「${outcome.query}」）")
			appendLine("这些是刚从搜索引擎拿到的，不是你本来就知道的事。用得上就自然地说出来，")
			appendLine("用不上就忽略；里面可能有过时或不准的内容，别当成铁板钉钉的事实。")
			appendLine("不要复述链接，也不要说「根据搜索结果」这种话。")

			outcome.hits.forEach { hit -> appendHit(hit) }
		}.trimEnd()
	}

	private fun StringBuilder.appendHit(hit: SearchHit) {
		appendLine()
		appendLine("## ${hit.title.ifBlank { "（无标题）" }}")

		// 正文节选比摘要更具体，有就优先给；摘要是 Bing 针对检索词挑的片段，
		// 两个都留着会重复大半，白花 token
		val body = hit.passage.ifBlank { hit.snippet }
		if (body.isNotBlank()) appendLine(body.trim())

		val source = hit.host
		if (source.isNotEmpty()) appendLine("（来源：$source）")
	}
}
