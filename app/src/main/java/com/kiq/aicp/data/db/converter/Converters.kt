// app/src/main/java/com/kiq/aicp/data/db/converter/Converters.kt
// 枚举 ↔ 文本 的双向转换。
// 统一用 name 而不是 ordinal：以后往枚举中间插一个值，ordinal 会把已存的老数据全错位。
// 读的时候一律带兜底 —— 库里出现了当前版本不认识的枚举名（降级安装、手改过库）不能让整个查询崩掉。

package com.kiq.aicp.data.db.converter

import androidx.room.TypeConverter
import com.kiq.aicp.domain.model.AttachmentKind
import com.kiq.aicp.domain.model.ChatRole
import com.kiq.aicp.domain.model.ConversationMode
import com.kiq.aicp.domain.model.MemoryCardType
import com.kiq.aicp.domain.model.MessageStatus

class Converters {

	@TypeConverter
	fun fromChatRole(value: ChatRole): String = value.name

	@TypeConverter
	fun toChatRole(value: String): ChatRole =
		ChatRole.entries.firstOrNull { it.name == value } ?: ChatRole.USER

	@TypeConverter
	fun fromConversationMode(value: ConversationMode): String = value.name

	@TypeConverter
	fun toConversationMode(value: String): ConversationMode =
		ConversationMode.entries.firstOrNull { it.name == value } ?: ConversationMode.SINGLE

	@TypeConverter
	fun fromMessageStatus(value: MessageStatus): String = value.name

	@TypeConverter
	fun toMessageStatus(value: String): MessageStatus =
		MessageStatus.entries.firstOrNull { it.name == value } ?: MessageStatus.OK

	@TypeConverter
	fun fromMemoryCardType(value: MemoryCardType): String = value.name

	@TypeConverter
	fun toMemoryCardType(value: String): MemoryCardType =
		MemoryCardType.entries.firstOrNull { it.name == value } ?: MemoryCardType.FACT

	@TypeConverter
	fun fromAttachmentKind(value: AttachmentKind): String = value.name

	@TypeConverter
	fun toAttachmentKind(value: String): AttachmentKind =
		AttachmentKind.entries.firstOrNull { it.name == value } ?: AttachmentKind.FILE
}
