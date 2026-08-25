// app/src/test/java/com/kiq/aicp/data/StickerRepositoryTest.kt
// 表情包仓库测试：label 去重与清洗、分组撞名、删除级联。
//
// import() 需要真的 SAF content:// Uri，Robolectric 里造不出来，
// 所以这里直接测 uniqueLabel（它开成 internal 就是为了这个）加上其余不依赖 Uri 的方法。
// 落盘那半段由 AttachmentStore 自己负责，删文件删的是不存在的路径，不会炸。

package com.kiq.aicp.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.kiq.aicp.data.attach.AttachmentStore
import com.kiq.aicp.data.db.AicpDatabase
import com.kiq.aicp.data.db.entity.StickerEntity
import com.kiq.aicp.data.repo.StickerRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class StickerRepositoryTest {

	private lateinit var db: AicpDatabase
	private lateinit var repo: StickerRepository

	@Before
	fun setUp() {
		val context: Context = ApplicationProvider.getApplicationContext()
		db = AicpDatabase.buildInMemory(context)
		repo = StickerRepository(db, AttachmentStore(context)) { 1_000L }
	}

	@After
	fun tearDown() {
		db.close()
	}

	/** 直接插库，绕开需要 Uri 的 import() */
	private suspend fun seed(packId: Long, label: String): Long =
		db.stickerDao().insertSticker(
			StickerEntity(
				packId = packId,
				label = label,
				localPath = "stickers/$label.png",
				mimeType = "image/png",
				byteSize = 512,
				width = 200,
				height = 200,
				createdAt = 1_000L,
			),
		)

	// ---------------- 分组 ----------------

	@Test
	fun `ensurePack 幂等，同名只建一次`() = runTest {
		val first = repo.ensurePack("我的表情")
		val second = repo.ensurePack("我的表情")

		assertEquals(first, second)
		assertEquals(1, db.stickerDao().packCount())
	}

	@Test
	fun `ensurePack 会把首尾空格吃掉，避免出现看不出区别的两个组`() = runTest {
		val a = repo.ensurePack("熊猫头")
		val b = repo.ensurePack("  熊猫头  ")

		assertEquals(a, b)
	}

	@Test
	fun `createPack 撞名要明确报错而不是悄悄合并`() = runTest {
		repo.createPack("熊猫头")

		val error = runCatching { repo.createPack("熊猫头") }.exceptionOrNull()

		assertTrue(error is IllegalArgumentException)
		assertTrue(error!!.message!!.contains("熊猫头"))
	}

	@Test
	fun `createPack 拒绝空名`() = runTest {
		assertTrue(runCatching { repo.createPack("   ") }.exceptionOrNull() is IllegalArgumentException)
	}

	@Test
	fun `renamePack 改成自己原来的名字不算撞名`() = runTest {
		val id = repo.createPack("原名")

		repo.renamePack(id, "原名")
		repo.renamePack(id, "新名")

		assertEquals("新名", db.stickerDao().packById(id)?.name)
	}

	@Test
	fun `renamePack 撞别人的名字要拒绝`() = runTest {
		val a = repo.createPack("组A")
		repo.createPack("组B")

		assertTrue(runCatching { repo.renamePack(a, "组B") }.exceptionOrNull() is IllegalArgumentException)
		assertEquals("组A", db.stickerDao().packById(a)?.name)
	}

	@Test
	fun `删分组把组内表情一起带走`() = runTest {
		val packId = repo.createPack("待删")
		seed(packId, "标记1")
		seed(packId, "标记2")

		repo.deletePack(packId)

		assertEquals(0, db.stickerDao().stickerCount())
		assertNull(db.stickerDao().packById(packId))
	}

	// ---------------- label 去重与清洗 ----------------

	@Test
	fun `label 不撞名时原样保留`() = runTest {
		assertEquals("开心", repo.uniqueLabel("开心"))
	}

	@Test
	fun `撞名按顺序追加数字`() = runTest {
		val packId = repo.ensurePack(StickerRepository.DEFAULT_PACK)
		seed(packId, "开心")

		assertEquals("开心2", repo.uniqueLabel("开心"))

		seed(packId, "开心2")
		assertEquals("开心3", repo.uniqueLabel("开心"))

		// 中间被占掉也要继续往后找，不能停在第一个空位之前
		seed(packId, "开心3")
		assertEquals("开心4", repo.uniqueLabel("开心"))
	}

	@Test
	fun `中括号和换行必须清掉，否则标记解析的边界会错位`() = runTest {
		assertEquals("开心", repo.uniqueLabel("[开心]"))
		assertEquals("开心笑", repo.uniqueLabel("开心\n笑"))
		assertEquals("制表符", repo.uniqueLabel("制表\t符"))
	}

	@Test
	fun `清洗后为空时退回默认标记，不会产生空 label`() = runTest {
		assertEquals("表情", repo.uniqueLabel("[]"))
		assertEquals("表情", repo.uniqueLabel("   "))
	}

	@Test
	fun `超长 label 被截到 20 字符，跟解析正则的上限对齐`() = runTest {
		val long = "一二三四五六七八九十一二三四五六七八九十还有更多"

		val label = repo.uniqueLabel(long)

		assertEquals(20, label.length)
		assertTrue(long.startsWith(label))
	}

	// ---------------- 表情操作 ----------------

	@Test
	fun `rename 撞名要拒绝且不改动原值`() = runTest {
		val packId = repo.ensurePack(StickerRepository.DEFAULT_PACK)
		val a = seed(packId, "开心")
		seed(packId, "无语")

		assertTrue(runCatching { repo.rename(a, "无语") }.exceptionOrNull() is IllegalArgumentException)
		assertEquals("开心", db.stickerDao().byId(a)?.label)
	}

	@Test
	fun `rename 会顺手清掉中括号`() = runTest {
		val packId = repo.ensurePack(StickerRepository.DEFAULT_PACK)
		val id = seed(packId, "开心")

		repo.rename(id, "[超开心]")

		assertEquals("超开心", db.stickerDao().byId(id)?.label)
	}

	@Test
	fun `rename 拒绝清洗后为空的标记`() = runTest {
		val packId = repo.ensurePack(StickerRepository.DEFAULT_PACK)
		val id = seed(packId, "开心")

		assertTrue(runCatching { repo.rename(id, "[]") }.exceptionOrNull() is IllegalArgumentException)
		assertEquals("开心", db.stickerDao().byId(id)?.label)
	}

	@Test
	fun `moveToPack 换组后 label 不变`() = runTest {
		val from = repo.createPack("组A")
		val to = repo.createPack("组B")
		val id = seed(from, "开心")

		repo.moveToPack(id, to)

		val moved = db.stickerDao().byId(id)!!
		assertEquals(to, moved.packId)
		assertEquals("开心", moved.label)
	}

	@Test
	fun `删单张表情不影响同组其他表情`() = runTest {
		val packId = repo.ensurePack(StickerRepository.DEFAULT_PACK)
		val a = seed(packId, "开心")
		seed(packId, "无语")

		repo.delete(a)

		assertNull(db.stickerDao().byId(a))
		assertNotNull(db.stickerDao().byLabel("无语"))
	}

	@Test
	fun `observeIndex 给出 label 到路径的映射，渲染直接查这张表`() = runTest {
		val packId = repo.ensurePack(StickerRepository.DEFAULT_PACK)
		seed(packId, "开心")
		seed(packId, "无语")

		val index = repo.observeIndex().first()

		assertEquals(2, index.size)
		assertEquals("stickers/开心.png", index["开心"])
	}

	@Test
	fun `promptLabels 按使用次数降序并限量`() = runTest {
		val packId = repo.ensurePack(StickerRepository.DEFAULT_PACK)
		seed(packId, "冷门")
		seed(packId, "热门")
		repo.bumpUsage(listOf("热门"))
		repo.bumpUsage(listOf("热门"))

		assertEquals(listOf("热门", "冷门"), repo.promptLabels(10))
		assertEquals(listOf("热门"), repo.promptLabels(1))
	}

	@Test
	fun `bumpUsage 传空列表不炸也不改数据`() = runTest {
		val packId = repo.ensurePack(StickerRepository.DEFAULT_PACK)
		seed(packId, "开心")

		repo.bumpUsage(emptyList())

		assertEquals(0, db.stickerDao().byLabel("开心")!!.useCount)
	}
}
