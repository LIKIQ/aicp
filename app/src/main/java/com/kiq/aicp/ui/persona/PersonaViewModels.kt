// app/src/main/java/com/kiq/aicp/ui/persona/PersonaViewModels.kt
// 性格列表与编辑页的状态。两个 ViewModel 放一个文件，因为它们共用同一批领域概念，
// 分成两个文件反而要来回跳。
//
// 编辑页的草稿是一个完整的 PersonaDraft：新建和编辑走同一套字段，
// "用一句话生成"只是把草稿整体替换掉，用户还能在保存前继续手改。
//
// 头像图片是"选完立刻落盘"，草稿里只留相对路径 —— 相册给的 content:// 过一会儿就读不动了，
// 攒到点保存时再读经常已经失效。代价是用户选完不保存就退出会留下一张没人引用的图，
// 这个交给孤儿清理兜底，比头像预览显示不出来划算。

package com.kiq.aicp.ui.persona

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.kiq.aicp.AicpApplication
import com.kiq.aicp.data.attach.AttachmentStore
import com.kiq.aicp.data.db.entity.PersonaEntity
import com.kiq.aicp.data.prefs.SettingsStore
import com.kiq.aicp.data.repo.PersonaRepository
import com.kiq.aicp.domain.persona.PersonaGenerator
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class PersonaListViewModel(
	private val personaRepository: PersonaRepository,
	private val attachmentStore: AttachmentStore,
) : ViewModel() {

	val personas: StateFlow<List<PersonaEntity>> = personaRepository.observeAll()
		.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

	/** 列表里的头像要能显示图片，不然编辑页配了图这里还是 emoji，两处对不上 */
	fun resolveFile(localPath: String): File = attachmentStore.resolve(localPath)

	private val _message = MutableStateFlow<String?>(null)
	val message: StateFlow<String?> = _message

	fun dismissMessage() {
		_message.value = null
	}

	fun delete(persona: PersonaEntity) {
		viewModelScope.launch {
			val removed = personaRepository.delete(persona.id)
			_message.value = if (removed) {
				"已删除 ${persona.name}"
			} else {
				"${persona.name} 是内置性格，删不掉；可以直接改它的人设"
			}
		}
	}

	companion object {
		val Factory = viewModelFactory {
			initializer {
				val c = AicpApplication.container()
				PersonaListViewModel(c.personaRepository, c.attachmentStore)
			}
		}
	}
}

data class PersonaDraft(
	val name: String = "",
	val avatarEmoji: String = "🙂",
	/** 已经落盘的头像图片相对路径；null 表示没配图，界面回落到 emoji */
	val avatarPath: String? = null,
	val tagline: String = "",
	/** 私人备注，永不进 prompt */
	val note: String = "",
	val systemPrompt: String = "",
	val greeting: String = "",
	val temperature: Float = 0.85f,
	val topP: Float = 0.95f,
	val maxTokens: Int = 1024,
	val modelOverride: String = "",
) {
	val valid: Boolean get() = name.isNotBlank() && systemPrompt.isNotBlank()
}

data class PersonaEditUiState(
	val draft: PersonaDraft = PersonaDraft(),
	val loaded: Boolean = false,
	val isBuiltIn: Boolean = false,
	val isNew: Boolean = true,
	val generating: Boolean = false,
	/** 头像正在压缩落盘。压 256 长边通常很快，但选到几十兆的大图会卡一下 */
	val avatarSaving: Boolean = false,
	val message: String? = null,
	val saved: Boolean = false,
)

