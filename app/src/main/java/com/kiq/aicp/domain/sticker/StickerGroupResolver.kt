/*
 * app/src/main/java/com/kiq/aicp/domain/sticker/StickerGroupResolver.kt
 * 把模型写的 [情绪] 换成 [具体某张表情的标记]
 * 职责：
 * - 模型只被告知情绪标签（开心、无语），它写 [开心]，这里挑一张对应的图换进去
 * - 已经是具体标记的不动，所以流式回复里反复调用也不会让同一处表情跳来跳去
 *
 * 情绪从哪来有两条路，这一层不关心：
 * 分组名本身是情绪时整组共用组名；分组名是"我的收藏"这类时，
 * 用每张图识图识出来的情绪。两条路都归到同一个标签集合里传进来。
 *
 * 为什么替换要发生在这一层、而不是渲染时按情绪随机取图：
 * 渲染会因为重组、滚动、深浅色切换反复跑，那里随机的话同一条消息的表情会不停变脸。
 * 替换必须在文本定型（累积/落库）时做一次，之后消息里存的就是确定的那张图。
 *
 * 情绪标签和某张图的标记撞名时，按标记处理而不是情绪：
 * 标记是确定的一张图，情绪要再挑一次，能确定就别引入随机。
 */
package com.kiq.aicp.domain.sticker

object StickerGroupResolver {

	/**
	 * @param emotions 当前可用的情绪标签（来自情绪分组的组名，或组内图片识别出的情绪）
	 * @param knownLabels 已知的单张表情标记，撞名时优先当标记
	 * @param pick 传入情绪，返回挑中那张的标记；这个情绪下没有图就返回 null
	 */
	fun resolve(
		text: String,
		emotions: Set<String>,
		knownLabels: Set<String>,
		pick: (String) -> String?,
	): String {
		if (text.isEmpty() || emotions.isEmpty()) return text

		return StickerParser.MARKER.replace(text) { match ->
			val name = match.groupValues[1]
			if (name in emotions && name !in knownLabels) {
				pick(name)?.let { "[$it]" } ?: match.value
			} else {
				match.value
			}
		}
	}

	/** 这段文字里有没有还没换掉的情绪标签，省掉没必要的字符串重建 */
	fun hasEmotionMarker(text: String, emotions: Set<String>, knownLabels: Set<String>): Boolean {
		if (text.isEmpty() || emotions.isEmpty()) return false
		return StickerParser.MARKER.findAll(text).any {
			val name = it.groupValues[1]
			name in emotions && name !in knownLabels
		}
	}

	/**
	 * 这段文字里还没换掉的情绪，按出现顺序去重。
	 *
	 * 单独开这个口子是因为挑图要查库（suspend），而 resolve 是纯函数：
	 * 调用方只能"先问有哪些情绪 → 逐个取好图 → 再同步替换"这个顺序。
	 * 让调用方自己去扫正则的话，两边的匹配规则早晚会走岔。
	 */
	fun emotionsIn(text: String, emotions: Set<String>, knownLabels: Set<String>): List<String> {
		if (text.isEmpty() || emotions.isEmpty()) return emptyList()
		return StickerParser.MARKER.findAll(text)
			.map { it.groupValues[1] }
			.filter { it in emotions && it !in knownLabels }
			.distinct()
			.toList()
	}
}
