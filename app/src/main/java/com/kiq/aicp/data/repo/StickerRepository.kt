// app/src/main/java/com/kiq/aicp/data/repo/StickerRepository.kt
// 表情包读写。
//
// 两件事这一层必须包住，不然调用方一定会漏：
//
// 1. label 清洗与去重。label 里带中括号或换行会直接破坏 StickerParser 的解析，
//    撞名会让 [开心] 指向哪张图变成随机。所以入库前一律过 uniqueLabel。
//
// 2. 删行与删文件的顺序。SQLite 的 CASCADE 管不到磁盘，
//    必须先把 localPath 捞出来，删完行再删文件；反了就留下孤儿文件没人清。

package com.kiq.aicp.data.repo

import android.net.Uri
import androidx.annotation.VisibleForTesting
import androidx.room.withTransaction
import com.kiq.aicp.data.attach.AttachmentStore
import com.kiq.aicp.data.db.AicpDatabase
import com.kiq.aicp.data.db.entity.StickerEntity
import com.kiq.aicp.data.db.entity.StickerPackEntity
import com.kiq.aicp.domain.sticker.StickerParser
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class StickerRepository(
	private val db: AicpDatabase,
	private val attachmentStore: AttachmentStore,
	private val clock: () -> Long = System::currentTimeMillis,
) {

	private val dao = db.stickerDao()

	fun observePacks(): Flow<List<StickerPackEntity>> = dao.observePacks()

	fun observeAll(): Flow<List<StickerEntity>> = dao.observeAll()

	/** label → localPath。聊天页订阅这个来渲染标记，查表比每条消息查一次库便宜得多 */
	fun observeIndex(): Flow<Map<String, String>> =
		dao.observeAll().map { list -> list.associate { it.label to it.localPath } }

	suspend fun allStickers(): List<StickerEntity> = dao.allStickers()

	// ---------------- 分组 ----------------

	/** 已存在就直接返回原有 id。导入时"自动建默认组"需要幂等 */
	suspend fun ensurePack(name: String): Long {
		val clean = name.trim().take(MAX_PACK_NAME).ifEmpty { DEFAULT_PACK }
		dao.packByName(clean)?.let { return it.id }
		return dao.insertPack(
			StickerPackEntity(name = clean, sortOrder = dao.packCount(), createdAt = clock()),
		)
	}

	/** 管理界面新建分组：撞名要让用户知道，不能悄悄合并 */
	suspend fun createPack(name: String): Long {
		val clean = name.trim().take(MAX_PACK_NAME)
		require(clean.isNotEmpty()) { "分组名不能为空" }
		require(dao.packByName(clean) == null) { "已经有叫「$clean」的分组了" }
		return dao.insertPack(
			StickerPackEntity(name = clean, sortOrder = dao.packCount(), createdAt = clock()),
		)
	}

	suspend fun renamePack(packId: Long, name: String) {
		val clean = name.trim().take(MAX_PACK_NAME)
		require(clean.isNotEmpty()) { "分组名不能为空" }
		val existing = dao.packByName(clean)
		require(existing == null || existing.id == packId) { "已经有叫「$clean」的分组了" }
		val pack = dao.packById(packId) ?: return
		dao.updatePack(pack.copy(name = clean))
	}

	suspend fun deletePack(packId: Long) {
		val paths = dao.collectPathsOfPack(packId)
		db.withTransaction { dao.deletePackById(packId) }
		attachmentStore.delete(paths)
	}

	// ---------------- 表情 ----------------

	/**
	 * 从一个本地文件导入表情。内置表情包走这条路 ——
	 * assets 里的素材没有 content:// URI，import(uri) 那条走不通。
	 *
	 * 跟 import 一样做撞名处理和失败回滚：内置素材的文件名可能跟用户已有的表情重名，
	 * 那时候自动加数字后缀而不是报错。
	 */
	suspend fun importFromFile(
		packId: Long,
		file: java.io.File,
		displayName: String,
		desiredLabel: String? = null,
	): StickerEntity {
		val saved = attachmentStore.saveStickerFromFile(file, displayName)
		return insertSaved(packId, saved, desiredLabel ?: saved.fileName.substringBeforeLast('.'))
	}

	/**
	 * 导入一张表情。
	 * @param desiredLabel 传 null 就用文件名（去扩展名）当标记
	 */
	suspend fun import(packId: Long, uri: Uri, desiredLabel: String? = null): StickerEntity {
		val saved = attachmentStore.saveSticker(uri)
		return insertSaved(packId, saved, desiredLabel ?: saved.fileName.substringBeforeLast('.'))
	}

	private suspend fun insertSaved(
		packId: Long,
		saved: com.kiq.aicp.data.attach.SavedAttachment,
		rawLabel: String,
	): StickerEntity {
		val label = uniqueLabel(rawLabel)

		val entity = StickerEntity(
			packId = packId,
			label = label,
			localPath = saved.localPath,
			mimeType = saved.mimeType,
			byteSize = saved.byteSize,
			width = saved.width,
			height = saved.height,
			createdAt = clock(),
		)

		val id = runCatching { dao.insertSticker(entity) }.getOrElse { e ->
			// 入库失败就把刚落盘的文件删掉，否则这张图永远没人引用
			attachmentStore.delete(listOf(saved.localPath))
			throw e
		}
		return entity.copy(id = id)
	}

	suspend fun rename(stickerId: Long, newLabel: String) {
		val sticker = dao.byId(stickerId) ?: return
		val clean = sanitizeLabel(newLabel)
		require(clean.isNotEmpty()) { "标记不能为空" }
		val holder = dao.byLabel(clean)
		require(holder == null || holder.id == stickerId) { "已经有叫「$clean」的表情了" }
		dao.updateSticker(sticker.copy(label = clean))
	}

	suspend fun moveToPack(stickerId: Long, packId: Long) {
		val sticker = dao.byId(stickerId) ?: return
		dao.updateSticker(sticker.copy(packId = packId))
	}

	suspend fun delete(stickerId: Long) {
		val path = dao.collectPathOfSticker(stickerId)
		dao.deleteStickerById(stickerId)
		path?.let { attachmentStore.delete(listOf(it)) }
	}

	/** 模型真发出去了才累加，只是出现在候选清单里不算 */
	suspend fun bumpUsage(labels: List<String>) {
		if (labels.isNotEmpty()) dao.bumpUseCount(labels)
	}

	/** 注入 system prompt 的候选标记，常用优先并限量 */
	suspend fun promptLabels(limit: Int = StickerParser.PROMPT_LIMIT): List<String> =
		dao.topUsed(limit).map { it.label }

	suspend fun collectAllPaths(): List<String> = dao.collectAllPaths()

	// ---------------- 内部 ----------------

	/**
	 * 撞名就追加数字：开心 → 开心2 → 开心3。
	 * 不用随机后缀是因为标记要让用户手打，"开心a3f9"没人愿意输。
	 *
	 * 开成 internal 是为了让单测能直接打这个逻辑 —— 它的入口 import() 需要真的 SAF Uri，
	 * 在 Robolectric 里造不出来，而"撞名加后缀"恰恰是这一层最容易写错的地方。
	 */
	@VisibleForTesting
	internal suspend fun uniqueLabel(desired: String): String {
		val base = sanitizeLabel(desired).ifEmpty { DEFAULT_LABEL }
		if (dao.countByLabel(base) == 0) return base

		var index = 2
		while (dao.countByLabel("$base$index") > 0) index++
		return "$base$index"
	}

	/** 中括号和换行必须去掉：它们会让 [标记] 的解析边界错位 */
	private fun sanitizeLabel(raw: String): String =
		raw.replace(LABEL_FORBIDDEN, "").trim().take(MAX_LABEL)

	companion object {
		const val DEFAULT_PACK = "我的表情"
		private const val DEFAULT_LABEL = "表情"
		private const val MAX_PACK_NAME = 20

		/** 跟 StickerParser 的正则上限对齐，超过就解析不到了 */
		private const val MAX_LABEL = 20

		private val LABEL_FORBIDDEN = Regex("""[\[\]\r\n\t]""")
	}
}
