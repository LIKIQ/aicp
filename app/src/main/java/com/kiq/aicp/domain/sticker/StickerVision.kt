/*
 * app/src/main/java/com/kiq/aicp/domain/sticker/StickerVision.kt
 * 表情识图：逐张问视觉模型"这张表达什么情绪"，把结果写回 sticker.emotion
 * 职责：
 * - 只处理"分组名不是情绪"的那些图（候选由 StickerRepository.allUnclassified 决定）
 * - 逐张串行调用，边跑边回报进度，认不出的原样跳过
 * - 把成败汇总成一份报告，交给 StickerVisionPolicy 决定这批算完了还是该重试
 *
 * 这一层不认识 WorkManager，也不弹通知：识图是后台自动跑的整理活儿，
 * 调度和重试策略归 work/StickerVisionWorker，它只负责"识图这件事本身"。
 *
 * 为什么串行不并发：一次可能几十张。并发省下的那点时间，
 * 换来的是限流 429 和一堆没法解释的失败，而且识图按张计费，出错时花掉的钱一分不退。
 *
 * 为什么认不出就跳过而不是塞个默认值：emotion 是模型选表情的依据，
 * 随手填个"开心"会让那张图从此以错误的情绪被发出去，比留空难查得多。
 * 留空的代价只是它暂时不参与选取，下次触发时会再试一遍。
 */
package com.kiq.aicp.domain.sticker

import com.kiq.aicp.data.attach.AttachmentStore
import com.kiq.aicp.data.db.entity.StickerEntity
import com.kiq.aicp.data.remote.LlmException
import com.kiq.aicp.data.remote.LlmImage
import com.kiq.aicp.data.remote.LlmMessage
import com.kiq.aicp.data.remote.LlmParams
import com.kiq.aicp.data.remote.LlmProvider
import com.kiq.aicp.data.repo.StickerRepository
import com.kiq.aicp.domain.model.AicpSettings
import com.kiq.aicp.domain.model.ChatRole
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * 一次批量识图的结果。
 *
 * reasons 先去重再截断：几十张图往往是同一个毛病（Key 过期、模型不支持图片），
 * 把同一句话重复三十遍只会把日志和提示挤爆，还盖掉真正不一样的那一条。
 */
data class StickerVisionReport(
	val total: Int,
	val ok: Int,
	val failed: Int,
	val reasons: List<String>,
	/**
	 * 其中"重试有意义"的失败张数（网络、限流、5xx）。
	 * 单独计数是给调度层用的：认不出、图读不出来这类重试一百次也是同样结果，
	 * 只有这个数大于 0 才值得让 WorkManager 退避后再来一趟。
	 */
	val retryable: Int = 0,
	/**
	 * 一次请求都没发出去——视觉模型没配置。
	 * 跟"识别失败"分开是因为处理办法完全不同：这个要去设置页填模型，那个多半是重试。
	 */
	val notConfigured: Boolean = false,
)

/** 这批跑完之后该干什么。刻意不用 WorkManager 的 Result：这一层不该认识 androidx.work */
enum class StickerVisionNext {
	/** 这轮到此为止。没识别成的图 emotion 还是空的，下次触发会再排到它 */
	DONE,

	/** 值得退避后重来一趟 */
	RETRY,
}

/**
 * 批次结果 → 下一步动作。抽成纯函数就是为了能单测：
 * Worker 里那几行编排不好测，而"什么时候该重试"恰恰是写错了会实际烧电池的地方。
 */
object StickerVisionPolicy {

	fun next(report: StickerVisionReport): StickerVisionNext = when {
		// 没配视觉模型时绝对不能重试：重试一百次也不会自己长出一个模型来，
		// 结果就是没配模型的用户被 WorkManager 无限退避重试，白耗电池
		report.notConfigured -> StickerVisionNext.DONE

		// 网络断了、被限流、服务端 5xx——换个时候来是真有可能成的
		report.retryable > 0 -> StickerVisionNext.RETRY

		// 剩下的失败都是"这张图就这样"（模型认不出、文件读不出来），
		// 重试改变不了结果。它们 emotion 留空，下次触发时自然会再排到
		else -> StickerVisionNext.DONE
	}
}

