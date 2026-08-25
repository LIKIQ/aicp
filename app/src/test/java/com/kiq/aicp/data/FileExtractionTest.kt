// app/src/test/java/com/kiq/aicp/data/FileExtractionTest.kt
// 文件正文提取测试：纯文本编码探测 + docx/xlsx 零依赖解析。
//
// docx/xlsx 是现场用 ZipOutputStream 造出来的真文件，不是 mock ——
// 自己解 ZIP+XML 这条路最容易错在"标签带不带命名空间前缀"和"共享字符串索引"上，
// 只有喂真结构才测得出来。
//
// 挂 Robolectric 是因为 OfficeTextExtractor 用了 android.util.Xml。

package com.kiq.aicp.data

import com.kiq.aicp.data.attach.TextExtractor
import java.io.File
import java.nio.charset.Charset
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FileExtractionTest {

	@get:Rule
	val temp = TemporaryFolder()

	private val extractor = TextExtractor()

	// ---------------- 纯文本与编码 ----------------

	@Test
	fun `UTF8 文本原样读出`() = runTest {
		val file = temp.newFile("note.txt").apply { writeText("第一行\n第二行", Charsets.UTF_8) }

		val result = extractor.extract(file, "note.txt", "text/plain")

		assertEquals("第一行\n第二行", result.text)
		assertFalse(result.truncated)
	}

	@Test
	fun `UTF8 BOM 不会被当成正文的第一个字符`() = runTest {
		val file = temp.newFile("bom.txt")
		file.writeBytes(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "有BOM".toByteArray())

		val result = extractor.extract(file, "bom.txt", "text/plain")

		assertEquals("有BOM", result.text)
	}

	@Test
	fun `GBK 编码的文本会被识别出来而不是变成乱码`() = runTest {
		val file = temp.newFile("gbk.txt")
		file.writeBytes("这是一段从记事本里存出来的中文，编码是GBK".toByteArray(Charset.forName("GBK")))

		val result = extractor.extract(file, "gbk.txt", "text/plain")

		assertTrue("实际读到：${result.text}", result.text.contains("这是一段从记事本里存出来的中文"))
		assertFalse("不该出现替换字符", result.text.contains('\uFFFD'))
	}

	@Test
	fun `CRLF 统一成 LF`() = runTest {
		val file = temp.newFile("crlf.txt").apply { writeText("一\r\n二\r\n三") }

		assertEquals("一\n二\n三", extractor.extract(file, "crlf.txt", "text/plain").text)
	}

	@Test
	fun `超长文本被截断并打上标记`() = runTest {
		val file = temp.newFile("long.txt").apply { writeText("字".repeat(TextExtractor.MAX_CHARS + 500)) }

		val result = extractor.extract(file, "long.txt", "text/plain")

		assertEquals(TextExtractor.MAX_CHARS, result.text.length)
		assertTrue(result.truncated)
	}

	@Test
	fun `空文件不会崩`() = runTest {
		val file = temp.newFile("empty.txt")

		val result = extractor.extract(file, "empty.txt", "text/plain")

		assertEquals("", result.text)
		assertFalse(result.truncated)
	}

	@Test
	fun `支持判断认代码文件和 office，不认二进制`() {
		assertTrue(TextExtractor.isSupported("Main.kt", "application/octet-stream"))
		assertTrue(TextExtractor.isSupported("data.json", "application/json"))
		assertTrue(TextExtractor.isSupported("表格.xlsx", "application/octet-stream"))
		assertTrue(TextExtractor.isSupported("文档.docx", "application/octet-stream"))
		assertTrue(TextExtractor.isSupported("readme", "text/plain"))
		assertFalse(TextExtractor.isSupported("song.mp3", "audio/mpeg"))
		assertFalse(TextExtractor.isSupported("app.apk", "application/vnd.android.package-archive"))
	}

	// ---------------- docx ----------------

	@Test
	fun `docx 按段落抽出文本，同段内的多个片段拼在一起`() = runTest {
		val file = temp.newFile("doc.docx")
		writeZip(
			file,
			"word/document.xml" to """
				<?xml version="1.0" encoding="UTF-8"?>
				<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
					<w:body>
						<w:p><w:r><w:t>第一段</w:t></w:r></w:p>
						<w:p><w:r><w:t>第二段</w:t></w:r><w:r><w:t>接着写</w:t></w:r></w:p>
					</w:body>
				</w:document>
			""".trimIndent(),
		)

		val result = extractor.extract(file, "doc.docx", "application/octet-stream")

		assertEquals("第一段\n第二段接着写", result.text)
	}

	@Test
	fun `docx 里的换行和制表符被保留`() = runTest {
		val file = temp.newFile("br.docx")
		writeZip(
			file,
			"word/document.xml" to """
				<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
					<w:body><w:p><w:r><w:t>上</w:t><w:br/><w:t>下</w:t><w:tab/><w:t>右</w:t></w:r></w:p></w:body>
				</w:document>
			""".trimIndent(),
		)

		assertEquals("上\n下\t右", extractor.extract(file, "br.docx", "application/octet-stream").text)
	}

	@Test
	fun `不是标准 docx 时给出能看懂的错误而不是空白`() = runTest {
		val file = temp.newFile("bad.docx")
		writeZip(file, "random.xml" to "<a/>")

		val error = runCatching { extractor.extract(file, "bad.docx", "application/octet-stream") }
			.exceptionOrNull()

		assertTrue(error?.message?.contains("word/document.xml") == true)
	}

	// ---------------- xlsx ----------------

	@Test
	fun `xlsx 的共享字符串能按索引还原，数字单元格直接取值`() = runTest {
		val file = temp.newFile("book.xlsx")
		writeZip(
			file,
			"xl/sharedStrings.xml" to """
				<sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
					<si><t>姓名</t></si><si><t>张三</t></si><si><t>李四</t></si>
				</sst>
			""".trimIndent(),
			"xl/worksheets/sheet1.xml" to """
				<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
					<sheetData>
						<row r="1"><c r="A1" t="s"><v>0</v></c><c r="B1" t="s"><v>1</v></c></row>
						<row r="2"><c r="A2" t="s"><v>2</v></c><c r="B2"><v>18</v></c></row>
					</sheetData>
				</worksheet>
			""".trimIndent(),
		)

		val result = extractor.extract(file, "book.xlsx", "application/octet-stream")

		assertEquals("姓名\t张三\n李四\t18", result.text)
	}

	@Test
	fun `xlsx 里的全空行被跳过，不会拖出一堆空行`() = runTest {
		val file = temp.newFile("sparse.xlsx")
		writeZip(
			file,
			"xl/worksheets/sheet1.xml" to """
				<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
					<sheetData>
						<row r="1"><c r="A1"><v>1</v></c></row>
						<row r="2"><c r="A2"><v></v></c></row>
						<row r="3"></row>
						<row r="4"><c r="A4"><v>4</v></c></row>
					</sheetData>
				</worksheet>
			""".trimIndent(),
		)

		assertEquals("1\n4", extractor.extract(file, "sparse.xlsx", "application/octet-stream").text)
	}

	@Test
	fun `多个工作表会分别标出来`() = runTest {
		val file = temp.newFile("multi.xlsx")
		writeZip(
			file,
			"xl/worksheets/sheet1.xml" to sheetWithSingleValue("甲"),
			"xl/worksheets/sheet2.xml" to sheetWithSingleValue("乙"),
		)

		val text = extractor.extract(file, "multi.xlsx", "application/octet-stream").text

		assertTrue(text.contains("# 工作表 1"))
		assertTrue(text.contains("# 工作表 2"))
		assertTrue(text.contains("甲"))
		assertTrue(text.contains("乙"))
	}

	// ---------------- 工具 ----------------

	private fun sheetWithSingleValue(value: String): String = """
		<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
			<sheetData><row r="1"><c r="A1" t="inlineStr"><is><t>$value</t></is></c></row></sheetData>
		</worksheet>
	""".trimIndent()

	private fun writeZip(target: File, vararg entries: Pair<String, String>) {
		ZipOutputStream(target.outputStream()).use { zip ->
			entries.forEach { (name, content) ->
				zip.putNextEntry(ZipEntry(name))
				zip.write(content.toByteArray(Charsets.UTF_8))
				zip.closeEntry()
			}
		}
	}
}
