// app/src/main/java/com/kiq/aicp/data/remote/WebSearchClient.kt
// 免 key 的联网搜索：问 Bing 的 RSS 接口，再按需抓一两个结果页面。
//
// 为什么是 Bing RSS：cn.bing.com/search?format=rss 不要 key、不要注册，国内直连可达
// （同一台机器上 zh.wikipedia.org 和 DuckDuckGo 都是超时），返回标准 RSS 2.0，
// 固定 10 条，count 参数无效。www.bing.com 会 302，所以地址写死 cn.bing.com。
//
// 抓页面的三条自我约束，都是花过代价才定下来的：
// 1. 只抓 https —— 走 CleartextGuard 判定，公网 http 一律不碰
// 2. Content-Type 不是 html 的直接扔，别把 PDF 和视频流当网页读
// 3. 响应体封顶 512KB，超了就截断，一个大页面不值得吃掉用户的流量和内存
//
// 请求头里绝对不带 Authorization：这条链路会访问任意第三方站点，
// 把 LLM 的 Key 捎过去是灾难。所以它不复用 OpenAiCompatProvider 那套装配。

package com.kiq.aicp.data.remote

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer
import okio.BufferedSource

interface WebSearchClient {

	/** 搜一次，返回原始 RSS 文本。任何失败都返回 null，调用方一律降级成"这轮不搜" */
	suspend fun searchRaw(query: String): String?

	/** 抓一个结果页面的 HTML。不可抓、非 html、失败都返回 null */
	suspend fun fetchPage(url: String): String?
}

