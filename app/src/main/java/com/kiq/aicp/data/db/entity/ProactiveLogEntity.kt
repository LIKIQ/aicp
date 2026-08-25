// app/src/main/java/com/kiq/aicp/data/db/entity/ProactiveLogEntity.kt
// 主动搭话记账表（v4 新增）。
//
// date 存 yyyy-MM-dd 字符串而不是 epoch 毫秒：后台醒来只要判断"是不是今天"，
// 跟 LocalDate.now() 直接比字符串就完事，省掉时区换算这个错误来源。
//
// **刻意不加外键**。这张表有一行 personaId = 0 的全局记录，代表"今天总共主动搭话几次"
// （每日配额是全局的：五个性格各发三次就是十五次骚扰，那不是用户想要的）。
// 挂了外键 personaId=0 就写不进去 —— 这一点是被 MigrationV3ToV4Test 实测证实的，
// 不是推测。代价是删性格后会残留一行记账，一行二十来字节，且 personas 用的是
// AUTOINCREMENT 不复用 id，残留记录不会被新性格误读。这个代价比为了外键洁癖
// 去改一套记账方案划算。

package com.kiq.aicp.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "proactive_logs")
data class ProactiveLogEntity(
	/** 真实性格 id，或 0 表示全局配额记录 */
	@PrimaryKey
	val personaId: Long,

	/** yyyy-MM-dd，本地日期。跨天判断靠它跟 LocalDate.now() 比 */
	val date: String,

	/** 这个日期内已经主动搭话几次 */
	val count: Int,
)
