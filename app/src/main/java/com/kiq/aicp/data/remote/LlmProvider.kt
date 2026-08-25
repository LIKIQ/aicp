// app/src/main/java/com/kiq/aicp/data/remote/LlmProvider.kt
// LLM 访问的抽象层 + 领域内的请求/响应模型 + 错误分类。
//
// 抽象成接口的两个理由：
// 1. 压缩引擎和"描述生成性格"都要调模型，但它们只需要一次性结果，不需要流式
// 2. 单测里塞一个 FakeLlmProvider，就能在完全离线的情况下测压缩策略
//
// 错误必须分类而不是笼统抛 IOException：UI 上"没配 Key""余额不足""网络断了"
// 要给完全不同的提示和不同的重试策略。

package com.kiq.aicp.data.remote

import com.kiq.aicp.domain.model.ChatRole
import kotlinx.coroutines.flow.Flow

/**
 * 随消息一起发出的图片。
 * base64 不带 data URI 前缀，前缀由 Provider 按 mimeType 拼 —— 查证确认那层前缀是必需的。
 * highDetail 对应接口里的 detail:"high"：只在"带文字的截图"这种场景开，
 * 因为 low 档会被服务端强降到 512×512，小字必丢；而 high 更贵。
 */
data class LlmImage(
	val base64: String,
	val mimeType: String,
	val highDetail: Boolean = false,
)

/** 发给模型的一条消息。name 用于群聊场景标注说话人 */
data class LlmMessage(
	val role: ChatRole,
	val content: String,
	val name: String? = null,
	/**
	 * 只有 user 消息能带图。这不是我的约定，是接口的硬限制 ——
	 * DeepSeek 文档明确写了图片放 system/assistant 会直接 400。
	 */
	val images: List<LlmImage> = emptyList(),
)

/** 单次请求的采样参数。model 为空表示用设置里的全局默认模型 */
data class LlmParams(
	val model: String,
	val temperature: Float = 0.8f,
	val topP: Float = 0.95f,
	val maxTokens: Int = 1024,
)

sealed interface LlmChunk {
	/** 增量文本片段 */
	data class Delta(val text: String) : LlmChunk

	/** 流正常结束。finishReason 可能是 stop / length / content_filter，也可能服务端没给 */
	data class Done(val finishReason: String?) : LlmChunk
}

class LlmException(
	message: String,
	val kind: Kind,
	cause: Throwable? = null,
) : Exception(message, cause) {

	enum class Kind {
		/** 还没填 Base URL 或 API Key */
		NO_CONFIG,

		/** 目标地址被明文守门人拒了 */
		CLEARTEXT_BLOCKED,

		/** 连不上、超时、DNS 失败 */
		NETWORK,

		/** 401/403：Key 不对或没权限 */
		AUTH,

		/** 429：限流或余额不足 */
		RATE_LIMIT,

		/** 5xx */
		SERVER,

		/** 4xx（非鉴权类），通常是参数或模型名不对 */
		BAD_REQUEST,

		/** 返回体解析不出来 */
		BAD_RESPONSE,
		;

		/** 这类错误重试是否有意义 */
		val retryable: Boolean
			get() = this == NETWORK || this == RATE_LIMIT || this == SERVER
	}
}

interface LlmProvider {

	/** 流式对话，逐片吐出增量。失败时 Flow 抛 LlmException */
	fun streamChat(messages: List<LlmMessage>, params: LlmParams): Flow<LlmChunk>

	/** 一次性拿完整回复。压缩、生成人设这类后台任务用它 */
	suspend fun complete(messages: List<LlmMessage>, params: LlmParams): String

	/** 当前配置是否够用（Base URL + Key 都填了）。UI 用它决定要不要提示去设置页 */
	suspend fun isConfigured(): Boolean
}