class StickerVision(
	private val stickerRepository: StickerRepository,
	private val attachmentStore: AttachmentStore,
	private val llmProvider: LlmProvider,
) {

	/**
	 * 把库里所有还没识别的图跑一遍。
	 *
	 * 后台任务的入口就是它：一次跑全量而不是按组，
	 * 因为"哪些图该识别"这件事跟用户当时在看哪个分组无关。
	 * 组名本身是情绪的那些组不在候选里，见 StickerRepository.allUnclassified。
	 */
	suspend fun classifyPending(
		settings: AicpSettings,
		onProgress: ((done: Int, total: Int) -> Unit)? = null,
	): StickerVisionReport = classify(stickerRepository.allUnclassified(), settings, onProgress)

	/**
	 * 逐张识别并写回。
	 *
	 * @param onProgress 每张（不论成败）结束后回报一次，调用方拿它记进度
	 */
	suspend fun classify(
		targets: List<StickerEntity>,
		settings: AicpSettings,
		onProgress: ((done: Int, total: Int) -> Unit)? = null,
	): StickerVisionReport {
		if (targets.isEmpty()) {
			return StickerVisionReport(total = 0, ok = 0, failed = 0, reasons = emptyList())
		}

		// 没配模型就别浪费一趟网络往返，直接把"去设置页"这件事告诉上层
		val model = settings.effectiveVisionModel()
		if (!settings.hasVisionModel || model.isBlank()) {
			return StickerVisionReport(
				total = targets.size,
				ok = 0,
				failed = targets.size,
				reasons = listOf(NO_VISION_MODEL),
				notConfigured = true,
			)
		}

		val params = LlmParams(
			model = model,
			// 只要一个词，额度给多了纯属给模型留出写解释的空间
			maxTokens = MAX_TOKENS,
			// 温度 0：同一张图两次识别该给同一个答案，否则用户会看到分类自己变来变去
			temperature = 0f,
			topP = 1f,
		)

		var ok = 0
		var retryable = 0
		val reasons = mutableListOf<String>()

		targets.forEachIndexed { index, sticker ->
			// 系统回收后台任务、用户退出应用时都该立刻停，别把剩下几十张的钱花完
			currentCoroutineContext().ensureActive()

			when (val outcome = classifyOne(sticker, params)) {
				is OneResult.Ok -> {
					stickerRepository.setEmotion(sticker.id, outcome.emotion)
					ok++
				}

				is OneResult.Fail -> {
					reasons += outcome.reason
					if (outcome.retryable) retryable++
				}
			}

			onProgress?.invoke(index + 1, targets.size)
		}

		return StickerVisionReport(
			total = targets.size,
			ok = ok,
			failed = targets.size - ok,
			reasons = reasons.distinct().take(MAX_REASONS),
			retryable = retryable,
		)
	}

	private sealed interface OneResult {
		data class Ok(val emotion: String) : OneResult

		/** retryable 只对网络类错误为 true，它决定整批要不要让调度层退避重来 */
		data class Fail(val reason: String, val retryable: Boolean = false) : OneResult
	}

	private suspend fun classifyOne(sticker: StickerEntity, params: LlmParams): OneResult {
		val raw = try {
			val base64 = attachmentStore.readBase64(sticker.localPath)
			llmProvider.complete(
				messages = listOf(
					LlmMessage(ChatRole.SYSTEM, StickerEmotion.visionSystem()),
					LlmMessage(
						ChatRole.USER,
						USER_ASK,
						// 表情图本身就小，用不着 highDetail 那档——它更贵，而且这里不需要认字
						images = listOf(LlmImage(base64 = base64, mimeType = sticker.mimeType)),
					),
				),
				params = params,
			)
		} catch (e: CancellationException) {
			// 取消不是失败，必须原样往上抛，否则这个循环会把取消吞掉继续跑完
			throw e
		} catch (e: LlmException) {
			return OneResult.Fail(
				reason = e.message?.takeIf { it.isNotBlank() } ?: "识图请求失败",
				retryable = e.kind.retryable,
			)
		} catch (e: Exception) {
			// 文件被用户从外部删了、读不出来都落在这里，重试也是同样结果
			return OneResult.Fail(e.message?.takeIf { it.isNotBlank() } ?: "这张图读不出来")
		}

		val emotion = StickerEmotion.parseReply(raw) ?: return OneResult.Fail(UNRECOGNIZED)
		return OneResult.Ok(emotion)
	}

	companion object {
		const val NO_VISION_MODEL = "还没配置视觉模型，先去设置页填一个能看图的模型"

		/** 模型回了话但不在词表里。措辞点明"这张保持未识别"，免得用户以为数据被写坏了 */
		const val UNRECOGNIZED = "模型没给出词表里的情绪，这张先留着没分类"

		private const val USER_ASK = "这张表情表达什么情绪？"

		/** 只需要一个词。这个数是刻意抠的：留大了模型会开始写理由，反而更难解析 */
		private const val MAX_TOKENS = 16

		private const val MAX_REASONS = 2
	}
}
