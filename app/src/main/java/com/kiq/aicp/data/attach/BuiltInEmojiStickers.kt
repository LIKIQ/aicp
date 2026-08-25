/*
 * app/src/main/java/com/kiq/aicp/data/attach/BuiltInEmojiStickers.kt
 * 内置预设表情：把系统 emoji 字体渲染成 PNG 灌进表情库
 * 职责：
 * - 首次启动（或预设升版）时按情绪分组建表情，让 AI 开箱就能发表情
 * - 渲染前用 Paint.hasGlyph 探一下这台机器认不认这个 emoji，不认就跳过
 *
 * 为什么不打包图片素材：
 * 现成的表情包图基本都有版权，打进 APK 分发有风险；自己画 32 张又是笔美术活。
 * 系统 emoji 字体是 Unicode 标准 + 厂商自带，渲染出来彩色清晰、零版权、体积小，
 * 代价是不同机型长得不完全一样 —— 但那正是用户在自己手机上早就习惯的样子。
 *
 * 为什么按 Unicode 9 以内挑 emoji：
 * minSdk 26（Android 8）的 emoji 字体大约停在 Unicode 10，
 * 用新版 emoji（🥹🫶 之类）在老机器上会渲染成豆腐块，而那是"内置表情自带一张废图"。
 * hasGlyph 兜住剩下的意外。
 */
