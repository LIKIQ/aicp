// app/src/main/java/com/kiq/aicp/data/attach/BuiltInStickers.kt
// 内置表情包：首次启动时把 assets/stickers/ 下的图自动导入成一个分组。
//
// 为什么走 assets 而不是把图片编进 drawable：
// 表情包在数据模型里是"用户导入的文件"，localPath 指向 filesDir。
// 编进 drawable 就得给 StickerEntity 再开一条"资源 id"的分支，
// 渲染、删除、孤儿清理每一处都要多一个 if。从 assets 拷进 filesDir 之后，
// 内置表情跟用户自己导的走完全同一条路径，后续代码一行都不用分情况。
//
// 代价是首次启动多一次拷贝（几百 KB 量级，在 IO 线程做，用户感知不到），
// 以及 APK 里那份 assets 是死重量。换来的是零特殊分支。
//
// 用户删掉内置表情后不会重新灌回来：靠 SettingsStore 里的一个"已灌过"标记，
// 而不是"检查表情是否存在"。不然用户每次删完重启就又冒出来，那是 bug 不是功能。

package com.kiq.aicp.data.attach

import android.content.Context
import android.util.Log
import com.kiq.aicp.data.repo.StickerRepository
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class BuiltInStickers(
	private val context: Context,
	private val stickerRepository: StickerRepository,
	private val attachmentStore: AttachmentStore,
) {

	/**
	 * @return 实际导入的张数。0 表示 assets 里没有素材，或者已经灌过了
	 */
	suspend fun importIfNeeded(): Int = withContext(Dispatchers.IO) {
		val names = runCatching { context.assets.list(ASSET_DIR) }.getOrNull()?.toList().orEmpty()
		if (names.isEmpty()) return@withContext 0

		val packId = stickerRepository.ensurePack(PACK_NAME)
		var imported = 0

		names.filter { it.isImageName() }.forEach { name ->
			runCatching { copyOne(packId, name) }
				.onSuccess { imported++ }
				.onFailure { Log.w(TAG, "内置表情 $name 导入失败", it) }
		}
		imported
	}

	/**
	 * assets 里的文件没有 content:// URI，AttachmentStore.saveSticker 那条路走不通，
	 * 所以先落到一个临时文件，再交给 store 走正常的压缩与落盘流程 ——
	 * 这样内置表情和用户导入的表情在磁盘上的形态完全一致。
	 */
	private suspend fun copyOne(packId: Long, assetName: String) {
		val temp = File(context.cacheDir, "builtin_$assetName")
		try {
			context.assets.open("$ASSET_DIR/$assetName").use { input ->
				temp.outputStream().use { output -> input.copyTo(output) }
			}
			stickerRepository.importFromFile(
				packId = packId,
				file = temp,
				displayName = assetName,
				desiredLabel = assetName.substringBeforeLast('.'),
			)
		} finally {
			temp.delete()
		}
	}

	private fun String.isImageName(): Boolean {
		val ext = substringAfterLast('.', "").lowercase()
		return ext in setOf("png", "jpg", "jpeg", "webp", "gif")
	}

	companion object {
		private const val TAG = "BuiltInStickers"

		/** APK 里放素材的目录：app/src/main/assets/stickers/ */
		const val ASSET_DIR = "stickers"

		/** 内置表情落进哪个分组。用户可以改名、可以删，删了不会自动回来 */
		const val PACK_NAME = "默认表情"
	}
}
