// app/src/main/java/com/kiq/aicp/data/repo/ProactiveRepository.kt
// 主动搭话的后台数据查询，WorkManager 醒来时在 work 协程里跑。
//
// 故意不接 Flow：后台任务只需要"此刻快照"——哪个会话该说话、哪个性格开口、
// 今天还剩几次额度。开 Flow 订阅还得在 work 协程里手动取消，白费功夫。
//
// pickSpeaker 的规则：只有 user 主动发过消息的会话才考虑（有开场白但没有对话的
// 新会话不搭话——开场白是欢迎语，不是聊天）。挑最近活跃的，随机打破平局。

package com.kiq.aicp.data.repo

import com.kiq.aicp.data.db.AicpDatabase
import com.kiq.aicp.data.db.entity.ProactiveLogEntity
import com.kiq.aicp.domain.model.ChatRole
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.random.Random

data class ProactiveTarget(
	val conversationId: Long,
	val personaId: Long,
	val personaName: String,
	/** 这个会话里最后一条消息的 id，用来在消息列表里定位有没有更新的消息 */
	val lastMessageId: Long?,
)

class ProactiveRepository(
	private val db: AicpDatabase,
) {

	private val convDao = db.conversationDao()
	private val msgDao = db.messageDao()
	private val personaDao = db.personaDao()
	private val logDao = db.proactiveLogDao()

	/** 今天还能主动搭话几次（全局），0 表示额度用完 */
	suspend fun remainingQuota(dailyLimit: Int): Int {
		if (dailyLimit <= 0) return 0
		val today = LocalDate.now().format(DATE)
		val used = logDao.byPersona(0)?.takeIf { it.date == today }?.count ?: 0
		return (dailyLimit - used).coerceAtLeast(0)
	}

	/** 全局累计一次主动搭话次数。personaId=0 是保留的全局占位记录 */
	suspend fun recordGlobalProactive() {
		val today = LocalDate.now().format(DATE)
		val existing = logDao.byPersona(0)
		val count = existing?.takeIf { it.date == today }?.count ?: 0
		logDao.upsert(
			ProactiveLogEntity(
				personaId = 0,
				date = today,
				count = count + 1,
			),
		)
	}

	/** 所有活跃会话里挑一个值得搭话的。null 表示没有合适的 */
	suspend fun pickTarget(): ProactiveTarget? {
		val candidates = mutableListOf<Pair<ProactiveTarget, Long>>()

		for (conv in convDao.activeList()) {
			val recent = msgDao.recentRaw(conv.id, 3)
			// 只有开场白、用户一句都没说过的会话不搭话 —— 开场白是欢迎语，不算聊过
			if (recent.none { it.role == ChatRole.USER }) continue

			val participants = convDao.getParticipants(conv.id)
			if (participants.isEmpty()) continue

			// 挑一个不是最后发言人的性格，群聊里避免同一个人连着自言自语
			val lastPersona = recent.firstOrNull()?.personaId
			val speaker = participants.firstOrNull { it.personaId != lastPersona }
				?: participants.first()
			val persona = personaDao.getById(speaker.personaId) ?: continue

			val target = ProactiveTarget(
				conversationId = conv.id,
				personaId = persona.id,
				personaName = persona.name,
				lastMessageId = recent.firstOrNull()?.id,
			)
			// 加一点随机量打破平局，免得每次都盯着同一个会话
			candidates += target to (recent.firstOrNull()?.createdAt ?: 0L) + Random.nextLong(0, 60_000L)
		}

		return candidates.maxByOrNull { it.second }?.first
	}

	companion object {
		private val DATE = DateTimeFormatter.ISO_LOCAL_DATE
	}
}
