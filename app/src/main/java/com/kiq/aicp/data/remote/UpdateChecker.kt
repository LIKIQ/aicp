/*
 * app/src/main/java/com/kiq/aicp/data/remote/UpdateChecker.kt
 * 应用内版本检测：问 GitHub 的 Release 接口有没有新版本
 * 职责：
 * - 走 gh-proxy 代理请求 releases/latest，取出 tag、标题、更新说明、发布时间、apk 直链
 * - 跟本地 versionName 比大小，产出"有新版本 / 已是最新 / 检查失败"
 * - 自动检查按 24 小时节流，用户手点的检查无视节流
 *
 * 为什么两处地址都套代理前缀：
 * 国内直连 api.github.com 和 release 附件的 CDN 大概率不通，不套的话这个功能
 * 在 KIQ 和他朋友的手机上等于不存在。代理的用法是"前缀 + 完整原始 URL"，
 * 对 api.github.com 和 release 下载都放行（查证过），所以接口和下载地址拼的是同一个前缀。
 *
 * 为什么自动检查失败必须静默：
 * 版本提示是锦上添花，没网、代理挂了、被限流都跟用户当下要做的事无关，
 * 拿这些去弹错误框是用自己的失败打扰他。手动检查是另一回事——
 * 他在等一个答复，所以失败原因原样带回给调用方，由它决定怎么显示。
 *
 * 为什么只给地址、不自己下载安装：
 * 自己装包要 REQUEST_INSTALL_PACKAGES 权限加 FileProvider，一年用不到几次，
 * 还会让 APK 更容易被安全软件标成风险。交给系统浏览器/下载器就够了。
 */
package com.kiq.aicp.data.remote

import com.kiq.aicp.BuildConfig
import com.kiq.aicp.domain.update.VersionCompare
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

// ---- 线上格式。GitHub 一个 release 能返回几十个字段，只声明用得上的那几个 ----

@Serializable
private data class GhAsset(
	val name: String = "",
	@SerialName("browser_download_url") val browserDownloadUrl: String = "",
)

@Serializable
private data class GhRelease(
	@SerialName("tag_name") val tagName: String = "",
	val name: String? = null,
	val body: String? = null,
	@SerialName("published_at") val publishedAt: String? = null,
	@SerialName("html_url") val htmlUrl: String? = null,
	val assets: List<GhAsset> = emptyList(),
)

/** 一个可以去装的新版本 */
data class UpdateInfo(
	/** tag 原样，形如 v0.5.0。展示用，比较交给 VersionCompare */
	val tag: String,
	/** release 标题；GitHub 上留空时回落成 tag */
	val title: String,
	/** 更新说明正文。可能是 markdown，这一层不渲染也不裁剪 */
	val notes: String,
	/** ISO-8601 的发布时刻，原样带出，怎么显示由 UI 定 */
	val publishedAt: String,
	/** 套过代理前缀的 apk 直链。release 里没挂 apk 时为 null */
	val apkUrl: String?,
	/** release 页面地址，不套代理——代理是给文件下载用的，HTML 页面交给浏览器直连 */
	val releaseUrl: String,
) {
	/**
	 * 「去下载」实际要打开的地址。
	 * 没有 apk 直链时把人送到 release 页面，而不是把按钮灰掉：
	 * 页面上一定能找到东西，灰按钮只会让他不知道该干什么。
	 */
	val downloadUrl: String
		get() = apkUrl ?: releaseUrl
}

sealed interface UpdateResult {

	data class Available(val info: UpdateInfo) : UpdateResult

	/**
	 * 已经是最新的。latestTag 带出来是给手动检查用的——
	 * 只说"已是最新"，用户没法判断到底是查过了还是没查动。
	 */
	data class UpToDate(val currentVersion: String, val latestTag: String) : UpdateResult

	/** 检查没成。自动检查时调用方应当丢掉它，手动检查时才显示 reason */
	data class Failed(val reason: String, val retryable: Boolean) : UpdateResult

	/**
	 * 节流拦下了，这次根本没发请求。
	 * 必须跟 UpToDate 分开：手动检查要是拿到 UpToDate 就会显示"已是最新"，
	 * 而实际情况是它压根没问过 GitHub，那句话就是假的。
	 */
	data object Skipped : UpdateResult
}

/**
 * @param lastCheckAt 读"上次检查时刻"，0 表示从没查过
 * @param markChecked 记下这次检查时刻
 * @param currentVersion 本地版本，默认取 BuildConfig；做成参数是为了单测能造"本地更新"的场景
 * @param proxyPrefix 代理前缀，留空表示直连；单测把它换成 MockWebServer 的地址
 *
 * lastCheckAt / markChecked 用函数注入而不是直接吃 SettingsStore：
 * 照 OpenAiCompatProvider 的 configLoader 那个路子，这样单测不用为了两个数字去搭 DataStore。
 */
