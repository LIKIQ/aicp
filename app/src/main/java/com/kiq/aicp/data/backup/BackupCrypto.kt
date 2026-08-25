/*
 * app/src/main/java/com/kiq/aicp/data/backup/BackupCrypto.kt
 * 备份文件的口令加密
 * 职责：
 * - 导出时把 zip 流套上 AES-256-GCM，文件头明文放盐和 IV
 * - 恢复时先整份解密到临时文件、验过 GCM tag 再交给解压逻辑
 * - 认出一份备份到底加没加密（读前 8 字节魔数）
 *
 * 为什么解密不用 CipherInputStream：
 * Android 上它遇到 AEADBadTagException 会吞掉异常直接返回 -1，
 * 结果是"口令错了却像成功了一样"，拿到一个截断的 zip 去恢复。
 * 这里自己控制 update/doFinal 的循环，tag 不对就明确抛出来。
 *
 * 口令丢了就真的打不开了，没有后门也没有找回。这一点必须在 UI 上跟用户讲清楚。
 */
package com.kiq.aicp.data.backup

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** 口令不对，或者文件被改过 */
class BackupPasswordException(message: String) : Exception(message)

object BackupCrypto {

	/** 文件头魔数。跟着格式版本一起改，将来换算法就是 AICPBAK2 */
	private val MAGIC = "AICPBAK1".toByteArray(Charsets.US_ASCII)

	private const val SALT_BYTES = 16
	private const val IV_BYTES = 12
	private const val TAG_BITS = 128
	private const val KEY_BITS = 256

	/**
	 * PBKDF2 迭代次数。手机上大约十分之几秒，用户等得起；
	 * 而离线爆破一个弱口令的成本被抬高了五个数量级。
	 */
	private const val ITERATIONS = 120_000

	private const val TRANSFORM = "AES/GCM/NoPadding"
	private const val KDF = "PBKDF2WithHmacSHA256"

	/** 一块 1 MB。解密时的堆占用就是这个数，跟备份多大无关 */
	private const val BLOCK_SIZE = 1 shl 20

	/** 密文块的长度上限，比明文块多出 tag 和一点余量。超了说明文件结构被破坏 */
	private const val MAX_SEALED_BLOCK = BLOCK_SIZE + 1024

	/** 头部总长：魔数 + 盐 + IV。这几段是明文，没有它们连自己也解不开 */
	val HEADER_SIZE = MAGIC.size + SALT_BYTES + IV_BYTES

	/**
	 * 光看开头这几个字节就知道要不要问口令。
	 * 不足 8 字节的文件直接当没加密，后面的解压逻辑会给出更准确的报错。
	 */
	fun looksEncrypted(head: ByteArray): Boolean {
		if (head.size < MAGIC.size) return false
		return MAGIC.indices.all { head[it] == MAGIC[it] }
	}

	/**
	 * 先往 out 写明文头部，再返回一个写进去就自动加密的流。
	 *
	 * 分块而不是整份一把梭，是因为 GCM 解密必须先验完 tag 才能吐明文——
	 * 平台实现会把整份密文缓在堆里，一份带图片的备份轻松几十上百 MB，
	 * 那样恢复时必然 OOM。切成 1 MB 一块，内存占用就固定住了，
	 * 而且哪一块被改过都能单独查出来。
	 *
	 * 返回的流 close 时才写出尾块和结束标记，那个 close 不能省。
	 */
	fun encryptingStream(out: OutputStream, password: CharArray): OutputStream {
		val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
		val baseIv = ByteArray(IV_BYTES).also { SecureRandom().nextBytes(it) }

		out.write(MAGIC)
		out.write(salt)
		out.write(baseIv)
		out.flush()

		return BlockEncryptingStream(out, deriveKey(password, salt), baseIv)
	}

