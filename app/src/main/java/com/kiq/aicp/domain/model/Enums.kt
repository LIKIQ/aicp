// app/src/main/java/com/kiq/aicp/domain/model/Enums.kt
// 全局枚举。存库时统一转成字符串（见 data/db/converter/Converters.kt），
// 不用 ordinal —— 将来往中间插一个枚举值就会把老数据全错位。

package com.kiq.aicp.domain.model

/** 消息角色。跟 OpenAI 的 role 字段一一对应 */
enum class ChatRole {
	USER,
	ASSISTANT,
	SYSTEM,
}

/** 会话形态：单人一对一，或多性格同场群聊 */
enum class ConversationMode {
	SINGLE,
	GROUP,
}

/** 消息生命周期。流式回复期间是 STREAMING，正常收尾转 OK */
enum class MessageStatus {
	PENDING,
	STREAMING,
	OK,
	FAILED,
}

/** 消息附件类型。图片走视觉模型，文件在选中时就抽成文本 */
enum class AttachmentKind {
	IMAGE,
	FILE,
}

/**
 * 记忆卡片类型。
 * 压缩时让模型按这几类抽取，抽出来的东西才好归类、好去重、好在设置页里给人看。
 */
enum class MemoryCardType {
	/** 客观事实：住哪、干什么工作、家里几只猫 */
	FACT,

	/** 喜好与忌讳：爱吃辣、讨厌被叫全名 */
	PREFERENCE,

	/** 发生过的事：上周面试、昨天吵架 */
	EVENT,

	/** 关系与约定：叫我 KIQ、说过每天提醒我喝水 */
	RELATION,

	/** 性格对用户的印象，跨会话累积，只对该性格生效 */
	IMPRESSION,
	;

	companion object {
		fun fromOrNull(raw: String?): MemoryCardType? =
			entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) }
	}
}
