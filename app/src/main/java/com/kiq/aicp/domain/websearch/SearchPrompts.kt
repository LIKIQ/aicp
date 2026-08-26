// app/src/main/java/com/kiq/aicp/domain/websearch/SearchPrompts.kt
// "这轮要不要联网、搜什么词"的判定提示词与结果解析。
//
// 为什么让模型判定而不是本地关键词表：关键词表挡不住"你今天心情怎么样"这种
// 带时间词但纯闲聊的句子，也认不出"那家店还在营业吗"这种没有任何触发词、
// 但确实需要查的问题。判定这活儿模型比正则强，一次几百 token 的调用买得起。
//
// 判定走压缩模型（effectiveCompressModel），跟摘要、体检共用那条"便宜模型干粗活"的路。
//
// 输出契约刻意做得极窄：只要一个 {"search": bool, "query": "..."}。
// 越简单的格式，小模型越不容易搞砸；真搞砸了也有 LenientJson 兜一层。

package com.kiq.aicp.domain.websearch

import com.kiq.aicp.domain.util.LenientJson
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class DecisionDto(
	val search: Boolean = false,
	val query: String = "",
	// 有些模型爱把字段名写成 need_search / keywords，顺手认一下，
	// 省一次白扔的调用。多认两个别名比重试一遍便宜
	@SerialName("need_search") val needSearch: Boolean? = null,
	val keywords: String? = null,
)

object SearchPrompts {

	/** 检索词太长反而搜不准，Bing 对长句的召回明显变差 */
	const val MAX_QUERY_CHARS = 60

	/** 判定只看对话尾部这么多条。给太多历史会让它把老话题当成当前需求 */
	const val CONTEXT_MESSAGES = 6

	private val json = Json {
		ignoreUnknownKeys = true
		isLenient = true
	}

	/**
	 * @param today 形如 2026-08-26。必须给，否则模型没法把"今天""最近"翻成有意义的检索词
	 * @param tail 对话尾部，每项已经是"角色：内容"的单行形式，早→晚
	 */
	fun decisionPrompt(today: String, tail: List<String>): String = buildString {
		appendLine("你是一个检索判定器。判断下面这段对话的最后一句，是否需要联网查资料才能答好。")
		appendLine()
		appendLine("今天是 $today。")
		appendLine()
		appendLine("需要联网的情况：问到实时或近期信息（天气、新闻、价格、比赛结果、某人现状）、")
		appendLine("问到你可能不了解的具体事物（新出的产品、小众工具、某个网站还在不在）、")
		appendLine("或者明确让你去查一下。")
		appendLine()
		appendLine("不需要联网的情况：闲聊、情绪交流、角色扮演、问你的看法或感受、")
		appendLine("纯粹的常识和已经聊过的内容、需要动脑但不需要新资料的推理和写作。")
		appendLine()
		appendLine("对话尾部：")
		tail.takeLast(CONTEXT_MESSAGES).forEach { appendLine(it) }
		appendLine()
		appendLine("只输出一个 JSON，不要任何解释：")
		appendLine("""{"search": true, "query": "放进搜索引擎的关键词"}""")
		appendLine("不需要联网就输出 {\"search\": false}。")
		append("query 用空格分词的关键词，不要写成问句，不超过 ${MAX_QUERY_CHARS} 个字。")
	}

	/** 解析模型的判定回复。任何解析不出来的情况都返回"不搜"，绝不让这一步阻断回复 */
	fun parseDecision(reply: String): SearchDecision {
		val body = LenientJson.salvageObject(reply) ?: return SearchDecision.No
		val dto = runCatching { json.decodeFromString<DecisionDto>(body) }.getOrNull()
			?: return SearchDecision.No

		val wants = dto.needSearch ?: dto.search
		if (!wants) return SearchDecision.No

		val query = (dto.query.ifBlank { dto.keywords.orEmpty() })
			.replace('\n', ' ')
			.trim()
			.take(MAX_QUERY_CHARS)
		// 说要搜却没给词，等于没判定成功。硬编一个词去搜只会污染上下文
		return if (query.isEmpty()) SearchDecision.No else SearchDecision(true, query)
	}
}
