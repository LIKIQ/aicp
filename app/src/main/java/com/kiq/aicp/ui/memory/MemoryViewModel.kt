// app/src/main/java/com/kiq/aicp/ui/memory/MemoryViewModel.kt
// 记忆管理页状态。
//
// 这一页的存在本身就是产品承诺的一部分：记忆是自动抽取的，那用户必须能看见、能改、能删。
// 钉住（pinned）的语义是"以后自动压缩不许再改这条"，MemoryRepository.upsertCard 里有对应的跳过逻辑。

package com.kiq.aicp.ui.memory

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.kiq.aicp.AicpApplication
import com.kiq.aicp.data.db.entity.MemoryCardEntity
import com.kiq.aicp.data.repo.MemoryRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MemoryViewModel(
	private val memoryRepository: MemoryRepository,
) : ViewModel() {

	val cards: StateFlow<List<MemoryCardEntity>> = memoryRepository.observeAllCards()
		.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

	private val _message = MutableStateFlow<String?>(null)
	val message: StateFlow<String?> = _message

	fun dismissMessage() {
		_message.value = null
	}

	fun setPinned(card: MemoryCardEntity, pinned: Boolean) {
		viewModelScope.launch {
			memoryRepository.setPinned(card.id, pinned)
			_message.value = if (pinned) {
				"已锁定「${card.keyword}」，自动压缩不会再改它"
			} else {
				"已解锁「${card.keyword}」"
			}
		}
	}

	fun edit(card: MemoryCardEntity, content: String, importance: Int) {
		viewModelScope.launch {
			memoryRepository.editCard(card, content, importance)
			_message.value = "已更新「${card.keyword}」"
		}
	}

	fun delete(card: MemoryCardEntity) {
		viewModelScope.launch {
			memoryRepository.deleteCard(card.id)
			_message.value = "已删除「${card.keyword}」"
		}
	}

	fun pruneCold() {
		viewModelScope.launch {
			val removed = memoryRepository.pruneCold()
			_message.value = if (removed > 0) {
				"清掉了 $removed 条长期没用到的低重要度记忆"
			} else {
				"没有需要清理的冷记忆"
			}
		}
	}

	companion object {
		val Factory = viewModelFactory {
			initializer {
				MemoryViewModel(AicpApplication.container().memoryRepository)
			}
		}
	}
}
