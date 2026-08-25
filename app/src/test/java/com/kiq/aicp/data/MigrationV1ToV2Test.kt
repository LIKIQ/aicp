// app/src/test/java/com/kiq/aicp/data/MigrationV1ToV2Test.kt
// v1 → v2 迁移测试。
//
// 没用 Room 的 MigrationTestHelper：它要把 schema 目录挂进 assets，配置绕，
// 而且在 Robolectric（非 instrumentation）环境下 API 形态还不一样。
// 这里直接用 Room 自己导出的 schemas/1.json 里的 createSql 建出一个真的 v1 库 ——
// 建表语句由 Room 生成，比我手抄准；然后让 Room 打开它触发迁移。
//
// 能正常查询本身就说明 Room 的 schema 校验通过了：
// 迁移后表结构和实体不一致时，Room 会在打开阶段直接抛 IllegalStateException。

package com.kiq.aicp.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.kiq.aicp.data.db.AicpDatabase
import com.kiq.aicp.data.db.AicpMigrations
import com.kiq.aicp.data.db.entity.MessageAttachmentEntity
import com.kiq.aicp.domain.model.AttachmentKind
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MigrationV1ToV2Test {

	private companion object {
		const val DB_NAME = "migration_v1_v2_test.db"
		const val SCHEMA_DIR = "schemas/com.kiq.aicp.data.db.AicpDatabase"
	}

	private lateinit var context: Context

	@Before
	fun setUp() {
		context = ApplicationProvider.getApplicationContext()
		deleteDbFiles()
	}

	@After
	fun tearDown() {
		deleteDbFiles()
	}

	private fun deleteDbFiles() {
		val base = context.getDatabasePath(DB_NAME)
		listOf(base, File("${base.path}-wal"), File("${base.path}-shm")).forEach { it.delete() }
	}

	/** 测试 JVM 的工作目录可能是 app/ 也可能是仓库根，两种都试 */
	private fun readSchema(version: Int): String {
		val relative = "$SCHEMA_DIR/$version.json"
		val candidates = listOf(File(relative), File("app/$relative"))
		return candidates.firstOrNull { it.exists() }?.readText()
			?: error("找不到 schema 文件 $relative，先跑一次 kspDebugKotlin 让 Room 导出")
	}

	/** 按 1.json 里的 createSql 建出真正的 v1 库，并塞几行旧数据 */
	private fun createV1DatabaseWithData() {
		val schema = JSONObject(readSchema(1)).getJSONObject("database")
		val entities = schema.getJSONArray("entities")

		val db = SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(DB_NAME), null)
		try {
			for (i in 0 until entities.length()) {
				val entity = entities.getJSONObject(i)
				val table = entity.getString("tableName")
				db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))

				val indices = entity.optJSONArray("indices") ?: continue
				for (j in 0 until indices.length()) {
					db.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
				}
			}

			db.execSQL(
				"INSERT INTO personas (id, name, avatarEmoji, tagline, systemPrompt, greeting, " +
					"temperature, topP, maxTokens, modelOverride, isBuiltIn, sortOrder, " +
					"generatedFromPrompt, createdAt, updatedAt) " +
					"VALUES (1, '旧性格', '🌸', '升级前就存在', '你是旧性格', '你好', " +
					"0.8, 0.9, 1024, NULL, 1, 0, NULL, 100, 100)",
			)
			db.execSQL(
				"INSERT INTO conversations (id, title, mode, pinned, archived, createdAt, updatedAt, " +
					"lastMessageAt, lastMessagePreview, compressedUntilMessageId, pendingTokens, " +
					"lastCompressAttemptAt, compressFailureCount) " +
					"VALUES (1, '旧会话', 'SINGLE', 0, 0, 100, 100, 100, '旧预览', 0, 12, 0, 0)",
			)
			db.execSQL(
				"INSERT INTO conversation_personas (conversationId, personaId, speakWeight, muted, joinedAt) " +
					"VALUES (1, 1, 1.0, 0, 100)",
			)
			db.execSQL(
				"INSERT INTO messages (id, conversationId, role, personaId, content, tokenEstimate, " +
					"status, errorMessage, createdAt, compressed) " +
					"VALUES (1, 1, 'USER', NULL, '升级前说过的话', 12, 'OK', NULL, 100, 0)",
			)
			db.execSQL(
				"INSERT INTO memory_cards (id, conversationId, personaId, scopeKey, type, keyword, " +
					"content, importance, hitCount, lastHitAt, pinned, createdAt, updatedAt) " +
					"VALUES (1, NULL, NULL, 'c:-|p:-', 'RELATION', '称呼', '叫他 KIQ', 5, 3, 100, 1, 100, 100)",
			)
			db.execSQL(
				"INSERT INTO memory_summaries (id, conversationId, level, content, fromMessageId, " +
					"toMessageId, messageCount, tokenEstimate, superseded, needsSemanticRedo, createdAt) " +
					"VALUES (1, 1, 1, '升级前的摘要', 0, 1, 1, 8, 0, 0, 100)",
			)

			db.version = 1
		} finally {
			db.close()
		}
	}

	private fun openV2(): AicpDatabase =
		Room.databaseBuilder(context, AicpDatabase::class.java, DB_NAME)
			.addMigrations(*AicpMigrations.ALL)
			.allowMainThreadQueries()
			.build()

	@Test
	fun `升级到 v2 之后旧数据一条都不能少`() = runTest {
		createV1DatabaseWithData()
		val db = openV2()
		try {
			val personas = db.personaDao().observeAll().first()
			assertEquals(1, personas.size)
			assertEquals("旧性格", personas.single().name)
			assertTrue(personas.single().isBuiltIn)

			val messages = db.messageDao().observeByConversation(1).first()
			assertEquals(1, messages.size)
			assertEquals("升级前说过的话", messages.single().content)

			val cards = db.memoryDao().observeAllCards().first()
			assertEquals(1, cards.size)
			assertEquals("叫他 KIQ", cards.single().content)
			assertEquals(3, cards.single().hitCount)

			assertEquals(1, db.memoryDao().countActiveSummaries(1, level = 1))
			assertEquals(12, db.conversationDao().getById(1)!!.pendingTokens)
		} finally {
			db.close()
		}
	}

	@Test
	fun `新增的列拿到迁移 SQL 里写的默认值`() = runTest {
		createV1DatabaseWithData()
		val db = openV2()
		try {
			assertFalse(db.personaDao().getById(1)!!.proactiveEnabled)

			val ref = db.conversationDao().getParticipants(1).single()
			assertEquals(0, ref.mood)
			assertEquals(0L, ref.moodUpdatedAt)
			// 老列的值不能被 ALTER 冲掉
			assertEquals(1f, ref.speakWeight, 0.001f)
			assertFalse(ref.muted)
		} finally {
			db.close()
		}
	}

	@Test
	fun `迁移出来的附件表能用，且跟着消息级联删除`() = runTest {
		createV1DatabaseWithData()
		val db = openV2()
		try {
			val attachmentId = db.attachmentDao().insert(
				MessageAttachmentEntity(
					messageId = 1,
					kind = AttachmentKind.IMAGE,
					localPath = "attachments/test.jpg",
					mimeType = "image/jpeg",
					fileName = "test.jpg",
					byteSize = 2048,
					width = 800,
					height = 600,
					createdAt = 200,
				),
			)
			assertTrue(attachmentId > 0)
			assertEquals(1, db.attachmentDao().byMessage(1).size)

			db.messageDao().deleteById(1)

			assertEquals(0, db.attachmentDao().count())
		} finally {
			db.close()
		}
	}

	@Test
	fun `v1 库升级后还能继续正常写入新数据`() = runTest {
		createV1DatabaseWithData()
		val db = openV2()
		try {
			val repo = com.kiq.aicp.data.repo.ChatRepository(db) { 300L }
			val newId = repo.appendUser(1, "升级之后说的话")

			assertTrue(newId > 1)
			assertEquals(2, db.messageDao().observeByConversation(1).first().size)
			assertEquals("升级之后说的话", db.conversationDao().getById(1)!!.lastMessagePreview)
		} finally {
			db.close()
		}
	}
}