class PersonaEditViewModel(
	private val personaId: Long?,
	private val personaRepository: PersonaRepository,
	private val settingsStore: SettingsStore,
	private val generator: PersonaGenerator,
	private val attachmentStore: AttachmentStore,
) : ViewModel() {

	private val _uiState = MutableStateFlow(PersonaEditUiState(isNew = personaId == null))
	val uiState: StateFlow<PersonaEditUiState> = _uiState

	/** 生成时用过的那句描述，保存进 generatedFromPrompt 方便以后回溯 */
	private var generatedFrom: String? = null

	/**
	 * 数据库当前引用着的头像路径。用来分辨草稿里那张图是"库里的"还是"本轮刚落盘的"：
	 * 前者不能删（用户可能退出不保存），后者换掉就该删，不然连点几次选图就攒一堆孤儿文件。
	 */
	private var persistedAvatarPath: String? = null

	init {
		if (personaId == null) {
			_uiState.value = _uiState.value.copy(loaded = true)
		} else {
			viewModelScope.launch {
				val persona = personaRepository.getById(personaId)
				if (persona == null) {
					_uiState.value = _uiState.value.copy(loaded = true, message = "这个性格已经不存在了")
				} else {
					generatedFrom = persona.generatedFromPrompt
					persistedAvatarPath = persona.avatarPath
					_uiState.value = PersonaEditUiState(
						draft = persona.toDraft(),
						loaded = true,
						isBuiltIn = persona.isBuiltIn,
						isNew = false,
					)
				}
			}
		}
	}

	fun update(transform: (PersonaDraft) -> PersonaDraft) {
		_uiState.value = _uiState.value.copy(draft = transform(_uiState.value.draft), message = null)
	}

	fun dismissMessage() {
		_uiState.value = _uiState.value.copy(message = null)
	}

	/** 头像预览要拿真实文件，但 attachmentStore 不往 Composable 里传，只递一个解析函数 */
	fun resolveFile(localPath: String): File = attachmentStore.resolve(localPath)

	/**
	 * 相册选中的图当场压缩落盘，草稿里换成新路径，预览立刻能显示。
	 * saveAvatar 对"不是图片""压完还超 2MB"是直接 error() 抛的，
	 * 这里必须接住 —— 让它冒回 Photo Picker 的回调等于当场闪退。
	 */
	fun pickAvatar(uri: Uri) {
		if (_uiState.value.avatarSaving) return

		viewModelScope.launch {
			_uiState.value = _uiState.value.copy(avatarSaving = true, message = null)
			runCatching { attachmentStore.saveAvatar(uri) }
				.onSuccess { saved ->
					val replaced = _uiState.value.draft.avatarPath
					_uiState.value = _uiState.value.copy(
						avatarSaving = false,
						draft = _uiState.value.draft.copy(avatarPath = saved.localPath),
						message = "头像换好了，记得点保存",
					)
					discardTempAvatar(replaced)
				}
				.onFailure { e ->
					_uiState.value = _uiState.value.copy(
						avatarSaving = false,
						message = e.message ?: "这张图存不下来，换一张试试",
					)
				}
		}
	}

	/** 清掉图片头像回落到 emoji。库里那张旧图等保存时由仓库删，这儿只管草稿 */
	fun clearAvatar() {
		val current = _uiState.value.draft.avatarPath ?: return
		_uiState.value = _uiState.value.copy(
			draft = _uiState.value.draft.copy(avatarPath = null),
			message = null,
		)
		viewModelScope.launch { discardTempAvatar(current) }
	}

	/** 只删本轮新落盘、还没进库的那张；库里在用的一律留着 */
	private suspend fun discardTempAvatar(path: String?) {
		if (path != null && path != persistedAvatarPath) {
			attachmentStore.delete(listOf(path))
		}
	}

	/** 用一句话描述生成整套人设，生成后草稿被整体替换，用户仍可手改再保存 */
	fun generateFrom(description: String) {
		val text = description.trim()
		if (text.isEmpty() || _uiState.value.generating) return

		viewModelScope.launch {
			_uiState.value = _uiState.value.copy(generating = true, message = null)
			val settings = settingsStore.current()
			if (!settings.hasEndpoint) {
				_uiState.value = _uiState.value.copy(
					generating = false,
					message = "生成人设要调模型，先去设置页填 Base URL 和 API Key",
				)
				return@launch
			}

			runCatching { generator.generate(text, settings) }
				.onSuccess { generated ->
					generatedFrom = text
					_uiState.value = _uiState.value.copy(
						generating = false,
						draft = PersonaDraft(
							name = generated.name,
							avatarEmoji = generated.avatarEmoji,
							// 图片头像和备注是用户自己动手弄的，生成人设不该把它们冲掉，
							// 跟 modelOverride 一个道理，从旧草稿原样带过来
							avatarPath = _uiState.value.draft.avatarPath,
							tagline = generated.tagline,
							note = _uiState.value.draft.note,
							systemPrompt = generated.systemPrompt,
							greeting = generated.greeting,
							temperature = generated.temperature,
							topP = generated.topP,
							maxTokens = generated.maxTokens,
							modelOverride = _uiState.value.draft.modelOverride,
						),
						message = "生成好了，可以直接改再保存",
					)
				}
				.onFailure { e ->
					_uiState.value = _uiState.value.copy(
						generating = false,
						message = e.message ?: "生成失败",
					)
				}
		}
	}

	fun save() {
		val draft = _uiState.value.draft
		if (!draft.valid) {
			_uiState.value = _uiState.value.copy(message = "名字和人设提示词都不能空着")
			return
		}

		viewModelScope.launch {
			if (personaId == null) {
				val newId = personaRepository.create(
					name = draft.name,
					avatarEmoji = draft.avatarEmoji,
					tagline = draft.tagline,
					systemPrompt = draft.systemPrompt,
					greeting = draft.greeting,
					temperature = draft.temperature,
					topP = draft.topP,
					maxTokens = draft.maxTokens,
					modelOverride = draft.modelOverride,
					generatedFromPrompt = generatedFrom,
				)
				// create() 还没有 avatarPath/note 两个参数。宁可多补一次 update 也不在这儿手搓
				// PersonaEntity：sortOrder 要查表里的 max，name/采样参数的归一化也都在仓库里，
				// 自己拼一份等于把那套规则抄第二遍，以后仓库改了这边就悄悄跑偏。
				// 没配图也没写备注时跳过，常见路径不多花一条 SQL。
				if (draft.avatarPath != null || draft.note.isNotBlank()) {
					personaRepository.getById(newId)?.let { created ->
						personaRepository.update(
							created.copy(avatarPath = draft.avatarPath, note = draft.note.trim()),
						)
					}
				}
			} else {
				val existing = personaRepository.getById(personaId)
				if (existing == null) {
					_uiState.value = _uiState.value.copy(message = "这个性格已经不存在了")
					return@launch
				}
				personaRepository.update(
					existing.copy(
						name = draft.name,
						avatarEmoji = draft.avatarEmoji,
						avatarPath = draft.avatarPath,
						tagline = draft.tagline,
						note = draft.note.trim(),
						systemPrompt = draft.systemPrompt,
						greeting = draft.greeting,
						temperature = draft.temperature,
						topP = draft.topP,
						maxTokens = draft.maxTokens,
						modelOverride = draft.modelOverride.takeIf { it.isNotBlank() },
						generatedFromPrompt = generatedFrom,
					),
				)
			}
			// 落库之后草稿里那张图就是"库里在用的"了，别让后续清理动它
			persistedAvatarPath = draft.avatarPath
			_uiState.value = _uiState.value.copy(saved = true)
		}
	}

	private fun PersonaEntity.toDraft() = PersonaDraft(
		name = name,
		avatarEmoji = avatarEmoji,
		avatarPath = avatarPath,
		tagline = tagline,
		note = note,
		systemPrompt = systemPrompt,
		greeting = greeting,
		temperature = temperature,
		topP = topP,
		maxTokens = maxTokens,
		modelOverride = modelOverride.orEmpty(),
	)

	companion object {
		fun factoryFor(personaId: Long?) = viewModelFactory {
			initializer {
				val c = AicpApplication.container()
				PersonaEditViewModel(
					personaId = personaId,
					personaRepository = c.personaRepository,
					settingsStore = c.settingsStore,
					generator = c.personaGenerator,
					attachmentStore = c.attachmentStore,
				)
			}
		}
	}
}