class UpdateChecker(
	baseClient: OkHttpClient,
	private val lastCheckAt: suspend () -> Long,
	private val markChecked: suspend (Long) -> Unit,
	private val currentVersion: String = BuildConfig.VERSION_NAME,
	private val proxyPrefix: String = GH_PROXY_PREFIX,
	private val repo: String = REPO,
	private val clock: () -> Long = System::currentTimeMillis,
	private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

	private val json = Json {
		ignoreUnknownKeys = true
		isLenient = true
	}

	/** 读超时压到 15 秒：要的就是个几 KB 的 JSON，代理不通的话早点失败早点静默 */
	private val client = baseClient.newBuilder()
		.readTimeout(15, TimeUnit.SECONDS)
		.build()

	/** @param manual 用户手点的检查，无视节流 */
	suspend fun check(manual: Boolean = false): UpdateResult = withContext(ioDispatcher) {
		val now = clock()
		if (!manual && isThrottled(now)) return@withContext UpdateResult.Skipped

		val request = Request.Builder()
			.url(proxied(latestReleaseApi()))
			.addHeader("Accept", "application/vnd.github+json")
			.build()

		val response = try {
			client.newCall(request).execute()
		} catch (e: IOException) {
			return@withContext UpdateResult.Failed(
				reason = "连不上更新服务器：${e.message ?: "网络异常"}",
				retryable = true,
			)
		}

		response.use { resp ->
			toResult(resp.code, runCatching { resp.body.string() }.getOrDefault(""), now)
		}
	}

	// ---------------- 内部 ----------------

	/**
	 * 距上次检查还没到 24 小时就跳过。
	 * 两个刻意的例外：从没查过（0）一定要查；时间戳比现在还晚（用户把系统时间往前调过）
	 * 也当过期，否则那台机器会永远停在"刚查过"。
	 */
	private suspend fun isThrottled(now: Long): Boolean {
		val last = lastCheckAt()
		if (last <= 0L) return false
		val elapsed = now - last
		return elapsed in 0 until CHECK_INTERVAL_MS
	}

	private fun latestReleaseApi(): String = "https://api.github.com/repos/$repo/releases/latest"

	/** 代理的用法就是拼在完整原始 URL 前面，所以这里只做拼接，不动原地址的任何一段 */
	private fun proxied(rawUrl: String): String =
		if (proxyPrefix.isBlank()) rawUrl else "${proxyPrefix.trimEnd('/')}/$rawUrl"

	private suspend fun toResult(code: Int, body: String, now: Long): UpdateResult {
		if (code !in 200..299) {
			return UpdateResult.Failed(
				reason = httpReason(code),
				retryable = code == 403 || code == 429 || code >= 500,
			)
		}

		val release = runCatching { json.decodeFromString<GhRelease>(body) }.getOrNull()
			?: return UpdateResult.Failed("更新信息读不出来，等下再试", retryable = false)

		// 能解析但没有 tag：这不是"已是最新"，是响应不对劲，别拿它去骗用户
		if (release.tagName.isBlank()) {
			return UpdateResult.Failed("更新信息里没有版本号", retryable = false)
		}

		// 只有真的拿到一份能读懂的答复才算查过。失败不占额度——
		// 节流是为了"别反复问同一个答案"，而失败根本没拿到答案，下次启动该再试一次
		markChecked(now)

		// tag 认不出来（latest、nightly 之类）时 isNewer 返回 false，于是落到 UpToDate，
		// 也就是不提示。这是 VersionCompare 里约定的"宁可不提示"
		if (!VersionCompare.isNewer(release.tagName, currentVersion)) {
			return UpdateResult.UpToDate(currentVersion, release.tagName.trim())
		}
		return UpdateResult.Available(release.toInfo())
	}

	/** 403 分不清是 GitHub 限流还是代理拒了，措辞上就不替它认领 */
	private fun httpReason(code: Int): String = when {
		code == 403 || code == 429 -> "查得太频繁被挡了（$code），过一会儿再试"
		code == 404 -> "仓库里还没有发布过版本"
		code >= 500 -> "更新服务器出错了（$code）"
		else -> "查询被拒绝（$code）"
	}

	private fun GhRelease.toInfo(): UpdateInfo {
		// 取第一个 .apk：一次发版就一个包，多了也没有依据挑（架构分包那天再说）
		val apk = assets.firstOrNull {
			it.name.endsWith(APK_SUFFIX, ignoreCase = true) ||
				it.browserDownloadUrl.endsWith(APK_SUFFIX, ignoreCase = true)
		}
		val tag = tagName.trim()
		return UpdateInfo(
			tag = tag,
			title = name?.trim()?.takeIf { it.isNotEmpty() } ?: tag,
			notes = body?.trim().orEmpty(),
			publishedAt = publishedAt?.trim().orEmpty(),
			apkUrl = apk?.browserDownloadUrl?.takeIf { it.isNotBlank() }?.let(::proxied),
			releaseUrl = htmlUrl?.trim()?.takeIf { it.isNotEmpty() }
				?: "https://github.com/$repo/releases/latest",
		)
	}

	companion object {
		/** KIQ 指定的代理。用法是前缀拼完整原始 URL，它明确支持 api.github.com */
		const val GH_PROXY_PREFIX = "https://v4.gh-proxy.org/"

		const val REPO = "LIKIQ/aicp"

		/** 自动检查的最小间隔。发版频率远低于一天一次，查得更勤只是白费流量 */
		const val CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L

		private const val APK_SUFFIX = ".apk"
	}
}
