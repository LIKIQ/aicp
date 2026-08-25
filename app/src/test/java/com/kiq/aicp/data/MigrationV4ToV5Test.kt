// app/src/test/java/com/kiq/aicp/data/MigrationV4ToV5Test.kt
// v4 → v5 迁移测试（personas 加 avatarPath/note，conversations 加 avatarEmoji/avatarPath）。
//
// 这次是 ALTER TABLE ADD COLUMN 而不是建新表，最容易错的地方换成了 DEFAULT 值：
// 迁移 SQL 里的 DEFAULT 必须跟实体上 @ColumnInfo(defaultValue=...) 逐字对齐，
// 差一个引号 Room 打开库时就抛 schema mismatch，表现是"升级后一启动就崩"。
// 所以除了"旧数据不丢"，这里专门验一遍老行读出来的新列到底是什么值。

package com.kiq.aicp.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.kiq.aicp.data.db.AicpDatabase
import com.kiq.aicp.data.db.AicpMigrations
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MigrationV4ToV5Test {

	private companion object {
		const val DB_NAME = "migration_v4_v5_test.db"
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

	private fun createV4DatabaseWithData() {
		val schema = JSONObject(readSchema(4)).getJSONObject("database")
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
					"VALUES (1, 'v4 性格', '🌸', '升级前就存在', '你是 v4 性格', '你好', " +
					"0.8, 0.9, 1024, NULL, 1, 0, NULL, 1, 100, 100)",
			)
			db.execSQL(
				"INSERT INTO conversations (id, title, mode, pinned, archived, createdAt, updatedAt, " +
					"lastMessageAt, lastMessagePreview, compressedUntilMessageId, pendingTokens, " +
					"lastCompressAttemptAt, compressFailureCount) " +
					"VALUES (1, 'v4 会话', 'GROUP', 1, 0, 100, 100, 100, '旧预览', 0, 12, 0, 0)",
			)
			db.execSQL(
				"INSERT INTO conversation_personas (conversationId, personaId, speakWeight, muted, " +
					"joinedAt, mood, moodUpdatedAt) VALUES (1, 1, 1.0, 0, 100, 2, 700)",
			)
			db.execSQL(
				"INSERT INTO messages (id, conversationId, role, personaId, content, tokenEstimate, " +
					"status, errorMessage, createdAt, compressed) " +
					"VALUES (1, 1, 'USER', NULL, 'v4 时期说过的话', 12, 'OK', NULL, 100, 0)",
			)
			db.execSQL(
				"INSERT INTO proactive_logs (personaId, date, count) VALUES (0, '2026-08-24', 2)",
			)

			db.version = 4
		} finally {
			db.close()
		}
	}

	private fun openV5(): AicpDatabase =
		Room.databaseBuilder(context, AicpDatabase::class.java, DB_NAME)
			.addMigrations(*AicpMigrations.ALL)
			.allowMainThreadQueries()
			.build()

	@Test
	fun `升级到 v5 之后 v4 的数据一条都不能少`() = runTest {
		createV4DatabaseWithData()
		val db = openV5()
		try {
			val persona = db.personaDao().observeAll().first().single()
			assertEquals("v4 性格", persona.name)
			assertEquals("🌸", persona.avatarEmoji)
			assertTrue("主动搭话开关要活着过来", persona.proactiveEnabled)

			val conv = db.conversationDao().observeById(1).first()
			assertEquals("v4 会话", conv?.title)
			assertTrue("置顶状态要保住", conv?.pinned == true)

			assertEquals("v4 时期说过的话", db.messageDao().observeByConversation(1).first().single().content)

			// v2 的 mood 和 v4 的记账表都得完好
			assertEquals(2, db.conversationDao().getParticipant(1, 1)?.mood)
			assertEquals(2, db.proactiveLogDao().byPersona(0)?.count)
		} finally {
			db.close()
		}
	}

	@Test
	fun `老行读出来的新列是预期的默认值`() = runTest {
		createV4DatabaseWithData()
		val db = openV5()
		try {
			val persona = db.personaDao().observeAll().first().single()
			assertNull("没配过图片头像就该是 null", persona.avatarPath)
			assertEquals("备注默认空串而不是 null", "", persona.note)

			val conv = db.conversationDao().observeById(1).first()!!
			assertEquals("群头像 emoji 默认空串", "", conv.avatarEmoji)
			assertNull("群头像图片默认 null", conv.avatarPath)
		} finally {
			db.close()
		}
	}

	@Test
	fun `新列写进去读出来一致`() = runTest {
		createV4DatabaseWithData()
		val db = openV5()
		try {
			val persona = db.personaDao().observeAll().first().single()
			db.personaDao().update(
				persona.copy(avatarPath = "avatars/p1.png", note = "这个性格用来练英语"),
			)

			val updated = db.personaDao().getById(1)!!
			assertEquals("avatars/p1.png", updated.avatarPath)
			assertEquals("这个性格用来练英语", updated.note)

			val conv = db.conversationDao().observeById(1).first()!!
			db.conversationDao().update(
				conv.copy(avatarEmoji = "🐼", avatarPath = "avatars/g1.png"),
			)

			val updatedConv = db.conversationDao().getById(1)!!
			assertEquals("🐼", updatedConv.avatarEmoji)
			assertEquals("avatars/g1.png", updatedConv.avatarPath)
		} finally {
			db.close()
		}
	}

	@Test
	fun `头像路径查询只返回配过图的那些`() = runTest {
		createV4DatabaseWithData()
		val db = openV5()
		try {
			assertTrue("初始没有任何图片头像", db.personaDao().collectAvatarPaths().isEmpty())
			assertTrue(db.conversationDao().collectAvatarPaths().isEmpty())

			val persona = db.personaDao().observeAll().first().single()
			db.personaDao().update(persona.copy(avatarPath = "avatars/p1.png"))

			assertEquals(listOf("avatars/p1.png"), db.personaDao().collectAvatarPaths())
		} finally {
			db.close()
		}
	}
}
