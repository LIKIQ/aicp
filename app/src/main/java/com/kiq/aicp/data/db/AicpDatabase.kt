// app/src/main/java/com/kiq/aicp/data/db/AicpDatabase.kt
// Room 数据库定义。
//
// 十张表分六层：
//   personas / conversations / conversation_personas —— 配置与关系
//   messages / message_attachments                   —— L0 原文与附件，永不因压缩而删除
//   memory_summaries / memory_cards                   —— L1/L2 摘要 与 L3 记忆卡片
//   sticker_packs / stickers                          —— 用户导入的表情包
//   proactive_logs                                   —— 主动搭话的调用记录
//
// 外键的 CASCADE 是靠 SQLite 的 foreign_keys pragma 生效的，Room 在打开连接时会自己打开这个开关，
// 所以删会话能连带清掉它的消息、附件、摘要、卡片和参与者关联，不用在代码里手动级联删。
// 唯一管不到的是附件的磁盘文件 —— 那部分由 AttachmentStore 负责，见它的注释。

package com.kiq.aicp.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.kiq.aicp.data.db.converter.Converters
import com.kiq.aicp.data.db.dao.AttachmentDao
import com.kiq.aicp.data.db.dao.ConversationDao
import com.kiq.aicp.data.db.dao.MemoryDao
import com.kiq.aicp.data.db.dao.MessageDao
import com.kiq.aicp.data.db.dao.PersonaDao
import com.kiq.aicp.data.db.dao.ProactiveLogDao
import com.kiq.aicp.data.db.dao.StickerDao
import com.kiq.aicp.data.db.entity.ConversationEntity
import com.kiq.aicp.data.db.entity.ConversationPersonaCrossRef
import com.kiq.aicp.data.db.entity.MemoryCardEntity
import com.kiq.aicp.data.db.entity.MemorySummaryEntity
import com.kiq.aicp.data.db.entity.MessageAttachmentEntity
import com.kiq.aicp.data.db.entity.MessageEntity
import com.kiq.aicp.data.db.entity.PersonaEntity
import com.kiq.aicp.data.db.entity.ProactiveLogEntity
import com.kiq.aicp.data.db.entity.StickerEntity
import com.kiq.aicp.data.db.entity.StickerPackEntity

@Database(
	entities = [
		PersonaEntity::class,
		ConversationEntity::class,
		ConversationPersonaCrossRef::class,
		MessageEntity::class,
		MessageAttachmentEntity::class,
		MemorySummaryEntity::class,
		MemoryCardEntity::class,
		StickerPackEntity::class,
		StickerEntity::class,
		ProactiveLogEntity::class,
	],
	version = 5,
	exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AicpDatabase : RoomDatabase() {

	abstract fun personaDao(): PersonaDao

	abstract fun conversationDao(): ConversationDao

	abstract fun messageDao(): MessageDao

	abstract fun attachmentDao(): AttachmentDao

	abstract fun memoryDao(): MemoryDao

	abstract fun stickerDao(): StickerDao

	abstract fun proactiveLogDao(): ProactiveLogDao

	companion object {
		const val DB_NAME = "aicp.db"

		fun build(context: Context): AicpDatabase =
			Room.databaseBuilder(context, AicpDatabase::class.java, DB_NAME)
				// 聊天场景是"写得碎、读得频"，WAL 能让流式写入不阻塞列表查询
				.setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
				.addMigrations(*AicpMigrations.ALL)
				.build()

		/** 单测用：进程内内存库，进程退出即消失 */
		fun buildInMemory(context: Context): AicpDatabase =
			Room.inMemoryDatabaseBuilder(context, AicpDatabase::class.java)
				.allowMainThreadQueries()
				.addMigrations(*AicpMigrations.ALL)
				.build()
	}
}
