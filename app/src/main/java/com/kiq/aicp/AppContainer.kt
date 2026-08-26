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
import com.kiq.aicp.data.attach.BuiltInEmojiStickers
import com.kiq.aicp.data.attach.BuiltInStickers
import com.kiq.aicp.data.attach.TextExtractor
import com.kiq.aicp.data.backup.BackupManager
import com.kiq.aicp.data.db.AicpDatabase
import com.kiq.aicp.data.prefs.KeystoreCipher
import com.kiq.aicp.data.prefs.SettingsStore
import com.kiq.aicp.data.remote.LlmConfig
import com.kiq.aicp.data.remote.LlmProvider
import com.kiq.aicp.data.remote.OpenAiCompatProvider
import com.kiq.aicp.data.remote.BingRssSearchClient
import com.kiq.aicp.data.remote.UpdateChecker
import com.kiq.aicp.data.remote.WebSearchClient
import com.kiq.aicp.data.repo.ChatRepository
import com.kiq.aicp.data.repo.ConversationRepository
import com.kiq.aicp.data.repo.MemoryRepository
import com.kiq.aicp.data.repo.PersonaRepository
import com.kiq.aicp.data.repo.ProactiveRepository
import com.kiq.aicp.data.repo.StickerRepository
import com.kiq.aicp.domain.memory.ContextBuilder
import com.kiq.aicp.domain.memory.MemoryCompressor
import com.kiq.aicp.domain.memory.MemoryLinter
import com.kiq.aicp.domain.persona.PersonaGenerator
import com.kiq.aicp.domain.sticker.StickerVision
import com.kiq.aicp.domain.websearch.WebSearchService
import com.kiq.aicp.work.KeepAliveService
import com.kiq.aicp.work.StickerVisionWorker
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

	/** 预设 emoji 表情。渲染要用 cacheDir 落临时 PNG，其余不碰 Context */
	val builtInEmojiStickers: BuiltInEmojiStickers by lazy {
		BuiltInEmojiStickers(appContext.cacheDir, stickerRepository, settingsStore)
	}

	val contextBuilder: ContextBuilder by lazy {
		ContextBuilder(chatRepository, memoryRepository, attachmentStore, stickerRepository)
	}

	val personaGenerator: PersonaGenerator by lazy { PersonaGenerator(llmProvider) }

	/** 记忆体检。用户在记忆页手点触发，跟自动压缩共用压缩模型 */
	val memoryLinter: MemoryLinter by lazy { MemoryLinter(memoryRepository, llmProvider) }

	/** 表情识图。用后台任务逐张跑，跟带图聊天共用视觉模型 */
	val stickerVision: StickerVision by lazy {
		StickerVision(stickerRepository, attachmentStore, llmProvider)
	}

	/**
	 * 联网搜索。搜索客户端刻意跟 llmProvider 分开装配：
	 * 它要访问任意第三方站点，绝不能带上那套 Authorization 头。
	 */
	val webSearchClient: WebSearchClient by lazy { BingRssSearchClient(httpClient) }

	val webSearchService: WebSearchService by lazy {
		WebSearchService(llmProvider, webSearchClient)
	}

	/**
	 * 排一次后台识图。
	 *
	 * 该在两处调：导入表情成功之后（新图需要分类），以及应用启动时（补上上次没跑完的）。
	 * 排程放在容器上而不是 StickerRepository 里：仓库是 data 层，不该认识 WorkManager，
	 * 否则单测里每建一个仓库都要先把 WorkManager 初始化起来。
	 *
	 * 重复调用是安全的——同名任务按 KEEP 并入已排的那个，不会叠出好几个任务
	 * 对同一批图各发一遍请求。没活干时 Worker 查一次库就 success 退出。
	 */
	fun scheduleStickerVision() {
		StickerVisionWorker.enqueue(appContext)
	}

	/**
	 * 保活前台服务的开关。
	 *
	 * 放在容器上跟 scheduleStickerVision 同理：appContext 在这儿，
	 * 而 AicpApplication 只负责"什么时候该开、什么时候该关"这个判断。
	 * 启动失败（比如从后台启前台服务被系统拦了）已经在 KeepAliveService 内部咽掉并记日志，
	 * 这一层不用再兜 —— 保活本身是"能活久点更好"，不是必须成功的前置条件。
	 *
	 * 重复调用是安全的：重复 start 只会让已在跑的服务多走一次 onStartCommand，
	 * 重复 stop 对没在跑的服务是空操作。
	 */
	fun applyKeepAlive(enabled: Boolean) {
		if (enabled) KeepAliveService.start(appContext) else KeepAliveService.stop(appContext)
	}

	/**
	 * 版本检测。跟 llmProvider 共用 httpClient —— 连接池和线程都省一份，
	 * 而它自己会把读超时压短（一个 JSON 用不着等 60 秒）。
	 * 节流状态存在 SettingsStore 里，这里只把读写两个口子接过去。
	 */
	val updateChecker: UpdateChecker by lazy {
		UpdateChecker(
			baseClient = httpClient,
			lastCheckAt = { settingsStore.lastUpdateCheckAt() },
			markChecked = { settingsStore.setLastUpdateCheckAt(it) },
		)
	}

	val memoryCompressor: MemoryCompressor by lazy {
		MemoryCompressor(
			chatRepository = chatRepository,
			memoryRepository = memoryRepository,
			conversationRepository = conversationRepository,
			personaRepository = personaRepository,
			llmProvider = llmProvider,
		)
	}

	/**
	 * 备份/恢复。by lazy 在这里格外要紧：它一被取用就会连带打开数据库（要做 WAL checkpoint），
	 * 而开机时的"待恢复搬运"必须发生在库被打开之前 —— 那一步走的是 BackupManager 的静态方法，
	 * 不碰容器，见 AicpApplication.onCreate。
	 */
	val backupManager: BackupManager by lazy { BackupManager(appContext, database, settingsStore) }
}
