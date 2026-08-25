// app/src/main/java/com/kiq/aicp/data/attach/AttachmentStore.kt
// 附件的落盘与读取。
//
// 为什么要复制一份进私有目录，而不是存 SAF 的 content:// URI：
// 1. SAF 授权重启后可能失效，除非 takePersistableUriPermission，而那会让"权限清单"越攒越长
// 2. 用户在相册里把原图删了，我们就再也打不开这条历史消息
// 记忆存在本地是这个应用的卖点，历史消息里的图打不开等于记忆残缺，所以宁可多占点空间。
//
// 删除有个 SQLite 管不到的地方：外键 CASCADE 只删数据库行，不删磁盘文件。
// 所以删消息/删会话时必须先把 localPath 捞出来再删行，最后删文件 —— 顺序反了就留下孤儿文件。

package com.kiq.aicp.data.attach

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 落盘结果，字段与 MessageAttachmentEntity 一一对应 */
data class SavedAttachment(
	val localPath: String,
	val mimeType: String,
	val fileName: String,
	val byteSize: Long,
	val width: Int = 0,
	val height: Int = 0,
)

class AttachmentStore(
	private val context: Context,
	private val ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO,
) {

	companion object {
		const val DIR_NAME = "attachments"
		const val STICKER_DIR = "stickers"
		const val AVATAR_DIR = "avatars"
		private const val MAX_FILE_NAME = 80

		private const val MIME_GIF = "image/gif"

		/** 表情包长边上限。表情在气泡里最多显示 120dp，再大纯属浪费空间 */
		private const val STICKER_MAX_EDGE = 512

		/** 头像长边上限。最大显示 56dp，256 像素在任何密度下都够 */
		private const val AVATAR_MAX_EDGE = 256

		/** 单张图片资产的体积上限，动图也按这个卡 */
		private const val STICKER_MAX_BYTES = 2 * 1024 * 1024

		/** 附件卡片上显示的体积。1024 进制，跟系统文件管理器口径一致 */
		fun humanSize(bytes: Long): String = when {
			bytes < 1024 -> "$bytes B"
			bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024.0)
			else -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
		}
	}

	private val root: File
		get() = File(context.filesDir, DIR_NAME).apply { if (!exists()) mkdirs() }

	private fun assetRoot(dir: String): File =
		File(context.filesDir, dir).apply { if (!exists()) mkdirs() }

	fun resolve(localPath: String): File = File(context.filesDir, localPath)

	fun exists(localPath: String): Boolean = resolve(localPath).isFile

	/** 图片：解码 → 按策略缩放 → JPEG 编码 → 落盘。返回的 width/height 是压缩后的尺寸 */
	suspend fun saveImage(uri: Uri, textHeavy: Boolean): SavedAttachment = withContext(ioDispatcher) {
		val displayName = queryDisplayName(uri) ?: "image.jpg"
		val maxEdge = ImageScalePolicy.maxEdgeFor(textHeavy)

		// 第一段：只读边界，避免大图直接 OOM
		val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
		openStream(uri).use { BitmapFactory.decodeStream(it, null, bounds) }
		require(bounds.outWidth > 0 && bounds.outHeight > 0) { "这个文件解不出图片：$displayName" }

		// 第二段：按 inSampleSize 真正解码
		val decodeOptions = BitmapFactory.Options().apply {
			inSampleSize = ImageScalePolicy.sampleSizeFor(bounds.outWidth, bounds.outHeight, maxEdge)
		}
		val decoded = openStream(uri).use { BitmapFactory.decodeStream(it, null, decodeOptions) }
			?: error("图片解码失败：$displayName")

		val (targetW, targetH) = ImageScalePolicy.targetSizeFor(decoded.width, decoded.height, maxEdge)
		val scaled = if (targetW != decoded.width || targetH != decoded.height) {
			Bitmap.createScaledBitmap(decoded, targetW, targetH, true).also {
				if (it !== decoded) decoded.recycle()
			}
		} else {
			decoded
		}

		val bytes = encodeJpeg(scaled, ImageScalePolicy.qualityFor(textHeavy))
		val width = scaled.width
		val height = scaled.height
		scaled.recycle()

		val target = newFile("jpg")
		target.writeBytes(bytes)

		SavedAttachment(
			localPath = "$DIR_NAME/${target.name}",
			mimeType = "image/jpeg",
			fileName = displayName.take(MAX_FILE_NAME),
			byteSize = bytes.size.toLong(),
			width = width,
			height = height,
		)
	}

	/** 普通文件：原样复制，文本抽取交给 TextExtractor */
	suspend fun saveFile(uri: Uri): SavedAttachment = withContext(ioDispatcher) {
		val displayName = queryDisplayName(uri) ?: "file"
		val mime = context.contentResolver.getType(uri) ?: guessMime(displayName)
		val target = newFile(displayName.substringAfterLast('.', "bin"))

		val copied = openStream(uri).use { input ->
			target.outputStream().use { output -> input.copyTo(output) }
		}

		SavedAttachment(
			localPath = "$DIR_NAME/${target.name}",
			mimeType = mime,
			fileName = displayName.take(MAX_FILE_NAME),
			byteSize = copied,
		)
	}

	/**
	 * 表情包落盘。不走 saveImage 是因为那条路径统一转 JPEG，
	 * 而表情包大量是带透明背景的 PNG，转 JPEG 会把透明区糊成黑块或白块。
	 *
	 * GIF 原样复制不重编码：解码再压会只剩第一帧，动图就死了。
	 * 代价是尺寸控制不了，所以单独卡一个体积上限。
	 */
	suspend fun saveSticker(uri: Uri): SavedAttachment =
		saveImageAsset(
			openInput = { openStream(uri) },
			displayName = queryDisplayName(uri) ?: "sticker",
			declaredMime = context.contentResolver.getType(uri),
			dir = STICKER_DIR,
			maxEdge = STICKER_MAX_EDGE,
		)

	/**
	 * 头像落盘。跟表情走同一条逻辑，只是更小 —— 头像最大显示 56dp，
	 * 256 像素在任何屏幕密度下都够用了，再大纯属白占空间。
	 */
	suspend fun saveAvatar(uri: Uri): SavedAttachment =
		saveImageAsset(
			openInput = { openStream(uri) },
			displayName = queryDisplayName(uri) ?: "avatar",
			declaredMime = context.contentResolver.getType(uri),
			dir = AVATAR_DIR,
			maxEdge = AVATAR_MAX_EDGE,
		)

	/**
	 * 从一个本地文件落盘成表情。内置表情包走这条路 ——
	 * assets 里的东西没有 content:// URI，contentResolver 那套用不上。
	 */
	suspend fun saveStickerFromFile(file: File, displayName: String): SavedAttachment =
		saveImageAsset(
			openInput = { file.inputStream() },
			displayName = displayName,
			declaredMime = null,
			dir = STICKER_DIR,
			maxEdge = STICKER_MAX_EDGE,
		)

	/**
	 * 图片资产落盘的公共实现。传 InputStream 工厂而不是 Uri，
	 * 是为了让 SAF 选来的图和 assets 里的内置素材共用同一段压缩逻辑 ——
	 * 否则内置表情要把缩放、编码、体积检查整段抄第二遍。
	 *
	 * openInput 会被调用两次（探测和真解码各一次），所以必须是能重复打开的工厂，
	 * 不能直接传一个已经打开的流。
	 */
	private suspend fun saveImageAsset(
		openInput: () -> InputStream,
		displayName: String,
		declaredMime: String?,
		dir: String,
		maxEdge: Int,
	): SavedAttachment = withContext(ioDispatcher) {
		val mime = declaredMime ?: guessMime(displayName)

		if (mime == MIME_GIF || displayName.endsWith(".gif", ignoreCase = true)) {
			return@withContext copyAnimatedImage(openInput, displayName, dir)
		}

		val source = openInput().use { BitmapFactory.decodeStream(it) }
			?: error("这个文件不是能显示的图片")

		val longEdge = maxOf(source.width, source.height)
		val bitmap = if (longEdge > maxEdge) {
			val ratio = maxEdge.toFloat() / longEdge
			val w = (source.width * ratio).toInt().coerceAtLeast(1)
			val h = (source.height * ratio).toInt().coerceAtLeast(1)
			Bitmap.createScaledBitmap(source, w, h, true).also { if (it !== source) source.recycle() }
		} else {
			source
		}

		// PNG 是无损的，compress 的 quality 参数会被忽略，传 100 只是占位
		val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
		require(bytes.size <= STICKER_MAX_BYTES) { "这张图压完还有 ${humanSize(bytes.size.toLong())}，太大了" }

		val target = newAssetFile(dir, "png")
		target.writeBytes(bytes)

		SavedAttachment(
			localPath = "$dir/${target.name}",
			mimeType = "image/png",
			fileName = displayName.take(MAX_FILE_NAME),
			byteSize = bytes.size.toLong(),
			width = bitmap.width,
			height = bitmap.height,
		)
	}

	private fun copyAnimatedImage(
		openInput: () -> InputStream,
		displayName: String,
		dir: String,
	): SavedAttachment {
		val target = newAssetFile(dir, "gif")
		val copied = openInput().use { input ->
			target.outputStream().use { output -> input.copyTo(output) }
		}
		if (copied > STICKER_MAX_BYTES) {
			target.delete()
			error("这张动图有 ${humanSize(copied)}，超过 ${humanSize(STICKER_MAX_BYTES.toLong())} 了")
		}

		// 只读尺寸不解码像素，动图整帧进内存没必要
		val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
		BitmapFactory.decodeFile(target.absolutePath, bounds)

		return SavedAttachment(
			localPath = "$dir/${target.name}",
			mimeType = MIME_GIF,
			fileName = displayName.take(MAX_FILE_NAME),
			byteSize = copied,
			width = bounds.outWidth.coerceAtLeast(0),
			height = bounds.outHeight.coerceAtLeast(0),
		)
	}

	/** 给视觉请求用：读出文件并 base64，不带 data URI 前缀 */	suspend fun readBase64(localPath: String): String = withContext(ioDispatcher) {
		val file = resolve(localPath)
		require(file.isFile) { "附件文件不在了：$localPath" }
		Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
	}

	suspend fun readBytes(localPath: String): ByteArray = withContext(ioDispatcher) {
		resolve(localPath).readBytes()
	}

	/** 删文件。数据库行必须已经删掉了再调，否则会出现"行还在文件没了" */
	suspend fun delete(localPaths: List<String>) = withContext(ioDispatcher) {
		localPaths.forEach { path ->
			runCatching { resolve(path).delete() }
		}
	}

	/**
	 * 清理孤儿文件：磁盘上有、数据库里没引用的。
	 * keepPaths 必须是**三张来源的并集**（message_attachments、stickers、personas/conversations 的头像），
	 * 只传一部分会把另一部分全删掉。
	 */
	suspend fun pruneOrphans(keepPaths: Set<String>): Int = withContext(ioDispatcher) {
		var removed = 0
		listOf(DIR_NAME, STICKER_DIR, AVATAR_DIR).forEach { dir ->
			assetRoot(dir).listFiles()?.forEach { file ->
				val relative = "$dir/${file.name}"
				if (relative !in keepPaths && file.delete()) removed++
			}
		}
		removed
	}

	private fun openStream(uri: Uri): InputStream =
		context.contentResolver.openInputStream(uri) ?: error("打不开这个文件：$uri")

	private fun encodeJpeg(bitmap: Bitmap, startQuality: Int): ByteArray {
		var quality = startQuality
		while (true) {
			val out = ByteArrayOutputStream()
			bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
			val bytes = out.toByteArray()
			// base64 有 4/3 膨胀，按编码后的大小判上限
			if (bytes.size * 4 / 3 <= ImageScalePolicy.MAX_BASE64_BYTES) return bytes
			quality = ImageScalePolicy.nextQuality(quality)
				?: error("这张图压到最低质量还是太大，换一张吧")
		}
	}

	private fun newFile(extension: String): File {
		val safeExt = extension.lowercase().filter { it.isLetterOrDigit() }.take(6).ifEmpty { "bin" }
		val name = "${System.currentTimeMillis()}_${(0..0xFFFF).random().toString(16)}.$safeExt"
		return File(root, name)
	}

	private fun newAssetFile(dir: String, extension: String): File {
		val name = "${System.currentTimeMillis()}_${(0..0xFFFF).random().toString(16)}.$extension"
		return File(assetRoot(dir), name)
	}

	private fun queryDisplayName(uri: Uri): String? =
		runCatching {
			context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
				?.use { cursor ->
					if (cursor.moveToFirst()) cursor.getString(0) else null
				}
		}.getOrNull() ?: uri.lastPathSegment

	private fun guessMime(fileName: String): String = when (fileName.substringAfterLast('.', "").lowercase()) {
		"txt", "log", "csv" -> "text/plain"
		"md" -> "text/markdown"
		"json" -> "application/json"
		"xml" -> "text/xml"
		"docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
		"xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
		"pdf" -> "application/pdf"
		else -> "application/octet-stream"
	}
}