class BingRssSearchClient(
	baseClient: OkHttpClient,
	/** 可注入是为了单测能指向 MockWebServer */
	private val searchEndpoint: String = DEFAULT_SEARCH_ENDPOINT,
	private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : WebSearchClient {

	/**
	 * 搜索和抓页面都不该等太久：用户已经发出消息在等回复了，
	 * 与其为一个慢站点吊着他十几秒，不如放弃这次检索照常回话。
	 *
	 * callTimeout 是这里的关键，不是随手加的保险：execute() 是阻塞调用，
	 * 外层协程的 withTimeoutOrNull 只能让调用方提前返回，掐不断底下的 socket——
	 * 一个每 200ms 滴 100 字节的站点永远碰不到 readTimeout（它管的是包间隔），
	 * 结果协程早走了、IO 线程和连接还被占着十几秒。callTimeout 管的是整次调用，
	 * 它才是真正的封顶。
	 */
	private val client = baseClient.newBuilder()
		.connectTimeout(SEARCH_CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
		.readTimeout(SEARCH_READ_TIMEOUT_SEC, TimeUnit.SECONDS)
		.callTimeout(SEARCH_CALL_TIMEOUT_SEC, TimeUnit.SECONDS)
		.build()

	override suspend fun searchRaw(query: String): String? = withContext(ioDispatcher) {
		val trimmed = query.trim()
		if (trimmed.isEmpty()) return@withContext null

		val url = (searchEndpoint.toHttpUrlOrNull() ?: return@withContext null)
			.newBuilder()
			.addQueryParameter("q", trimmed)
			.addQueryParameter("format", "rss")
			.build()

		get(url, expectHtml = false)
	}

	override suspend fun fetchPage(url: String): String? = withContext(ioDispatcher) {
		val parsed = url.toHttpUrlOrNull() ?: return@withContext null
		// 公网明文一律不碰。守卫是纯函数，跟 LLM 请求共用同一套白名单
		if (!CleartextGuard.isAllowed(parsed.scheme, parsed.host)) return@withContext null

		get(parsed, expectHtml = true)
	}

	private fun get(url: HttpUrl, expectHtml: Boolean): String? {
		val request = Request.Builder()
			.url(url)
			.header("User-Agent", USER_AGENT)
			.header("Accept-Language", "zh-CN,zh;q=0.9")
			.build()

		return try {
			client.newCall(request).execute().use { response ->
				if (!response.isSuccessful) return null
				val body = response.body

				// 非 html 的东西读进来只会变成乱码或者一大坨二进制。
				// 但"没声明 Content-Type"不算证据：okhttp 不猜类型，
				// 配置粗糙的站点压根不发这个头，一律拒掉等于白抓一次
				val contentType = body.contentType()
				val subtype = contentType?.subtype
				if (expectHtml && subtype != null && !subtype.contains("html")) return null

				val bytes = readAtMost(body.source(), MAX_BODY_BYTES)
				decode(bytes, contentType?.charset()?.name())
			}
		} catch (_: IOException) {
			// 超时、断网、DNS 挂了都走这里。搜索是锦上添花，失败就当没这回事
			null
		}
	}

	/** 读最多 limit 字节。别用 body.string()：一个几 MB 的页面会直接吃满内存 */
	private fun readAtMost(source: BufferedSource, limit: Long): ByteArray {
		val buffer = Buffer()
		while (buffer.size < limit) {
			val read = source.read(buffer, limit - buffer.size)
			if (read == -1L) break
		}
		return buffer.readByteArray()
	}

	/**
	 * 定编码的顺序：响应头 → 页面里的 meta charset → UTF-8。
	 *
	 * 光按响应头解不够：国内不少站点的响应头写着 utf-8、页面其实是 GBK
	 * （Nginx 默认头没改），按错的编码解码不会抛异常，只会产出一整页 U+FFFD。
	 * 那种内容喂给模型比不喂更糟——它会当成真的然后一本正经地胡说。
	 * 所以按头解完还要看一眼像不像乱码，像就继续往下探 meta。
	 */
	private fun decode(bytes: ByteArray, headerCharset: String?): String {
		val byHeader = headerCharset?.let { name -> tryDecode(bytes, name) }
		if (byHeader != null && !looksGarbled(byHeader)) return trimTail(byHeader)

		// 只在开头一小段里找：meta 标签一定在 head 里，翻完整页纯属浪费
		val probe = String(bytes, 0, minOf(bytes.size, CHARSET_PROBE_BYTES), Charsets.ISO_8859_1)
		META_CHARSET.find(probe)?.groupValues?.get(1)?.trim()?.let { name ->
			tryDecode(bytes, name)?.takeIf { !looksGarbled(it) }?.let { return trimTail(it) }
		}

		// 两条路都没给出干净结果时，宁可交回按头解出来的那份：
		// 它至少是站点自己声明的编码，比硬套 UTF-8 更可能对
		return trimTail(byHeader ?: String(bytes, Charsets.UTF_8))
	}

	private fun tryDecode(bytes: ByteArray, charsetName: String): String? =
		runCatching { String(bytes, charset(charsetName)) }.getOrNull()

	/** 替换字符超过 1% 就当解错了。正常网页顶多零星几个 */
	private fun looksGarbled(text: String): Boolean {
		if (text.isEmpty()) return false
		val bad = text.count { it == '\uFFFD' }
		return bad * 100 > text.length
	}

	/** 512KB 那一刀可能砍在多字节字符中间，末尾会多出一个替换字符 */
	private fun trimTail(text: String): String = text.trimEnd('\uFFFD')

	companion object {
		const val DEFAULT_SEARCH_ENDPOINT = "https://cn.bing.com/search"

		private const val SEARCH_CONNECT_TIMEOUT_SEC = 6L
		private const val SEARCH_READ_TIMEOUT_SEC = 8L

		/** 整次调用的封顶。慢速滴水的站点只有这个能掐断 */
		private const val SEARCH_CALL_TIMEOUT_SEC = 10L

		/** 512KB。正文再长也用不上这么多，超过的部分只会是评论区和推荐位 */
		private const val MAX_BODY_BYTES = 512L * 1024

		private const val CHARSET_PROBE_BYTES = 2048

		/** 装成手机浏览器。有些站对空 UA 直接返回精简版或者 403 */
		private const val USER_AGENT =
			"Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
				"Chrome/122.0.0.0 Mobile Safari/537.36"

		private val META_CHARSET = Regex(
			"""charset\s*=\s*["']?([a-zA-Z0-9_\-]+)""",
			RegexOption.IGNORE_CASE,
		)
	}
}
