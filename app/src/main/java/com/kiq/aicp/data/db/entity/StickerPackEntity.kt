// app/src/main/java/com/kiq/aicp/data/db/entity/StickerPackEntity.kt
// 表情包分组表（v3 新增）。用户自己从相册导入的表情按组归类，比如"熊猫头""猫猫虫"。
//
// 不预置任何表情：微信、QQ 那些是有版权的美术资源，打包进 APK 分发有风险，
// 这一点已经跟 KIQ 说清楚了。所以初始状态是空的，全靠用户 SAF 导入。
//
// sortOrder 让用户能把常用组拖到前面。相同 sortOrder 时按 createdAt 兜底排，
// 不然导入两组后顺序会随查询计划漂。

package com.kiq.aicp.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
	tableName = "sticker_packs",
	indices = [Index(value = ["name"], unique = true)],
)
data class StickerPackEntity(
	@PrimaryKey(autoGenerate = true)
	val id: Long = 0,

	/** 分组名，同时也是唯一键 —— 两个同名分组在管理界面里没法区分 */
	val name: String,

	val sortOrder: Int = 0,

	/**
	 * 这个分组是不是应用自带的预设。
	 *
	 * 表情面板要把"内置预设"和"我自己导的"分开展示，而这件事没法靠名字猜 ——
	 * 用户完全可以自己建一个叫「开心」的分组。所以在建组时就记下来源。
	 */
	val builtIn: Boolean = false,

	val createdAt: Long,
)
