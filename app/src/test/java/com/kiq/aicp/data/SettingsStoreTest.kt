// app/src/test/java/com/kiq/aicp/data/SettingsStoreTest.kt
// API Key 可选持久化的契约测试：关闭后只在内存生效，重新开启才恢复加密落盘。

package com.kiq.aicp.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.kiq.aicp.data.prefs.SecretCipher
import com.kiq.aicp.data.prefs.SettingsStore
import com.kiq.aicp.domain.model.AicpSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.test.runTest

class SettingsStoreTest {

	@Test
	fun `关闭后填写的 Key 不进 DataStore`() = runTest {
		val dataStore = MemoryPreferencesDataStore()
		val store = newStore(dataStore)
		store.setRememberApiKey(false)
		store.setApiKey("sk-memory")

		assertEquals("sk-memory", store.current().apiKey)
		assertEquals("", newStore(dataStore).current().apiKey)
	}

	@Test
	fun `重新打开时把内存 Key 写回 DataStore`() = runTest {
		val dataStore = MemoryPreferencesDataStore()
		val store = newStore(dataStore)
		store.setRememberApiKey(false)
		store.setApiKey("sk-memory")
		store.setRememberApiKey(true)

		val restarted = newStore(dataStore).current()
		assertTrue(restarted.rememberApiKey)
		assertEquals("sk-memory", restarted.apiKey)
	}

	@Test
	fun `关闭时删除旧 Key 但当前进程仍能使用`() = runTest {
		val dataStore = MemoryPreferencesDataStore()
		val store = newStore(dataStore)
		store.setApiKey("sk-stored")
		store.setRememberApiKey(false)

		assertEquals("sk-stored", store.current().apiKey)
		val restarted = newStore(dataStore).current()
		assertFalse(restarted.rememberApiKey)
		assertEquals("", restarted.apiKey)
	}

	@Test
	fun `导入不带 Key 的配置时沿用当前 Key`() = runTest {
		val dataStore = MemoryPreferencesDataStore()
		val store = newStore(dataStore)
		store.setApiKey("sk-stored")

		store.applyImported(AicpSettings(baseUrl = "https://api.example.com", model = "m", apiKey = ""))

		assertEquals("sk-stored", store.current().apiKey)
		assertEquals("sk-stored", newStore(dataStore).current().apiKey)
	}

	@Test
	fun `导入一份不记住 Key 的配置不会写盘`() = runTest {
		val dataStore = MemoryPreferencesDataStore()
		val store = newStore(dataStore)
		store.setApiKey("sk-stored")

		store.applyImported(
			AicpSettings(
				baseUrl = "https://api.example.com",
				model = "m",
				apiKey = "sk-imported",
				rememberApiKey = false,
			),
		)

		// 本次运行仍然可用
		assertEquals("sk-imported", store.current().apiKey)
		// 重启后连旧的 sk-stored 也不该留下
		val restarted = newStore(dataStore).current()
		assertFalse(restarted.rememberApiKey)
		assertEquals("", restarted.apiKey)
	}

	@Test
	fun `不记住模式下清空 Key 立即生效`() = runTest {
		val dataStore = MemoryPreferencesDataStore()
		val store = newStore(dataStore)
		store.setRememberApiKey(false)
		store.setApiKey("sk-memory")
		store.setApiKey("")

		assertEquals("", store.current().apiKey)
	}

	private fun newStore(dataStore: DataStore<Preferences>): SettingsStore =
		SettingsStore(dataStore, TestCipher)

	private class MemoryPreferencesDataStore : DataStore<Preferences> {
		private val state = MutableStateFlow<Preferences>(emptyPreferences())
		override val data: Flow<Preferences> = state

		override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
			val updated = transform(state.value)
			state.value = updated
			return updated
		}
	}

	private object TestCipher : SecretCipher {
		override fun encrypt(plain: String): String = "enc:$plain"
		override fun decrypt(token: String): String? = token.removePrefix("enc:")
	}
}
