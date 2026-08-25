// app/src/main/java/com/kiq/aicp/domain/humanize/ProactiveDecider.kt
// 判断"现在该不该主动搭话"。前台空闲触发和后台 WorkManager 唤醒共用这一份逻辑。
//
// 抽成纯函数是为了能测：主动搭话最怕的两件事——在用户刚发完消息等回复时插嘴、
// 已经连发好几条还在刷屏——都是靠"末尾连续 assistant 条数"这一个条件挡掉的，
// 写错了在真机上极难复现，必须用单测钉死。
//
// 前台和后台唯一的区别是免打扰：前台是用户自己开着 App 看着，不算打扰，
// 所以 respectQuietHours 传 false；后台推送才传 true。逻辑只有一份，差异在入参。

package com.kiq.aicp.domain.humanize

data class ProactiveContext(
	/** 会话里有几个可发言性格。0 表示没人，不可能搭话 */
	val participantCount: Int,
	/** 从最后一条消息往前数，连续的 assistant 消息条数 */
	val trailingAssistantCount: Int,
	/** 最后一条消息距现在多少毫秒 */
	val idleMillis: Long,
	/** 今天已经主动搭话几次 */
	val todayProactiveCount: Int,
	/** 当前小时 0..23，用于免打扰判断 */
	val hourOfDay: Int,
)

sealed interface ProactiveDecision {
	object Go : ProactiveDecision

	/** 带上原因，方便调试和在设置页解释"为什么没搭话" */
	data class Skip(val reason: String) : ProactiveDecision

	val shouldSpeak: Boolean get() = this is Go
}

object ProactiveDecider {

	/**
	 * 追加在上下文末尾的一句指令，前台和后台共用。
	 * 写得具体一点，否则模型会输出"有什么可以帮您的吗"这种客服腔，跟陪伴场景完全不搭。
	 */
	const val INSTRUCTION: String =
		"现在是你主动找对方说话，对方还没有回你上一条。" +
			"根据你们聊过的内容说一句自然的话：可以是想起了什么、关心一下、或者分享点自己的事。" +
			"只说一两句，不要问「有什么可以帮你」这种客套话，也不要提你是主动发消息的。"

	/** 主动搭话就一两句，给大 maxTokens 只会让它写小作文 */
	const val MAX_TOKENS = 200

	/**
	 * 末尾允许的连续 assistant 条数上限。
	 * < 2 才放行意味着：用户最后说了话（末尾是 user，count=0）可以搭话；
	 * AI 已主动说过一条（count=1）还能再补一条；连发两条后就闭嘴，等用户回。
	 */
	const val MAX_TRAILING_ASSISTANT = 2

	fun decide(
		settings: com.kiq.aicp.domain.model.AicpSettings,
		ctx: ProactiveContext,
		respectQuietHours: Boolean,
	): ProactiveDecision {
		if (!settings.proactiveEnabled) return ProactiveDecision.Skip("主动搭话已关闭")
		if (ctx.participantCount <= 0) return ProactiveDecision.Skip("会话里没有性格")

		// 末尾已经堆了两条以上 AI 消息：再发就是自言自语刷屏
		if (ctx.trailingAssistantCount >= MAX_TRAILING_ASSISTANT) {
			return ProactiveDecision.Skip("已经连着说了好几句，等对方回应")
		}

		val idleThreshold = settings.proactiveIdleMinutes.toLong() * 60_000L
		if (ctx.idleMillis < idleThreshold) {
			return ProactiveDecision.Skip("还没到空闲时长")
		}

		if (ctx.todayProactiveCount >= settings.proactiveDailyLimit) {
			return ProactiveDecision.Skip("今天主动搭话次数已用完")
		}

		if (respectQuietHours && settings.isQuietAt(ctx.hourOfDay)) {
			return ProactiveDecision.Skip("正处在免打扰时段")
		}

		return ProactiveDecision.Go
	}

	/**
	 * 从消息列表尾部数连续的 assistant 条数。
	 * 抽出来是因为"从尾部数同类"这种小逻辑最容易差一位，单独测。
	 */
	fun trailingAssistantCount(roles: List<Boolean>): Int {
		// roles: true = assistant, false = user
		var count = 0
		for (i in roles.indices.reversed()) {
			if (roles[i]) count++ else break
		}
		return count
	}
}
