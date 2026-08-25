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
//
// 3. 情绪的两条来源在这一层合并。分组名本身是情绪（"开心""伤心表情包"）时整组共用组名，
//    分组名是"我的收藏"这类时用每张图识图识出来的情绪。上层（提示词、挑图、待识别计数）
//    只该看到一份统一的情绪视图，不该各自再判一遍"这个组名算不算情绪"。

package com.kiq.aicp.data.repo

import android.net.Uri
import androidx.annotation.VisibleForTesting
import androidx.room.withTransaction
import com.kiq.aicp.data.attach.AttachmentStore
import com.kiq.aicp.data.db.AicpDatabase
import com.kiq.aicp.data.db.entity.StickerEntity
import com.kiq.aicp.data.db.entity.StickerPackEntity
import com.kiq.aicp.domain.sticker.StickerEmotion
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

	/**
	 * 注入 system prompt 的情绪清单，热度降序并限量。
	 *
	 * 模型现在看的是这份情绪清单而不是几十张图各自的标记：
	 * 它只需要决定"这句话该配开心还是无语"，具体哪张图由 pickForEmotion 挑。
	 * 附带的好处是清单短了很多——二十张开心表情在提示词里只占一个词。
	 *
	 * 热度按两条来源累加：情绪分组用整组的发送次数，散图用自己那张的次数。
	 * 平票时按 StickerEmotion.ALL 的次序兜底，不然清单顺序会随查询计划漂，
	 * 同一份库两次拼出的提示词都不一样。
	 */
	suspend fun promptEmotions(limit: Int = StickerParser.PROMPT_LIMIT): List<String> {
		if (limit <= 0) return emptyList()

		val heat = mutableMapOf<String, Int>()

		// 来源一：组名本身就是情绪，整组共用它，不需要识图
		dao.packHeats().forEach { row ->
			val emotion = StickerEmotion.emotionOf(row.name) ?: return@forEach
			heat[emotion] = (heat[emotion] ?: 0) + row.heat
		}

		// 来源二：组名不是情绪的组里，每张图识图识出来的情绪。
		// 组名是情绪的组即使图上带着 emotion 也跳过——那一组已经在来源一里算过了，
		// 再加一次会让它凭同一批图占两份热度
		dao.emotionHeats().forEach { row ->
			if (StickerEmotion.emotionOf(row.packName) != null) return@forEach
			if (!StickerEmotion.isEmotion(row.emotion)) return@forEach
			heat[row.emotion] = (heat[row.emotion] ?: 0) + row.heat
		}

		return heat.entries
			.sortedWith(
				compareByDescending<Map.Entry<String, Int>> { it.value }
					.thenBy { StickerEmotion.ALL.indexOf(it.key) },
			)
			.take(limit)
			.map { it.key }
	}

	/**
	 * 挑一张这个情绪的图。两条来源取并集后随机：
	 * 同一个情绪连着出现两次时，发两张不同的图才像真人在用表情包。
	 *
	 * distinct 不能省：一张图可能既躺在名为"开心"的组里、又被识图标了开心，
	 * 不去重它被抽中的概率就是别人的两倍。
	 */
	suspend fun pickForEmotion(emotion: String): String? {
		val clean = emotion.trim()
		if (!StickerEmotion.isEmotion(clean)) return null

		val emotionPacks = dao.allPacks()
			.filter { StickerEmotion.emotionOf(it.name) == clean }
			.map { it.name }

		val fromPacks = if (emotionPacks.isEmpty()) emptyList() else dao.labelsInPacks(emotionPacks)
		return (fromPacks + dao.labelsWithEmotion(clean)).distinct().randomOrNull()
	}

	/**
	 * 组内还没识别出情绪的图。
	 *
	 * 组名本身是情绪时返回空：整组共用组名当情绪，一张张去识图是白花钱，
	 * 管理页也不该在这种组上显示"N 张待识别"催用户去点。
	 */
	suspend fun unclassifiedIn(packId: Long): List<StickerEntity> {
		val pack = dao.packById(packId) ?: return emptyList()
		if (StickerEmotion.emotionOf(pack.name) != null) return emptyList()
		return dao.unclassifiedInPack(packId)
	}

	/** 待识别张数。理由同 unclassifiedIn，情绪分组恒为 0 */
	suspend fun unclassifiedCount(packId: Long): Int {
		val pack = dao.packById(packId) ?: return 0
		if (StickerEmotion.emotionOf(pack.name) != null) return 0
		return dao.countUnclassifiedInPack(packId)
	}

	/**
	 * 全库还需要识别的图。后台识图任务的输入。
	 *
	 * 先把"组名是情绪"的分组挑出来再整批排除，而不是逐组查一遍：
	 * 分组数量是十几个的量级，图是几百张的量级，一次查全部未识别的图再过滤最省事。
	 */
	suspend fun allUnclassified(): List<StickerEntity> {
		val emotionPackIds = dao.allPacks()
			.filter { StickerEmotion.emotionOf(it.name) != null }
			.map { it.id }
			.toSet()

		val pending = dao.unclassifiedAll()
		return if (emotionPackIds.isEmpty()) pending else pending.filterNot { it.packId in emotionPackIds }
	}

	/**
	 * 写回识图结果。词表外的值直接忽略而不是抛异常：
	 * 这里收的是模型输出，它偶尔会回"略带忧郁的欣喜"这种东西，
	 * 让批量识图因为一张图的胡言乱语整批中断不值得——那张保持未识别，下次重试就是。
	 *
	 * 空串同样被忽略，清除要走 clearEmotion，理由写在那边。
	 */
	suspend fun setEmotion(stickerId: Long, emotion: String) {
		val clean = emotion.trim()
		if (!StickerEmotion.isEmotion(clean)) return
		dao.updateEmotion(stickerId, clean)
	}

	/**
	 * 清掉识别结果，这张图重新变成待识别，下次后台识图会再捡起它。
	 *
	 * 单开一个方法而不是让 setEmotion 接受空串：setEmotion 的入参来自模型输出，
	 * "词表外一律忽略"这条不变式一旦为清除开了口子，
	 * 哪天有人写成 setEmotion(id, parseReply(raw).orEmpty())，
	 * 模型认不出的那张图就会被静默清空——而"被清空"和"从没识别过"在库里长得一模一样，
	 * 事后根本查不出来是谁擦的。清除是用户的明确动作，那就给它一个明确的名字。
	 */
	suspend fun clearEmotion(stickerId: Long) {
		dao.updateEmotion(stickerId, "")
	}

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
