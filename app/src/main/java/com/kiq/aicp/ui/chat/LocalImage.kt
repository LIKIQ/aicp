// app/src/main/java/com/kiq/aicp/ui/chat/LocalImage.kt
// 本地图片加载：从 filesDir 里的附件文件解出 ImageBitmap 给 Compose 用。
//
// 没引 Coil。附件在落盘时已经压到长边 ≤1568 的 JPEG，单张一两百 KB，
// 为它拉一个图片框架不划算。这里只补 Coil 真正帮到我们的两件事：
// - 解码不在主线程（大图 decode 十几毫秒起，够掉帧）
// - 一个小 LRU，列表来回滚不重复 decode
//
// 缓存键带上目标宽度：同一张图在气泡里和在预览条里尺寸不同，
// 不带宽度会互相覆盖，滚一次列表就闪一下。

package com.kiq.aicp.ui.chat

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private object ThumbnailCache {
	// 8MB 够放几十张缩略图；超了按最久未用淘汰
	private val cache = object : LruCache<String, ImageBitmap>(8 * 1024 * 1024) {
		override fun sizeOf(key: String, value: ImageBitmap): Int = value.width * value.height * 4
	}

	fun get(key: String): ImageBitmap? = cache.get(key)

	fun put(key: String, value: ImageBitmap) = cache.put(key, value)
}

/**
 * 异步读一张本地图，读之前先返回 null，让调用方摆个占位。
 * targetWidthPx 是期望宽度，用来算 inSampleSize —— 缩略图不需要按原分辨率解。
 */
@Composable
fun rememberLocalImage(file: File, targetWidthPx: Int): State<ImageBitmap?> {
	val key = "${file.absolutePath}@$targetWidthPx"
	val state = remember(key) { mutableStateOf(ThumbnailCache.get(key)) }

	LaunchedEffect(key) {
		if (state.value != null) return@LaunchedEffect
		val decoded = withContext(Dispatchers.IO) { decodeScaled(file, targetWidthPx) }
		if (decoded != null) {
			ThumbnailCache.put(key, decoded)
			state.value = decoded
		}
	}
	return state
}

/** 两趟解码：先只读尺寸算采样率，再按采样率真解，避免大图整张进内存 */
private fun decodeScaled(file: File, targetWidthPx: Int): ImageBitmap? {
	if (!file.exists()) return null

	val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
	BitmapFactory.decodeFile(file.absolutePath, bounds)
	if (bounds.outWidth <= 0) return null

	var sample = 1
	while (targetWidthPx > 0 && bounds.outWidth / (sample * 2) >= targetWidthPx) sample *= 2

	val options = BitmapFactory.Options().apply { inSampleSize = sample }
	return runCatching { BitmapFactory.decodeFile(file.absolutePath, options)?.asImageBitmap() }.getOrNull()
}
