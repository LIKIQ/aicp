// app/src/test/java/com/kiq/aicp/data/MigrationV6ToV7Test.kt
// v6 → v7 迁移测试（表情多一列 emotion）。
//
// 这次迁移只加一列，但风险恰恰在这里：stickers 里躺的是用户自己一张张导入的图，
// 迁移写歪了（比如有人图省事改成 DROP + CREATE）就是把他的表情包清空。
// 所以这里逐字段核对旧数据还在，而不只是看新列建出来了。
//
// 另一半守的是 Room 的 schema 校验：ADD COLUMN 的 DEFAULT 必须跟实体上的默认值逐字对齐，
// 差一个引号就是"升级后一启动就崩"，那种崩溃在这里会表现成打不开库。

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
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MigrationV6ToV7Test {

	private companion object {
		const val DB_NAME = "migration_v6_v7_test.db"
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

	/** 按 6.json 建真 v6 库，塞一个分组和两张已经用过的表情 */
	private fun createV6DatabaseWithStickers() {
		val schema = JSONObject(readSchema(6)).getJSONObject("database")
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
				"INSERT INTO sticker_packs (id, name, sortOrder, createdAt) " +
					"VALUES (1, '我的收藏', 0, 100)",
			)
			db.execSQL(
				"INSERT INTO stickers (id, packId, label, localPath, mimeType, byteSize, " +
					"width, height, useCount, createdAt) " +
					"VALUES (1, 1, '熊猫头笑', 'stickers/1_panda.png', 'image/png', 2048, " +
					"240, 240, 7, 100)",
			)
			db.execSQL(
				"INSERT INTO stickers (id, packId, label, localPath, mimeType, byteSize, " +
					"width, height, useCount, createdAt) " +
					"VALUES (2, 1, '猫猫叹气', 'stickers/2_cat.gif', 'image/gif', 4096, " +
					"320, 200, 0, 200)",
			)

			db.version = 6
		} finally {
			db.close()
		}
	}

	private fun openV7(): AicpDatabase =
		Room.databaseBuilder(context, AicpDatabase::class.java, DB_NAME)
			.addMigrations(*AicpMigrations.ALL)
			.allowMainThreadQueries()
			.build()

	@Test
	fun `已有表情一张不少，字段逐个都还在`() = runTest {
		createV6DatabaseWithStickers()
		val db = openV7()
		try {
			val dao = db.stickerDao()
			assertEquals(2, dao.stickerCount())

			val panda = dao.byLabel("熊猫头笑")!!
			assertEquals("stickers/1_panda.png", panda.localPath)
			assertEquals("image/png", panda.mimeType)
			assertEquals(2048L, panda.byteSize)
			assertEquals(240, panda.width)
			assertEquals(240, panda.height)
			// useCount 丢了的话提示词里的常用排序会全部归零
			assertEquals(7, panda.useCount)
			assertEquals(100L, panda.createdAt)

			assertNotNull("另一张也得在", dao.byLabel("猫猫叹气"))
			assertEquals("我的收藏", dao.packById(1)?.name)
		} finally {
			db.close()
		}
	}

	@Test
	fun `新列默认是空串，表示这些老表情还没识别过`() = runTest {
		createV6DatabaseWithStickers()
		val db = openV7()
		try {
			val dao = db.stickerDao()

			assertEquals("", dao.byLabel("熊猫头笑")!!.emotion)
			assertEquals("", dao.byLabel("猫猫叹气")!!.emotion)
			// 组名"我的收藏"不是情绪，所以这两张都该排进待识别
			assertEquals(2, dao.countUnclassifiedInPack(1))
		} finally {
			db.close()
		}
	}

	@Test
	fun `新列可写，写完就不再算待识别`() = runTest {
		createV6DatabaseWithStickers()
		val db = openV7()
		try {
			val dao = db.stickerDao()
			dao.updateEmotion(1, "大笑")

			assertEquals("大笑", dao.byLabel("熊猫头笑")!!.emotion)
			assertEquals(1, dao.countUnclassifiedInPack(1))
			assertEquals(listOf("熊猫头笑"), dao.labelsWithEmotion("大笑"))
			// 只该动那一张，另一张的状态不能被顺手改掉
			assertEquals("", dao.byLabel("猫猫叹气")!!.emotion)
		} finally {
			db.close()
		}
	}
}
