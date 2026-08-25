// app/src/main/java/com/kiq/aicp/AppContainer.kt
// 手写的依赖容器（ServiceLocator）。
//
// 没上 Hilt 的理由很实际：这个工程的依赖图只有一层（DB / DataStore / OkHttp / 四个 Repository），
// 引 Hilt 要多背一个注解处理器，还得盯着它跟 AGP 内建 Kotlin、KSP 版本的兼容性。
// 全部 by lazy，用到才建 —— 冷启动不为没打开的页面付钱。

package com.kiq.aicp

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import com.kiq.aicp.data.attach.AttachmentStore
import com.kiq.aicp.data.attach.BuiltInStickers
import com.kiq.aicp.data.attach.TextExtractor
import com.kiq.aicp.data.db.AicpDatabase
import com.kiq.aicp.data.prefs.KeystoreCipher
import com.kiq.aicp.data.prefs.SettingsStore
import com.kiq.aicp.data.remote.LlmConfig
import com.kiq.aicp.data.remote.LlmProvider
import com.kiq.aicp.data.remote.OpenAiCompatProvider
import com.kiq.aicp.data.repo.ChatRepository
import com.kiq.aicp.data.repo.ConversationRepository
import com.kiq.aicp.data.repo.MemoryRepository
import com.kiq.aicp.data.repo.PersonaRepository
import com.kiq.aicp.data.repo.ProactiveRepository
import com.kiq.aicp.data.repo.StickerRepository
import com.kiq.aicp.domain.memory.ContextBuilder
import com.kiq.aicp.domain.memory.MemoryCompressor
import com.kiq.aicp.domain.persona.PersonaGenerator
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

private val Context.settingsDataStore: DataStore<Preferences> by
	preferencesDataStore(name = SettingsStore.STORE_NAME)

class AppContainer(context: Context) {

	private val appContext: Context = context.applicationContext

	val database: AicpDatabase by lazy { AicpDatabase.build(appContext) }

	val settingsStore: SettingsStore by lazy {
		SettingsStore(appContext.settingsDataStore, KeystoreCipher())
	}

	/**
	 * 基础 client。readTimeout 给 60 秒是给非流式请求用的（压缩、生成人设都要等模型出整段）；
	 * 流式请求会在 Provider 内部 newBuilder 把 readTimeout 放开成 0。
	 */
	private val httpClient: OkHttpClient by lazy {
		OkHttpClient.Builder()
			.connectTimeout(20, TimeUnit.SECONDS)
			.readTimeout(60, TimeUnit.SECONDS)
			.writeTimeout(30, TimeUnit.SECONDS)
			.retryOnConnectionFailure(true)
			.build()
	}

	val llmProvider: LlmProvider by lazy {
		// 必须用命名参数：尾随 lambda 会被当成最后一个形参 ioDispatcher，而不是 configLoader
		OpenAiCompatProvider(
			baseClient = httpClient,
			configLoader = {
				val s = settingsStore.current()
				LlmConfig(baseUrl = s.baseUrl, apiKey = s.apiKey, defaultModel = s.model)
			},
		)
	}

	val attachmentStore: AttachmentStore by lazy { AttachmentStore(appContext) }

	val textExtractor: TextExtractor by lazy { TextExtractor() }

	val personaRepository: PersonaRepository by lazy {
		PersonaRepository(database.personaDao(), attachmentStore)
	}

	val conversationRepository: ConversationRepository by lazy {
		ConversationRepository(database, attachmentStore)
	}

	val chatRepository: ChatRepository by lazy { ChatRepository(database, attachmentStore) }

	val memoryRepository: MemoryRepository by lazy { MemoryRepository(database.memoryDao()) }

	val stickerRepository: StickerRepository by lazy { StickerRepository(database, attachmentStore) }

	val proactiveRepository: ProactiveRepository by lazy { ProactiveRepository(database) }

	val builtInStickers: BuiltInStickers by lazy {
		BuiltInStickers(appContext, stickerRepository, attachmentStore)
	}

	val contextBuilder: ContextBuilder by lazy {
		ContextBuilder(chatRepository, memoryRepository, attachmentStore, stickerRepository)
	}

	val personaGenerator: PersonaGenerator by lazy { PersonaGenerator(llmProvider) }

	val memoryCompressor: MemoryCompressor by lazy {
		MemoryCompressor(
			chatRepository = chatRepository,
			memoryRepository = memoryRepository,
			conversationRepository = conversationRepository,
			personaRepository = personaRepository,
			llmProvider = llmProvider,
		)
	}
}
