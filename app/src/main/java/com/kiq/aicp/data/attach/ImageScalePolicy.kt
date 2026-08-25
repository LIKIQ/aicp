// app/src/main/java/com/kiq/aicp/data/attach/ImageScalePolicy.kt
// 图片预处理的尺寸/质量策略，纯算术，单独拆出来是为了能不碰 Bitmap 就测。
//
// 参数依据（查证自各家视觉接口文档）：
// - DeepSeek 服务端会把图缩到约 800×800 的像素量级，且每图 token 封顶 384 —— 传 4000px 纯属白烧流量
// - OpenAI 的 high 档也只到 2048px；low 档固定 512×512
// - 所以普通图长边 1024 足够；带文字的截图给 1568 并显式要 detail:high，
//   否则 low 档强降 512 会把小字糊掉
// - 质量 82：85 以上文件涨得快而识别收益已饱和；低于 75 小字开始糊

package com.kiq.aicp.data.attach

object ImageScalePolicy {

	const val MAX_EDGE_NORMAL = 1024
	const val MAX_EDGE_TEXT_HEAVY = 1568
	const val QUALITY_NORMAL = 82
	const val QUALITY_TEXT_HEAVY = 90

	/** base64 会把体积放大 4/3，所以按编码后的大小卡上限，别按原图 */
	const val MAX_BASE64_BYTES = 6 * 1024 * 1024

	fun maxEdgeFor(textHeavy: Boolean): Int =
		if (textHeavy) MAX_EDGE_TEXT_HEAVY else MAX_EDGE_NORMAL

	fun qualityFor(textHeavy: Boolean): Int =
		if (textHeavy) QUALITY_TEXT_HEAVY else QUALITY_NORMAL

	/**
	 * 第一段解码用的 inSampleSize：只取 2 的幂，且保证解出来的图不小于目标尺寸
	 * （宁可多解一点再精确缩放，也不要解得不够然后放大）。
	 */
	fun sampleSizeFor(srcWidth: Int, srcHeight: Int, maxEdge: Int): Int {
		if (srcWidth <= 0 || srcHeight <= 0 || maxEdge <= 0) return 1
		val longEdge = maxOf(srcWidth, srcHeight)
		if (longEdge <= maxEdge) return 1

		var sample = 1
		// 保持 longEdge / (sample*2) >= maxEdge，即缩完仍不小于目标
		while (longEdge / (sample * 2) >= maxEdge) {
			sample *= 2
		}
		return sample
	}

	/** 精确缩放的目标尺寸，等比，短边至少 1px */
	fun targetSizeFor(srcWidth: Int, srcHeight: Int, maxEdge: Int): Pair<Int, Int> {
		if (srcWidth <= 0 || srcHeight <= 0) return 0 to 0
		val longEdge = maxOf(srcWidth, srcHeight)
		if (longEdge <= maxEdge) return srcWidth to srcHeight

		val ratio = maxEdge.toDouble() / longEdge
		val w = (srcWidth * ratio).toInt().coerceAtLeast(1)
		val h = (srcHeight * ratio).toInt().coerceAtLeast(1)
		return w to h
	}

	/** 编码后还是太大时逐档降质量，返回下一档；到底了返回 null 让调用方报错 */
	fun nextQuality(current: Int): Int? = when {
		current > 70 -> 70
		current > 55 -> 55
		current > 45 -> 45
		else -> null
	}
}
