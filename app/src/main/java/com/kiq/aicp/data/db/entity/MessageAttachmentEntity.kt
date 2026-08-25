// app/src/main/java/com/kiq/aicp/data/db/entity/MessageAttachmentEntity.kt
// 消息附件表（v2 新增）。一条消息可以带多个附件，所以是独立表而不是给 messages 加几列。
//
// localPath 存的是应用私有目录下的相对路径，不是 SAF 的 content:// URI ——
// SAF 授权在重启后可能失效，而且用户在系统相册里删掉原图后我们就再也读不到了。
// 选完立刻复制一份进私有目录，代价是占点空间，换来的是历史消息永远打得开。
//
// extractedText 给文件用：选中文件时就把正文抽出来存好，
// 后面每次拼上下文直接读这一列，不用反复解压 docx 或重读大文件。

package com.kiq.aicp.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.kiq.aicp.domain.model.AttachmentKind

@Entity(
	tableName = "message_attachments",
	foreignKeys = [
		ForeignKey(
			entity = MessageEntity::class,
			parentColumns = ["id"],
			childColumns = ["messageId"],
			onDelete = ForeignKey.CASCADE,
		),
	],
	indices = [Index("messageId")],
)
data class MessageAttachmentEntity(
	@PrimaryKey(autoGenerate = true)
	val id: Long = 0,

	val messageId: Long,

	val kind: AttachmentKind,

	/** 相对 filesDir 的路径，形如 attachments/1724500000000_a1b2.jpg */
	val localPath: String,

	val mimeType: String,

	/** 展示用的原始文件名 */
	val fileName: String,

	val byteSize: Long,

	/** 图片才有；文件一律存 0 */
	val width: Int = 0,
	val height: Int = 0,

	/** 文件抽出来的正文（图片留 null）。过长的部分在抽取时就已经截断并记进 truncated */
	val extractedText: String? = null,

	/** 正文被截断过：拼上下文时要告诉模型"这只是文件的前一部分" */
	val truncated: Boolean = false,

	/** 用户勾了"这是带文字的截图"：走高清档，detail 传 high */
	val textHeavy: Boolean = false,

	val createdAt: Long,
)
