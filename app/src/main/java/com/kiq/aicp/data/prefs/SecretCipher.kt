// app/src/main/java/com/kiq/aicp/data/prefs/SecretCipher.kt
// API Key 的本地加解密。
//
// 为什么不用 androidx.security 的 EncryptedSharedPreferences：
// 它在 1.1.0 里已经被官方标记 Deprecated（废弃说明直接写 "Use SharedPreferences instead"），
// Jetpack 不再提供这层封装了。所以自己走 AndroidKeystore。
//
// 必须说清楚的边界：这套加密防的是"别人拿到你手机、翻应用私有目录/备份"，
// 防不住把 APK 拉下来逆向的人 —— 端上没有真正藏得住的秘密。
// 真要一个 Key 谁都拿不到，只能走自建后端代理，不把 Key 放在客户端。

package com.kiq.aicp.data.prefs

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface SecretCipher {
	/** 明文 -> 可落盘的字符串。空串原样返回 */
	fun encrypt(plain: String): String

	/** 解不出来返回 null（密钥被系统清了、数据损坏、换过设备），调用方要提示重新填 Key */
	fun decrypt(token: String): String?
}

class KeystoreCipher(
	private val alias: String = DEFAULT_ALIAS,
) : SecretCipher {

	override fun encrypt(plain: String): String {
		if (plain.isEmpty()) return ""
		return runCatching {
			val cipher = Cipher.getInstance(TRANSFORMATION)
			cipher.init(Cipher.ENCRYPT_MODE, secretKey())
			val iv = cipher.iv
			val body = cipher.doFinal(plain.toByteArray(StandardCharsets.UTF_8))
			// GCM 的 IV 每次随机，跟密文拼在一起存，解密时再切开
			Base64.encodeToString(iv + body, Base64.NO_WRAP)
		}.getOrDefault("")
	}

	override fun decrypt(token: String): String? {
		if (token.isEmpty()) return null
		return runCatching {
			val raw = Base64.decode(token, Base64.NO_WRAP)
			if (raw.size <= IV_LENGTH) return null
			val iv = raw.copyOfRange(0, IV_LENGTH)
			val body = raw.copyOfRange(IV_LENGTH, raw.size)
			val cipher = Cipher.getInstance(TRANSFORMATION)
			cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, iv))
			String(cipher.doFinal(body), StandardCharsets.UTF_8)
		}.getOrNull()
	}

	private fun secretKey(): SecretKey {
		val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
		(keyStore.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

		val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
		generator.init(
			KeyGenParameterSpec.Builder(
				alias,
				KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
			)
				.setBlockModes(KeyProperties.BLOCK_MODE_GCM)
				.setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
				.setKeySize(KEY_SIZE)
				// 不要求用户解锁：压缩是后台任务，锁屏时也得能跑
				.setUserAuthenticationRequired(false)
				.build(),
		)
		return generator.generateKey()
	}

	private companion object {
		const val ANDROID_KEYSTORE = "AndroidKeyStore"
		const val DEFAULT_ALIAS = "aicp_api_key_v1"
		const val TRANSFORMATION = "AES/GCM/NoPadding"
		const val IV_LENGTH = 12
		const val TAG_BITS = 128
		const val KEY_SIZE = 256
	}
}
