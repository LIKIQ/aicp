// app/src/main/java/com/kiq/aicp/data/attach/OfficeTextExtractor.kt
// docx / xlsx 的正文提取，零第三方依赖。
//
// 为什么不用 Apache POI：引进去直接把 dex 方法数顶破 65536（社区实测），
// 要 shading + 重定位 + multidex 才能跑起来。为一个"把文件内容念给模型听"的辅助功能
// 付那个代价完全不值。
//
// docx / xlsx 本质就是 ZIP + XML，Android 自带 java.util.zip 和 XmlPullParser，够用：
//   docx：word/document.xml 里取所有 <w:t> 文本，<w:p> 结束换行
//   xlsx：xl/sharedStrings.xml 是字符串池，sheet 里 t="s" 的单元格 <v> 存的是池索引
// 代价是不支持文本框、批注、嵌套表格这些边角内容 —— 对喂给模型这个目标不影响。
//
// 标签名统一按 substringAfterLast(':') 取本地名：
// XmlPullParser 的 processNamespaces 默认关，name 会带 "w:" 前缀；开了就不带。两种都要能认。

package com.kiq.aicp.data.attach

import android.util.Xml
import java.io.File
import java.io.StringReader
import java.util.zip.ZipFile
import org.xmlpull.v1.XmlPullParser

internal class OfficeTextExtractor {

	fun extractDocx(file: File): String {
		val xml = readEntry(file, "word/document.xml")
			?: error("这个 docx 里找不到 word/document.xml，可能不是标准 Word 文件")

		val out = StringBuilder()
		parse(xml) { event, parser ->
			val tag = parser.localName()
			when (event) {
				XmlPullParser.START_TAG -> when (tag) {
					"br" -> out.append('\n')
					"tab" -> out.append('\t')
				}

				XmlPullParser.TEXT -> if (parser.insideTextRun) out.append(parser.text)

				XmlPullParser.END_TAG -> if (tag == "p") out.append('\n')
			}
		}
		return out.toString()
	}

	fun extractXlsx(file: File): String {
		val shared = readEntry(file, "xl/sharedStrings.xml")?.let { parseSharedStrings(it) } ?: emptyList()
		val sheetNames = listEntries(file) { it.startsWith("xl/worksheets/sheet") && it.endsWith(".xml") }
			.sorted()

		val out = StringBuilder()
		sheetNames.forEachIndexed { index, entryName ->
			val xml = readEntry(file, entryName) ?: return@forEachIndexed
			if (sheetNames.size > 1) out.append("# 工作表 ${index + 1}\n")
			out.append(parseSheet(xml, shared))
			out.append('\n')
		}
		return out.toString()
	}

	// ---------------- 内部 ----------------

	private fun parseSharedStrings(xml: String): List<String> {
		val items = mutableListOf<String>()
		val current = StringBuilder()
		var inItem = false

		parse(xml) { event, parser ->
			val tag = parser.localName()
			when (event) {
				XmlPullParser.START_TAG -> if (tag == "si") {
					inItem = true
					current.setLength(0)
				}

				XmlPullParser.TEXT -> if (inItem && parser.insideTextRun) current.append(parser.text)

				XmlPullParser.END_TAG -> if (tag == "si") {
					items += current.toString()
					inItem = false
				}
			}
		}
		return items
	}

	private fun parseSheet(xml: String, shared: List<String>): String {
		val out = StringBuilder()
		val row = mutableListOf<String>()
		val cell = StringBuilder()
		var cellType: String? = null
		var inValue = false

		parse(xml) { event, parser ->
			val tag = parser.localName()
			when (event) {
				XmlPullParser.START_TAG -> when (tag) {
					"row" -> row.clear()
					"c" -> {
						cell.setLength(0)
						cellType = parser.getAttributeValue(null, "t")
					}

					"v" -> inValue = true
					// inlineStr 的文本在 <is><t> 里，靠 insideTextRun 收
				}

				XmlPullParser.TEXT -> if (inValue || parser.insideTextRun) cell.append(parser.text)

				XmlPullParser.END_TAG -> when (tag) {
					"v" -> inValue = false
					"c" -> {
						val raw = cell.toString().trim()
						row += if (cellType == "s") {
							raw.toIntOrNull()?.let { shared.getOrNull(it) } ?: ""
						} else {
							raw
						}
					}

					"row" -> {
						// 整行全空就不占一行，避免表格下方几百个空行喂进上下文
						if (row.any { it.isNotBlank() }) {
							out.append(row.joinToString("\t")).append('\n')
						}
					}
				}
			}
		}
		return out.toString()
	}

	/** 统一的拉取式解析。回调里能拿到 event 和 parser，文本段用 insideTextRun 判断是否在 <t> 里 */
	private fun parse(xml: String, onEvent: (Int, TrackingParser) -> Unit) {
		val parser = Xml.newPullParser()
		parser.setInput(StringReader(xml))
		val tracking = TrackingParser(parser)

		var event = parser.eventType
		while (event != XmlPullParser.END_DOCUMENT) {
			tracking.beforeDispatch(event)
			onEvent(event, tracking)
			tracking.afterDispatch(event)
			event = parser.next()
		}
	}

	/** 包一层只为跟踪"当前是否在 <t> 文本节点里"，避免每个调用点都自己维护一个布尔 */
	internal class TrackingParser(private val delegate: XmlPullParser) {
		var insideTextRun: Boolean = false
			private set

		val text: String get() = delegate.text ?: ""

		fun localName(): String = (delegate.name ?: "").substringAfterLast(':')

		fun getAttributeValue(namespace: String?, name: String): String? =
			delegate.getAttributeValue(namespace, name)

		fun beforeDispatch(event: Int) {
			if (event == XmlPullParser.START_TAG && localName() == "t") insideTextRun = true
		}

		fun afterDispatch(event: Int) {
			if (event == XmlPullParser.END_TAG && localName() == "t") insideTextRun = false
		}
	}

	private fun readEntry(file: File, entryName: String): String? =
		runCatching {
			ZipFile(file).use { zip ->
				zip.getEntry(entryName)?.let { entry ->
					zip.getInputStream(entry).use { it.readBytes().toString(Charsets.UTF_8) }
				}
			}
		}.getOrNull()

	private fun listEntries(file: File, filter: (String) -> Boolean): List<String> =
		runCatching {
			ZipFile(file).use { zip ->
				zip.entries().asSequence().map { it.name }.filter(filter).toList()
			}
		}.getOrDefault(emptyList())
}
