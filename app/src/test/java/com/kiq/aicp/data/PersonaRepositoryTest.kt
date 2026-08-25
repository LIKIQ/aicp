// app/src/test/java/com/kiq/aicp/data/PersonaRepositoryTest.kt
// 性格库的写入规则：种子只灌一次、内置不许删、参数越界要夹回合法区间。

package com.kiq.aicp.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.kiq.aicp.data.db.AicpDatabase
import com.kiq.aicp.data.repo.PersonaRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PersonaRepositoryTest {

	private lateinit var db: AicpDatabase
	private lateinit var personas: PersonaRepository

	private var now = 1_700_000_000_000L

	@Before
	fun setUp() {
		db = AicpDatabase.buildInMemory(ApplicationProvider.getApplicationContext<Context>())
		personas = PersonaRepository(db.personaDao()) { now }
	}

	@After
	fun tearDown() {
		db.close()
	}

	@Test
	fun `首次启动灌入内置性格，再调一次不会重复灌`() = runTest {
		assertTrue(personas.ensureSeeded())

		val seeded = personas.observeAll().first()
		assertEquals(4, seeded.size)
		assertTrue(seeded.all { it.isBuiltIn })
		assertTrue(seeded.all { it.systemPrompt.isNotBlank() })
		// 排序字段必须严格递增，否则列表顺序会随机
		assertEquals(listOf(0, 1, 2, 3), seeded.map { it.sortOrder })

		assertFalse(personas.ensureSeeded())
		assertEquals(4, personas.observeAll().first().size)
	}

	@Test
	fun `内置性格删不掉，自建的能删`() = runTest {
		personas.ensureSeeded()
		val builtIn = personas.observeAll().first().first()

		assertFalse(personas.delete(builtIn.id))
		assertEquals(4, personas.observeAll().first().size)

		val customId = personas.create(
			name = "临时角色",
			avatarEmoji = "🧪",
			tagline = "测试用",
			systemPrompt = "你是一个测试角色",
			greeting = "",
			temperature = 0.7f,
			topP = 0.9f,
			maxTokens = 512,
		)
		assertEquals(5, personas.observeAll().first().size)
		assertTrue(personas.delete(customId))
		assertEquals(4, personas.observeAll().first().size)
	}

	@Test
	fun `创建时越界参数被夹回合法区间，空名字有兜底`() = runTest {
		val id = personas.create(
			name = "   ",
			avatarEmoji = "",
			tagline = "",
			systemPrompt = "  随便  ",
			greeting = "",
			temperature = 9f,
			topP = -1f,
			maxTokens = 1,
			modelOverride = "   ",
		)

		val p = personas.getById(id)!!
		assertEquals("未命名性格", p.name)
		assertEquals("🙂", p.avatarEmoji)
		assertEquals(2f, p.temperature, 0.001f)
		assertEquals(0f, p.topP, 0.001f)
		assertEquals(64, p.maxTokens)
		assertEquals(null, p.modelOverride)
		assertEquals("随便", p.systemPrompt)
		assertFalse(p.isBuiltIn)
	}

	@Test
	fun `内置性格允许改内容，改完 updatedAt 会推进`() = runTest {
		personas.ensureSeeded()
		val builtIn = personas.observeAll().first().first()

		now += 5_000
		personas.update(builtIn.copy(systemPrompt = "被我改过的人设", temperature = 5f))

		val after = personas.getById(builtIn.id)!!
		assertEquals("被我改过的人设", after.systemPrompt)
		assertEquals(2f, after.temperature, 0.001f)
		assertEquals(1_700_000_005_000L, after.updatedAt)
		assertTrue(after.isBuiltIn)
	}

	@Test
	fun `自建性格排在内置之后`() = runTest {
		personas.ensureSeeded()
		val id = personas.create(
			name = "新角色",
			avatarEmoji = "🆕",
			tagline = "",
			systemPrompt = "x",
			greeting = "",
			temperature = 0.8f,
			topP = 0.9f,
			maxTokens = 512,
		)
		assertEquals(id, personas.observeAll().first().last().id)
	}

	@Test
	fun `reorder 按传入顺序重写 sortOrder`() = runTest {
		personas.ensureSeeded()
		val ids = personas.observeAll().first().map { it.id }

		personas.reorder(ids.reversed())

		assertEquals(ids.reversed(), personas.observeAll().first().map { it.id })
	}
}
