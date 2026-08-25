// app/src/main/java/com/kiq/aicp/data/db/dao/StickerDao.kt
// 表情包读写（v3 新增）。
//
// 跟 AttachmentDao 一个路子：删行不删文件，所以留了 collectPaths*，
// 调用方先捞路径再删行，最后删磁盘文件。顺序反了就是孤儿文件。
//
// labelExists / nextLabelLike 两个查询是给"导入撞名自动加后缀"用的。
// 不在 Kotlin 侧把全表 label 拉出来比对：表情几百个是常态，
// 每次导入都全量拉一遍纯属浪费。

package com.kiq.aicp.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.kiq.aicp.data.db.entity.StickerEntity
import com.kiq.aicp.data.db.entity.StickerPackEntity
import kotlinx.coroutines.flow.Flow

/** 分组带上它的表情，给管理界面一次查完 */
data class StickerPackWithItems(
	val pack: StickerPackEntity,
	val stickers: List<StickerEntity>,
)

@Dao
interface StickerDao {

	// ---------------- 分组 ----------------

	@Insert
	suspend fun insertPack(pack: StickerPackEntity): Long

	@Update
	suspend fun updatePack(pack: StickerPackEntity)

	@Query("SELECT * FROM sticker_packs ORDER BY sortOrder ASC, createdAt ASC")
	fun observePacks(): Flow<List<StickerPackEntity>>

	@Query("SELECT * FROM sticker_packs ORDER BY sortOrder ASC, createdAt ASC")
	suspend fun allPacks(): List<StickerPackEntity>

	@Query("SELECT * FROM sticker_packs WHERE id = :id")
	suspend fun packById(id: Long): StickerPackEntity?

	@Query("SELECT * FROM sticker_packs WHERE name = :name")
	suspend fun packByName(name: String): StickerPackEntity?

	@Query("DELETE FROM sticker_packs WHERE id = :id")
	suspend fun deletePackById(id: Long)

	@Query("SELECT COUNT(*) FROM sticker_packs")
	suspend fun packCount(): Int

	// ---------------- 表情 ----------------

	@Insert
	suspend fun insertSticker(sticker: StickerEntity): Long

	@Update
	suspend fun updateSticker(sticker: StickerEntity)

	@Query("SELECT * FROM stickers ORDER BY packId ASC, createdAt ASC")
	fun observeAll(): Flow<List<StickerEntity>>

	@Query("SELECT * FROM stickers ORDER BY packId ASC, createdAt ASC")
	suspend fun allStickers(): List<StickerEntity>

	@Query("SELECT * FROM stickers WHERE packId = :packId ORDER BY createdAt ASC")
	suspend fun byPack(packId: Long): List<StickerEntity>

	@Query("SELECT * FROM stickers WHERE id = :id")
	suspend fun byId(id: Long): StickerEntity?

	@Query("SELECT * FROM stickers WHERE label = :label")
	suspend fun byLabel(label: String): StickerEntity?

	/**
	 * 注入 system prompt 用：常用的排前面，同频次按新导入的优先。
	 * 限量是必须的 —— 几百个表情全塞进 system prompt，光标记清单就能吃掉上千 token。
	 */
	@Query("SELECT * FROM stickers ORDER BY useCount DESC, createdAt DESC LIMIT :limit")
	suspend fun topUsed(limit: Int): List<StickerEntity>

	@Query("UPDATE stickers SET useCount = useCount + 1 WHERE label IN (:labels)")
	suspend fun bumpUseCount(labels: List<String>)

	@Query("SELECT localPath FROM stickers WHERE packId = :packId")
	suspend fun collectPathsOfPack(packId: Long): List<String>

	@Query("SELECT localPath FROM stickers WHERE id = :id")
	suspend fun collectPathOfSticker(id: Long): String?

	@Query("SELECT localPath FROM stickers")
	suspend fun collectAllPaths(): List<String>

	@Query("DELETE FROM stickers WHERE id = :id")
	suspend fun deleteStickerById(id: Long)

	@Query("SELECT COUNT(*) FROM stickers WHERE label = :label")
	suspend fun countByLabel(label: String): Int

	@Query("SELECT COUNT(*) FROM stickers")
	suspend fun stickerCount(): Int

	/** 管理界面用：分组 + 组内表情一次查完，避免 UI 层 N+1 */
	@Transaction
	suspend fun packsWithItems(): List<StickerPackWithItems> =
		allPacks().map { pack -> StickerPackWithItems(pack, byPack(pack.id)) }
}
