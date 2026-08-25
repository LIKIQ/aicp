// app/src/main/java/com/kiq/aicp/data/repo/ProactiveLogDao.kt
// 主动搭话的调用记录（v4 新增）。表里只记"今天发了几次"和"最后一次是几点"。
//
// 为什么单独建表而不是翻 messages 数？
// 后台唤醒时 WorkManager 要给一个尽量短的答案：今天主动发了几次、上次主动是哪天。
// 翻 messages 得 JOIN conversations 过滤 proactive 标记，麻烦且慢。
// 一张小表两行 SQL 搞定，还天然跟用户手动发的消息区分开。
//
// 用户手动发消息会顺手清掉 count —— 这是个重要细节：今天你主动找它聊过，
// "它今天已经主动搭话 X 次"的配额就该归零重算，不然会出现它突然又插一句的观感。

package com.kiq.aicp.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.kiq.aicp.data.db.entity.ProactiveLogEntity
import java.time.LocalDate

@Dao
interface ProactiveLogDao {

	@Query("SELECT * FROM proactive_logs WHERE personaId = :personaId LIMIT 1")
	suspend fun byPersona(personaId: Long): ProactiveLogEntity?

	@Insert(onConflict = OnConflictStrategy.REPLACE)
	suspend fun upsert(log: ProactiveLogEntity)

	@Query("DELETE FROM proactive_logs WHERE personaId = :personaId")
	suspend fun deleteByPersona(personaId: Long)
}
