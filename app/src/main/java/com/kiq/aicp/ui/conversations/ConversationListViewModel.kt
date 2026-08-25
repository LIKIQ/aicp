// app/src/main/java/com/kiq/aicp/ui/conversations/ConversationListViewModel.kt
// 会话列表状态。新建会话后要跳进聊天页，所以用 pendingOpen 把"刚建好的 id"抛给 UI，
// UI 消费完调 consumeOpen 清掉 —— 不这么做的话旋转屏幕会重复导航一次。
//
// 卡片头像要的数据不在 conversations 表里：单聊显示的是对面那个性格的头像，
// 所以这里把"全部会话的参与者关联"整张拉进来，跟性格列表在内存里对一次。
// 逐个会话查参与者是 N+1，行数又只有"会话数 × 参与者数"这个量级，整表划算得多。

package com.kiq.aicp.ui.conversations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.kiq.aicp.AicpApplication
import com.kiq.aicp.data.attach.AttachmentStore
import com.kiq.aicp.data.db.entity.ConversationEntity
import com.kiq.aicp.data.db.entity.PersonaEntity
import com.kiq.aicp.data.repo.ConversationRepository
import com.kiq.aicp.data.repo.PersonaRepository
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ConversationListUiState(
	val conversations: List<ConversationEntity> = emptyList(),
	val personas: List<PersonaEntity> = emptyList(),
	/**
	 * 单聊 → 对面那个性格。只有"参与者恰好一个"的会话才进这张表；
	 * 性格已经被删掉的单聊也不在里面，那种情况头像退回会话名首字。
	 */
	val soloPersonas: Map<Long, PersonaEntity> = emptyMap(),
	val error: String? = null,
) {
	val isEmpty: Boolean get() = conversations.isEmpty()

	/**
	 * 卡片头像的取值。整块二选一，不逐字段回退 ——
	 * 否则单聊没配头像时会莫名捡起群头像来显示。
	 */
	fun avatarEmojiOf(conversation: ConversationEntity): String =
		soloPersonas[conversation.id]?.avatarEmoji ?: conversation.avatarEmoji

	fun avatarPathOf(conversation: ConversationEntity): String? {
		val solo = soloPersonas[conversation.id]
		return if (solo != null) solo.avatarPath else conversation.avatarPath
	}
}

class ConversationListViewModel(
	private val conversationRepository: ConversationRepository,
	private val personaRepository: PersonaRepository,
	private val attachmentStore: AttachmentStore,
) : ViewModel() {

	private val error = MutableStateFlow<String?>(null)

	val uiState: StateFlow<ConversationListUiState> = combine(
		conversationRepository.observeActive(),
		personaRepository.observeAll(),
		conversationRepository.observeAllParticipants(),
		error,
	) { conversations, personas, refs, err ->
		val byId = personas.associateBy { it.id }
		val soloPersonas = refs
			.groupBy { it.conversationId }
			.mapNotNull { (convId, members) ->
				val only = members.singleOrNull() ?: return@mapNotNull null
				val persona = byId[only.personaId] ?: return@mapNotNull null
				convId to persona
			}
			.toMap()

		ConversationListUiState(conversations, personas, soloPersonas, err)
	}.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ConversationListUiState())

	/** 卡片渲染头像要把 localPath 变成真文件，这层薄封装省得把 store 传进 Composable */
	fun resolveAvatar(localPath: String): File = attachmentStore.resolve(localPath)

	/** 刚建好、等着被打开的会话 id */
	private val _pendingOpen = MutableStateFlow<Long?>(null)
	val pendingOpen: StateFlow<Long?> = _pendingOpen

	fun consumeOpen() {
		_pendingOpen.value = null
	}

	fun startSingle(personaId: Long) {
		viewModelScope.launch {
			runCatching { conversationRepository.createSingle(personaId) }
				.onSuccess { _pendingOpen.value = it }
				.onFailure { error.value = it.message ?: "创建会话失败" }
		}
	}

	fun startGroup(personaIds: List<Long>) {
		if (personaIds.isEmpty()) return
		viewModelScope.launch {
			runCatching { conversationRepository.createGroup(personaIds) }
				.onSuccess { _pendingOpen.value = it }
				.onFailure { error.value = it.message ?: "创建群聊失败" }
		}
	}

	fun togglePin(conversation: ConversationEntity) {
		viewModelScope.launch {
			conversationRepository.setPinned(conversation.id, !conversation.pinned)
		}
	}

	fun archive(conversation: ConversationEntity) {
		viewModelScope.launch { conversationRepository.setArchived(conversation.id, true) }
	}

	fun delete(conversation: ConversationEntity) {
		viewModelScope.launch { conversationRepository.delete(conversation.id) }
	}

	fun dismissError() {
		error.value = null
	}

	companion object {
		val Factory = viewModelFactory {
			initializer {
				val c = AicpApplication.container()
				ConversationListViewModel(
					c.conversationRepository,
					c.personaRepository,
					c.attachmentStore,
				)
			}
		}
	}
}
