// app/src/main/java/com/kiq/aicp/data/backup/BackupManager.kt
// 全量数据的导出与恢复：一个 zip 装走所有"丢了就造不回来"的东西。
//
// 这个文件是 AndroidManifest 里 allowBackup=false 那句注释的另一半。既然不让数据进系统云备份，
// 就得自己给一条换手机时能把记忆带走的路，不然那行"需要备份时走应用内导出功能"只是空头承诺。
//
// 装什么：数据库（会话、消息、记忆摘要与卡片、表情索引）+ 三个资源目录
// （attachments / stickers / avatars）。
//
// 刻意不装的两类，理由都不是"不重要"：
// 1. API Key。它在 DataStore 里是 AndroidKeystore 加密过的密文，而那把密钥绑死当前设备、根本导不出来，
//    换机之后这段密文谁也解不开。带出去只是把一段无用密文送出设备，纯增风险不增价值。
// 2. DataStore 里其余的设置项（模型名、各种阈值、真人模拟与主动搭话参数）。
//    判据是"能恢复的才值得导出"：真要恢复它们，就得在冷启动、容器还没建起来的时候写 DataStore，
//    那是全应用最不该加失败面的位置；而手挑字段进 JSON 等于多养一份 schema，
//    AicpSettings 以后加字段忘了同步，用户看到的是"设置悄悄回默认了"这种最难查的问题。
//    设置项是可再生的（几分钟重调完），会话和记忆不是。将来想改这条，先把上面两点想明白。
//
// 恢复必须跨一次进程重启，这是整个文件最要紧的约束：Room 运行时握着 db 连接，
// 连接里有缓存页、有 WAL 的读点，文件在脚下被换掉，轻则查询读到半旧半新，重则把新库写坏。
// 所以阶段一（用户点确认时）只把 zip 解到暂存目录并置标记；真正的文件搬运在阶段二，
// 由 AicpApplication.onCreate 在任何人碰数据库之前同步跑完，见 applyPendingRestore。

package com.kiq.aicp.data.backup

import android.content.Context
import android.net.Uri
import android.os.storage.StorageManager
import android.provider.OpenableColumns
import android.util.Log
import com.kiq.aicp.BuildConfig
import com.kiq.aicp.data.attach.AttachmentStore
import com.kiq.aicp.data.db.AICP_DB_VERSION
import com.kiq.aicp.data.db.AicpDatabase
import com.kiq.aicp.data.prefs.SettingsStore
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * zip 里那份 manifest.json。
 *
 * 字段全给默认值：以后加字段，旧备份照样解得出来（缺的走默认），
 * 而不是解析炸掉之后只能对用户说一句"这个文件坏了"。
 *
 * exportedAt 和 exportedAtText 是同一个时刻的两种写法 —— 后者给人看（总有人会直接解开 zip 翻），
 * 前者给代码用，省得读的时候还要猜导出那台机器的时区。
 */
@Serializable
data class BackupManifest(
	val backupVersion: Int = 0,
	val exportedAt: Long = 0L,
	val exportedAtText: String = "",
	val appVersionCode: Int = 0,
	val appVersionName: String = "",
	val dbVersion: Int = 0,
	/** 明写出来，免得以后有人猜"这份备份是不是偷偷带了 Key" */
	val containsApiKey: Boolean = false,
)

/** 导出结果，只用来给 KIQ 报个数 */
data class ExportSummary(val fileCount: Int, val byteSize: Long)

/** 阶段一的结果：备份已经解到暂存目录并校验通过，等重启 */
data class StagedRestore(val manifest: BackupManifest, val fileCount: Int)

/** 恢复确认框里要报的数字。具体到条数才能让人真停下来想一下，"确定吗"是拦不住误操作的 */
data class DataSummary(
	val conversations: Int,
	val messages: Int,
	val memories: Int,
	val stickers: Int,
)

/** 阶段二的结论。进程内传一次给设置页，让用户知道上次启动到底恢复成没成 */
sealed interface StartupRestoreOutcome {
	data class Done(val movedCount: Int, val elapsedMs: Long) : StartupRestoreOutcome

