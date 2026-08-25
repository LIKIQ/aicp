// app/src/main/java/com/kiq/aicp/data/db/dao/PersonaDao.kt
// 性格表读写。
// 删除只开放给自建性格（SQL 里带 isBuiltIn = 0）—— 内置预设删掉就找不回来了，
// 想让它消失就改 sortOrder 沉底或者自己改内容，不给删。

package com.kiq.aicp.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.kiq.aicp.data.db.entity.PersonaEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PersonaDao {

	@Query("SELECT * FROM personas ORDER BY sortOrder ASC, id ASC")
	fun observeAll(): Flow<List<PersonaEntity>>

	@Query("SELECT * FROM personas WHERE id = :id")
	fun observeById(id: Long): Flow<PersonaEntity?>

	@Query("SELECT * FROM personas WHERE id = :id")
	suspend fun getById(id: Long): PersonaEntity?

	@Query("SELECT * FROM personas WHERE id IN (:ids)")
	suspend fun getByIds(ids: List<Long>): List<PersonaEntity>

	@Query("SELECT COUNT(*) FROM personas")
	suspend fun count(): Int

	@Query("SELECT MAX(sortOrder) FROM personas")
	suspend fun maxSortOrder(): Int?

	@Insert
	suspend fun insert(persona: PersonaEntity): Long

	@Insert
	suspend fun insertAll(personas: List<PersonaEntity>): List<Long>

	@Update
	suspend fun update(persona: PersonaEntity)

	/** 返回实际删除行数：0 说明拦在了 isBuiltIn 上 */
	@Query("DELETE FROM personas WHERE id = :id AND isBuiltIn = 0")
	suspend fun deleteCustomById(id: Long): Int

	/** 孤儿文件清理用：所有还被引用着的头像图片路径 */
	@Query("SELECT avatarPath FROM personas WHERE avatarPath IS NOT NULL")
	suspend fun collectAvatarPaths(): List<String>

	@Query("UPDATE personas SET sortOrder = :sortOrder, updatedAt = :updatedAt WHERE id = :id")
	suspend fun updateSortOrder(id: Long, sortOrder: Int, updatedAt: Long)
}
