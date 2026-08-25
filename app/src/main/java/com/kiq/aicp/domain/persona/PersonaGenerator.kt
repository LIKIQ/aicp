// app/src/main/java/com/kiq/aicp/domain/persona/PersonaGenerator.kt
// 用一句话描述生成完整人设。
//
// 提示词里有两条是踩过坑之后加的：
// 1. 强制要求 systemPrompt 里至少写一条禁忌 —— 不写的话模型给出的人设千篇一律都是
//    "友好、耐心、乐于助人"，跑起来全是客服腔，四个角色说话一个味。
// 2. 明确禁止在 systemPrompt 里提记忆和上下文机制 —— 那部分由 ContextBuilder 统一拼，
//    混进人设里会导致用户改人设时把记忆规则一起改坏。

package com.kiq.aicp.domain.persona

import com.kiq.aicp.data.remote.LlmMessage
import com.kiq.aicp.data.remote.LlmParams
import com.kiq.aicp.data.remote.LlmProvider
import com.kiq.aicp.domain.model.AicpSettings
import com.kiq.aicp.domain.model.ChatRole
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

data class GeneratedPersona(
	val name: String,
	val avatarEmoji: String,
	val tagline: String,
	val systemPrompt: String,
	val greeting: String,
	val temperature: Float,
	val topP: Float,
	val maxTokens: Int,
)

class PersonaGenerator(private val llmProvider: LlmProvider) {

	suspend fun generate(description: String, settings: AicpSettings): GeneratedPersona {
		val raw = llmProvider.complete(
			messages = listOf(
				LlmMessage(ChatRole.SYSTEM, SYSTEM_PROMPT),
				LlmMessage(ChatRole.USER, "用户的描述：${description.trim()}"),
			),
			params = LlmParams(
				model = settings.effectiveCompressModel(),
				temperature = 0.9f,
				topP = 0.95f,
				maxTokens = 1_200,
			),
		)
		return parse(raw, fallbackName = description)
	}

	companion object {
		private val json = Json { ignoreUnknownKeys = true; isLenient = true }

		val SYSTEM_PROMPT: String = """
			你在帮用户创建一个 AI 陪聊角色。用户给一句话描述，你把它扩写成完整人设。

			只输出一个 JSON 对象，不要 markdown 代码块，不要任何解释文字：
			{
			  "name": "2 到 6 个字的名字",
			  "avatarEmoji": "一个 emoji",
			  "tagline": "不超过 15 字的一句话简介",
			  "systemPrompt": "150 到 400 字的系统提示词",
			  "greeting": "不超过 30 字的开场白，用这个角色的语气说",
			  "temperature": 0.85,
			  "topP": 0.95,
			  "maxTokens": 1024
			}

			systemPrompt 的要求：
			- 用第二人称写，以"你叫XX"开头
			- 写清身份关系、说话风格（句子长短、语气词、要不要用 emoji）
			- 必须至少包含一条明确的禁忌（不说什么、不做什么）。
			  没有禁忌的人设跑起来全是客服腔，几个角色说话一个味
			- 不要提记忆、上下文、token、系统机制这类内容，那些由程序另外处理

			temperature 的取法：越随性活泼取 0.9 到 1.1，越理性克制取 0.3 到 0.6。
		""".trimIndent()

		@Serializable
		private data class PersonaDto(
			val name: String = "",
			val avatarEmoji: String = "",
			val tagline: String = "",
			val systemPrompt: String = "",
			val greeting: String = "",
			val temperature: Float = 0.85f,
			val topP: Float = 0.95f,
			val maxTokens: Int = 1024,
		)

		/** 解析失败也要给出能用的结果：把整段回复当系统提示词，其余字段兜默认值 */
		fun parse(raw: String, fallbackName: String): GeneratedPersona {
			val text = raw.trim()
			val body = extractJsonObject(stripCodeFence(text))
			val dto = body?.let { runCatching { json.decodeFromString<PersonaDto>(it) }.getOrNull() }

			if (dto == null || dto.systemPrompt.isBlank()) {
				return GeneratedPersona(
					name = fallbackName.trim().take(6).ifEmpty { "新角色" },
					avatarEmoji = "🙂",
					tagline = fallbackName.trim().take(15),
					systemPrompt = text.ifEmpty { "你是一个陪伴型角色，说话自然、有自己的态度。" },
					greeting = "",
					temperature = 0.85f,
					topP = 0.95f,
					maxTokens = 1024,
				)
			}

			return GeneratedPersona(
				name = dto.name.trim().take(12).ifEmpty { fallbackName.trim().take(6).ifEmpty { "新角色" } },
				avatarEmoji = firstEmojiOrDefault(dto.avatarEmoji),
				tagline = dto.tagline.trim().take(30),
				systemPrompt = dto.systemPrompt.trim(),
				greeting = dto.greeting.trim().take(60),
				temperature = dto.temperature.coerceIn(0f, 2f),
				topP = dto.topP.coerceIn(0f, 1f),
				maxTokens = dto.maxTokens.coerceIn(256, 8_192),
			)
		}

		/** emoji 可能是代理对，按码点取第一个字符 */
		private fun firstEmojiOrDefault(value: String): String {
			val trimmed = value.trim()
			if (trimmed.isEmpty()) return "🙂"
			val first = trimmed.codePointAt(0)
			return String(Character.toChars(first))
		}

		private fun stripCodeFence(text: String): String {
			if (!text.startsWith("```")) return text
			return text.removePrefix("```json")
				.removePrefix("```JSON")
				.removePrefix("```")
				.removeSuffix("```")
				.trim()
		}

		private fun extractJsonObject(text: String): String? {
			val start = text.indexOf('{')
			val end = text.lastIndexOf('}')
			return if (start >= 0 && end > start) text.substring(start, end + 1) else null
		}
	}
}
