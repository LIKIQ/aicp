// app/src/test/java/com/kiq/aicp/data/FakeLlmProvider.kt
// 测试用的假模型。压缩策略的测试必须能在完全离线的情况下跑，
// 所以这里可以按输入决定输出、可以指定抛哪种错、并记下每次被调用时收到的完整消息列表
// （断言"提示词里到底带了什么"就靠它）。

package com.kiq.aicp.data

import com.kiq.aicp.data.remote.LlmChunk
import com.kiq.aicp.data.remote.LlmException
import com.kiq.aicp.data.remote.LlmMessage
import com.kiq.aicp.data.remote.LlmParams
import com.kiq.aicp.data.remote.LlmProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class FakeLlmProvider : LlmProvider {

	/** 每次 complete 收到的消息，按调用顺序 */
	val completeCalls = mutableListOf<List<LlmMessage>>()

	/** 每次 complete 收到的参数，用来断言压缩走的是压缩专用模型 */
	val completeParams = mutableListOf<LlmParams>()

	var configured: Boolean = true

	/** 按调用顺序依次返回；用完之后重复最后一个 */
	var scriptedReplies: MutableList<String> = mutableListOf()

	/** 非 null 时每次调用都抛这个错 */
	var failure: LlmException? = null

	/** 优先级最高：直接按输入算输出 */
	var responder: ((List<LlmMessage>) -> String)? = null

	var streamDeltas: List<String> = emptyList()

	override fun streamChat(messages: List<LlmMessage>, params: LlmParams): Flow<LlmChunk> = flow {
		failure?.let { throw it }
		streamDeltas.forEach { emit(LlmChunk.Delta(it)) }
		emit(LlmChunk.Done("stop"))
	}

	override suspend fun complete(messages: List<LlmMessage>, params: LlmParams): String {
		completeCalls += messages
		completeParams += params
		failure?.let { throw it }
		responder?.let { return it(messages) }
		return when {
			scriptedReplies.isEmpty() -> ""
			scriptedReplies.size == 1 -> scriptedReplies.first()
			else -> scriptedReplies.removeAt(0)
		}
	}

	override suspend fun isConfigured(): Boolean = configured

	/** 造一个符合压缩协议的 JSON 回复 */
	companion object {
		fun compressionJson(
			summary: String,
			cards: List<Triple<String, String, String>> = emptyList(),
			importance: Int = 4,
		): String {
			val cardJson = cards.joinToString(",") { (type, keyword, content) ->
				"""{"type":"$type","keyword":"$keyword","content":"$content","importance":$importance}"""
			}
			return """{"summary":"$summary","cards":[$cardJson]}"""
		}
	}
}
