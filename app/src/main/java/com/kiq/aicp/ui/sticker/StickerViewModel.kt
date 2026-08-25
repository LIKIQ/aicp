// app/src/main/java/com/kiq/aicp/ui/sticker/StickerViewModel.kt
// 表情包管理页状态。
//
// 分组和表情走两条 Flow（observePacks / observeAll），在这里按 packId 归并成
// 「分组 + 组内表情」。没用 StickerDao.packsWithItems：那是 suspend 的一次性查询，
// 导入完不会自己刷新，而管理页必须跟着库实时变。
//
// 仓库层的撞名、空名、图片超限全靠 require/check 抛异常表达，所以这一层每个写操作
// 都得 runCatching 接住再转成 error 文案 —— viewModelScope 里漏出去的异常会直接崩掉
// 整个应用，而"名字重复了"这种事根本不值得崩。
//
// 导入刻意做成逐张独立成败：一次选十张里夹了个坏文件，不能让剩下九张陪葬。

package com.kiq.aicp.ui.sticker

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.kiq.aicp.AicpApplication
import com.kiq.aicp.data.attach.AttachmentStore
import com.kiq.aicp.data.db.entity.StickerEntity
import com.kiq.aicp.data.db.entity.StickerPackEntity
import com.kiq.aicp.data.prefs.SettingsStore
import com.kiq.aicp.data.repo.StickerRepository
import com.kiq.aicp.domain.sticker.StickerParser
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 一个分组连着它的表情，UI 一次拿全，不用在 Composable 里再 filter 一遍 */
data class StickerPackGroup(
	val pack: StickerPackEntity,
	val stickers: List<StickerEntity>,
)

/** 页面内的临时状态：不落库，也不该被上游 Flow 的新值冲掉 */
private data class Transient(
	val importing: Boolean = false,
	val error: String? = null,
	val notice: String? = null,
)

data class StickerUiState(
	val groups: List<StickerPackGroup> = emptyList(),
	val stickersEnabled: Boolean = true,
	val promptLimit: Int = StickerParser.PROMPT_LIMIT,
	val importing: Boolean = false,
	val error: String? = null,
	val notice: String? = null,
) {
	val totalCount: Int get() = groups.sumOf { it.stickers.size }

	/** 一张都没有：这时候空状态引导比列表重要，导入按钮要摆到最显眼处 */
	val noStickers: Boolean get() = totalCount == 0
}

