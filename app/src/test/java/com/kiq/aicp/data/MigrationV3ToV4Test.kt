// app/src/test/java/com/kiq/aicp/data/MigrationV3ToV4Test.kt
// v3 → v4 迁移测试（新增 proactive_logs）。
//
// 路子跟前两个迁移测试一致：用 Room 导出的 3.json 建真 v3 库，塞数据，
// 再让 Room 打开触发迁移。能查出来就说明 schema 校验过了。
//
// 这次额外验一件事：proactive_logs 的主键是 personaId，同一性格重复写入应该是覆盖
// 而不是插出两行 —— 记账表要是能写重，"今天搭话几次"就永远算不对。

package com.kiq.aicp.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.kiq.aicp.data.db.AicpDatabase
import com.kiq.aicp.data.db.AicpMigrations
import com.kiq.aicp.data.db.entity.ProactiveLogEntity
import com.kiq.aicp.data.db.entity.StickerEntity
import com.kiq.aicp.data.db.entity.StickerPackEntity
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MigrationV3ToV4Test {

	private companion object {
		const val DB_NAME = "migration_v3_v4_test.db"
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

	private fun readSchema(version: Int): String {
		val relative = "$SCHEMA_DIR/$version.json"
		return listOf(File(relative), File("app/$relative")).firstOrNull { it.exists() }?.readText()
			?: error("找不到 schema 文件 $relative，先跑一次 kspDebugKotlin 让 Room 导出")
	}

	/** 按 3.json 建真 v3 库，塞上性格、会话、消息和表情包（v3 才有的表） */
	private fun createV3DatabaseWithData() {
		val schema = JSONObject(readSchema(3)).getJSONObject("database")
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
					"generatedFromPrompt, proactiveEnabled, createdAt, updatedAt) " +
					"VALUES (1, 'v3 性格', '🌸', '升级前就存在', '你是 v3 性格', '你好', " +
					"0.8, 0.9, 1024, NULL, 1, 0, NULL, 1, 100, 100)",
			)
			// 再来一个自定义性格：内置性格被 deleteCustomById 拦着删不掉，测级联要用这个
			db.execSQL(
				"INSERT INTO personas (id, name, avatarEmoji, tagline, systemPrompt, greeting, " +
					"temperature, topP, maxTokens, modelOverride, isBuiltIn, sortOrder, " +
					"generatedFromPrompt, proactiveEnabled, createdAt, updatedAt) " +
					"VALUES (2, '自定义性格', '🐱', '用户自己建的', '你是自定义性格', '嗨', " +
					"0.8, 0.9, 1024, NULL, 0, 1, NULL, 1, 100, 100)",
			)
			db.execSQL(
				"INSERT INTO conversations (id, title, mode, pinned, archived, createdAt, updatedAt, " +
					"lastMessageAt, lastMessagePreview, compressedUntilMessageId, pendingTokens, " +
					"lastCompressAttemptAt, compressFailureCount) " +
					"VALUES (1, 'v3 会话', 'SINGLE', 0, 0, 100, 100, 100, '旧预览', 0, 12, 0, 0)",
			)
			db.execSQL(
				"INSERT INTO conversation_personas (conversationId, personaId, speakWeight, muted, " +
					"joinedAt, mood, moodUpdatedAt) VALUES (1, 1, 1.0, 0, 100, 1, 500)",
			)
			db.execSQL(
				"INSERT INTO messages (id, conversationId, role, personaId, content, tokenEstimate, " +
					"status, errorMessage, createdAt, compressed) " +
					"VALUES (1, 1, 'USER', NULL, 'v3 时期说过的话', 12, 'OK', NULL, 100, 0)",
			)
			db.execSQL(
				"INSERT INTO sticker_packs (id, name, sortOrder, createdAt) VALUES (1, '旧表情组', 0, 100)",
			)
			db.execSQL(
				"INSERT INTO stickers (id, packId, label, localPath, mimeType, byteSize, width, height, " +
					"useCount, createdAt) " +
					"VALUES (1, 1, '开心', 'stickers/a.png', 'image/png', 512, 200, 200, 3, 100)",
			)

			db.version = 3
		} finally {
			db.close()
		}
	}

	private fun openV4(): AicpDatabase =
		Room.databaseBuilder(context, AicpDatabase::class.java, DB_NAME)
			.addMigrations(*AicpMigrations.ALL)
			.allowMainThreadQueries()
			.build()

	@Test
	fun `升级到 v4 之后 v3 的数据一条都不能少`() = runTest {
		createV3DatabaseWithData()
		val db = openV4()
		try {
			assertEquals(2, db.personaDao().observeAll().first().size)
			assertEquals("v3 会话", db.conversationDao().observeById(1).first()?.title)
			assertEquals("v3 时期说过的话", db.messageDao().observeByConversation(1).first().single().content)

			// v2 加的 mood 和 v3 加的表情包都要活着过来
			val ref = db.conversationDao().getParticipant(1, 1)
			assertEquals(1, ref?.mood)
			assertEquals(500L, ref?.moodUpdatedAt)

			val sticker = db.stickerDao().byLabel("开心")
			assertEquals("stickers/a.png", sticker?.localPath)
			assertEquals(3, sticker?.useCount)
		} finally {
			db.close()
		}
	}

	@Test
	fun `新表能读写，同一性格重复写入是覆盖不是新增`() = runTest {
		createV3DatabaseWithData()
		val db = openV4()
		try {
			val dao = db.proactiveLogDao()
			dao.upsert(ProactiveLogEntity(personaId = 1, date = "2026-08-24", count = 1))
			dao.upsert(ProactiveLogEntity(personaId = 1, date = "2026-08-24", count = 2))

			val log = dao.byPersona(1)
			assertEquals(2, log?.count)
			assertEquals("2026-08-24", log?.date)
		} finally {
			db.close()
		}
	}

	@Test
	fun `删性格后记账记录会残留，这是为了让全局配额能用而付的代价`() = runTest {
		createV3DatabaseWithData()
		val db = openV4()
		try {
			db.proactiveLogDao().upsert(ProactiveLogEntity(personaId = 2, date = "2026-08-24", count = 1))

			assertEquals(1, db.personaDao().deleteCustomById(2))

			// 没有外键，所以不级联。残留一行二十几字节，而 personas 是 AUTOINCREMENT 不复用 id，
			// 新性格不会误读到这条旧记录
			assertEquals(1, db.proactiveLogDao().byPersona(2)?.count)
		} finally {
			db.close()
		}
	}

	@Test
	fun `全局配额记录用 personaId 0，不挂外键才写得进去`() = runTest {
		createV3DatabaseWithData()
		val db = openV4()
		try {
			// personaId=0 在 personas 里不存在。挂了外键这里会抛 SQLiteConstraintException，
			// 全局配额就没地方记了 —— 所以这张表刻意不加外键
			db.proactiveLogDao().upsert(ProactiveLogEntity(personaId = 0, date = "2026-08-24", count = 1))

			assertEquals(1, db.proactiveLogDao().byPersona(0)?.count)
		} finally {
			db.close()
		}
	}
}
