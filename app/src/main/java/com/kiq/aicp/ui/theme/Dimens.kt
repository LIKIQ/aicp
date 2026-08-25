// app/src/main/java/com/kiq/aicp/ui/theme/Dimens.kt
// 全局尺寸标尺。
//
// 为什么要有这个文件：之前每个页面各写各的 padding，12dp、14dp、16dp 混着来，
// 单看每页都正常，连起来滑就会觉得"哪里不对劲但说不出来"——不对劲的就是节奏不齐。
//
// 间距只留五档，刻意不给更多选择：档位一多，写代码时就会开始纠结"这里用 10 还是 11"，
// 而这两个值的差别用户根本看不出来，只会让整体更乱。
//
// 圆角按元素类型分而不是按大小分：卡片、气泡、按钮各有各的既定值，
// 换主题时改这里一处就能全局跟着变。

package com.kiq.aicp.ui.theme

import androidx.compose.ui.unit.dp

object Dimens {

	// ---------------- 间距 ----------------

	/** 紧贴元素之间，比如图标和它的文字 */
	val spaceXs = 4.dp

	/** 同组元素之间 */
	val spaceSm = 8.dp

	/** 卡片内部的行间距 */
	val spaceMd = 12.dp

	/** 卡片内边距、屏幕左右边距 */
	val spaceLg = 16.dp

	/** 分区之间的呼吸空间 */
	val spaceXl = 24.dp

	// ---------------- 圆角 ----------------

	val radiusSmall = 8.dp
	val radiusCard = 16.dp

	/** 气泡比卡片略圆一点，看着更"软"，符合聊天的语境 */
	val radiusBubble = 18.dp

	/** 胶囊形按钮和输入框 */
	val radiusPill = 20.dp

	// ---------------- 头像 ----------------

	/** 会话列表、性格列表 */
	val avatarList = 44.dp

	/** 消息气泡旁 */
	val avatarBubble = 36.dp

	/** 顶栏 */
	val avatarTopBar = 36.dp

	/** 编辑页和资料卡里的大头像 */
	val avatarLarge = 72.dp

	// ---------------- 其他 ----------------

	/** 可点列表项的最小高度，低于这个值手指点不准（Material 的建议是 48） */
	val touchTargetMin = 48.dp

	/** 屏幕内容区左右边距 */
	val screenPadding = 16.dp

	/** 气泡最大宽度。太宽的话一行字数多，读起来要来回扫视 */
	val bubbleMaxWidth = 300.dp
}
