// app/src/main/java/com/kiq/aicp/ui/sticker/StickerViewModel.kt
// 表情包管理页状态。
//
// 分组和表情走两条 Flow（observePacks / observeAll），在这里按 packId 归并成
// 「分组 + 组内表情」。没用 StickerDao.packsWithItems：那是 suspend 的一次性查询，
// 导入完不会自己刷新，而管理页必须跟着库实时变。
//
// 情绪归属也在这一层算完再交给 UI：组名认得出情绪就整组共用，认不出才看每张图的 emotion。
// 没调 StickerRepository.unclassifiedCount 那个 suspend 版本 —— 组名和每张图的 emotion
// 都跟着上面两条 Flow 一起到手了，再查一遍库等于按组数多发 N 次往返，而 combine 的任何一条
// 上游变动（连一句 snackbar 提示都算）都会把 transform 整个重跑。两边对"情绪分组恒为 0 张
// 待识别"的口径是一致的，换成仓库那条查询也是同一个结果。
//
// 仓库层的撞名、空名、图片超限全靠 require/check 抛异常表达，所以这一层每个写操作
// 都得 runCatching 接住再转成 error 文案 —— viewModelScope 里漏出去的异常会直接崩掉
// 整个应用，而"名字重复了"这种事根本不值得崩。
//
// 导入刻意做成逐张独立成败：一次选十张里夹了个坏文件，不能让剩下九张陪葬。
//
// 导入成功后还要排一次后台识图（AppContainer.scheduleStickerVision）。这一步只能落在这一层：
// 仓库是 data 层、不认识 WorkManager，而"刚进了几张没情绪的新图"这个时机只有这里知道。

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
import com.kiq.aicp.domain.sticker.StickerEmotion
import com.kiq.aicp.domain.sticker.StickerParser
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 一个分组连着它的表情，UI 一次拿全，不用在 Composable 里再 filter 一遍。
 *
 * 后三个情绪字段是派生值。放进 state 而不是让 Composable 现算，是因为算它们要知道
 * "组名算不算情绪"以及"组内有几张还没识别"，后者在仓库层是 suspend 的，
 * 摆到重组路径上就成了每帧查库。
 */
data class StickerPackGroup(
	val pack: StickerPackEntity,
	val stickers: List<StickerEntity>,

	/** 组名认出来的情绪。非 null 就是整组共用它，组内图片自己识别出的 emotion 让位 */
	val packEmotion: String? = null,

	/** 还没识别出情绪的张数。情绪分组恒为 0：整组共用组名，本来就没必要为单张去识图 */
	val unclassifiedCount: Int = 0,

	/**
	 * 情绪分组里带着别的 emotion 的张数。
	 * 这些值现在不生效（组名优先），得让用户知道，不然他会以为自己把图改坏了。
	 */
	val shadowedCount: Int = 0,
) {
	/** 组名是情绪：这组按组名走，不识图 */
	val byPackName: Boolean get() = packEmotion != null

	/** 这张图当前真正生效的情绪，null 表示还没分类 */
	fun effectiveEmotion(sticker: StickerEntity): String? =
		packEmotion ?: sticker.emotion.takeIf { it.isNotBlank() }
}

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

	/**
	 * 识图到底跑不跑得起来。没配能看图的模型时 StickerVision 直接判 notConfigured、
	 * Worker 也不会重试，那些图会一直挂在待分类上 —— 页面必须把这件事说出来，
	 * 不然"等着就行"就是让用户白等。
	 */
	val visionReady: Boolean = true,

	val importing: Boolean = false,
	val error: String? = null,
	val notice: String? = null,
) {
	val totalCount: Int get() = groups.sumOf { it.stickers.size }

	/** 全库还有多少张等着后台识别。顶部说明用它说一句"还在排队"，用户不用管 */
	val unclassifiedTotal: Int get() = groups.sumOf { it.unclassifiedCount }

	/** 一张都没有：这时候空状态引导比列表重要，导入按钮要摆到最显眼处 */
	val noStickers: Boolean get() = totalCount == 0
}

