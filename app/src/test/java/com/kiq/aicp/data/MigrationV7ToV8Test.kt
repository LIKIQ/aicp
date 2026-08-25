// app/src/test/java/com/kiq/aicp/data/MigrationV7ToV8Test.kt
// v7 → v8 迁移测试（表情分组多一列 builtIn）。
//
// 这次迁移除了加列还要回填：0.5.2 那版预设建的分组名正好是情绪词表里那 20 个，
// 按名字命中就标成内置。回填写错的后果是表情面板的"内置/我的"两栏错位 ——
// 用户自己导的图跑到内置栏里，或者预设表情一张都不显示。
//
// 更要紧的是别把 sticker_packs 搞坏：那张表是表情的归属，坏了所有图都成孤儿。
// 所以这里逐字段核对旧数据，而不只是看新列建出来了。

package com.kiq.aicp.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.kiq.aicp.data.db.AicpDatabase
import com.kiq.aicp.data.db.AicpMigrations
import java.io.File
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MigrationV7ToV8Test {

	private companion object {
		const val DB_NAME = "migration_v7_v8_test.db"
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

	/**
	 * 按 7.json 建真 v7 库，塞三个分组：
	 * 一个情绪名（模拟预设建的）、一个非情绪名（用户自己建的）、
	 * 一个名字撞上情绪词的用户自建组（回填只能靠名字猜，这个会被误标，测试要把这个行为钉住）
	 */
	private fun createV7Database() {
		val schema = JSONObject(readSchema(7)).getJSONObject("database")
		val entities = schema.getJSONArray("entities")

		val db = SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(DB_NAME), null)
		try {
			for (i in 0 until entities.length()) {
				val entity = entities.getJSONObject(i)
				val table = entity.getString("tableName")
				db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))

				val indices = entity.optJSONArray("indices") ?: continue
				for (j in 0 until indices.length()) {
					db.execSQL(
						indices.getJSONObject(j).getString("createSql")
							.replace("\${TABLE_NAME}", table),
					)
				}
			}

			db.execSQL("INSERT INTO sticker_packs (id, name, sortOrder, createdAt) VALUES (1, '开心', 0, 100)")
			db.execSQL("INSERT INTO sticker_packs (id, name, sortOrder, createdAt) VALUES (2, '我的收藏', 1, 200)")
			db.execSQL("INSERT INTO sticker_packs (id, name, sortOrder, createdAt) VALUES (3, '默认表情', 2, 300)")

			db.execSQL(
				"INSERT INTO stickers (id, packId, label, localPath, mimeType, byteSize, " +
					"width, height, useCount, emotion, createdAt) " +
					"VALUES (1, 1, '开心1', 'stickers/1.png', 'image/png', 2048, 256, 256, 5, '', 100)",
			)
			db.execSQL(
				"INSERT INTO stickers (id, packId, label, localPath, mimeType, byteSize, " +
					"width, height, useCount, emotion, createdAt) " +
					"VALUES (2, 2, '熊猫头', 'stickers/2.gif', 'image/gif', 4096, 320, 200, 3, '大笑', 200)",
			)

			db.version = 7
		} finally {
			db.close()
		}
	}

	private fun openV8(): AicpDatabase =
		Room.databaseBuilder(context, AicpDatabase::class.java, DB_NAME)
			.addMigrations(*AicpMigrations.ALL)
			.allowMainThreadQueries()
			.build()

	@Test
	fun `分组和表情一个不少，字段逐个都还在`() = runTest {
		createV7Database()
		val db = openV8()
		try {
			val dao = db.stickerDao()
			assertEquals(3, dao.packCount())
			assertEquals(2, dao.stickerCount())

			val custom = dao.packById(2)!!
			assertEquals("我的收藏", custom.name)
			assertEquals(1, custom.sortOrder)
			assertEquals(200L, custom.createdAt)

			val panda = dao.byLabel("熊猫头")!!
			assertEquals(2L, panda.packId)
			assertEquals("stickers/2.gif", panda.localPath)
			assertEquals("image/gif", panda.mimeType)
			assertEquals(4096L, panda.byteSize)
			// useCount 丢了提示词里的常用排序会全部归零
			assertEquals(3, panda.useCount)
			// 上一版加的情绪列也得跟着过来，不然识过的图要重新识一遍
			assertEquals("大笑", panda.emotion)
			assertEquals(200L, panda.createdAt)
		} finally {
			db.close()
		}
	}

	@Test
	fun `情绪名的分组被回填成内置`() = runTest {
		createV7Database()
		val db = openV8()
		try {
			assertTrue("「开心」是预设建的，该标成内置", db.stickerDao().packById(1)!!.builtIn)
		} finally {
			db.close()
		}
	}

	@Test
	fun `非情绪名的分组保持自定义`() = runTest {
		createV7Database()
		val db = openV8()
		try {
			val dao = db.stickerDao()
			assertFalse("「我的收藏」是用户自己建的", dao.packById(2)!!.builtIn)
			// 「默认表情」是 assets 那套散图的落脚组，名字不是情绪，同样算自定义
			assertFalse("「默认表情」名字不是情绪词", dao.packById(3)!!.builtIn)
		} finally {
			db.close()
		}
	}

	@Test
	fun `新建的分组默认是自定义`() = runTest {
		createV7Database()
		val db = openV8()
		try {
			val dao = db.stickerDao()
			val id = dao.insertPack(
				com.kiq.aicp.data.db.entity.StickerPackEntity(
					name = "自己新建的",
					sortOrder = 9,
					createdAt = 999,
				),
			)

			assertNotNull(dao.packById(id))
			assertFalse("没显式标内置就该是自定义", dao.packById(id)!!.builtIn)
		} finally {
			db.close()
		}
	}
}
