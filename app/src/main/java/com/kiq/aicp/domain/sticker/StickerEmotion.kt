/*
 * app/src/main/java/com/kiq/aicp/domain/sticker/StickerEmotion.kt
 * 表情的情绪词表
 * 职责：
 * - 定义一套固定的情绪标签，模型选表情、识图分类、分组名判断三处共用同一份
 * - 判断一个分组名算不算情绪（"开心""开心的图"都算，"我的收藏"不算）
 *
 * 为什么用固定词表而不让模型自由发挥：
 * 让它自己描述情绪会得到"略带忧郁的欣喜"这种没法归类的东西，
 * 同一张图两次识别还可能给出两个说法，那这个标签就没有检索价值了。
 * 词表锁死之后，识图输出和分组名落在同一个坐标系里，才能混在一起选。
 *
 * 二十个的取舍：再多模型会在近义词之间犹豫（"伤心"还是"委屈"），
 * 再少表达不了日常聊天的层次。顺序即优先级，分组名撞上多个词时取靠前那个。
 */
package com.kiq.aicp.domain.sticker

object StickerEmotion {

	/** 全部情绪标签。改这里会影响已识别数据的可用性，加可以，改名要慎重 */
	val ALL = listOf(
		"开心", "大笑", "害羞", "得意", "撒娇",
		"无语", "尴尬", "疑惑", "思考", "惊讶",
		"伤心", "哭", "委屈", "生气", "无奈",
		"困", "累", "可爱", "点赞", "比心",
	)

	private val set = ALL.toSet()

	/** 这个标签是不是词表里的情绪 */
	fun isEmotion(label: String): Boolean = label.trim() in set

	/**
	 * 从分组名里认出情绪。
	 *
	 * 先试精确匹配，再试包含——用户建组习惯叫"开心的图""伤心表情包"，
	 * 只认精确的话这些组全都会被当成未分类，白白让他再跑一次识图。
	 */
	fun emotionOf(groupName: String): String? {
		val clean = groupName.trim()
		if (clean.isEmpty()) return null
		if (clean in set) return clean
		return ALL.firstOrNull { clean.contains(it) }
	}

	/** 从模型的识图回复里抠出情绪。认不出返回 null，调用方按"这张没识别成"处理 */
	fun parseReply(raw: String): String? {
		val text = raw.trim()
		if (text.isEmpty()) return null
		if (text in set) return text
		// 模型爱回"这张图是开心的表情"，扫一遍词表比让它严格输出更省事
		return ALL.firstOrNull { text.contains(it) }
	}

	fun visionSystem(): String = """
		你是表情包分类器。看一张表情图，判断它表达的情绪。

		只能从下面这些词里选一个，直接输出那个词，不要加标点、解释或者别的字：
		${ALL.joinToString("、")}

		判断依据是这张图想表达的情绪，不是画面里有什么物体。
		拿不准时选最接近的那个，不要自己造词，也不要输出多个词。
	""".trimIndent()

	/**
	 * 注入聊天 system prompt 的那一段。
	 *
	 * 措辞里有两处是被模型的实际表现逼出来的：
	 * - 要点明方括号里填的是情绪、不是图片名字。以前给的是图名清单，
	 *   不说清楚它会顺着旧习惯编一个 [熊猫头大笑] 出来，而那个标记谁都查不到
	 * - 要点明只有方括号算。见过它写成 (开心)、*开心*、【开心】各种变体
	 *
	 * 反过来"哪张图"这件事必须告诉它不用管：说明里含糊的话，
	 * 它会追问或者自己在括号里补一句"（发一张熊猫头）"，那句会原样出现在气泡里。
	 */
	fun promptFor(emotions: List<String>): String {
		if (emotions.isEmpty()) return ""
		return buildString {
			append("【发表情】想发表情就在回复里写 [情绪]，例如 [")
			append(emotions.first())
			append("]。只有英文方括号这一种写法会被识别成图片，圆括号或星号都不行。\n")
			append("方括号里填的是情绪，不是图片名字。可用的情绪：")
			append(emotions.joinToString("、"))
			append("\n具体发哪张图由我按这个情绪自动挑，你不用关心也指定不了。\n")
			append("清单里没有的词写了不算，只会显示成文字。表情要和说话内容搭得上再发，不用每句都带。")
		}
	}
}
