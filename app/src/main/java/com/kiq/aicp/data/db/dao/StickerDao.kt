// app/src/main/java/com/kiq/aicp/data/db/dao/StickerDao.kt
// 表情包读写（v3 新增）。
//
// 跟 AttachmentDao 一个路子：删行不删文件，所以留了 collectPaths*，
// 调用方先捞路径再删行，最后删磁盘文件。顺序反了就是孤儿文件。
//
// labelExists / nextLabelLike 两个查询是给"导入撞名自动加后缀"用的。
// 不在 Kotlin 侧把全表 label 拉出来比对：表情几百个是常态，
// 每次导入都全量拉一遍纯属浪费。
//
// 情绪相关的查询刻意分成两条（packHeats / emotionHeats、labelsInPacks / labelsWithEmotion）：
// "分组名本身就是情绪"和"组内每张图各自识别出情绪"是两条独立来源，
// 而"这个组名算不算情绪"是 StickerEmotion 里的 Kotlin 规则（精确 + 包含匹配），
// SQL 表达不了。硬凑成一条查询只会得到一段没人看得懂的 SQL，
// 所以两条分别取、在 StickerRepository 里合并。

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

/** 分组的使用热度：组内累计发出次数与图数。只有有图的组才会出现 */
data class StickerPackHeat(
	val name: String,
	val heat: Int,
	val count: Int,
)

/**
 * 已识别出情绪的图按「所在分组 + 情绪」聚合的热度。
 * 带上组名是因为组名本身是情绪时整组共用组名，那些组里的 emotion 列不该再单独算一条来源。
 */
data class StickerEmotionHeat(
	val packName: String,
	val emotion: String,
	val heat: Int,
	val count: Int,
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

	/**
	 * 每个有图分组的热度，常用的排前面。
	 * 组名是不是情绪由调用方判断——空分组不出现在结果里，选了也发不出东西。
	 */
	@Query(
		"SELECT p.name AS name, SUM(s.useCount) AS heat, COUNT(s.id) AS count " +
			"FROM sticker_packs p JOIN stickers s ON s.packId = p.id " +
			"GROUP BY p.id ORDER BY heat DESC, p.sortOrder ASC",
	)
	suspend fun packHeats(): List<StickerPackHeat>

	/** 识图识出情绪的那些图，按「分组 + 情绪」聚合。没识别的（emotion 为空串）不算 */
	@Query(
		"SELECT p.name AS packName, s.emotion AS emotion, SUM(s.useCount) AS heat, " +
			"COUNT(s.id) AS count FROM stickers s JOIN sticker_packs p ON s.packId = p.id " +
			"WHERE s.emotion <> '' GROUP BY p.id, s.emotion ORDER BY heat DESC",
	)
	suspend fun emotionHeats(): List<StickerEmotionHeat>

	/**
	 * 一批分组里的全部标记，用来随机挑一张。
	 * 收一个名字列表而不是单个名字：同一个情绪可能对应好几个组（"开心""开心的图"都算开心），
	 * 逐组查就变成 N 次往返了。
	 */
	@Query(
		"SELECT s.label FROM stickers s JOIN sticker_packs p ON s.packId = p.id " +
			"WHERE p.name IN (:names)",
	)
	suspend fun labelsInPacks(names: List<String>): List<String>

	/** 识图识出这个情绪的图。跟 labelsInPacks 那条路取并集，见 StickerRepository.pickForEmotion */
	@Query("SELECT label FROM stickers WHERE emotion = :emotion")
	suspend fun labelsWithEmotion(emotion: String): List<String>

	/** 组内还没识别出情绪的图。识图任务的输入就是它 */
	@Query("SELECT * FROM stickers WHERE packId = :packId AND emotion = '' ORDER BY createdAt ASC")
	suspend fun unclassifiedInPack(packId: Long): List<StickerEntity>

	/**
	 * 全库还没识别的图，不分组。后台识图一次跑全量走这条。
	 * 组名是情绪的那些组要在 Kotlin 侧剔掉，见 StickerRepository.allUnclassified。
	 */
	@Query("SELECT * FROM stickers WHERE emotion = '' ORDER BY packId ASC, createdAt ASC")
	suspend fun unclassifiedAll(): List<StickerEntity>

	@Query("SELECT COUNT(*) FROM stickers WHERE packId = :packId AND emotion = ''")
	suspend fun countUnclassifiedInPack(packId: Long): Int

	/**
	 * 单独一条 UPDATE 而不是读出实体改完再 updateSticker：
	 * 识图是逐张写回的，整行覆盖会把同一时间用户在管理页改的 label、分组一起冲掉。
	 */
	@Query("UPDATE stickers SET emotion = :emotion WHERE id = :id")
	suspend fun updateEmotion(id: Long, emotion: String)

	/** 组内最大序号，导入时用来给自动标记接着编号 */
	@Query("SELECT COUNT(*) FROM stickers WHERE packId = :packId")
	suspend fun countInPack(packId: Long): Int

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
