// app/src/main/java/com/kiq/aicp/data/remote/OpenAiCompatProvider.kt
// OpenAI 兼容接口的实现（DeepSeek / 智谱 / Kimi / OpenRouter / ollama 兼容层都走这条）。
//
// 三个关键处理：
// 1. 发请求前先过 CleartextGuard，公网 http 直接拒，别把 Key 明文发出去
// 2. 流式用独立的 client：readTimeout 必须放开，模型思考十几秒是常态
// 3. 取消要真的能断连：flow 被取消时 call.cancel()，否则协程走了但 socket 还在读

package com.kiq.aicp.data.remote

import com.kiq.aicp.domain.model.ChatRole
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Provider 需要的那三项设置 */
data class LlmConfig(
	val baseUrl: String,
	val apiKey: String,
	val defaultModel: String,
)

class OpenAiCompatProvider(
	baseClient: OkHttpClient,
	private val configLoader: suspend () -> LlmConfig,
	private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : LlmProvider {

	private val json = Json {
		ignoreUnknownKeys = true
		isLenient = true
		encodeDefaults = true
	}

	private val oneShotClient = baseClient

	/** 流式连接不能用默认的读超时，模型可能想很久才吐第一个字 */
	private val streamClient = baseClient.newBuilder()
		.readTimeout(0, TimeUnit.MILLISECONDS)
		.build()

	override suspend fun isConfigured(): Boolean {
		val config = configLoader()
		return config.baseUrl.isNotBlank() && config.apiKey.isNotBlank()
	}

	override fun streamChat(messages: List<LlmMessage>, params: LlmParams): Flow<LlmChunk> = flow {
		val config = configLoader()
		val url = resolveUrl(config)
		val call = streamClient.newCall(buildRequest(url, config, messages, params, stream = true))

		// flow 被取消时把 socket 掐掉，不然阻塞在 readUtf8Line 上的线程会一直挂着
		currentCoroutineContext()[Job]?.invokeOnCompletion { call.cancel() }

		val response = try {
			call.execute()
		} catch (e: IOException) {
			throw LlmException("连接失败：${e.message ?: "网络异常"}", LlmException.Kind.NETWORK, e)
		}

		response.use { resp ->
			val body = resp.body
			if (!resp.isSuccessful) {
				throw httpError(resp.code, runCatching { body.string() }.getOrDefault(""))
			}

			val parser = SseParser()
			val source = body.source()
			var finishReason: String? = null
			var done = false

			while (!done && currentCoroutineContext().isActive) {
				val line = try {
					source.readUtf8Line()
				} catch (e: IOException) {
					if (!currentCoroutineContext().isActive) throw CancellationException("已取消")
					throw LlmException("读取流失败：${e.message ?: "连接中断"}", LlmException.Kind.NETWORK, e)
				} ?: break

				when (val event = parser.feedLine(line)) {
					is SseParser.Event.Data -> {
						val chunk = parseStreamPayload(event.payload) ?: continue
						chunk.choices.firstOrNull()?.let { choice ->
							choice.delta?.content?.takeIf { it.isNotEmpty() }?.let { emit(LlmChunk.Delta(it)) }
							choice.finishReason?.let { finishReason = it }
						}
					}

					SseParser.Event.Done -> done = true
					null -> Unit
				}
			}

			// 服务端没发空行就断流的情况，把残留的最后一段补上
			if (!done) {
				(parser.flush() as? SseParser.Event.Data)?.let { last ->
					parseStreamPayload(last.payload)?.choices?.firstOrNull()?.let { choice ->
						choice.delta?.content?.takeIf { it.isNotEmpty() }?.let { emit(LlmChunk.Delta(it)) }
						choice.finishReason?.let { finishReason = it }
					}
				}
			}

			emit(LlmChunk.Done(finishReason))
		}
	}.flowOn(ioDispatcher)

	override suspend fun complete(messages: List<LlmMessage>, params: LlmParams): String =
		withContext(ioDispatcher) {
			val config = configLoader()
			val url = resolveUrl(config)
			val call = oneShotClient.newCall(buildRequest(url, config, messages, params, stream = false))
			currentCoroutineContext()[Job]?.invokeOnCompletion { call.cancel() }

			val response = try {
				call.execute()
			} catch (e: IOException) {
				throw LlmException("连接失败：${e.message ?: "网络异常"}", LlmException.Kind.NETWORK, e)
			}

			response.use { resp ->
				val text = runCatching { resp.body.string() }.getOrDefault("")
				if (!resp.isSuccessful) throw httpError(resp.code, text)

				val parsed = runCatching { json.decodeFromString<OaCompletionResponse>(text) }
					.getOrElse {
						throw LlmException("响应解析失败", LlmException.Kind.BAD_RESPONSE, it)
					}
				parsed.choices.firstOrNull()?.message?.content?.trim()
					?: throw LlmException("模型没有返回内容", LlmException.Kind.BAD_RESPONSE)
			}
		}

	// ---------------- 内部 ----------------

	private fun resolveUrl(config: LlmConfig): String {
		if (config.baseUrl.isBlank() || config.apiKey.isBlank()) {
			throw LlmException("还没配置接口地址和 API Key", LlmException.Kind.NO_CONFIG)
		}
		val url = LlmEndpoint.chatCompletions(config.baseUrl)
		val (scheme, host) = LlmEndpoint.schemeAndHost(url)
			?: throw LlmException("接口地址格式不对：${config.baseUrl}", LlmException.Kind.NO_CONFIG)

		if (!CleartextGuard.isAllowed(scheme, host)) {
			throw LlmException(
				CleartextGuard.rejectReason(scheme, host),
				LlmException.Kind.CLEARTEXT_BLOCKED,
			)
		}
		return url
	}

	private fun buildRequest(
		url: String,
		config: LlmConfig,
		messages: List<LlmMessage>,
		params: LlmParams,
		stream: Boolean,
	): Request {
		val payload = OaChatRequest(
			model = params.model.ifBlank { config.defaultModel },
			messages = messages.map {
				OaMessage(
					role = it.role.toWireRole(),
					content = buildContent(it),
					name = it.name,
				)
			},
			temperature = params.temperature,
			topP = params.topP,
			maxTokens = params.maxTokens,
			stream = stream,
		)
		return Request.Builder()
			.url(url)
			.addHeader("Authorization", "Bearer ${config.apiKey}")
			.addHeader("Accept", if (stream) "text/event-stream" else "application/json")
			.post(json.encodeToString(payload).toRequestBody(JSON_MEDIA))
			.build()
	}

	/**
	 * 没图时 content 就是普通字符串（兼容性最好，所有服务都认）；
	 * 有图时才展开成数组。
	 *
	 * 两个刻意的顺序/位置约束，都是查证来的，别随手改：
	 * - text 必须排在 image 前面：OpenRouter 因为内容解析顺序的关系明确要求这样
	 * - 图片只挂 user 消息：DeepSeek 文档写明放 system/assistant 会直接 400，
	 *   所以非 user 角色的 images 一律忽略而不是硬塞
	 */
	private fun buildContent(message: LlmMessage): JsonElement {
		val images = if (message.role == ChatRole.USER) message.images else emptyList()
		if (images.isEmpty()) return JsonPrimitive(message.content)

		return buildJsonArray {
			if (message.content.isNotEmpty()) {
				addJsonObject {
					put("type", "text")
					put("text", message.content)
				}
			}
			images.forEach { image ->
				addJsonObject {
					put("type", "image_url")
					putJsonObject("image_url") {
						put("url", "data:${image.mimeType};base64,${image.base64}")
						// detail 不是所有服务都支持，只在确实需要高清时才传，其余交给服务端默认
						if (image.highDetail) put("detail", "high")
					}
				}
			}
		}
	}

	private fun parseStreamPayload(payload: String): OaStreamChunk? =
		runCatching { json.decodeFromString<OaStreamChunk>(payload) }.getOrNull()

	private fun httpError(code: Int, body: String): LlmException {
		val detail = extractErrorMessage(body)
		val kind = when {
			code == 401 || code == 403 -> LlmException.Kind.AUTH
			code == 429 -> LlmException.Kind.RATE_LIMIT
			code >= 500 -> LlmException.Kind.SERVER
			else -> LlmException.Kind.BAD_REQUEST
		}
		val prefix = when (kind) {
			LlmException.Kind.AUTH -> "鉴权失败（$code）"
			LlmException.Kind.RATE_LIMIT -> "被限流或余额不足（$code）"
			LlmException.Kind.SERVER -> "服务端错误（$code）"
			else -> "请求被拒绝（$code）"
		}
		return LlmException(if (detail.isEmpty()) prefix else "$prefix：$detail", kind)
	}

	private fun extractErrorMessage(body: String): String {
		if (body.isBlank()) return ""
		val parsed = runCatching { json.decodeFromString<OaErrorEnvelope>(body) }.getOrNull()
		val message = parsed?.error?.message ?: parsed?.message
		return message?.trim()?.take(200) ?: body.trim().take(200)
	}

	private fun ChatRole.toWireRole(): String = when (this) {
		ChatRole.USER -> "user"
		ChatRole.ASSISTANT -> "assistant"
		ChatRole.SYSTEM -> "system"
	}

	private companion object {
		private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
	}
}
