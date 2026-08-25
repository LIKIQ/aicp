// app/src/main/java/com/kiq/aicp/data/db/entity/StickerEntity.kt
// 表情表（v3 新增）。
//
// label 是全局唯一而不是组内唯一，这是个硬约束不是洁癖：
// 模型回复里写的是 [开心] 这样一个裸标记，解析时手上没有任何分组信息，
// 组内唯一的话 [开心] 就可能同时命中"熊猫头/开心"和"猫猫虫/开心"，只能瞎猜一个。
// 导入时撞名就自动加数字后缀（开心、开心2、开心3），交给 StickerRepository 处理。
//
// localPath 跟消息附件同一套路：复制进私有目录，不留 content:// URI。
// 表情是要反复用的，原图被用户从相册删掉后还得能发。

package com.kiq.aicp.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
	tableName = "stickers",
	foreignKeys = [
		ForeignKey(
			entity = StickerPackEntity::class,
			parentColumns = ["id"],
			childColumns = ["packId"],
			onDelete = ForeignKey.CASCADE,
		),
	],
	indices = [
		Index("packId"),
		Index(value = ["label"], unique = true),
	],
)
data class StickerEntity(
	@PrimaryKey(autoGenerate = true)
	val id: Long = 0,

	val packId: Long,

	/** 模型和用户输入时写的标记文字，不含中括号。全局唯一 */
	val label: String,

	/** 相对 filesDir 的路径，形如 stickers/1724500000000_a1b2.png */
	val localPath: String,

	val mimeType: String,

	val byteSize: Long,

	val width: Int = 0,
	val height: Int = 0,

	/** 被发出去过几次。注入 system prompt 时按这个降序挑，常用的优先让模型看见 */
	val useCount: Int = 0,

	val createdAt: Long,
)
