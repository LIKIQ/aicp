/*
 * app/src/main/java/com/kiq/aicp/domain/config/ConfigDiff.kt
 * 配置码导入前的差异预览
 * 职责：
 * - 把"当前设置"和"配置码里的设置"逐项比对，只列出真的会变的项
 * - 每条输出一句人话，直接给确认框用
 *
 * 为什么必须有这一步：
 * 导入会一次覆盖接口、模型、以及二十几个调优开关，这些都是用户慢慢调出来的，
 * 覆盖之后没有撤销。不给他看清"哪几项会变"就点确认，等于让他闭着眼睛赌。
 * apiKey 只说"会被覆盖"，不显示值——预览框是会被截图和录屏的地方。
 */
package com.kiq.aicp.domain.config

import com.kiq.aicp.domain.model.AicpSettings

object ConfigDiff {

	/** 一个可比对的设置项：中文名 + 怎么把它渲染成一句可读的值 */
	private class Field(val label: String, val render: (AicpSettings) -> String)

	private fun onOff(flag: Boolean) = if (flag) "开" else "关"

	private fun blankAs(text: String, fallback: String) = text.ifBlank { fallback }

	/**
	 * 比对清单。加设置项时这里补一行，忘了补不会出错但预览会漏报那一项，
	 * ConfigDiffTest 用反射盯着字段总数，漏了会红。
	 */
	private val fields = listOf(
		Field("接口地址") { blankAs(it.baseUrl, "未填") },
		Field("主模型") { blankAs(it.model, "未填") },
		Field("记住 API Key") { onOff(it.rememberApiKey) },
		Field("压缩模型") { blankAs(it.compressModel, "跟随主模型") },
		Field("识图模型") { blankAs(it.visionModel, "跟随主模型") },
		Field("每轮带图上限") { "${it.maxImagesInContext} 张" },
		Field("自动整理记忆") { onOff(it.autoCompressEnabled) },
		Field("上下文预算") { "${it.contextBudgetTokens} token" },
		Field("保留最近消息") { "${it.keepRecentMessages} 条" },
		Field("压缩触发 token") { "${it.compressTriggerTokens}" },
		Field("压缩触发条数") { "${it.compressTriggerCount} 条" },
		Field("摘要合并阈值") { "${it.summaryMergeThreshold} 份" },
		Field("记忆条目上限") { "${it.memoryCardLimit} 条" },
		Field("群聊每轮发言人") { "${it.groupMaxSpeakersPerTurn} 个" },
		Field("表情包") { onOff(it.stickersEnabled) },
		Field("表情清单上限") { "${it.stickerPromptLimit} 个" },
		Field("真人模拟") { onOff(it.humanizeEnabled) },
		Field("最多分几条发") { "${it.humanizeMaxSegments} 条" },
		Field("每字打字耗时") { "${it.humanizeMsPerChar} 毫秒" },
		Field("看完消息的停顿") { "${it.humanizeReadDelayMs} 毫秒" },
		Field("主动搭话") { onOff(it.proactiveEnabled) },
		Field("多久没聊算闲着") { "${it.proactiveIdleMinutes} 分钟" },
		Field("主动搭话推送") { onOff(it.proactivePushEnabled) },
		Field("每天最多搭话") { "${it.proactiveDailyLimit} 次" },
		Field("免打扰起点") { "${it.quietHoursStart} 点" },
		Field("免打扰终点") { "${it.quietHoursEnd} 点" },
		Field("保持后台运行") { onOff(it.keepAliveEnabled) },
		Field("记忆规则") { blankAs(it.memorySchema.take(20), "没写") },
		Field("联网搜索") { onOff(it.webSearchEnabled) },
		Field("搜索结果条数") { "${it.webSearchResultCount} 条" },
		Field("抓正文篇数") { if (it.webSearchFetchPages == 0) "只用摘要" else "${it.webSearchFetchPages} 篇" },
		Field("每篇正文字数") { "${it.webSearchPageChars} 字" },
		Field("搜索结果预算") { "${it.webSearchBudgetTokens} token" },
		Field("跟随系统取色") { onOff(it.dynamicColor) },
	)

	/**
	 * 会变的项，每条形如"主模型：gpt-4o → claude-opus"。
	 * 一条都没有时返回空列表，UI 那边应该据此告诉用户"这段码跟当前设置一模一样"，
	 * 而不是弹一个空荡荡的确认框让他确认"没有变化"。
	 */
	fun describe(current: AicpSettings, incoming: AicpSettings): List<String> =
		fields.mapNotNull { field ->
			val before = field.render(current)
			val after = field.render(incoming)
			if (before == after) null else "${field.label}：$before → $after"
		}

	/**
	 * 这段配置码会不会覆盖 API Key。
	 * 单独拎出来是因为它不能进 describe——那些字符串会显示在屏幕上、
	 * 被截图、被贴进聊天记录，凭证不该出现在任何一句预览文案里。
	 */
	fun overwritesApiKey(incoming: AicpSettings): Boolean = incoming.apiKey.isNotBlank()

	/** 给测试用：比对清单覆盖了几项 */
	val fieldCount: Int get() = fields.size
}