	data class Failed(val reason: String) : StartupRestoreOutcome
}

class BackupManager(
	private val context: Context,
	private val database: AicpDatabase,
	private val settingsStore: SettingsStore,
	private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

	// prettyPrint 是给人看的：manifest 就几行，多几个换行换来"解开 zip 能直接读懂"
	private val json = Json {
		ignoreUnknownKeys = true
		prettyPrint = true
	}

	// ---------------- 导出 ----------------

	/**
	 * 打包写进 SAF 选出来的位置。
	 *
	 * checkpoint 在拷文件之前，顺序不能反：WAL 模式下最近的写入还躺在 -wal 里，
	 * 反过来的话拷出去的是一份"少了最后几条消息"的库，而且用户当场看不出来。
	 */
	suspend fun export(target: Uri, password: CharArray? = null): ExportSummary =
		withContext(ioDispatcher) {
			checkpointWal()

			val dbFile = context.getDatabasePath(AicpDatabase.DB_NAME)
			var fileCount = 0
			var byteSize = 0L

			val output = context.contentResolver.openOutputStream(target)
				?: error("系统没给出可写的位置，换个目录再试一次")

			// 加了口令就在 zip 外面再套一层 AES-GCM。留空则还是普通 zip，
			// 用任何解压软件都能打开自查——这个选择权留给用户，不替他决定
			val sink: OutputStream = BufferedOutputStream(output).let { buffered ->
				if (password != null && password.isNotEmpty()) {
					BackupCrypto.encryptingStream(buffered, password)
				} else {
					buffered
				}
			}

			ZipOutputStream(sink).use { zip ->
				val now = System.currentTimeMillis()
				val manifest = BackupManifest(
					backupVersion = BACKUP_VERSION,
					exportedAt = now,
					exportedAtText = timestampText(now),
					appVersionCode = BuildConfig.VERSION_CODE,
					appVersionName = BuildConfig.VERSION_NAME,
					dbVersion = CURRENT_DB_VERSION,
				)
				zip.putNextEntry(ZipEntry(MANIFEST_NAME))
				zip.write(json.encodeToString(BackupManifest.serializer(), manifest).toByteArray())
				zip.closeEntry()
				fileCount++

				dbFiles(dbFile).forEach { file ->
					if (!file.isFile) return@forEach
					copyIntoZip(zip, file.name, file)
					fileCount++
					byteSize += file.length()
				}

				ASSET_DIRS.forEach { dir ->
					File(context.filesDir, dir).listFiles()?.forEach { file ->
						if (!file.isFile) return@forEach
						copyIntoZip(zip, "$dir/${file.name}", file)
						fileCount++
						byteSize += file.length()
					}
				}
			}

			ExportSummary(fileCount = fileCount, byteSize = byteSize)
		}

	/**
	 * 恢复前先看一眼这份备份要不要口令，UI 拿它决定弹不弹口令框。
	 * 读不出来就当没加密，让后面的解压给出更准确的报错。
	 */
	suspend fun needsPassword(source: Uri): Boolean = withContext(ioDispatcher) {
		runCatching {
			context.contentResolver.openInputStream(source)?.use { input ->
				val head = ByteArray(BackupCrypto.HEADER_SIZE)
				var filled = 0
				while (filled < head.size) {
					val n = input.read(head, filled, head.size - filled)
					if (n < 0) break
					filled += n
				}
				BackupCrypto.looksEncrypted(head.copyOf(filled))
			} ?: false
		}.getOrDefault(false)
	}

	private fun copyIntoZip(zip: ZipOutputStream, entryName: String, file: File) {
		zip.putNextEntry(ZipEntry(entryName))
		file.inputStream().use { it.copyTo(zip) }
		zip.closeEntry()
	}

	/**
	 * PRAGMA 在 SQLite 里也是查询，不取一行它就不会真的执行 —— 这里的 moveToFirst 不是多余动作。
	 * TRUNCATE 会把 WAL 的内容落进主库并把 -wal 清零，跑完这一下三个文件才是自洽的一套。
	 */
	private fun checkpointWal() {
		database.openHelper.writableDatabase
			.query("PRAGMA wal_checkpoint(TRUNCATE)")
			.use { it.moveToFirst() }
	}

	// ---------------- 恢复：阶段一 ----------------

	/**
	 * 校验 zip 并解到暂存目录，一个字节都不碰正式位置。
	 *
	 * 解压目标是 restore_staging，全部落地并校验通过之后才 rename 成 restore_pending。
	 * 这次改名就是整个流程的"提交"动作：同一分区内 rename 是原子的，
	 * 所以下次启动看到 restore_pending 就一定是完整的一份 —— 解压途中被系统杀掉留下的残缺数据
	 * 只会留在 restore_staging 里，没人会去搬它。
	 */
	suspend fun stageRestore(source: Uri, password: CharArray? = null): StagedRestore =
		withContext(ioDispatcher) {
			val staging = File(context.filesDir, STAGING_DIR)
			staging.deleteRecursively()
			if (!staging.mkdirs()) error("建不出临时目录，先看一下存储空间还剩多少")

			// 上一次失败留下的证据这时候可以清了：日志里已经记了原因，而它占的是一整份备份的空间
			File(context.filesDir, FAILED_DIR).deleteRecursively()

			// 解压或校验中途出任何岔子都得把半成品收干净 —— 留一个"看着像能恢复"的暂存目录最误导人。
			// 收尾集中在这一处，unpackInto 里那些检查就只管抛错，不必各自记着删目录
			val staged = runCatching { unpackInto(source, staging, password) }
				.onFailure { staging.deleteRecursively() }
				.getOrThrow()

			val pending = File(context.filesDir, PENDING_DIR)
			pending.deleteRecursively()
			if (!staging.renameTo(pending)) {
				staging.deleteRecursively()
				error("暂存目录提交失败，这次恢复没有开始（现有数据一点没动）")
			}

			// 这个标记只负责 UI 上那句"等重启"；真正决定要不要搬的是 restore_pending 目录在不在，
			// 因为阶段二跑的时候 DataStore 还用不上（见 applyPendingRestore 的注释）
			settingsStore.setRestorePending(true)
			staged
		}

	/**
	 * 解压 + 校验。跑完只保证"暂存目录里是一份认得的备份"，提交改名和置标记都在外面做。
	 *
	 * 加密备份走的是"先整份解密到缓存、验过每块的 tag 再解压"这条路。
	 * 解密产物刻意不放 staging 里：那个目录整体会被搬进 filesDir，
	 * 多一个临时 zip 就会跟着搬过去，白占一份备份大小的空间。
	 */
	private fun unpackInto(source: Uri, staging: File, password: CharArray?): StagedRestore {
		val zipSize = zipSizeOrZero(source)
		requireRoom(staging, zipSize, extraCopy = password != null && password.isNotEmpty())

		if (password == null || password.isEmpty()) {
			val input = context.contentResolver.openInputStream(source)
				?: error("打不开这个文件，可能已经被移动或者删掉了")
			return unpackStream(BufferedInputStream(input), staging, zipSize)
		}

		val decrypted = File(context.cacheDir, DECRYPTED_TEMP)
		decrypted.delete()
		try {
			context.contentResolver.openInputStream(source)?.use { raw ->
				BackupCrypto.decryptTo(BufferedInputStream(raw), decrypted, password)
			} ?: error("打不开这个文件，可能已经被移动或者删掉了")

			return unpackStream(BufferedInputStream(decrypted.inputStream()), staging, zipSize)
		} finally {
			// 解密出来的是一份完整的明文备份，成功失败都不能留在缓存里
			decrypted.delete()
		}
	}

	private fun unpackStream(input: InputStream, staging: File, zipSize: Long): StagedRestore {
		// 解出来的总量上限。正常备份（数据库 + png）压缩比落在 2~3 倍，20 倍已经远超正常范围；
		// 没有这道闸，一个刻意构造的小 zip 能把整块存储写满，那是设备级的麻烦而不只是本应用的。
		// 拿不到 zip 大小时干脆不设限 —— 猜一个绝对值只会误伤大备份
		val inflateLimit = if (zipSize > 0L) {
			maxOf(zipSize * MAX_INFLATE_RATIO, MIN_INFLATE_ALLOWANCE)
		} else {
			Long.MAX_VALUE
		}

		var manifestText: String? = null
		var fileCount = 0
		var written = 0L

		ZipInputStream(input).use { zip ->
			while (true) {
				val entry = zip.nextEntry ?: break
				if (entry.isDirectory) {
					zip.closeEntry()
					continue
				}
				val name = safeEntryName(entry.name)
				if (name == null) {
					// 白名单外的条目跳过而不是报错：谁往 zip 里多塞了张说明图，
					// 没道理因此让整份备份不能恢复
					Log.w(TAG, "备份里有不认识的条目，跳过：${entry.name}")
					zip.closeEntry()
					continue
				}
				val file = File(staging, name)
				// zip slip 防御。只比对条目名不够牢，落地路径按 canonicalPath 再核一次
				if (!file.canonicalPath.startsWith(staging.canonicalPath + File.separator)) {
					error("备份里有越界的文件路径，为安全起见没有继续：${entry.name}")
				}
				file.parentFile?.mkdirs()
				written += file.outputStream().use { zip.copyTo(it) }
				if (written > inflateLimit) {
					error(
						"这个 zip 解出来的体积远超它本身（已经 ${AttachmentStore.humanSize(written)}），" +
							"不像正常的备份，停下了",
					)
				}
				if (name == MANIFEST_NAME) manifestText = file.readText()
				fileCount++
				zip.closeEntry()
			}
		}

		val manifest = manifestText
			?.let { text -> runCatching { json.decodeFromString(BackupManifest.serializer(), text) }.getOrNull() }
			?: error("这个 zip 里没有能读懂的 manifest.json，不像是 AICP 导出的备份")

		if (manifest.backupVersion !in SUPPORTED_BACKUP_VERSIONS) {
			error(
				"备份格式版本 ${manifest.backupVersion} 超出本版认得的范围" +
					"（${SUPPORTED_BACKUP_VERSIONS.first}~${SUPPORTED_BACKUP_VERSIONS.last}）",
			)
		}
		// 高版本的库有本版没有的表和列，Room 只会在 open 的时候抛 schema 不匹配 ——
		// 而那时候用户的旧数据已经被换掉了，等于两头都没了。所以这一关必须挡在搬文件之前
		if (manifest.dbVersion > CURRENT_DB_VERSION) {
			error(
				"这份备份来自更新版本的 AICP（数据库 v${manifest.dbVersion}），" +
					"当前应用只认到 v$CURRENT_DB_VERSION。先把应用升级再来恢复",
			)
		}
		val stagedDb = File(staging, AicpDatabase.DB_NAME)
		if (!stagedDb.isFile) {
			error("备份里没有数据库文件（${AicpDatabase.DB_NAME}），恢复了也是一片空白")
		}
		verifyDatabaseFile(stagedDb)

		return StagedRestore(manifest = manifest, fileCount = fileCount)
	}

	/**
	 * 直接读库文件头，确认它真是个 SQLite 库、而且版本本应用认得。
	 *
	 * 为什么不信 manifest 里那个 dbVersion：manifest 是 zip 里的一个纯文本文件，谁都能改；
	 * 而这里读的是文件头里的真值。一旦让野文件或者高版本库搬到正式位置，下次启动 Room 一 open 就抛，
	 * 用户连设置页都进不去，等于把应用锁死了 —— 这一关必须过在搬文件之前。
	 *
	 * 用读字节而不是真开一次 SQLite 连接：读头 64 字节零副作用，也不会因为"只读模式打不开 WAL 库"
	 * 这类环境问题把一份好备份误判成坏的。
	 */
	private fun verifyDatabaseFile(dbFile: File) {
		val head = ByteArray(SQLITE_HEADER_BYTES)
		val read = dbFile.inputStream().use { it.read(head) }
		if (read < head.size || String(head, 0, SQLITE_MAGIC.length, Charsets.US_ASCII) != SQLITE_MAGIC) {
			error("备份里的 ${AicpDatabase.DB_NAME} 不是 SQLite 数据库文件，恢复它只会让应用起不来")
		}

		// user_version 躺在文件头偏移 60 的四个字节里（大端），Room 的 schema 版本就存这儿
		val version = ByteBuffer.wrap(head).getInt(USER_VERSION_OFFSET)
		if (version <= 0) {
			error("备份里的数据库还没建过表（版本 $version），恢复它等于把现有数据换成一个空库")
		}
		if (version > CURRENT_DB_VERSION) {
			error(
				"备份里的数据库是 v$version，当前应用只认到 v$CURRENT_DB_VERSION。" +
					"先把应用升级再来恢复，否则恢复完会直接起不来",
			)
		}
	}

	/** 用户改主意了：暂存目录和标记一起撤掉，下次启动就当没这回事 */
	suspend fun cancelStagedRestore() = withContext(ioDispatcher) {
		File(context.filesDir, PENDING_DIR).deleteRecursively()
		File(context.filesDir, STAGING_DIR).deleteRecursively()
		File(context.filesDir, FAILED_DIR).deleteRecursively()
		settingsStore.setRestorePending(false)
	}

	/**
	 * 解压要占多少空间没法精确算（zip 目录里存的是压缩后的大小），按源文件的三倍留余量 ——
	 * 数据库和 png 的压缩比大致落在二到三倍。宁可提前说一句"空间不够"，
	 * 也别写到一半才 No space left on device：那时候暂存目录里已经是半份数据了。
	 * 拿不到大小（有些 provider 不给 SIZE 列）就不拦，让真实写入去报错。
	 */
	private fun requireRoom(staging: File, zipSize: Long, extraCopy: Boolean = false) {
		if (zipSize <= 0L) return

		// 加密备份要先整份解密落盘再解压，那份临时明文也得算进去
		val needed = zipSize * if (extraCopy) 4 else 3
		val usable = allocatableBytes(staging)
		if (usable < needed) {
			error(
				"剩余空间不够解压这份备份：大约需要 ${AttachmentStore.humanSize(needed)}，" +
					"当前可用 ${AttachmentStore.humanSize(usable)}",
			)
		}
	}

	/**
	 * 可用空间。优先问 StorageManager 要"可分配字节"，它把系统随时能清掉的缓存也算进来；
	 * File.usableSpace 只看当下的空闲块，在存储紧张的机器上会偏保守，
	 * 结果是一份其实放得下的备份被我们拦在门外，而用户只能对着"空间不够"发愁。
	 * 拿不到就退回 usableSpace，宁可保守也不能不判断。
	 */
	private fun allocatableBytes(target: File): Long = runCatching {
		val manager = context.getSystemService(StorageManager::class.java)
			?: return@runCatching target.usableSpace
		manager.getAllocatableBytes(manager.getUuidForPath(target))
	}.getOrElse { target.usableSpace }

	/** 拿不到 SIZE 列就返回 0，调用方按"未知"处理，别拿 0 当"空文件" */
	private fun zipSizeOrZero(source: Uri): Long = runCatching {
		context.contentResolver
			.query(source, arrayOf(OpenableColumns.SIZE), null, null, null)
			?.use { cursor ->
				if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else 0L
			} ?: 0L
	}.getOrDefault(0L)

	// ---------------- 确认框要用的数字 ----------------

	/**
	 * 直接走 openHelper 查计数，没往 DAO 里加方法：这四个全表 COUNT 只服务于一句确认文案，
	 * 为它动 DAO 会牵连 Room 的生成代码和迁移测试基线，不划算。
	 * 表名是本文件里的字面量，不存在拼外部输入的注入问题。
	 */
	suspend fun dataSummary(): DataSummary = withContext(ioDispatcher) {
		DataSummary(
			conversations = countOf("conversations"),
			messages = countOf("messages"),
			// 摘要和卡片在用户眼里都是"记住的东西"，分开报数只会让人算不清
			memories = countOf("memory_summaries") + countOf("memory_cards"),
			stickers = countOf("stickers"),
		)
	}

	private fun countOf(table: String): Int =
		database.openHelper.readableDatabase
			.query("SELECT COUNT(*) FROM $table")
			.use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }

	companion object {
		private const val TAG = "AicpBackup"

		/** zip 的布局版本。加了新目录、改了摆放才动它，跟数据库版本是两码事 */
		const val BACKUP_VERSION = 1

		/** 认得的备份格式区间。以后出 v2 就把上界抬到 2，同时保证还能读 v1 */
		private val SUPPORTED_BACKUP_VERSIONS = 1..1

		/**
		 * 当前 Room 库版本。直接读 AicpDatabase 那个顶层常量，
		 * 原先这里自己写死一份，库升到 v6 之后它还留在 5，导出的 manifest 跟着一起撒谎。
		 */
		const val CURRENT_DB_VERSION = AICP_DB_VERSION

		const val MANIFEST_NAME = "manifest.json"
		const val MIME_ZIP = "application/zip"

		/** 解密加密备份时的落脚点。放缓存目录，用完就删 */
		private const val DECRYPTED_TEMP = "restore_decrypted.zip"

		private const val WAL_SUFFIX = "-wal"
		private const val SHM_SUFFIX = "-shm"

		/**
		 * 回滚日志。它不在导出范围内，但可能留在设备上，所以恢复时必须一起挪走：
		 * 换了库文件之后，一个属于旧库的 -journal 会被 SQLite 当成"热日志"去做回滚恢复，
		 * 那是真能把刚恢复进来的库读坏的。
		 */
		private const val JOURNAL_SUFFIX = "-journal"

		private const val PENDING_DIR = "restore_pending"
		private const val STAGING_DIR = "restore_staging"
		private const val OLD_DIR = "restore_backup_old"
		private const val FAILED_DIR = "restore_failed"

		/** 允许的最大膨胀倍数，用来挡 zip bomb。正常备份 2~3 倍，20 倍留得足够宽 */
		private const val MAX_INFLATE_RATIO = 20L

		/** 小备份的膨胀余量下限：几十 KB 的 zip 乘 20 倍才几百 KB，卡那么死会误伤正常的小备份 */
		private const val MIN_INFLATE_ALLOWANCE = 64L * 1024 * 1024

		/** SQLite 文件头的魔数，头 16 字节（含结尾的 \u0000） */
		private const val SQLITE_MAGIC = "SQLite format 3\u0000"

		/** 校验只需要文件头这么多字节：魔数在最前面，user_version 在偏移 60 */
		private const val SQLITE_HEADER_BYTES = 64
		private const val USER_VERSION_OFFSET = 60

		private val ASSET_DIRS = listOf(
			AttachmentStore.DIR_NAME,
			AttachmentStore.STICKER_DIR,
			AttachmentStore.AVATAR_DIR,
		)

		private val DB_ENTRY_NAMES = setOf(
			AicpDatabase.DB_NAME,
			AicpDatabase.DB_NAME + WAL_SUFFIX,
			AicpDatabase.DB_NAME + SHM_SUFFIX,
		)

		/**
		 * 阶段二的结论没地方存：它发生在容器和 DataStore 都还不可用的那一刻。
		 * 进程内一个 @Volatile 字段是最小代价，生命周期跟"这次进程"正好重合，
		 * 设置页起来之后取一次就清掉。
		 */
		@Volatile
		private var startupOutcome: StartupRestoreOutcome? = null

		/** 建议文件名带日期，一眼能认出哪份是哪天的。Locale.US 是为了别在泰历之类的区域里出 2569 年 */
		fun suggestedFileName(now: Long = System.currentTimeMillis()): String =
			"aicp-backup-${SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(now))}.zip"

		private fun timestampText(at: Long): String =
			SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(at))

		fun hasPendingRestore(context: Context): Boolean =
			File(context.filesDir, PENDING_DIR).isDirectory

		/** 取一次就清空：这是"上次冷启动那一瞬间发生的事"，只该跟用户报一遍 */
		fun consumeStartupOutcome(): StartupRestoreOutcome? {
			val outcome = startupOutcome
			startupOutcome = null
			return outcome
		}

		// ---------------- 恢复：阶段二 ----------------

		/**
		 * 把暂存的备份搬到正式位置。
		 *
		 * 调用位置有硬要求：只能在 AicpApplication.onCreate 里、AppContainer 赋值之前，而且必须同步跑。
		 * 扔进协程就没意义了 —— container.database 是 by lazy，谁先碰到它谁就先把库打开了，
		 * 那时候文件还没换，Room 握上的是旧连接。
		 *
		 * 代价是有待恢复标记的那次冷启动会被阻塞几十到几百毫秒（取决于附件总量）。
		 * 这个代价只在用户刚点过"从备份恢复"的那一次发生，换来的是"要么完整恢复、要么原样不动"，
		 * 值。没有标记时这个方法只做一次目录 stat 就返回，等于零成本。
		 *
		 * 整段用 runCatching 兜住：恢复失败可以接受，恢复失败导致应用起不来不能接受。
		 */
		fun applyPendingRestore(context: Context) {
			val pending = File(context.filesDir, PENDING_DIR)
			if (!pending.isDirectory) return

			val startedAt = System.currentTimeMillis()
			startupOutcome = runCatching { swapIn(context, pending) }.fold(
				onSuccess = { moved ->
					val elapsed = System.currentTimeMillis() - startedAt
					Log.i(TAG, "恢复完成：搬了 $moved 项，耗时 $elapsed ms")
					StartupRestoreOutcome.Done(movedCount = moved, elapsedMs = elapsed)
				},
				onFailure = { e ->
					Log.e(TAG, "恢复失败，已尽力回滚", e)
					StartupRestoreOutcome.Failed(e.message ?: e.javaClass.simpleName)
				},
			)
		}

		/**
		 * 真正的文件交换。分两趟走：先把现有数据整体挪进 restore_backup_old，再把备份搬到正式位置。
		 * 每一步 rename 都记进 undo 栈，中间任何一步炸了就倒着走回去，还原成"什么都没发生过"。
		 * 直接删旧数据再搬新的话，搬到一半失败就是两手空空 —— 那是这个功能最不能出现的结局。
		 */
		private fun swapIn(context: Context, pending: File): Int {
			if (!File(pending, AicpDatabase.DB_NAME).isFile) {
				// 正常流程进不来（阶段一校验过），这里挡的是有人手工造了个 restore_pending 目录
				pending.deleteRecursively()
				error("暂存目录里没有数据库文件，已丢弃")
			}

			val plan = restorePlan(context)
			val parkDir = File(context.filesDir, OLD_DIR)
			parkDir.deleteRecursively()
			if (!parkDir.mkdirs()) error("建不出回滚目录 $OLD_DIR")

			val undo = ArrayDeque<Pair<File, File>>()
			var moved = 0
			try {
				plan.forEach { (name, target) ->
					if (!target.exists()) return@forEach
					val parked = File(parkDir, name)
					moveOrThrow(target, parked)
					undo.addLast(parked to target)
				}
				plan.forEach { (name, target) ->
					val staged = File(pending, name)
					if (!staged.exists()) return@forEach
					target.parentFile?.mkdirs()
					moveOrThrow(staged, target)
					undo.addLast(target to staged)
					moved++
				}
			} catch (e: Throwable) {
				rollback(undo)
				// 别留着这份暂存目录让每次冷启动都重试同一个失败：改名留证据，下次启动直接跳过
				val failed = File(context.filesDir, FAILED_DIR)
				failed.deleteRecursively()
				runCatching { pending.renameTo(failed) }
				throw e
			}

			// 新数据已经就位，旧数据和暂存目录都可以清了 —— 留着只是白占用户的空间
			parkDir.deleteRecursively()
			pending.deleteRecursively()
			File(context.filesDir, FAILED_DIR).deleteRecursively()
			return moved
		}

		/**
		 * 倒着把记下来的每一步 rename 还原。
		 *
		 * 还原前先清目标位置：那儿可能躺着一份搬到一半的东西（moveOrThrow 的复制退路不是原子的）。
		 * 这一步删掉的只会是备份内容，用户的原数据此刻还在 restore_backup_old 里。
		 * 回滚本身只能尽力而为 —— 同分区 rename 都失败的话，多抛一次异常也救不回来，
		 * 只能把每一处失败都记进日志，留给下次排查。
		 */
		private fun rollback(undo: ArrayDeque<Pair<File, File>>) {
			while (undo.isNotEmpty()) {
				val (from, to) = undo.removeLast()
				runCatching {
					to.deleteRecursively()
					moveOrThrow(from, to)
				}.onFailure { Log.e(TAG, "回滚 ${to.absolutePath} 失败", it) }
			}
		}

		/**
		 * 待交换清单：zip 里的条目名 → 设备上的正式位置。
		 * 两趟搬运共用同一份清单，就不会出现"挪走了三个目录，只搬回来两个"这种对不上的情况。
		 */
		private fun restorePlan(context: Context): List<Pair<String, File>> {
			val dbFile = context.getDatabasePath(AicpDatabase.DB_NAME)
			val journal = File(dbFile.path + JOURNAL_SUFFIX)
			return dbFiles(dbFile).map { it.name to it } +
				(journal.name to journal) +
				ASSET_DIRS.map { it to File(context.filesDir, it) }
		}

		/** 主库 + WAL 两兄弟。导出和恢复都按这一份清单来，少一个就是拷了份不自洽的库 */
		private fun dbFiles(dbFile: File): List<File> = listOf(
			dbFile,
			File(dbFile.path + WAL_SUFFIX),
			File(dbFile.path + SHM_SUFFIX),
		)

		/**
		 * 条目名白名单。返回 null 表示"这条不认识，跳过"。
		 *
		 * 收得很紧是故意的：只放 manifest、三个库文件，以及三个资源目录下的一层文件。
		 * 目录里再套目录也不收 —— AttachmentStore 落盘一向是平铺的，
		 * 出现嵌套只可能是别人手工改过 zip，那就更没理由信它。
		 */
		private fun safeEntryName(raw: String): String? {
			val name = raw.replace('\\', '/').trimStart('/')
			if (name.isEmpty() || name.contains("..")) return null
			if (name == MANIFEST_NAME || name in DB_ENTRY_NAMES) return name

			val dir = name.substringBefore('/')
			val leaf = name.substringAfter('/', "")
			if (dir in ASSET_DIRS && leaf.isNotEmpty() && !leaf.contains('/')) return name
			return null
		}

		/**
		 * filesDir 和 databases/ 在同一个分区，rename 正常都能成，也正是它的原子性让回滚有意义。
		 * 复制那条退路是留给个别机型（目录被特殊挂载、SELinux 拦住）的：不原子，但外层回滚兜得住。
		 */
		private fun moveOrThrow(from: File, to: File) {
			if (from.renameTo(to)) return

			if (from.isDirectory) {
				from.copyRecursively(to, overwrite = true)
			} else {
				from.copyTo(to, overwrite = true)
			}
			if (!from.deleteRecursively()) Log.w(TAG, "复制搬迁后没删掉原件：${from.absolutePath}")
			if (!to.exists()) error("搬迁失败：${from.name}")
		}
	}
}

