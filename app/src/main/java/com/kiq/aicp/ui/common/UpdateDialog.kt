/*
 * app/src/main/java/com/kiq/aicp/ui/common/UpdateDialog.kt
 * 发现新版本时的提示框
 * 职责：
 * - 显示新版本号、发布日期、更新说明（纯文本、可滚动）
 * - 「去下载」把地址交给系统浏览器/下载器，「以后再说」直接关掉
 *
 * 更新说明按纯文本显示：release body 是 markdown，要渲染就得引一个库，
 * 而这里的说明本来就是几行短句，纯文本读起来没差别。
 * 但必须能滚——说明一长，在小屏上会把两个按钮顶出可视区，那就成了一个关不掉的框。
 *
 * 为什么抽在 ui/common：启动时的自动提示和设置页里手点检查都要用它，
 * 各写一份必然演化成两个长得不一样的框。
 *
 * 为什么按钮只是打开链接：
 * 自己下载安装要 REQUEST_INSTALL_PACKAGES 权限和 FileProvider，
 * 一年用不到几次，还会让 APK 更容易被安全软件标风险——交给系统去开就够了。
 */
package com.kiq.aicp.ui.common

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.kiq.aicp.BuildConfig
import com.kiq.aicp.data.remote.UpdateInfo
import com.kiq.aicp.ui.theme.Dimens

/**
 * 更新说明的最大高度。
 * 不限高的话，一篇长说明会把对话框撑到超出屏幕，「以后再说」被挤出去就没法关了。
 */
private val NotesMaxHeight = 240.dp

/** ISO-8601 前 10 位就是日期（2026-08-25T10:00:00Z） */
private const val DATE_LENGTH = 10

/**
 * @param info 检查出来的新版本
 * @param onDismiss 关掉这个框。点「去下载」之后也会调它——浏览器已经在前台了，
 * 留着一个框在后面等他回来只是碍事
 * @param currentVersion 本地版本，默认取 BuildConfig，预览和测试可以自己给
 */
@Composable
fun UpdateDialog(
	info: UpdateInfo,
	onDismiss: () -> Unit,
	modifier: Modifier = Modifier,
	currentVersion: String = BuildConfig.VERSION_NAME,
) {
	val context = LocalContext.current
	val clipboard = LocalClipboardManager.current

	AlertDialog(
		modifier = modifier,
		onDismissRequest = onDismiss,
		title = { Text("有新版本 ${info.tag}") },
		text = {
			Column(
				modifier = Modifier
					.fillMaxWidth()
					.heightIn(max = NotesMaxHeight)
					.verticalScroll(rememberScrollState()),
				verticalArrangement = Arrangement.spacedBy(Dimens.spaceSm),
			) {
				Text(
					text = versionLine(info, currentVersion),
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.outline,
				)

				// 标题跟 tag 一样时不重复显示一遍，标题栏已经写了
				if (info.title.isNotBlank() && info.title != info.tag) {
					Text(text = info.title, style = MaterialTheme.typography.titleSmall)
				}

				Text(
					text = info.notes.ifBlank { "这次没写更新说明。" },
					style = MaterialTheme.typography.bodyMedium,
				)

				if (info.apkUrl == null) {
					Text(
						text = "这一版没挂安装包，按钮会打开发布页面，去那里手动下载。",
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.outline,
					)
				}
			}
		},
		confirmButton = {
			TextButton(
				onClick = {
					openLink(context, clipboard, info.downloadUrl)
					onDismiss()
				},
			) { Text("去下载") }
		},
		dismissButton = {
			TextButton(onClick = onDismiss) { Text("以后再说") }
		},
	)
}

/** "当前 0.4.0 → 新版 v0.5.0 ・ 2026-08-25 发布"，发布时间认不出来时就只留前半句 */
private fun versionLine(info: UpdateInfo, currentVersion: String): String {
	val head = "当前 $currentVersion → 新版 ${info.tag}"
	val date = info.publishedAt.take(DATE_LENGTH)
	val looksLikeDate = date.length == DATE_LENGTH && date[4] == '-' && date[7] == '-'
	return if (looksLikeDate) "$head ・ $date 发布" else head
}

/**
 * 把地址交给系统。
 *
 * catch 不是凑数的防御：精简系统、浏览器被停用的机器上真的会一个 http 处理者都没有，
 * 那时候崩掉比打不开难看得多。退一步把地址塞进剪贴板，他还能自己找地方粘。
 *
 * 为什么是 try 而不是先 resolveActivity 判一下：
 * Android 11 起有包可见性限制，不在清单里写 <queries> 的话 resolveActivity 一律返回 null，
 * 于是"能开的也被判成不能开"。直接发出去再兜住异常反而是准的，也不用为此改清单。
 *
 * FLAG_ACTIVITY_NEW_TASK 是为了在非 Activity 的 Context 下也能起——
 * 这个组件将来可能被挂在别的地方，不指望调用方一定给的是 Activity。
 *
 * 用 LocalClipboardManager 而不是新的 LocalClipboard：项目里 ChatScreen 和设置页
 * 都在用它，三处保持同一个 API 才好一起换。
 */
private fun openLink(context: Context, clipboard: ClipboardManager, url: String) {
	val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
		.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
	try {
		context.startActivity(intent)
	} catch (_: ActivityNotFoundException) {
		clipboard.setText(AnnotatedString(url))
		Toast.makeText(context, "没有能打开链接的应用，下载地址已复制", Toast.LENGTH_LONG).show()
	}
}
