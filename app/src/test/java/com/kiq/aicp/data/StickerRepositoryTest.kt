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

	/** 直接插库，绕开需要 Uri 的 import()。emotion 传空串就是"还没识别过" */
	private suspend fun seed(packId: Long, label: String, emotion: String = ""): Long =
		db.stickerDao().insertSticker(
			StickerEntity(
				packId = packId,
				label = label,
				localPath = "stickers/$label.png",
				mimeType = "image/png",
				byteSize = 512,
				width = 200,
				height = 200,
				emotion = emotion,
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

	// ---------------- 情绪：两条来源的合并 ----------------
	//
	// 情绪要么来自分组名（整组共用），要么来自每张图识图的结果。
	// 下面每条都明确站在其中一条来源上，混着测的话某一条断了也看不出是哪条。

	@Test
	fun `promptEmotions 认出组名本身就是情绪的组`() = runTest {
		val happy = repo.createPack("开心")
		seed(happy, "熊猫笑")
		val sad = repo.createPack("伤心表情包")
		seed(sad, "猫猫哭")

		assertEquals(setOf("开心", "伤心"), repo.promptEmotions(10).toSet())
	}

	@Test
	fun `promptEmotions 也收非情绪分组里识图标出来的情绪`() = runTest {
		val fav = repo.createPack("我的收藏")
		seed(fav, "图A", emotion = "无语")
		// 还没识别的那张不该让"我的收藏"这个组名漏进清单
		seed(fav, "图B")

		assertEquals(listOf("无语"), repo.promptEmotions(10))
	}

	@Test
	fun `promptEmotions 两条来源撞上同一个情绪只算一份`() = runTest {
		val happy = repo.createPack("开心")
		seed(happy, "熊猫笑")
		val fav = repo.createPack("我的收藏")
		seed(fav, "图A", emotion = "开心")

		assertEquals(listOf("开心"), repo.promptEmotions(10))
	}

	@Test
	fun `情绪分组里图上的 emotion 不单独算，整组共用组名`() = runTest {
		val happy = repo.createPack("开心")
		// 这张图上留着跟组名不一样的旧识别结果，组名是情绪时它不该被当成来源
		seed(happy, "熊猫笑", emotion = "伤心")

		assertEquals(listOf("开心"), repo.promptEmotions(10))
	}

	@Test
	fun `promptEmotions 按热度降序并限量`() = runTest {
		val happy = repo.createPack("开心")
		seed(happy, "熊猫笑")
		val fav = repo.createPack("我的收藏")
		seed(fav, "图A", emotion = "无语")
		repo.bumpUsage(listOf("图A"))
		repo.bumpUsage(listOf("图A"))

		assertEquals(listOf("无语", "开心"), repo.promptEmotions(10))
		assertEquals(listOf("无语"), repo.promptEmotions(1))
		assertTrue(repo.promptEmotions(0).isEmpty())
	}

	@Test
	fun `热度相同时按词表顺序，同一份库两次拼出的清单要一样`() = runTest {
		val sad = repo.createPack("伤心")
		seed(sad, "猫猫哭")
		val happy = repo.createPack("开心")
		seed(happy, "熊猫笑")

		// ALL 里"开心"在"伤心"前面
		assertEquals(listOf("开心", "伤心"), repo.promptEmotions(10))
	}

	@Test
	fun `一张表情都没有时清单为空，等于表情功能自动不生效`() = runTest {
		repo.createPack("开心")

		assertTrue(repo.promptEmotions(10).isEmpty())
	}

	@Test
	fun `pickForEmotion 从情绪分组里挑`() = runTest {
		val happy = repo.createPack("开心的图")
		seed(happy, "熊猫笑")

		assertEquals("熊猫笑", repo.pickForEmotion("开心"))
	}

	@Test
	fun `pickForEmotion 从识图结果里挑`() = runTest {
		val fav = repo.createPack("我的收藏")
		seed(fav, "图A", emotion = "无语")

		assertEquals("图A", repo.pickForEmotion("无语"))
	}

	@Test
	fun `pickForEmotion 两条来源合在一起随机挑`() = runTest {
		val happy = repo.createPack("开心")
		seed(happy, "熊猫笑")
		val fav = repo.createPack("我的收藏")
		seed(fav, "图A", emotion = "开心")

		val seen = (1..60).mapNotNull { repo.pickForEmotion("开心") }.toSet()

		assertEquals(setOf("熊猫笑", "图A"), seen)
	}

	@Test
	fun `pickForEmotion 对词表外的词和没图的情绪都给 null`() = runTest {
		val fav = repo.createPack("我的收藏")
		seed(fav, "图A", emotion = "无语")

		assertNull("分组名不是情绪，不该被当成情绪来挑图", repo.pickForEmotion("我的收藏"))
		assertNull(repo.pickForEmotion("开心"))
		assertNull(repo.pickForEmotion(""))
	}

	@Test
	fun `unclassifiedIn 只给还没识别的那些`() = runTest {
		val fav = repo.createPack("我的收藏")
		seed(fav, "图A", emotion = "无语")
		seed(fav, "图B")
		seed(fav, "图C")

		assertEquals(setOf("图B", "图C"), repo.unclassifiedIn(fav).map { it.label }.toSet())
		assertEquals(2, repo.unclassifiedCount(fav))
	}

	@Test
	fun `组名本身是情绪时整组都不用识别`() = runTest {
		val happy = repo.createPack("开心")
		seed(happy, "熊猫笑")
		seed(happy, "熊猫乐")

		assertEquals(0, repo.unclassifiedCount(happy))
		assertTrue(repo.unclassifiedIn(happy).isEmpty())
	}

	@Test
	fun `分组不存在时待识别是 0 而不是异常`() = runTest {
		assertEquals(0, repo.unclassifiedCount(999))
		assertTrue(repo.unclassifiedIn(999).isEmpty())
	}

	@Test
	fun `allUnclassified 跨组给出所有该识别的图`() = runTest {
		val fav = repo.createPack("我的收藏")
		seed(fav, "图A")
		seed(fav, "图B", emotion = "无语")
		val panda = repo.createPack("熊猫头")
		seed(panda, "图C")

		assertEquals(setOf("图A", "图C"), repo.allUnclassified().map { it.label }.toSet())
	}

	@Test
	fun `allUnclassified 不碰情绪分组，那些组整组共用组名`() = runTest {
		val happy = repo.createPack("开心")
		seed(happy, "熊猫笑")
		seed(happy, "熊猫乐")
		val fav = repo.createPack("我的收藏")
		seed(fav, "图A")

		assertEquals(listOf("图A"), repo.allUnclassified().map { it.label })
	}

	@Test
	fun `全是情绪分组时后台识图无事可做`() = runTest {
		val happy = repo.createPack("开心的图")
		seed(happy, "熊猫笑")
		val sad = repo.createPack("伤心表情包")
		seed(sad, "猫猫哭")

		assertTrue(repo.allUnclassified().isEmpty())
	}

	@Test
	fun `setEmotion 只接受词表里的值，模型胡说的一律忽略`() = runTest {
		val fav = repo.createPack("我的收藏")
		val id = seed(fav, "图A")

		repo.setEmotion(id, " 开心 ")
		assertEquals("开心", db.stickerDao().byId(id)?.emotion)

		repo.setEmotion(id, "略带忧郁的欣喜")
		assertEquals("已有的识别结果不能被一句胡话冲掉", "开心", db.stickerDao().byId(id)?.emotion)
	}

	@Test
	fun `识别过的图不会再被后台任务捞出来跑第二遍`() = runTest {
		val fav = repo.createPack("我的收藏")
		val id = seed(fav, "图A")

		repo.setEmotion(id, "开心")

		assertTrue(repo.allUnclassified().isEmpty())
	}

	@Test
	fun `setEmotion 传空串是 no-op，清除必须走 clearEmotion`() = runTest {
		val fav = repo.createPack("我的收藏")
		val id = seed(fav, "图A", emotion = "开心")

		repo.setEmotion(id, "")
		repo.setEmotion(id, "   ")

		// 空串合法的话，"模型没解析出结果"和"用户要清除"就分不开了
		assertEquals("开心", db.stickerDao().byId(id)?.emotion)
	}

	@Test
	fun `clearEmotion 把图退回待识别，下次后台识图会再捡起它`() = runTest {
		val fav = repo.createPack("我的收藏")
		val id = seed(fav, "图A", emotion = "开心")

		repo.clearEmotion(id)

		assertEquals("", db.stickerDao().byId(id)?.emotion)
		assertEquals(listOf("图A"), repo.allUnclassified().map { it.label })
		assertEquals(1, repo.unclassifiedCount(fav))
		// 清掉之后它也不该再被当成"开心"发出去
		assertNull(repo.pickForEmotion("开心"))
	}
}
