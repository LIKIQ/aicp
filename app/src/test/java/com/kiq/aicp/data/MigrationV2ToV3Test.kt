// app/src/test/java/com/kiq/aicp/data/MigrationV2ToV3Test.kt
// v2 → v3 迁移测试（新增 sticker_packs / stickers 两张表）。
//
// 路子跟 MigrationV1ToV2Test 一样：拿 Room 自己导出的 schemas/2.json 里的 createSql
// 建一个真的 v2 库，塞上各表的数据，再让 Room 打开触发迁移。
// 手抄建表语句必然抄错，让 Room 自己生成的 SQL 来建才靠得住。
//
// 除了"旧数据不丢"，这里还专门验两个唯一索引真的建出来了 ——
// 迁移 SQL 里漏掉 CREATE UNIQUE INDEX 时，Room 的 schema 校验**不一定**会拦住，
// 但 [标记] 解析会因为 label 重复而指向随机一张图，属于事后极难查的问题。

package com.kiq.aicp.data

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.kiq.aicp.data.db.AicpDatabase
import com.kiq.aicp.data.db.AicpMigrations
import com.kiq.aicp.data.db.entity.StickerEntity
import com.kiq.aicp.data.db.entity.StickerPackEntity
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MigrationV2ToV3Test {

	private companion object {
		const val DB_NAME = "migration_v2_v3_test.db"
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

	/** 按 2.json 建真 v2 库并塞旧数据，含 v2 才有的附件表 */
	private fun createV2DatabaseWithData() {
		val schema = JSONObject(readSchema(2)).getJSONObject("database")
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
					"VALUES (1, 'v2 性格', '🌸', '升级前就存在', '你是 v2 性格', '你好', " +
					"0.8, 0.9, 1024, NULL, 1, 0, NULL, 0, 100, 100)",
			)
			db.execSQL(
				"INSERT INTO conversations (id, title, mode, pinned, archived, createdAt, updatedAt, " +
					"lastMessageAt, lastMessagePreview, compressedUntilMessageId, pendingTokens, " +
					"lastCompressAttemptAt, compressFailureCount) " +
					"VALUES (1, 'v2 会话', 'SINGLE', 0, 0, 100, 100, 100, '旧预览', 0, 12, 0, 0)",
			)
			db.execSQL(
				"INSERT INTO conversation_personas (conversationId, personaId, speakWeight, muted, " +
					"joinedAt, mood, moodUpdatedAt) VALUES (1, 1, 1.0, 0, 100, 0, 0)",
			)
			db.execSQL(
				"INSERT INTO messages (id, conversationId, role, personaId, content, tokenEstimate, " +
					"status, errorMessage, createdAt, compressed) " +
					"VALUES (1, 1, 'USER', NULL, 'v2 时期说过的话', 12, 'OK', NULL, 100, 0)",
			)
			db.execSQL(
				"INSERT INTO message_attachments (id, messageId, kind, localPath, mimeType, fileName, " +
					"byteSize, width, height, extractedText, truncated, textHeavy, createdAt) " +
					"VALUES (1, 1, 'IMAGE', 'attachments/old.jpg', 'image/jpeg', 'old.jpg', " +
					"2048, 800, 600, NULL, 0, 0, 100)",
			)
			db.execSQL(
				"INSERT INTO memory_cards (id, conversationId, personaId, scopeKey, type, keyword, " +
					"content, importance, hitCount, lastHitAt, pinned, createdAt, updatedAt) " +
					"VALUES (1, NULL, NULL, 'c:-|p:-', 'RELATION', '称呼', '叫他 KIQ', 5, 3, 100, 1, 100, 100)",
			)

			db.version = 2
		} finally {
			db.close()
		}
	}

	private fun openV3(): AicpDatabase =
		Room.databaseBuilder(context, AicpDatabase::class.java, DB_NAME)
			.addMigrations(*AicpMigrations.ALL)
			.allowMainThreadQueries()
			.build()

	@Test
	fun `升级到 v3 之后 v2 的数据一条都不能少`() = runTest {
		createV2DatabaseWithData()
		val db = openV3()
		try {
			assertEquals("v2 性格", db.personaDao().observeAll().first().single().name)
			assertEquals("v2 会话", db.conversationDao().observeById(1).first()?.title)
			assertEquals("v2 时期说过的话", db.messageDao().observeByConversation(1).first().single().content)

			val attachment = db.attachmentDao().byMessage(1).single()
			assertEquals("attachments/old.jpg", attachment.localPath)

			assertEquals("叫他 KIQ", db.memoryDao().observeAllCards().first().single().content)
		} finally {
			db.close()
		}
	}

	@Test
	fun `新表建出来了并且能正常读写`() = runTest {
		createV2DatabaseWithData()
		val db = openV3()
		try {
			val dao = db.stickerDao()
			val packId = dao.insertPack(StickerPackEntity(name = "熊猫头", createdAt = 200))
			val stickerId = dao.insertSticker(
				StickerEntity(
					packId = packId,
					label = "开心",
					localPath = "stickers/happy.png",
					mimeType = "image/png",
					byteSize = 1024,
					width = 240,
					height = 240,
					createdAt = 200,
				),
			)

			assertNotNull(dao.byId(stickerId))
			assertEquals("开心", dao.byLabel("开心")?.label)
			assertEquals(1, dao.byPack(packId).size)
			assertEquals(0, dao.byLabel("开心")!!.useCount)
		} finally {
			db.close()
		}
	}

	@Test
	fun `label 的唯一索引真的建出来了`() = runTest {
		createV2DatabaseWithData()
		val db = openV3()
		try {
			val dao = db.stickerDao()
			val packA = dao.insertPack(StickerPackEntity(name = "组A", createdAt = 200))
			val packB = dao.insertPack(StickerPackEntity(name = "组B", createdAt = 201))

			dao.insertSticker(sticker(packA, "开心", "stickers/a.png"))

			// 换个分组也不许重名：[开心] 是裸标记，重复了就没法定位到具体哪张图
			val error = runCatching { dao.insertSticker(sticker(packB, "开心", "stickers/b.png")) }
				.exceptionOrNull()

			assertTrue("期望唯一约束冲突，实际是 $error", error is SQLiteConstraintException)
		} finally {
			db.close()
		}
	}

	@Test
	fun `分组名的唯一索引也生效`() = runTest {
		createV2DatabaseWithData()
		val db = openV3()
		try {
			val dao = db.stickerDao()
			dao.insertPack(StickerPackEntity(name = "同名组", createdAt = 200))

			val error = runCatching { dao.insertPack(StickerPackEntity(name = "同名组", createdAt = 201)) }
				.exceptionOrNull()

			assertTrue("期望唯一约束冲突，实际是 $error", error is SQLiteConstraintException)
		} finally {
			db.close()
		}
	}

	@Test
	fun `删分组会级联删掉组内表情`() = runTest {
		createV2DatabaseWithData()
		val db = openV3()
		try {
			val dao = db.stickerDao()
			val packId = dao.insertPack(StickerPackEntity(name = "待删组", createdAt = 200))
			dao.insertSticker(sticker(packId, "标记1", "stickers/1.png"))
			dao.insertSticker(sticker(packId, "标记2", "stickers/2.png"))
			assertEquals(2, dao.stickerCount())

			dao.deletePackById(packId)

			assertEquals(0, dao.stickerCount())
			assertEquals(0, dao.packCount())
		} finally {
			db.close()
		}
	}

	@Test
	fun `useCount 累加只影响命中的 label`() = runTest {
		createV2DatabaseWithData()
		val db = openV3()
		try {
			val dao = db.stickerDao()
			val packId = dao.insertPack(StickerPackEntity(name = "统计组", createdAt = 200))
			dao.insertSticker(sticker(packId, "常用", "stickers/1.png"))
			dao.insertSticker(sticker(packId, "没用过", "stickers/2.png"))

			dao.bumpUseCount(listOf("常用"))
			dao.bumpUseCount(listOf("常用"))

			assertEquals(2, dao.byLabel("常用")!!.useCount)
			assertEquals(0, dao.byLabel("没用过")!!.useCount)

			// topUsed 要按 useCount 降序，注入 prompt 时常用的排前面
			assertEquals("常用", dao.topUsed(1).single().label)
		} finally {
			db.close()
		}
	}

	private fun sticker(packId: Long, label: String, path: String) = StickerEntity(
		packId = packId,
		label = label,
		localPath = path,
		mimeType = "image/png",
		byteSize = 1024,
		width = 240,
		height = 240,
		createdAt = 200,
	)
}
