// app/src/main/java/com/kiq/aicp/data/db/AicpMigrations.kt
// 数据库迁移。
//
// 为什么不用 fallbackToDestructiveMigration：KIQ 已经装了 v1 的包并聊过，
// 销毁式迁移会把他的会话、记忆卡片全部清空。这个应用的卖点就是"记忆存在本地"，
// 一次升级把记忆清光比少一个功能严重得多。
//
// 写迁移 SQL 时的两个硬要求（踩过就知道）：
// 1. ADD COLUMN 的 DEFAULT 必须和实体上的 @ColumnInfo(defaultValue=...) 逐字对齐，
//    否则 Room 打开库时的 schema 校验会抛 IllegalStateException，表现是"升级后一启动就崩"
// 2. 新建表的列定义要跟 Room 自己生成的 CREATE TABLE 一致（含 NOT NULL、外键动作），
//    这一点靠 MigrationTest 里的 validateMigration 兜底，不靠肉眼

package com.kiq.aicp.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object AicpMigrations {

	/**
	 * v1 → v2
	 * - 新增 message_attachments：图片与文件附件
	 * - conversation_personas 增加 mood / moodUpdatedAt：会话级心情
	 * - personas 增加 proactiveEnabled：是否允许主动搭话
	 */
	val MIGRATION_1_2 = object : Migration(1, 2) {
		override fun migrate(db: SupportSQLiteDatabase) {
			db.execSQL(
				"""
				CREATE TABLE IF NOT EXISTS `message_attachments` (
					`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
					`messageId` INTEGER NOT NULL,
					`kind` TEXT NOT NULL,
					`localPath` TEXT NOT NULL,
					`mimeType` TEXT NOT NULL,
					`fileName` TEXT NOT NULL,
					`byteSize` INTEGER NOT NULL,
					`width` INTEGER NOT NULL,
					`height` INTEGER NOT NULL,
					`extractedText` TEXT,
					`truncated` INTEGER NOT NULL,
					`textHeavy` INTEGER NOT NULL,
					`createdAt` INTEGER NOT NULL,
					FOREIGN KEY(`messageId`) REFERENCES `messages`(`id`)
						ON UPDATE NO ACTION ON DELETE CASCADE
				)
				""".trimIndent(),
			)
			db.execSQL(
				"CREATE INDEX IF NOT EXISTS `index_message_attachments_messageId` " +
					"ON `message_attachments` (`messageId`)",
			)

			db.execSQL(
				"ALTER TABLE `conversation_personas` ADD COLUMN `mood` INTEGER NOT NULL DEFAULT 0",
			)
			db.execSQL(
				"ALTER TABLE `conversation_personas` ADD COLUMN `moodUpdatedAt` INTEGER NOT NULL DEFAULT 0",
			)
			db.execSQL(
				"ALTER TABLE `personas` ADD COLUMN `proactiveEnabled` INTEGER NOT NULL DEFAULT 0",
			)
		}
	}

	/**
	 * v2 → v3
	 * - 新增 sticker_packs / stickers：用户导入的表情包与分组
	 *
	 * 两个唯一索引都是功能性的，不是洁癖：
	 * pack.name 唯一是因为管理界面靠名字区分组；sticker.label 唯一是因为
	 * 模型回复里的 [开心] 是个裸标记，重复了就无法定位到具体哪张图。
	 */
	val MIGRATION_2_3 = object : Migration(2, 3) {
		override fun migrate(db: SupportSQLiteDatabase) {
			db.execSQL(
				"""
				CREATE TABLE IF NOT EXISTS `sticker_packs` (
					`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
					`name` TEXT NOT NULL,
					`sortOrder` INTEGER NOT NULL,
					`createdAt` INTEGER NOT NULL
				)
				""".trimIndent(),
			)
			db.execSQL(
				"CREATE UNIQUE INDEX IF NOT EXISTS `index_sticker_packs_name` ON `sticker_packs` (`name`)",
			)

			db.execSQL(
				"""
				CREATE TABLE IF NOT EXISTS `stickers` (
					`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
					`packId` INTEGER NOT NULL,
					`label` TEXT NOT NULL,
					`localPath` TEXT NOT NULL,
					`mimeType` TEXT NOT NULL,
					`byteSize` INTEGER NOT NULL,
					`width` INTEGER NOT NULL,
					`height` INTEGER NOT NULL,
					`useCount` INTEGER NOT NULL,
					`createdAt` INTEGER NOT NULL,
					FOREIGN KEY(`packId`) REFERENCES `sticker_packs`(`id`)
						ON UPDATE NO ACTION ON DELETE CASCADE
				)
				""".trimIndent(),
			)
			db.execSQL("CREATE INDEX IF NOT EXISTS `index_stickers_packId` ON `stickers` (`packId`)")
			db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_stickers_label` ON `stickers` (`label`)")
		}
	}

	/**
	 * v3 → v4
	 * - 新增 proactive_logs：记录主动搭话的日期与次数
	 *
	 * 没有外键：表里有一行 personaId = 0 的全局配额记录，挂外键就写不进去。
	 * 这一点由 MigrationV3ToV4Test 实测确认过，不是想省事。
	 */
	val MIGRATION_3_4 = object : Migration(3, 4) {
		override fun migrate(db: SupportSQLiteDatabase) {
			db.execSQL(
				"""
				CREATE TABLE IF NOT EXISTS `proactive_logs` (
					`personaId` INTEGER NOT NULL,
					`date` TEXT NOT NULL,
					`count` INTEGER NOT NULL,
					PRIMARY KEY(`personaId`)
				)
				""".trimIndent(),
			)
		}
	}

	/**
	 * v4 → v5
	 * - personas 增加 avatarPath / note：图片头像与只给用户看的备注
	 * - conversations 增加 avatarEmoji / avatarPath：群聊自己的头像
	 *
	 * TEXT 可空列的 DEFAULT NULL 要和实体上 @ColumnInfo(defaultValue = "NULL") 逐字对齐，
	 * 非空 TEXT 则要 DEFAULT ''（两个单引号）对齐 defaultValue = "''"。
	 * 这两处写歪了 Room 打开库时会直接抛 schema mismatch，表现是"升级后一启动就崩"。
	 */
	val MIGRATION_4_5 = object : Migration(4, 5) {
		override fun migrate(db: SupportSQLiteDatabase) {
			db.execSQL("ALTER TABLE `personas` ADD COLUMN `avatarPath` TEXT DEFAULT NULL")
			db.execSQL("ALTER TABLE `personas` ADD COLUMN `note` TEXT NOT NULL DEFAULT ''")
			db.execSQL("ALTER TABLE `conversations` ADD COLUMN `avatarEmoji` TEXT NOT NULL DEFAULT ''")
			db.execSQL("ALTER TABLE `conversations` ADD COLUMN `avatarPath` TEXT DEFAULT NULL")
		}
	}

	/**
	 * v5 → v6：记忆升级成 wiki 条目结构
	 * - 新增 memory_entries：条目化的记忆（标题 + 别名 + 一行摘要 + 正文）
	 * - 新增 memory_logs：记忆操作的时间线
	 * - **旧的 memory_cards 保留不动**，数据一对一复制进 memory_entries
	 *
	 * 为什么不直接改造 memory_cards：那要重建表加列再搬数据，中途失败就是半残状态。
	 * 新建表 + 复制的代价是多占一份空间（记忆卡片本来就小，几百条也就几十 KB），
	 * 换来的是"新结构出问题时原始记忆还完整躺在旧表里"。
	 * 等新结构跑稳了再单独一次迁移清掉旧表。
	 *
	 * 复制时的字段映射：keyword → title（条目名），content 同时进 oneLiner 和 body ——
	 * 旧卡片内容本来就只有 60 字上限，既够当摘要也够当正文，后续对话会把正文养厚。
	 */
	val MIGRATION_5_6 = object : Migration(5, 6) {
		override fun migrate(db: SupportSQLiteDatabase) {
			db.execSQL(
				"""
				CREATE TABLE IF NOT EXISTS `memory_entries` (
					`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
					`conversationId` INTEGER,
					`personaId` INTEGER,
					`scopeKey` TEXT NOT NULL,
					`category` TEXT NOT NULL,
					`title` TEXT NOT NULL,
					`aliases` TEXT NOT NULL,
					`oneLiner` TEXT NOT NULL,
					`body` TEXT NOT NULL,
					`importance` INTEGER NOT NULL,
					`hitCount` INTEGER NOT NULL,
					`lastHitAt` INTEGER NOT NULL,
					`pinned` INTEGER NOT NULL,
					`sourceCount` INTEGER NOT NULL,
					`conflictNote` TEXT,
					`createdAt` INTEGER NOT NULL,
					`updatedAt` INTEGER NOT NULL,
					FOREIGN KEY(`conversationId`) REFERENCES `conversations`(`id`)
						ON UPDATE NO ACTION ON DELETE CASCADE,
					FOREIGN KEY(`personaId`) REFERENCES `personas`(`id`)
						ON UPDATE NO ACTION ON DELETE CASCADE
				)
				""".trimIndent(),
			)
			db.execSQL(
				"CREATE UNIQUE INDEX IF NOT EXISTS `index_memory_entries_scopeKey_category_title` " +
					"ON `memory_entries` (`scopeKey`, `category`, `title`)",
			)
			db.execSQL(
				"CREATE INDEX IF NOT EXISTS `index_memory_entries_conversationId` " +
					"ON `memory_entries` (`conversationId`)",
			)
			db.execSQL(
				"CREATE INDEX IF NOT EXISTS `index_memory_entries_personaId` " +
					"ON `memory_entries` (`personaId`)",
			)

			db.execSQL(
				"""
				CREATE TABLE IF NOT EXISTS `memory_logs` (
					`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
					`conversationId` INTEGER,
					`kind` TEXT NOT NULL,
					`summary` TEXT NOT NULL,
					`touchedTitles` TEXT NOT NULL,
					`createdAt` INTEGER NOT NULL
				)
				""".trimIndent(),
			)
			db.execSQL(
				"CREATE INDEX IF NOT EXISTS `index_memory_logs_createdAt` ON `memory_logs` (`createdAt`)",
			)
			db.execSQL(
				"CREATE INDEX IF NOT EXISTS `index_memory_logs_conversationId` " +
					"ON `memory_logs` (`conversationId`)",
			)

			// 旧卡片一对一搬过来。同一 (scopeKey, type, keyword) 在旧表里本来就唯一，
			// 所以不会撞新表的唯一索引
			db.execSQL(
				"""
				INSERT INTO `memory_entries` (
					conversationId, personaId, scopeKey, category, title, aliases,
					oneLiner, body, importance, hitCount, lastHitAt, pinned,
					sourceCount, conflictNote, createdAt, updatedAt
				)
				SELECT
					conversationId, personaId, scopeKey, type, keyword, '',
					content, content, importance, hitCount, lastHitAt, pinned,
					1, NULL, createdAt, updatedAt
				FROM `memory_cards`
				""".trimIndent(),
			)
		}
	}

	/**
	 * v7：表情多一列 emotion。
	 *
	 * 加列而不是新建表：stickers 里存的是用户自己导入的图，
	 * 重建表要搬数据、还要处理外键，为了一列不值得冒那个风险。
	 * 已有表情的 emotion 一律是空串，表示"还没识别"——
	 * 分组名本身是情绪的那些组根本不需要识别，选取时走组名那条路。
	 */
	val MIGRATION_6_7 = object : Migration(6, 7) {
		override fun migrate(db: SupportSQLiteDatabase) {
			db.execSQL("ALTER TABLE `stickers` ADD COLUMN `emotion` TEXT NOT NULL DEFAULT ''")
		}
	}

	val ALL: Array<Migration> = arrayOf(
		MIGRATION_1_2,
		MIGRATION_2_3,
		MIGRATION_3_4,
		MIGRATION_4_5,
		MIGRATION_5_6,
		MIGRATION_6_7,
	)
}