class StickerViewModel(
	private val stickerRepository: StickerRepository,
	private val settingsStore: SettingsStore,
	private val attachmentStore: AttachmentStore,
	private val scheduleVision: () -> Unit = {},
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
			groups = packs.map { pack -> groupOf(pack, byPack[pack.id].orEmpty()) },
			stickersEnabled = settings.stickersEnabled,
			promptLimit = settings.stickerPromptLimit,
			visionReady = settings.hasVisionModel,
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
	 * 组名一变（重命名分组）情绪归属就可能从"看图片"翻成"整组共用"，所以这里每次都重算
	 * 而不缓存：Room 的 @Update 会让 sticker_packs 失效、observePacks 重新发射，
	 * 这个 transform 跟着重跑一遍，UI 自然就跟上了。
	 */
	private fun groupOf(pack: StickerPackEntity, items: List<StickerEntity>): StickerPackGroup {
		val packEmotion = StickerEmotion.emotionOf(pack.name)
		return StickerPackGroup(
			pack = pack,
			stickers = items,
			packEmotion = packEmotion,
			unclassifiedCount = if (packEmotion != null) 0 else items.count { it.emotion.isBlank() },
			shadowedCount = if (packEmotion == null) {
				0
			} else {
				items.count { it.emotion.isNotBlank() && it.emotion != packEmotion }
			},
		)
	}

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

	/**
	 * 改组名顺带会改情绪归属：叫「开心」的组整组按开心发，改成「我的收藏」就退回看每张图的
	 * emotion。这里不做额外提示，UI 那边靠分组头部把当前归属写明白。
	 */
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
	 *
	 * 进了至少一张就排一次后台识图：新图的 emotion 是空的，不排就得等下次冷启动才分类，
	 * 而页面上写着"后台会自动分类"，那句话得当场兑现。同名任务按 KEEP 并入，连着导几批也只有一个任务。
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

			// 排程失败（WorkManager 没初始化起来之类）不该影响导入本身的结果提示：
			// 图已经在库里了，最坏情况是等下次启动补跑
			if (ok > 0) runCatching { scheduleVision() }
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

	/**
	 * 手动指定这张图的情绪，把识图结果盖掉；传空串是"清除"，让它退回待分类，
	 * 后台下一轮识图会重新捡起它。
	 *
	 * 词表校验交给仓库层（它同时接识图的输出，校验必须在那一层兜住），
	 * 这里只负责把用户点中的那个词递过去，顺手挡掉"选的还是原来那个"的空操作。
	 */
	fun setEmotion(sticker: StickerEntity, emotion: String) {
		val clean = emotion.trim()
		if (clean == sticker.emotion) return
		viewModelScope.launch {
			runCatching {
				// 清除走单独一个仓库方法：setEmotion 那条路刻意不收空串，
				// 免得"模型认不出"被写成静默清空（理由见 StickerRepository.clearEmotion）
				if (clean.isEmpty()) {
					stickerRepository.clearEmotion(sticker.id)
				} else {
					stickerRepository.setEmotion(sticker.id, clean)
				}
			}
				.onSuccess {
					notice(
						if (clean.isEmpty()) {
							"[${sticker.label}] 退回待分类了"
						} else {
							"[${sticker.label}] 记成「$clean」了"
						},
					)
				}
				.onFailure { fail(it, "情绪没改成") }
		}
	}

	/**
	 * 换组。移进情绪组之后这张图就归组名管了，提示里得说出来 ——
	 * 用户看到的往前一步是"移动"，实际发生的还有"情绪变了"，只说前半句他会以为图白移了。
	 */
	fun moveSticker(sticker: StickerEntity, pack: StickerPackEntity) {
		if (sticker.packId == pack.id) return
		viewModelScope.launch {
			runCatching { stickerRepository.moveToPack(sticker.id, pack.id) }
				.onSuccess {
					val emotion = StickerEmotion.emotionOf(pack.name)
					notice(
						if (emotion != null) {
							"[${sticker.label}] 移到「${pack.name}」了，现在按情绪「$emotion」发"
						} else {
							"[${sticker.label}] 移到「${pack.name}」了"
						},
					)
				}
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
				StickerViewModel(
					c.stickerRepository,
					c.settingsStore,
					c.attachmentStore,
					// 排程走容器而不是仓库：WorkManager 是 app 层的事，
					// data 层认识它的话单测里每建一个仓库都得先把 WorkManager 拉起来
					c::scheduleStickerVision,
				)
			}
		}
	}
}
