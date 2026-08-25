// app/src/main/java/com/kiq/aicp/data/attach/TextExtractor.kt
// 文件正文提取的入口。
//
// 编码探测这件事对中文用户特别要紧：从 Windows 拿来的 txt/csv 有相当比例是 GBK，
// 直接按 UTF-8 解会得到满屏 U+FFFD。这里按 BOM → UTF-8 试解 → GBK 回落 三段判。
//
// 截断策略：超过 MAX_CHARS 就截断并置 truncated=true，
// 拼提示词时会明确告诉模型"这只是文件的前一部分"，不让它以为自己看到了全文。

package com.kiq.aicp.data.attach

import java.io.File
import java.nio.charset.Charset
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ExtractedText(
	val text: String,
	val truncated: Boolean,
)

class TextExtractor(
	private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

	companion object {
		/** 约 4 万 token 量级，够放整个源码文件，又不至于一次把上下文顶爆 */
		const val MAX_CHARS = 60_000

		private const val UTF8_BOM = "\uFEFF"

		private val PLAIN_EXTENSIONS = setOf(
			"txt", "md", "markdown", "log", "csv", "tsv", "json", "xml", "yaml", "yml",
			"ini", "conf", "cfg", "properties", "env", "sql", "html", "htm", "css",
			"kt", "kts", "java", "py", "js", "ts", "tsx", "jsx", "c", "h", "cpp", "hpp",
			"cs", "go", "rs", "rb", "php", "swift", "m", "mm", "sh", "bat", "ps1",
			"gradle", "toml", "lock", "gitignore", "proto", "graphql", "dart", "lua", "r",
		)

		private const val EXT_DOCX = "docx"
		private const val EXT_XLSX = "xlsx"

		fun isSupported(fileName: String, mimeType: String): Boolean {
			val ext = fileName.substringAfterLast('.', "").lowercase()
			return ext in PLAIN_EXTENSIONS ||
				ext == EXT_DOCX ||
				ext == EXT_XLSX ||
				mimeType.startsWith("text/") ||
				mimeType == "application/json" ||
				mimeType == "application/xml"
		}

		/** 给用户看的"支持哪些格式"提示 */
		fun supportedHint(): String =
			"纯文本与源码（txt / md / json / csv / log / 各类代码文件）、Word（docx）、Excel（xlsx）"
	}

	private val office = OfficeTextExtractor()

	suspend fun extract(file: File, fileName: String, mimeType: String): ExtractedText =
		withContext(ioDispatcher) {
			val ext = fileName.substringAfterLast('.', "").lowercase()
			val raw = when {
				ext == EXT_DOCX -> office.extractDocx(file)
				ext == EXT_XLSX -> office.extractXlsx(file)
				else -> readTextGuessingCharset(file)
			}
			clamp(raw)
		}

	private fun clamp(raw: String): ExtractedText {
		val normalized = raw.replace("\r\n", "\n").trim()
		return if (normalized.length <= MAX_CHARS) {
			ExtractedText(normalized, truncated = false)
		} else {
			ExtractedText(normalized.take(MAX_CHARS), truncated = true)
		}
	}

	/**
	 * BOM 优先，其次试 UTF-8，替换字符太多就按 GBK 再解一次。
	 * 阈值取 1%：正常 UTF-8 文本几乎不会出现替换字符，而 GBK 中文按 UTF-8 解会大面积出现。
	 */
	private fun readTextGuessingCharset(file: File): String {
		val bytes = file.readBytes()
		if (bytes.isEmpty()) return ""

		detectByBom(bytes)?.let { (charset, offset) ->
			return String(bytes, offset, bytes.size - offset, charset)
		}

		val asUtf8 = String(bytes, Charsets.UTF_8)
		val replacementCount = asUtf8.count { it == '\uFFFD' }
		if (replacementCount == 0 || replacementCount * 100 < asUtf8.length) {
			return asUtf8.removePrefix(UTF8_BOM)
		}

		return runCatching { String(bytes, Charset.forName("GBK")) }.getOrDefault(asUtf8)
	}

	private fun detectByBom(bytes: ByteArray): Pair<Charset, Int>? {
		if (bytes.size >= 3 &&
			bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
		) {
			return Charsets.UTF_8 to 3
		}
		if (bytes.size >= 2) {
			if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) return Charsets.UTF_16LE to 2
			if (bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) return Charsets.UTF_16BE to 2
		}
		return null
	}
}