class StickerViewModel(
	private val stickerRepository: StickerRepository,
	private val settingsStore: SettingsStore,
	private val attachmentStore: AttachmentStore,
) : ViewModel() {

	private val transient = MutableStateFlow(Transient())

	val uiState: StateFlow<StickerUiState> = combine(
		stickerRepository.observePacks(),
		stickerRepository.observeAll(),
		settingsStore.settings,
		transient,
	) { packs, stickers, settings, t ->
		val byPack = stickers.groupBy { it.packId }
		StickerUiState(
			groups = packs.map { pack -> StickerPackGroup(pack, byPack[pack.id].orEmpty()) },
			stickersEnabled = settings.stickersEnabled,
			promptLimit = settings.stickerPromptLimit,
			importing = t.importing,
			error = t.error,
			notice = t.notice,
		)
	}.stateIn(
		scope = viewModelScope,
		started = SharingStarted.WhileSubscribed(5_000),
		initialValue = StickerUiState(),
	)

	/**
	 * localPath 换成真实文件。attachmentStore 不直接递给 Composable：
	 * 页面只该知道"有个 File 能画出来"，不该知道文件躺在 filesDir 的哪一层。
	 */
	fun resolveFile(localPath: String): File = attachmentStore.resolve(localPath)

	fun dismissError() {
		transient.value = transient.value.copy(error = null)
	}

	fun dismissNotice() {
		transient.value = transient.value.copy(notice = null)
	}

	// ---------------- 分组 ----------------

	fun createPack(name: String) {
		val clean = name.trim()
		viewModelScope.launch {
			runCatching { stickerRepository.createPack(clean) }
				.onSuccess { notice("建好了「$clean」") }
				.onFailure { fail(it, "分组没建成") }
		}
	}

	fun renamePack(pack: StickerPackEntity, name: String) {
		val clean = name.trim()
		if (clean == pack.name) return
		viewModelScope.launch {
			runCatching { stickerRepository.renamePack(pack.id, clean) }
				.onSuccess { notice("「${pack.name}」改名成「$clean」了") }
				.onFailure { fail(it, "改名没成功") }
		}
	}

	/** 连带删掉组内所有表情和磁盘文件，所以调用方必须先做二次确认 */
	fun deletePack(group: StickerPackGroup) {
		viewModelScope.launch {
			runCatching { stickerRepository.deletePack(group.pack.id) }
				.onSuccess {
					notice(
						if (group.stickers.isEmpty()) {
							"已删除空分组「${group.pack.name}」"
						} else {
							"已删除「${group.pack.name}」和组里 ${group.stickers.size} 张表情"
						},
					)
				}
				.onFailure { fail(it, "分组没删掉") }
		}
	}

	// ---------------- 表情 ----------------

	/**
	 * 批量导入。packId 传 null 表示还没有任何分组，先幂等建一个默认组再往里塞。
	 *
	 * SAF 交出来的 uri 只在这次授权期内可读，所以进来立刻落盘拷进私有目录，
	 * 库里存的是相对路径而不是 uri —— 用户回头把原图从相册删了，表情还得能发。
	 */
	fun importInto(packId: Long?, uris: List<Uri>) {
		if (uris.isEmpty() || transient.value.importing) return

		viewModelScope.launch {
			transient.value = Transient(importing = true)

			val target = runCatching {
				packId ?: stickerRepository.ensurePack(StickerRepository.DEFAULT_PACK)
			}.getOrElse { e ->
				transient.value = Transient(error = e.message ?: "默认分组没建起来")
				return@launch
			}

			var ok = 0
			val reasons = mutableListOf<String>()
			uris.forEach { uri ->
				runCatching { stickerRepository.import(target, uri) }
					.onSuccess { ok++ }
					.onFailure { e -> reasons += e.message?.takeIf { it.isNotBlank() } ?: "这张图读不出来" }
			}

			transient.value = Transient(notice = importSummary(ok, reasons))
		}
	}

	fun renameSticker(sticker: StickerEntity, label: String) {
		val clean = label.trim()
		if (clean == sticker.label) return
		viewModelScope.launch {
			runCatching { stickerRepository.rename(sticker.id, clean) }
				.onSuccess { notice("标记改成 [$clean] 了") }
				.onFailure { fail(it, "标记没改成") }
		}
	}

	fun moveSticker(sticker: StickerEntity, pack: StickerPackEntity) {
		if (sticker.packId == pack.id) return
		viewModelScope.launch {
			runCatching { stickerRepository.moveToPack(sticker.id, pack.id) }
				.onSuccess { notice("[${sticker.label}] 移到「${pack.name}」了") }
				.onFailure { fail(it, "没能移动") }
		}
	}

	fun deleteSticker(sticker: StickerEntity) {
		viewModelScope.launch {
			runCatching { stickerRepository.delete(sticker.id) }
				.onSuccess { notice("已删除 [${sticker.label}]") }
				.onFailure { fail(it, "没删掉") }
		}
	}

	// ---------------- 设置项 ----------------
	//
	// 这两个开关归表情包管这一摊，所以顺手在这个 ViewModel 上开口：
	// 设置页的表情包分区直接复用它，省得为两个字段再写一个 ViewModel。

	fun setStickersEnabled(enabled: Boolean) {
		viewModelScope.launch { settingsStore.setStickersEnabled(enabled) }
	}

	fun setPromptLimit(limit: Int) {
		viewModelScope.launch { settingsStore.setStickerPromptLimit(limit) }
	}

	// ---------------- 内部 ----------------

	private fun notice(text: String) {
		transient.value = Transient(notice = text)
	}

	/** 仓库层抛出来的本来就是给人看的话（"已经有叫 xx 的分组了"），能用就直接用 */
	private fun fail(e: Throwable, fallback: String) {
		transient.value = Transient(error = e.message?.takeIf { it.isNotBlank() } ?: fallback)
	}

	/** 失败原因先去重：十张图同一个毛病，重复十遍没有信息量，只会把提示挤爆 */
	private fun importSummary(ok: Int, reasons: List<String>): String {
		if (reasons.isEmpty()) return "导入 $ok 张"
		val distinct = reasons.distinct()
		val shown = distinct.take(2).joinToString("；")
		val more = if (distinct.size > 2) "等" else ""
		return "导入 $ok 张，${reasons.size} 张失败：$shown$more"
	}

	companion object {
		val Factory = viewModelFactory {
			initializer {
				val c = AicpApplication.container()
				StickerViewModel(c.stickerRepository, c.settingsStore, c.attachmentStore)
			}
		}
	}
}
