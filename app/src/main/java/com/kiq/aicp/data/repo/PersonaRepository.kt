// app/src/main/java/com/kiq/aicp/data/repo/PersonaRepository.kt
// 性格的增删改查 + 首次启动灌内置预设。
//
// clock 注入的是时间源，单测里换成固定值就能断言 createdAt/updatedAt，
// 不然只能写"大于 0"这种没意义的断言。
//
// attachmentStore 只用来收拾头像图片文件：换头像和删性格时旧图要跟着走，
// 不然每换一次就在 avatars/ 里留一张永远没人引用的图。

package com.kiq.aicp.data.repo

import com.kiq.aicp.data.attach.AttachmentStore
import com.kiq.aicp.data.db.DefaultPersonas
import com.kiq.aicp.data.db.dao.PersonaDao
import com.kiq.aicp.data.db.entity.PersonaEntity
import kotlinx.coroutines.flow.Flow

class PersonaRepository(
	private val dao: PersonaDao,
	/** 清理头像文件用；单测里可以不传 */
	private val attachmentStore: AttachmentStore? = null,
	private val clock: () -> Long = System::currentTimeMillis,
) {

	fun observeAll(): Flow<List<PersonaEntity>> = dao.observeAll()

	fun observeById(id: Long): Flow<PersonaEntity?> = dao.observeById(id)

	suspend fun getById(id: Long): PersonaEntity? = dao.getById(id)

	suspend fun getByIds(ids: List<Long>): List<PersonaEntity> =
		if (ids.isEmpty()) emptyList() else dao.getByIds(ids)

	/**
	 * 空库才灌种子。判定条件是"整表为空"而不是"内置性格为空"——
	 * 用户要是把内置的删了（目前不允许）或者改名了，不该在下次启动时又冒出来一份。
	 * 返回是否真的灌了。
	 */
	suspend fun ensureSeeded(): Boolean {
		if (dao.count() > 0) return false
		dao.insertAll(DefaultPersonas.all(clock()))
		return true
	}

	suspend fun create(
		name: String,
		avatarEmoji: String,
		tagline: String,
		systemPrompt: String,
		greeting: String,
		temperature: Float,
		topP: Float,
		maxTokens: Int,
		modelOverride: String? = null,
		generatedFromPrompt: String? = null,
	): Long {
		val now = clock()
		return dao.insert(
			PersonaEntity(
				name = name.trim().ifEmpty { "未命名性格" },
				avatarEmoji = avatarEmoji.ifEmpty { "🙂" },
				tagline = tagline.trim(),
				systemPrompt = systemPrompt.trim(),
				greeting = greeting.trim(),
				temperature = temperature.coerceIn(0f, 2f),
				topP = topP.coerceIn(0f, 1f),
				maxTokens = maxTokens.coerceIn(64, 32_768),
				modelOverride = modelOverride?.trim()?.takeIf { it.isNotEmpty() },
				isBuiltIn = false,
				sortOrder = (dao.maxSortOrder() ?: 0) + 1,
				generatedFromPrompt = generatedFromPrompt,
				createdAt = now,
				updatedAt = now,
			),
		)
	}

	/** 内置性格也允许改内容，只是不允许删 */
	suspend fun update(persona: PersonaEntity) {
		val old = dao.getById(persona.id)
		dao.update(
			persona.copy(
				name = persona.name.trim().ifEmpty { "未命名性格" },
				temperature = persona.temperature.coerceIn(0f, 2f),
				topP = persona.topP.coerceIn(0f, 1f),
				maxTokens = persona.maxTokens.coerceIn(64, 32_768),
				updatedAt = clock(),
			),
		)

		// 换了头像图就把旧的删掉。判等而不是判非空：清空头像（新值为 null）也算换
		val oldPath = old?.avatarPath
		if (oldPath != null && oldPath != persona.avatarPath) {
			attachmentStore?.delete(listOf(oldPath))
		}
	}

	/** 返回 false 表示这是内置性格，被 SQL 里的 isBuiltIn = 0 拦住了 */
	suspend fun delete(id: Long): Boolean {
		val path = dao.getById(id)?.avatarPath
		val deleted = dao.deleteCustomById(id) > 0
		// 只有真删掉了才清文件：内置性格被拦下来时头像还在用
		if (deleted && path != null) attachmentStore?.delete(listOf(path))
		return deleted
	}

	/** 孤儿清理用：库里还在引用的头像路径 */
	suspend fun collectAvatarPaths(): List<String> = dao.collectAvatarPaths()

	suspend fun reorder(orderedIds: List<Long>) {
		val now = clock()
		orderedIds.forEachIndexed { index, id ->
			dao.updateSortOrder(id = id, sortOrder = index, updatedAt = now)
		}
	}
}
