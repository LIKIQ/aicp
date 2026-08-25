// app/src/test/java/com/kiq/aicp/data/BackupCryptoTest.kt
// 备份口令加密的往返与防篡改测试。纯 JVM（javax.crypto 在 JVM 上是真实现，不是桩）。
//
// 这一层守的是两件事：
// 1. 加了口令的备份必须能原样解回来 —— 解不回来等于用户的数据没了
// 2. 口令错、文件被改、文件被截断，都必须明确报错，绝不能吐出半截数据
//    让后面的解压逻辑去啃。GCM 用 CipherInputStream 就会犯这个错，所以那边是手写循环。
//
// 跨块那几条不能省：1 MB 分块是为了避免恢复大备份时 OOM，
// 而分块逻辑的边界（正好一块、跨两块、空数据）恰恰是最容易写错的地方。

package com.kiq.aicp.data

import com.kiq.aicp.data.backup.BackupCrypto
import com.kiq.aicp.data.backup.BackupPasswordException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BackupCryptoTest {

	@get:Rule
	val temp = TemporaryFolder()

	private val password = "kiq-的口令-2026".toCharArray()

	private fun seal(plain: ByteArray, pwd: CharArray = password): ByteArray {
		val out = ByteArrayOutputStream()
		BackupCrypto.encryptingStream(out, pwd).use { it.write(plain) }
		return out.toByteArray()
	}

	private fun open(sealed: ByteArray, pwd: CharArray = password): ByteArray {
		val target = File(temp.root, "out-${System.nanoTime()}.bin")
		BackupCrypto.decryptTo(ByteArrayInputStream(sealed), target, pwd)
		return target.readBytes()
	}

	@Test
	fun `加密再解密拿回一模一样的内容`() {
		val plain = "KIQ 的聊天记录，带中文和 emoji 🌸".toByteArray()

		assertArrayEquals(plain, open(seal(plain)))
	}

	@Test
	fun `空内容也能往返`() {
		assertArrayEquals(ByteArray(0), open(seal(ByteArray(0))))
	}

	@Test
	fun `正好一整块的数据能往返`() {
		val plain = ByteArray(1 shl 20).also { Random(7).nextBytes(it) }

		assertArrayEquals(plain, open(seal(plain)))
	}

	@Test
	fun `跨多块的数据能往返`() {
		// 2.5 MB：三块，最后一块是零头。分块逻辑写错的话这条第一个红
		val plain = ByteArray((2.5 * (1 shl 20)).toInt()).also { Random(11).nextBytes(it) }

		assertArrayEquals(plain, open(seal(plain)))
	}

	@Test
	fun `一字节一字节写进去也能往返`() {
		val plain = "逐字节写入".toByteArray()
		val out = ByteArrayOutputStream()
		BackupCrypto.encryptingStream(out, password).use { stream ->
			plain.forEach { stream.write(it.toInt()) }
		}

		assertArrayEquals(plain, open(out.toByteArray()))
	}

	@Test
	fun `同一口令两次加密产出的密文不同`() {
		val plain = "同样的内容".toByteArray()

		val first = seal(plain)
		val second = seal(plain)

		assertFalse(first.contentEquals(second))
		// 但都能解回同一份原文
		assertArrayEquals(plain, open(first))
		assertArrayEquals(plain, open(second))
	}

	@Test
	fun `加密后的文件带得出加密标记`() {
		val sealed = seal("内容".toByteArray())

		assertTrue(BackupCrypto.looksEncrypted(sealed))
	}

	@Test
	fun `普通 zip 和太短的文件都不算加密`() {
		// PK\u0003\u0004 是 zip 的头
		assertFalse(BackupCrypto.looksEncrypted(byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0, 0, 0, 0)))
		assertFalse(BackupCrypto.looksEncrypted(byteArrayOf(1, 2, 3)))
		assertFalse(BackupCrypto.looksEncrypted(ByteArray(0)))
	}

	@Test
	fun `zip 套在加密流外面能原样解回来`() {
		// 这条测的是流嵌套和 close 级联：ZipOutputStream 关闭时必须把尾块和结束标记一路推出去，
		// 少一环就会得到一个"导出看着成功、恢复时打不开"的备份，而那要等真机上才发现
		val out = ByteArrayOutputStream()
		BackupCrypto.encryptingStream(out, password).use { sealed ->
			java.util.zip.ZipOutputStream(sealed).use { zip ->
				zip.putNextEntry(java.util.zip.ZipEntry("manifest.json"))
				zip.write("""{"backupVersion":1}""".toByteArray())
				zip.closeEntry()
				zip.putNextEntry(java.util.zip.ZipEntry("aicp.db"))
				// 造一段比一块大的内容，顺带压到分块边界
				zip.write(ByteArray(1 shl 21).also { Random(5).nextBytes(it) })
				zip.closeEntry()
			}
		}

		val plainZip = open(out.toByteArray())
		val names = mutableListOf<String>()
		java.util.zip.ZipInputStream(ByteArrayInputStream(plainZip)).use { zip ->
			while (true) {
				val entry = zip.nextEntry ?: break
				names += entry.name
				zip.closeEntry()
			}
		}

		assertEquals(listOf("manifest.json", "aicp.db"), names)
	}

	@Test
	fun `口令不对要明确报错而不是吐出半截数据`() {
		val sealed = seal("机密内容".toByteArray())

		val e = runCatching { open(sealed, "错误的口令".toCharArray()) }.exceptionOrNull()

		assertTrue(e is BackupPasswordException)
		assertTrue(e!!.message!!.contains("口令不对"))
	}

	@Test
	fun `密文被改过一个字节就解不开`() {
		val sealed = seal("原始内容".toByteArray())
		// 头部之后是第一块的长度和密文，动最后一个字节等于动 GCM tag
		sealed[sealed.size - 5] = (sealed[sealed.size - 5] + 1).toByte()

		val e = runCatching { open(sealed) }.exceptionOrNull()

		assertTrue(e is BackupPasswordException)
	}

	@Test
	fun `盐被换掉就解不开`() {
		val sealed = seal("原始内容".toByteArray())
		sealed[10] = (sealed[10] + 1).toByte()

		assertTrue(runCatching { open(sealed) }.exceptionOrNull() is BackupPasswordException)
	}

	@Test
	fun `少了结束标记的文件算截断`() {
		val sealed = seal("内容".toByteArray())
		// 砍掉尾部的 4 字节结束标记
		val truncated = sealed.copyOfRange(0, sealed.size - 4)

		val e = runCatching { open(truncated) }.exceptionOrNull()

		assertTrue(e is BackupPasswordException)
		assertTrue(e!!.message!!.contains("截断") || e.message!!.contains("断了"))
	}

	@Test
	fun `文件从中间断掉也要报错`() {
		val plain = ByteArray(1 shl 21).also { Random(3).nextBytes(it) }
		val sealed = seal(plain)
		val half = sealed.copyOfRange(0, sealed.size / 2)

		assertTrue(runCatching { open(half) }.exceptionOrNull() is BackupPasswordException)
	}

	@Test
	fun `头部就不完整的文件直接拒绝`() {
		assertTrue(
			runCatching { open(byteArrayOf(1, 2, 3, 4)) }.exceptionOrNull() is BackupPasswordException,
		)
	}

	@Test
	fun `没有加密标记的文件不该走解密`() {
		val zipLike = ByteArray(64).also { it[0] = 0x50; it[1] = 0x4B }

		val e = runCatching { open(zipLike) }.exceptionOrNull()

		assertTrue(e is BackupPasswordException)
		assertTrue(e!!.message!!.contains("没有加密标记"))
	}

	@Test
	fun `解密失败后不留下半成品文件`() {
		val sealed = seal("内容".toByteArray())
		val target = File(temp.root, "half.bin")

		runCatching {
			BackupCrypto.decryptTo(ByteArrayInputStream(sealed), target, "错口令".toCharArray())
		}

		assertFalse("解密失败却留下了文件，后面的解压会去读它", target.exists())
	}

	@Test
	fun `头部长度就是魔数加盐加 IV`() {
		assertEquals(8 + 16 + 12, BackupCrypto.HEADER_SIZE)
	}
}
