/*
 * app/src/main/java/com/kiq/aicp/domain/update/VersionCompare.kt
 * 版本号比较：把 GitHub Release 的 tag 跟本地 versionName 比大小
 * 职责：
 * - 削掉 tag 上常见的 v 前缀，按点分段做数值比较
 * - 段数不齐时短的一侧补零，让 1.0 和 1.0.0 判定相等
 * - 带预发布后缀的那一段（1.0.0-beta）判定为比同号正式版小
 * - 认不出来的输入返回 UNKNOWN，约定由调用方按"不提示更新"处理
 *
 * 为什么必须走数值而不是字符串：
 * 0.10.0 和 0.9.0 按字典序是 "1" < "9"，直接判反。版本号一进两位数就会踩到，
 * 而那时候包已经在用户手机上了，改不动。
 *
 * 为什么认不出来要返回 UNKNOWN 而不是宁可提示：
 * tag 是发版时手打的，写成 latest、nightly、release-0.5 都可能。
 * 漏提示一次的代价是用户晚几天知道新版本；判错方向的代价是他每次开 App 都被弹一次，
 * 弹到第三回这个提示就永久失效了——他不会再看。
 *
 * 已知挡不住的一种写法：2024-01-15 这类日期 tag 会被读成主版本 2024，判成有新版本。
 * 它确实解析出了一个合法数字段，这一层没有依据判它无效，只能靠发版时 tag 守规矩。
 */
package com.kiq.aicp.domain.update

object VersionCompare {

	enum class Order {
		/** 远端比本地新，可以提示更新 */
		NEWER,

		/** 一样新 */
		SAME,

		/** 本地反而更新。自己编包装机时的常态，不该提示 */
		OLDER,

		/** 至少一边不像版本号 */
		UNKNOWN,
	}

	/** 一段版本号。preRelease 表示这段数字后面还挂着别的东西（-beta、rc1） */
	private class Segment(val number: Long, val preRelease: Boolean)

	/**
	 * @param remote GitHub 上的 tag，形如 v0.5.0 或 0.5.0
	 * @param local 本地 BuildConfig.VERSION_NAME，形如 0.4.0
	 */
	fun compare(remote: String?, local: String?): Order {
		val left = parse(remote) ?: return Order.UNKNOWN
		val right = parse(local) ?: return Order.UNKNOWN

		for (i in 0 until maxOf(left.size, right.size)) {
			// 缺的段按 0 补，1.0 因此等于 1.0.0
			val a = left.getOrNull(i) ?: PADDING
			val b = right.getOrNull(i) ?: PADDING

			if (a.number != b.number) {
				return if (a.number > b.number) Order.NEWER else Order.OLDER
			}
			if (a.preRelease != b.preRelease) {
				// 数字相同就看后缀：带后缀的那边是正式版之前的东西，判小
				return if (a.preRelease) Order.OLDER else Order.NEWER
			}
			// 两边同一段都带后缀：不猜 alpha/beta/rc 谁大（各家排法都不一样），
			// 当作相等收手。结果是不提示更新，比猜错方向安全
			if (a.preRelease) return Order.SAME
		}
		return Order.SAME
	}

	/** 远端是不是真的更新。UNKNOWN 一律当"不是"，这是"宁可不提示"那条约定的落点 */
	fun isNewer(remote: String?, local: String?): Boolean = compare(remote, local) == Order.NEWER

	private fun parse(raw: String?): List<Segment>? {
		val text = raw?.trim().orEmpty()
		if (text.isEmpty()) return null

		// 只削一个 v/V：v0.5.0 是 GitHub 上最常见的写法，
		// 但 version1.0 这种不削——多削一个字符就等于在替用户猜他想写什么
		val body = if (text.length > 1 && (text[0] == 'v' || text[0] == 'V')) text.substring(1) else text
		if (!body[0].isDigit()) return null

		val segments = ArrayList<Segment>()
		for (part in body.split('.')) {
			val digits = part.takeWhile { it.isDigit() }
			// 位数离谱的段（一串 20 位数字）当认不出来，别让它悄悄溢出成一个小数字
			val number = if (digits.isEmpty()) 0L else digits.toLongOrNull() ?: return null
			// digits 为空说明这段压根没数字（1.beta 的第二段），一样按"预发布"处理，
			// 于是 1.beta 落在 1.0.0 之前
			segments += Segment(number, preRelease = digits.length != part.length)
		}
		return segments
	}

	private val PADDING = Segment(0L, preRelease = false)
}