	/** 一块攒满就加密吐出去，块与块之间用 4 字节长度隔开 */
	private class BlockEncryptingStream(
		private val sink: OutputStream,
		private val key: SecretKeySpec,
		private val baseIv: ByteArray,
	) : OutputStream() {

		private val buffer = ByteArray(BLOCK_SIZE)
		private var filled = 0
		private var blockIndex = 0
		private var closed = false

		override fun write(b: Int) {
			buffer[filled++] = b.toByte()
			if (filled == BLOCK_SIZE) flushBlock()
		}

		override fun write(src: ByteArray, off: Int, len: Int) {
			var cursor = off
			var left = len
			while (left > 0) {
				val room = BLOCK_SIZE - filled
				val step = minOf(room, left)
				System.arraycopy(src, cursor, buffer, filled, step)
				filled += step
				cursor += step
				left -= step
				if (filled == BLOCK_SIZE) flushBlock()
			}
		}

		private fun flushBlock() {
			if (filled == 0) return
			val cipher = Cipher.getInstance(TRANSFORM).apply {
				init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, ivFor(baseIv, blockIndex)))
			}
			val sealed = cipher.doFinal(buffer, 0, filled)
			sink.write(intToBytes(sealed.size))
			sink.write(sealed)
			blockIndex++
			filled = 0
		}

		override fun flush() = sink.flush()

		/** 结束标记是长度 0 的空块：读到它才算读完整，否则就是文件被截断了 */
		override fun close() {
			if (closed) return
			closed = true
			flushBlock()
			sink.write(intToBytes(0))
			sink.flush()
			sink.close()
		}
	}

	/**
	 * 整份解密到 target，验过每一块的 tag 才算成功。
	 *
	 * 解出来先落盘再交给解压，是故意的：口令错、文件被截、某块被改，
	 * 这些都在解压开始之前就被拦下，而不是让 ZipInputStream 去啃一半的垃圾数据。
	 * 失败时把半成品删掉，不留给后面的流程添乱。
	 */
	fun decryptTo(input: InputStream, target: File, password: CharArray) {
		try {
			FileOutputStream(target).use { decryptInto(input, it, password) }
		} catch (e: Throwable) {
			target.delete()
			throw e
		}
	}

	/**
	 * 内存版加密，给配置码这类小数据用。
	 * 格式跟备份文件完全一致（同一套分块和 KDF），只是不落盘。
	 */
	fun sealBytes(plain: ByteArray, password: CharArray): ByteArray {
		val out = ByteArrayOutputStream()
		encryptingStream(out, password).use { it.write(plain) }
		return out.toByteArray()
	}

	/** 内存版解密。口令不对同样抛 BackupPasswordException */
	fun openBytes(sealed: ByteArray, password: CharArray): ByteArray {
		val out = ByteArrayOutputStream()
		decryptInto(ByteArrayInputStream(sealed), out, password)
		return out.toByteArray()
	}

	/** 解密的正主。只管把 input 解到 sink，落盘还是留在内存由调用方决定 */
	private fun decryptInto(input: InputStream, sink: OutputStream, password: CharArray) {
		val header = ByteArray(HEADER_SIZE)
		if (!input.readFully(header)) {
			throw BackupPasswordException("这段内容太短，不像一份完整的加密数据")
		}
		if (!looksEncrypted(header)) {
			throw BackupPasswordException("这段内容没有加密标记，不该走解密这条路")
		}

		val salt = header.copyOfRange(MAGIC.size, MAGIC.size + SALT_BYTES)
		val baseIv = header.copyOfRange(MAGIC.size + SALT_BYTES, HEADER_SIZE)
		val key = deriveKey(password, salt)

		var blockIndex = 0
		var sawTerminator = false
		val lenBuf = ByteArray(4)

		while (true) {
			if (!input.readFully(lenBuf)) break
			val len = bytesToInt(lenBuf)
			if (len == 0) {
				sawTerminator = true
				break
			}
			if (len < 0 || len > MAX_SEALED_BLOCK) {
				throw BackupPasswordException("数据结构不对，可能已经损坏")
			}

			val sealed = ByteArray(len)
			if (!input.readFully(sealed)) {
				throw BackupPasswordException("数据在中途断了，没法完整恢复")
			}

			val cipher = Cipher.getInstance(TRANSFORM).apply {
				init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, ivFor(baseIv, blockIndex)))
			}
			// 口令错在这里现形：GCM 的 tag 对不上就直接抛，不会吐出半截明文
			val plain = try {
				cipher.doFinal(sealed)
			} catch (e: AEADBadTagException) {
				throw BackupPasswordException("口令不对，或者这份数据被改动过")
			}
			sink.write(plain)
			blockIndex++
		}

		if (!sawTerminator) {
			throw BackupPasswordException("数据没有正常结束，可能是复制粘贴时被截断了")
		}
	}

	/*
	 * 这套格式挡得住什么、挡不住什么，说清楚免得后来人误判：
	 *
	 * 每块独立 GCM，所以任何一块的内容被改动都会在 doFinal 处炸出来，改不了。
	 * 挡不住的是"删"——攻击者把某块连同后面的内容一起砍掉、再补一个终止标记，
	 * 解密会正常结束，只是明文少了一截。
	 *
	 * 没有为此加块数校验，是因为两条使用路径上都有更靠后的兜底：
	 * 备份文件那边缺 manifest、SQLite 头不对、Room schema 不匹配，三道任一都会拦住；
	 * 配置码那边不完整的 JSON 解析必然失败。
	 * 真要加也不是不行（魔数抬到 AICPBAK2、终止标记后带块数），
	 * 但那会引入一个长期存在的兼容分支，而兼容分支自己就是 bug 温床。
	 * 如果哪天这份数据被用在没有后置校验的地方，这个决定就要重新算。
	 */
	/** 流一次读不满是常事，读到够或者到底为止 */
	private fun InputStream.readFully(dst: ByteArray): Boolean {
		var filled = 0
		while (filled < dst.size) {
			val n = read(dst, filled, dst.size - filled)
			if (n < 0) return false
			filled += n
		}
		return true
	}

	private fun deriveKey(password: CharArray, salt: ByteArray): SecretKeySpec {
		val spec = PBEKeySpec(password, salt, ITERATIONS, KEY_BITS)
		val bytes = SecretKeyFactory.getInstance(KDF).generateSecret(spec).encoded
		spec.clearPassword()
		return SecretKeySpec(bytes, "AES")
	}

	/**
	 * 每块一个不重复的 IV：前 8 字节是这次导出的随机值，后 4 字节放块序号。
	 * GCM 最怕同一个 key 复用 IV，序号保证同一份文件里绝不会撞。
	 */
	private fun ivFor(base: ByteArray, index: Int): ByteArray {
		val iv = base.copyOf()
		iv[8] = (index ushr 24).toByte()
		iv[9] = (index ushr 16).toByte()
		iv[10] = (index ushr 8).toByte()
		iv[11] = index.toByte()
		return iv
	}

	private fun intToBytes(value: Int) = byteArrayOf(
		(value ushr 24).toByte(),
		(value ushr 16).toByte(),
		(value ushr 8).toByte(),
		value.toByte(),
	)

	private fun bytesToInt(b: ByteArray): Int =
		((b[0].toInt() and 0xFF) shl 24) or
			((b[1].toInt() and 0xFF) shl 16) or
			((b[2].toInt() and 0xFF) shl 8) or
			(b[3].toInt() and 0xFF)
}