package com.kiq.aicp.data.attach

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.util.Log
import com.kiq.aicp.data.prefs.SettingsStore
import com.kiq.aicp.data.repo.StickerRepository
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class BuiltInEmojiStickers(
	private val cacheDir: File,
	private val stickerRepository: StickerRepository,
	private val settingsStore: SettingsStore,
) {

	/**
	 * 预设清单：情绪 → 这个情绪下的 emoji。
	 *
	 * 分组名直接用情绪词，所以这批表情不需要走识图那条路，装上就能被模型选中。
	 * 常用的情绪多给一两张，同一个情绪连着出现时发的图不会重复。
	 * 全部选在 Unicode 9 以内，Android 8 的字体就认。
	 */
	/**
	 * 需要就灌一遍。
	 *
	 * @return 实际导入的张数。0 表示已经灌过这一版，或者这台机器一个 emoji 都渲染不出来
	 *
	 * 已经灌到当前版本就直接收工 —— 用户删掉某张内置表情之后不该下次启动又冒出来，
	 * 那是 bug 不是贴心。往预设里加表情时抬 PRESET_VERSION，那时才会再进来补差量。
	 */
	suspend fun importIfNeeded(): Int = withContext(Dispatchers.IO) {
		if (settingsStore.builtInEmojiVersion() >= PRESET_VERSION) return@withContext 0

		val paint = emojiPaint()
		var imported = 0

		PRESET.forEach { (emotion, emojis) ->
			val usable = emojis.filter { paint.hasGlyph(it) }
			if (usable.isEmpty()) {
				// 整组都渲染不出来就别建空分组，空分组在提示词里占位置却选不出图
				Log.w(TAG, "这台机器的字体认不出「$emotion」组的 emoji，跳过")
				return@forEach
			}

			val packId = runCatching { stickerRepository.ensurePack(emotion) }.getOrElse { e ->
				Log.w(TAG, "分组「$emotion」建不出来", e)
				return@forEach
			}

			usable.forEachIndexed { index, emoji ->
				runCatching { importOne(packId, emotion, index + 1, emoji, paint) }
					.onSuccess { imported++ }
					.onFailure { Log.w(TAG, "「$emotion」第 ${index + 1} 张渲染失败", it) }
			}
		}

		// 一张都没成也记版本：这台机器的字体就是不支持，下次启动重试一遍同样白费
		settingsStore.markBuiltInEmojiVersion(PRESET_VERSION)
		imported
	}

	private suspend fun importOne(
		packId: Long,
		emotion: String,
		index: Int,
		emoji: String,
		paint: Paint,
	) {
		val temp = File(cacheDir, "builtin_emoji_${emotion}_$index.png")
		try {
			renderTo(temp, emoji, paint)
			stickerRepository.importFromFile(
				packId = packId,
				file = temp,
				displayName = temp.name,
				// 标记只是内部标识，模型看不到；用「情绪+序号」让用户在表情页里也认得出
				desiredLabel = "$emotion$index",
			)
		} finally {
			temp.delete()
		}
	}

	private fun emojiPaint() = Paint(Paint.ANTI_ALIAS_FLAG).apply {
		textSize = TEXT_SIZE
		textAlign = Paint.Align.CENTER
	}

	/**
	 * 把一个 emoji 画成透明底 PNG。
	 *
	 * 基线按 FontMetrics 算而不是随手取 height * 0.75：
	 * emoji 的 ascent/descent 比例跟普通文字不一样，估算会让图整体偏上或偏下，
	 * 32 张里只要有几张偏了，表情面板看起来就是歪的。
	 */
	private fun renderTo(target: File, emoji: String, paint: Paint) {
		val bitmap = Bitmap.createBitmap(CANVAS_SIZE, CANVAS_SIZE, Bitmap.Config.ARGB_8888)
		try {
			val canvas = Canvas(bitmap)
			val metrics = paint.fontMetrics
			val baseline = CANVAS_SIZE / 2f - (metrics.ascent + metrics.descent) / 2f
			canvas.drawText(emoji, CANVAS_SIZE / 2f, baseline, paint)

			target.outputStream().use { out ->
				if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
					error("PNG 编码失败")
				}
			}
		} finally {
			bitmap.recycle()
		}
	}

	companion object {
		private const val TAG = "BuiltInEmoji"

		/**
		 * 预设清单：情绪 → 这个情绪下的 emoji。
		 *
		 * 放 companion 而不是实例属性：它是纯数据，单测要数数量和覆盖面，
		 * 没必要为此造一个带仓库依赖的实例。
		 *
		 * 分组名直接用情绪词，所以这批表情不需要走识图那条路，装上就能被模型选中。
		 * 常用的情绪多给一两张，同一个情绪连着出现时发的图不会重复。
		 * 全部选在 Unicode 9 以内 —— minSdk 26 的字体大约停在 Unicode 10，
		 * 用新版 emoji（🥹🫶 那类）在老机器上会渲染成豆腐块。
		 */
		val PRESET: List<Pair<String, List<String>>> = listOf(
			"开心" to listOf("\uD83D\uDE0A", "\uD83D\uDE04"),
			"大笑" to listOf("\uD83D\uDE02", "\uD83E\uDD23"),
			"害羞" to listOf("\uD83D\uDE33", "\u263A\uFE0F"),
			"得意" to listOf("\uD83D\uDE0F", "\uD83D\uDE0E"),
			"撒娇" to listOf("\uD83D\uDE18", "\uD83D\uDE1A"),
			"无语" to listOf("\uD83D\uDE10", "\uD83D\uDE44"),
			"尴尬" to listOf("\uD83D\uDE05", "\uD83D\uDE2C"),
			"疑惑" to listOf("\uD83E\uDD14"),
			"思考" to listOf("\uD83D\uDCAD"),
			"惊讶" to listOf("\uD83D\uDE32", "\uD83D\uDE31"),
			"伤心" to listOf("\uD83D\uDE22"),
			"哭" to listOf("\uD83D\uDE2D"),
			"委屈" to listOf("\uD83D\uDE16"),
			"生气" to listOf("\uD83D\uDE20", "\uD83D\uDE21"),
			"无奈" to listOf("\uD83E\uDD37"),
			"困" to listOf("\uD83D\uDE34", "\uD83D\uDE2A"),
			"累" to listOf("\uD83D\uDE35"),
			"可爱" to listOf("\uD83D\uDC31", "\uD83C\uDF38"),
			"点赞" to listOf("\uD83D\uDC4D"),
			"比心" to listOf("\u2764\uFE0F", "\uD83D\uDC95"),
		)

		val PRESET_EMOJI_COUNT: Int get() = PRESET.sumOf { it.second.size }
		val PRESET_EMOTIONS: List<String> get() = PRESET.map { it.first }

		/** 预设版本。往 PRESET 里加表情时把这个数加一，老用户下次启动就会补上新增那批 */
		const val PRESET_VERSION = 1

		/** 渲染尺寸。表情在气泡里显示不过 100dp，256 够用且单张只有十几 KB */
		private const val CANVAS_SIZE = 256

		/** 字号留出 12% 余量：部分 emoji 的实际绘制范围会超出标称字号，贴边会被切 */
		private const val TEXT_SIZE = CANVAS_SIZE * 0.88f
	}
}
