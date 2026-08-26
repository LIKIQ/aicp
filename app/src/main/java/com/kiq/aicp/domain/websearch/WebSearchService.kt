// app/src/main/java/com/kiq/aicp/domain/websearch/WebSearchService.kt
// 联网搜索的编排：判定 → 搜 → 抓正文 → 拼成一段。
//
// 整条链路的总原则是"宁可不搜，绝不拖累回复"。任何一环失败——判定超时、
// 模型胡言乱语、搜索接口挂了、页面抓不下来——都退化成"这轮没搜"，
// 然后角色照常说话。用户发消息是为了聊天，不是为了看搜索报错。
//
// 所以这里满眼都是 withTimeoutOrNull 和 runCatching，不是防御性编程上瘾：
// LLM 判定那一步没有 OkHttp 的超时兜着，真卡住能卡到用户放弃；
// 而抓页面是访问任意第三方站点，什么破事都可能发生。

package com.kiq.aicp.domain.websearch

import android.util.Log
import com.kiq.aicp.data.remote.LlmMessage
import com.kiq.aicp.data.remote.LlmParams
import com.kiq.aicp.data.remote.LlmProvider
import com.kiq.aicp.data.remote.WebSearchClient
import com.kiq.aicp.domain.model.AicpSettings
import com.kiq.aicp.domain.model.ChatRole
import java.time.LocalDate
import kotlinx.coroutines.withTimeoutOrNull

class WebSearchService(
	private val llmProvider: LlmProvider,
	private val client: WebSearchClient,
	/** 可注入是为了单测能钉住"今天"，判定提示词里带日期 */
	private val today: () -> String = { LocalDate.now().toString() },
) {

	/**
	 * @param tail 对话尾部，每项形如"用户：…"或"小雨：…"，早→晚。判定就看这个
	 * @return 没搜、搜不到、被判定为不需要搜，统一返回 [WebSearchOutcome.Empty]
	 */
	suspend fun run(tail: List<String>, settings: AicpSettings): WebSearchOutcome {
		if (!settings.webSearchEnabled || tail.isEmpty()) return WebSearchOutcome.Empty
		if (!settings.hasEndpoint) return WebSearchOutcome.Empty

		val decision = decide(tail, settings)
		if (!decision.shouldSearch) return WebSearchOutcome.Empty

		val hits = search(decision.query, settings)
		if (hits.isEmpty()) return WebSearchOutcome.Empty

		return WebSearchOutcome(decision.query, enrich(hits, decision.query, settings))
	}

	/** 拼给模型的那一段。空结果返回空串，调用方可以直接拼接 */
	fun compose(outcome: WebSearchOutcome): String =
		WebSearchComposer.compose(outcome, today())

	private suspend fun decide(tail: List<String>, settings: AicpSettings): SearchDecision {
		val prompt = SearchPrompts.decisionPrompt(today(), tail)
		val reply = withTimeoutOrNull(DECIDE_TIMEOUT_MS) {
			runCatching {
				llmProvider.complete(
					messages = listOf(LlmMessage(ChatRole.USER, prompt)),
					params = LlmParams(
						// 判定是粗活，跟摘要、体检共用便宜模型那条路
						model = settings.effectiveCompressModel(),
						// 判定要的是稳定，不是创意。温度高了它会开始给检索词加形容词
						temperature = 0f,
						maxTokens = DECIDE_MAX_TOKENS,
					),
				)
			}.onFailure { Log.w(TAG, "检索判定失败，这轮不搜", it) }.getOrNull()
		} ?: return SearchDecision.No

		return SearchPrompts.parseDecision(reply)
	}

	private suspend fun search(query: String, settings: AicpSettings): List<SearchHit> {
		val xml = withTimeoutOrNull(SEARCH_TIMEOUT_MS) {
			runCatching { client.searchRaw(query) }
				.onFailure { Log.w(TAG, "搜索请求失败", it) }
				.getOrNull()
		} ?: return emptyList()

		return RssParser.parse(xml, settings.webSearchResultCount)
	}

	/**
	 * 给前几条结果补正文。串行抓而不是并发：默认只抓一篇，为省一秒钟去开协程
	 * 不值得，而且并发抓多个站点更容易撞上限流。
	 *
	 * 抓不到、筛不出相关段落的，原样保留只带摘要的那条 —— 摘要是必应针对
	 * 检索词挑的片段，本身就够用，正文只是加分项。
	 */
	private suspend fun enrich(
		hits: List<SearchHit>,
		query: String,
		settings: AicpSettings,
	): List<SearchHit> {
		val quota = settings.webSearchFetchPages
		if (quota <= 0) return hits

		val keywords = query.split(' ', '\u3000', ',', '，').filter { it.isNotBlank() }

		return hits.mapIndexed { index, hit ->
			if (index >= quota) return@mapIndexed hit
			val passage = fetchPassage(hit.link, keywords, settings.webSearchPageChars)
			if (passage.isEmpty()) hit else hit.copy(passage = passage)
		}
	}

	private suspend fun fetchPassage(url: String, keywords: List<String>, maxChars: Int): String {
		val html = withTimeoutOrNull(FETCH_TIMEOUT_MS) {
			runCatching { client.fetchPage(url) }
				.onFailure { Log.w(TAG, "抓页面失败：$url", it) }
				.getOrNull()
		} ?: return ""

		val lines = HtmlTextExtractor.toLines(html)
		// SPA 抓下来是空壳、纯导航页筛不出东西，都会在这里返回空串然后退回摘要
		return PassagePicker.pick(lines, keywords, maxChars)
	}

	private companion object {
		const val TAG = "WebSearchService"

		/** 判定这一步没有 OkHttp 超时兜着，必须自己压死 */
		const val DECIDE_TIMEOUT_MS = 15_000L
		const val SEARCH_TIMEOUT_MS = 10_000L
		const val FETCH_TIMEOUT_MS = 8_000L

		/** 只要一个短 JSON，给多了它就开始解释自己为什么这么判 */
		const val DECIDE_MAX_TOKENS = 120
	}
}
