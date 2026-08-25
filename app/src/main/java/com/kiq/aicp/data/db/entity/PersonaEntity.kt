// app/src/main/java/com/kiq/aicp/data/db/entity/PersonaEntity.kt
// 性格（人格）表。内置预设和用户自建的都躺在这张表里，只用 isBuiltIn 区分。
//
// 头像有两条路：avatarEmoji 是保底（一个字符，零成本），avatarPath 是用户从相册选的图。
// 两个都留着而不是只保留图片：绝大多数性格用户懒得配图，emoji 现成就能用；
// 而 avatarPath 有值时优先，因为它是用户明确花力气选过的。
//
// note 是给人看的私人备注，跟 tagline 的区别在于 tagline 是"人设简介"（将来可能进 prompt），
// note 明确永不进 prompt —— KIQ 要的就是一个只给自己看的地方。

package com.kiq.aicp.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "personas")
data class PersonaEntity(
	@PrimaryKey(autoGenerate = true)
	val id: Long = 0,

	val name: String,

	/** 单个 emoji，没配图片头像时用它 */
	val avatarEmoji: String,

	/**
	 * 头像图片的相对路径（avatars/xxx.png）。null 表示没配图，回退到 avatarEmoji。
	 * 存路径不存 content:// URI：SAF 授权会失效，用户删掉原图后头像就空了。
	 */
	@ColumnInfo(defaultValue = "NULL")
	val avatarPath: String? = null,

	/** 一句话人设，只给人看，不进 prompt */
	val tagline: String,

	/**
	 * 用户自己写的备注，**永远不进 prompt**。
	 * 加这条字段的原始需求："备注之类只需要给用户看"。
	 * 以后要往 prompt 里加东西请改 systemPrompt 或 tagline，别动这里。
	 */
	@ColumnInfo(defaultValue = "''")
	val note: String = "",

	/** 真正喂给模型的系统提示词 */
	val systemPrompt: String,

	/** 新会话的第一句话，空串表示不主动开口 */
	val greeting: String,

	val temperature: Float,
	val topP: Float,
	val maxTokens: Int,

	/** 这个性格专用的模型；null 表示跟随设置里的全局模型 */
	val modelOverride: String? = null,

	/** 内置预设。允许改、允许禁用，但不允许删（删了就回不来了） */
	val isBuiltIn: Boolean = false,

	/** 手动排序用，越小越靠前 */
	val sortOrder: Int = 0,

	/** 由"描述一句话自动生成"产出的性格，留个标记方便回溯 */
	val generatedFromPrompt: String? = null,

	/**
	 * 允许这个性格主动搭话（v2 新增）。默认关：没经用户同意就自己冒出来发消息很讨人厌，
	 * 而且要先拿到通知权限才有意义。
	 */
	@ColumnInfo(defaultValue = "0")
	val proactiveEnabled: Boolean = false,

	val createdAt: Long,
	val updatedAt: Long,
)
