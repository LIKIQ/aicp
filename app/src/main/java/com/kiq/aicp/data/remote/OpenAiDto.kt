// app/src/main/java/com/kiq/aicp/data/remote/OpenAiDto.kt
// OpenAI 兼容接口的线上格式。
// 所有字段都给默认值 + Json 开 ignoreUnknownKeys：各家中转站会塞自己的扩展字段
// （usage、reasoning_content、system_fingerprint…），少一个默认值就会在半夜炸一次解析。

package com.kiq.aicp.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * content 声明成 JsonElement 而不是 String：纯文本时是字符串，带图时是
 * [{type:text,...},{type:image_url,...}] 数组。两种形状同一个字段，只能用 JsonElement 承载。
 * 构造逻辑在 OpenAiCompatProvider.buildContent()。
 */
@Serializable
internal data class OaMessage(
	val role: String,
	val content: JsonElement,
	val name: String? = null,
)

@Serializable
internal data class OaChatRequest(
	val model: String,
	val messages: List<OaMessage>,
	val temperature: Float,
	@SerialName("top_p") val topP: Float,
	@SerialName("max_tokens") val maxTokens: Int,
	val stream: Boolean,
)

// ---- 流式 ----

@Serializable
internal data class OaStreamDelta(
	val content: String? = null,
	val role: String? = null,
)

@Serializable
internal data class OaStreamChoice(
	val delta: OaStreamDelta? = null,
	@SerialName("finish_reason") val finishReason: String? = null,
)

@Serializable
internal data class OaStreamChunk(
	val choices: List<OaStreamChoice> = emptyList(),
)

// ---- 非流式 ----

/**
 * 回复里的 content 一律是字符串，所以这里不用 JsonElement。
 * 单独定义一个 OaReplyMessage 而不是复用 OaMessage：复用会被迫在读取侧做 JsonElement 拆箱，
 * 每个调用点都要判一次"是字符串还是数组"，不值。
 */
@Serializable
internal data class OaReplyMessage(
	val role: String = "assistant",
	val content: String = "",
)

@Serializable
internal data class OaCompletionChoice(
	val message: OaReplyMessage? = null,
	@SerialName("finish_reason") val finishReason: String? = null,
)

@Serializable
internal data class OaCompletionResponse(
	val choices: List<OaCompletionChoice> = emptyList(),
)

// ---- 错误体 ----

@Serializable
internal data class OaErrorBody(
	val message: String? = null,
	val type: String? = null,
	val code: String? = null,
)

@Serializable
internal data class OaErrorEnvelope(
	val error: OaErrorBody? = null,
	/** 有些中转站不套 error 层，直接返回 {"message": "..."} */
	val message: String? = null,
)
