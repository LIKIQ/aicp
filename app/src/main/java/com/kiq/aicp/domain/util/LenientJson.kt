// app/src/main/java/com/kiq/aicp/domain/util/LenientJson.kt
// 模型吐出来的 JSON 的"抢救"工具。
//
// 为什么需要它：让模型输出 JSON，十次里有两三次会给你套上 ```json 代码围栏，
// 或者在前面加一句"好的，这是结果："。严格解析这些全是失败，但内容本身是好的，
// 掐头去尾就能用 —— 为一句客套话丢掉一次模型调用太浪费。
//
// 这里原本是压缩、体检、生成人设三处各抄了一份一模一样的私有实现。
// 三份意味着改判定规则要改三处，早晚会漏一处，所以收在这里。

package com.kiq.aicp.domain.util

object LenientJson {

	/**
	 * 剥掉外层代码围栏。只在整段以 ``` 开头时才动手：
	 * 正文里恰好含反引号（比如模型在字符串里写了代码片段）的情况不能碰。
	 */
	fun stripCodeFence(text: String): String {
		if (!text.startsWith("```")) return text
		return text.removePrefix("```json")
			.removePrefix("```JSON")
			.removePrefix("```")
			.removeSuffix("```")
			.trim()
	}

	/** 模型爱在 JSON 前后加话，掐头去尾只取第一个 { 到最后一个 } */
	fun extractObject(text: String): String? {
		val start = text.indexOf('{')
		val end = text.lastIndexOf('}')
		return if (start >= 0 && end > start) text.substring(start, end + 1) else null
	}

	/** 先剥围栏再掐头去尾。三处调用点要的都是这个组合 */
	fun salvageObject(text: String): String? = extractObject(stripCodeFence(text))
}
