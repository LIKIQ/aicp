// app/src/test/java/com/kiq/aicp/data/MigrationV5ToV6Test.kt
// v5 → v6 迁移测试（记忆升级成 wiki 条目）。
//
// 这次迁移比前几次危险：它不只是建表，还要把 memory_cards 的数据搬进 memory_entries。
// 搬错了用户的记忆就废了，所以这里逐字段核对映射结果，
// 并且明确验证旧表**没被清空**——保留旧表是这次迁移的安全网，
// 哪天有人"顺手清理一下"把 DROP TABLE 加进迁移，这里会立刻红。

package com.kiq.aicp.data

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.kiq.aicp.data.db.AicpDatabase
import com.kiq.aicp.data.db.AicpMigrations
import com.kiq.aicp.data.db.entity.MemoryEntryEntity
import com.kiq.aicp.data.db.entity.MemoryLogEntity
import com.kiq.aicp.data.db.entity.MemoryLogKind
import com.kiq.aicp.domain.model.MemoryCardType
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
class MigrationV5ToV6Test {

	private companion object {
		const val DB_NAME = "migration_v5_v6_test.db"
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

	/** 按 5.json 建真 v5 库，塞三张不同作用域的记忆卡片 */
	private fun createV5DatabaseWithCards() {
		val schema = JSONObject(readSchema(5)).getJSONObject("database")
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
				"INSERT INTO personas (id, name, avatarEmoji, avatarPath, note, tagline, systemPrompt, " +
					"greeting, temperature, topP, maxTokens, modelOverride, isBuiltIn, sortOrder, " +
					"generatedFromPrompt, proactiveEnabled, createdAt, updatedAt) " +
					"VALUES (1, 'v5 性格', '🌸', NULL, '', '简介', '你是 v5 性格', '你好', " +
					"0.8, 0.9, 1024, NULL, 1, 0, NULL, 0, 100, 100)",
			)
			db.execSQL(
				"INSERT INTO conversations (id, title, mode, avatarEmoji, avatarPath, pinned, archived, " +
					"createdAt, updatedAt, lastMessageAt, lastMessagePreview, compressedUntilMessageId, " +
					"pendingTokens, lastCompressAttemptAt, compressFailureCount) " +
					"VALUES (1, 'v5 会话', 'SINGLE', '', NULL, 0, 0, 100, 100, 100, '预览', 0, 12, 0, 0)",
			)

			// 全局事实、会话级事件、性格级印象，正好覆盖三种作用域
			db.execSQL(
				"INSERT INTO memory_cards (id, conversationId, personaId, scopeKey, type, keyword, " +
					"content, importance, hitCount, lastHitAt, pinned, createdAt, updatedAt) " +
					"VALUES (1, NULL, NULL, 'c:-|p:-', 'FACT', '职业', '在做安卓开发', 4, 7, 500, 0, 100, 200)",
			)
			db.execSQL(
				"INSERT INTO memory_cards (id, conversationId, personaId, scopeKey, type, keyword, " +
					"content, importance, hitCount, lastHitAt, pinned, createdAt, updatedAt) " +
					"VALUES (2, 1, NULL, 'c:1|p:-', 'EVENT', '上周', '改了压缩逻辑', 3, 0, 0, 1, 100, 200)",
			)
			db.execSQL(
				"INSERT INTO memory_cards (id, conversationId, personaId, scopeKey, type, keyword, " +
					"content, importance, hitCount, lastHitAt, pinned, createdAt, updatedAt) " +
					"VALUES (3, NULL, 1, 'c:-|p:1', 'IMPRESSION', '印象', '很较真', 5, 2, 300, 0, 100, 200)",
			)

			db.version = 5
		} finally {
			db.close()
		}
	}

	private fun openV6(): AicpDatabase =
		Room.databaseBuilder(context, AicpDatabase::class.java, DB_NAME)
			.addMigrations(*AicpMigrations.ALL)
			.allowMainThreadQueries()
			.build()

	@Test
	fun `旧卡片一张不少地搬进条目表`() = runTest {
		createV5DatabaseWithCards()
		val db = openV6()
		try {
			val entries = db.memoryDao().observeAllEntries().first().associateBy { it.title }

			assertEquals(3, entries.size)
			assertTrue(entries.containsKey("职业"))
			assertTrue(entries.containsKey("上周"))
			assertTrue(entries.containsKey("印象"))
		} finally {
			db.close()
		}
	}

	@Test
	fun `字段映射逐个对得上`() = runTest {
		createV5DatabaseWithCards()
		val db = openV6()
		try {
			val job = db.memoryDao().observeAllEntries().first().single { it.title == "职业" }

			assertEquals(MemoryCardType.FACT, job.category)
			// keyword → title，content 同时进 oneLiner 和 body
			assertEquals("在做安卓开发", job.oneLiner)
			assertEquals("在做安卓开发", job.body)
			assertEquals(4, job.importance)
			// 命中统计和时间戳要原样带过来，不然冷条目淘汰会误判
			assertEquals(7, job.hitCount)
			assertEquals(500L, job.lastHitAt)
			assertEquals(100L, job.createdAt)
			assertEquals(200L, job.updatedAt)
			// 迁移进来的条目还没有别名，也没有矛盾
			assertEquals("", job.aliases)
			assertNull(job.conflictNote)
			assertEquals(1, job.sourceCount)
		} finally {
			db.close()
		}
	}

	@Test
	fun `钉住状态和三种作用域都保住了`() = runTest {
		createV5DatabaseWithCards()
		val db = openV6()
		try {
			val entries = db.memoryDao().observeAllEntries().first().associateBy { it.title }

			assertTrue("钉住的卡片迁移后还该是钉住的", entries["上周"]!!.pinned)
			assertEquals("c:-|p:-", entries["职业"]!!.scopeKey)
			assertEquals("c:1|p:-", entries["上周"]!!.scopeKey)
			assertEquals("c:-|p:1", entries["印象"]!!.scopeKey)
			// 作用域的两个外键列也要跟着对
			assertEquals(1L, entries["上周"]!!.conversationId)
			assertEquals(1L, entries["印象"]!!.personaId)
		} finally {
			db.close()
		}
	}

	@Test
	fun `旧表保留不动，这是这次迁移的安全网`() = runTest {
		createV5DatabaseWithCards()
		val db = openV6()
		try {
			// 数据还在旧表里躺着，新结构真出问题时能拿它回滚
			assertEquals(3, db.memoryDao().countCards())
			assertEquals(3, db.memoryDao().observeAllCards().first().size)
		} finally {
			db.close()
		}
	}

	@Test
	fun `条目表的唯一索引真的建出来了`() = runTest {
		createV5DatabaseWithCards()
		val db = openV6()
		try {
			val error = runCatching {
				db.memoryDao().insertEntry(
					MemoryEntryEntity(
						scopeKey = "c:-|p:-",
						category = MemoryCardType.FACT,
						title = "职业",
						oneLiner = "重复标题",
						body = "重复标题",
						importance = 3,
						createdAt = 300,
						updatedAt = 300,
					),
				)
			}.exceptionOrNull()

			// insertEntry 用的是 REPLACE 策略，所以不抛异常而是替换掉原来那条 ——
			// 这正是 upsert 想要的行为，验的是"没有变成两条"
			assertNull(error)
			assertEquals(
				1,
				db.memoryDao().observeAllEntries().first().count { it.title == "职业" },
			)
		} finally {
			db.close()
		}
	}

	@Test
	fun `日志表能写能读`() = runTest {
		createV5DatabaseWithCards()
		val db = openV6()
		try {
			db.memoryDao().insertLog(
				MemoryLogEntity(
					conversationId = 1,
					kind = MemoryLogKind.INGEST,
					summary = "压缩了 8 条对话，更新条目 2 个",
					touchedTitles = "职业|咖啡",
					createdAt = 1_000,
				),
			)

			val log = db.memoryDao().recentLogs(10).single()
			assertEquals(MemoryLogKind.INGEST, log.kind)
			assertEquals("职业|咖啡", log.touchedTitles)
			assertEquals(1, db.memoryDao().countLogs())
		} finally {
			db.close()
		}
	}

	@Test
	fun `index 查询只取四列，不含正文`() = runTest {
		createV5DatabaseWithCards()
		val db = openV6()
		try {
			val index = db.memoryDao().getEntryIndex(listOf("c:-|p:-"), limit = 10)

			assertEquals(1, index.size)
			assertEquals("职业", index.single().title)
			assertEquals("在做安卓开发", index.single().oneLiner)
		} finally {
			db.close()
		}
	}
}
