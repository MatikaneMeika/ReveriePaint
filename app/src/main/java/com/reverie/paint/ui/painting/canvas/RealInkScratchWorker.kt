package com.reverie.paint.ui.painting.canvas

import android.graphics.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.reverie.paint.core.PerfTrace
import com.reverie.paint.core.RealInkScratch
import com.reverie.paint.model.RealInkPolicy

/**
 * 引擎草稿 dab 的后台渲染 (docs/REAL-INK-FRONT-BUFFER.md)。
 *
 * 旧版在 UI 线程逐事件同步调用 Krita paintop, 单次数毫秒, 直接吃掉帧预算。
 * 现在: UI 线程只拷贝样本 (单槽邮箱, 新样本覆盖旧样本, 永不排队),
 * 本线程渲染, 结果经主线程 postAtFrontOfQueue 投递到前缓冲。
 * 同时最多一个结果在途 (RealInkScratch.pixels 由主线程消费完才会被覆盖), 天然限流。
 *
 * 每笔结束时以 tag "RealInk" 打一行统计, 说明草稿是否生效、为何回落。
 */
internal class RealInkScratchWorker(
    private val deliver: (pixels: ByteArray, w: Int, h: Int, tileToScreen: Matrix, gen: Int) -> Boolean,
) {
    private val thread = HandlerThread("RealInkScratch", Process.THREAD_PRIORITY_DISPLAY).apply { start() }
    private val bg = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()

    // 邮箱 (lock)
    private val inXY = FloatArray(RealInkPolicy.MAX_SCRATCH_SAMPLES * 2)
    private val inP = FloatArray(RealInkPolicy.MAX_SCRATCH_SAMPLES)
    private var inN = 0
    private val inDocToScreen = Matrix()
    private var inGen = 0
    private var hasInput = false
    private var running = false // 渲染中或结果在途

    // 渲染线程私有
    private val wXY = FloatArray(RealInkPolicy.MAX_SCRATCH_SAMPLES * 2)
    private val wP = FloatArray(RealInkPolicy.MAX_SCRATCH_SAMPLES)
    private val wDocToScreen = Matrix()
    private val outMatrix = Matrix()

    // 每笔统计 (lock)
    @Volatile var generation = 0; private set
    private var strokeStartMs = 0L
    private val breaker = RealInkPolicy.StrokeBreaker()
    private var submitted = 0
    private var coalesced = 0
    private var okCount = 0
    private var nullCount = 0
    private var shown = 0
    private var firstOkMs = -1L
    private var maxRenderMs = 0.0
    private var sumRenderMs = 0.0
    private var lastSkipReason: String? = null

    /** UI 线程最近一次贴出草稿 tile 的时刻 (uptime ms), 用于让位给戳印 */
    @Volatile var lastShownUptimeMs = 0L; private set

    val tripped: Boolean get() = synchronized(lock) { breaker.tripped }

    /** 起笔: 结算上一笔日志并开始新一笔 */
    fun beginStroke() {
        synchronized(lock) {
            logStrokeLocked()
            generation++
            strokeStartMs = SystemClock.uptimeMillis()
            breaker.reset()
            submitted = 0; coalesced = 0; okCount = 0; nullCount = 0; shown = 0
            firstOkMs = -1L; maxRenderMs = 0.0; sumRenderMs = 0.0; lastSkipReason = null
            hasInput = false
            lastShownUptimeMs = 0L
        }
    }

    /** 记录本笔为何没走草稿 (仅保留最近一个原因) */
    fun noteSkip(reason: String) {
        synchronized(lock) { lastSkipReason = reason }
    }

    /** UI 线程: 投递最新样本。[docToScreen] 为文档→屏幕仿射。 */
    fun submit(xy: FloatArray, p: FloatArray, n: Int, docToScreen: Matrix) {
        if (n <= 0) return
        val kick: Boolean
        synchronized(lock) {
            if (breaker.tripped) return
            if (hasInput) coalesced++
            System.arraycopy(xy, 0, inXY, 0, n * 2)
            System.arraycopy(p, 0, inP, 0, n)
            inN = n
            inDocToScreen.set(docToScreen)
            inGen = generation
            hasInput = true
            submitted++
            kick = !running
            if (kick) running = true
        }
        if (kick) bg.post(renderRunnable)
    }

    private val renderRunnable = Runnable { renderOnce() }

    private fun renderOnce() {
        val n: Int
        val gen: Int
        synchronized(lock) {
            if (!hasInput) { running = false; return }
            n = inN
            gen = inGen
            System.arraycopy(inXY, 0, wXY, 0, n * 2)
            System.arraycopy(inP, 0, wP, 0, n)
            wDocToScreen.set(inDocToScreen)
            hasInput = false
        }
        val t0 = System.nanoTime()
        val ok = RealInkScratch.render(wXY, wP, n)
        val ms = (System.nanoTime() - t0) / 1e6
        PerfTrace.tickNanos("realink.scratch", System.nanoTime() - t0)
        synchronized(lock) {
            if (gen == generation) {
                val since = SystemClock.uptimeMillis() - strokeStartMs
                breaker.onResult(ok, since, ms)
                if (ok) { okCount++; if (firstOkMs < 0) firstOkMs = since } else nullCount++
                sumRenderMs += ms
                if (ms > maxRenderMs) maxRenderMs = ms
            }
        }
        if (!ok || gen != generation) { finishOrContinue(); return }
        val w = RealInkScratch.width
        val h = RealInkScratch.height
        outMatrix.set(wDocToScreen)
        outMatrix.preTranslate(RealInkScratch.docX.toFloat(), RealInkScratch.docY.toFloat())
        val m = Matrix(outMatrix)
        main.postAtFrontOfQueue {
            if (gen == generation && deliver(RealInkScratch.pixels, w, h, m, gen)) {
                lastShownUptimeMs = SystemClock.uptimeMillis()
                synchronized(lock) { if (gen == generation) shown++ }
            }
            finishOrContinue()
        }
    }

    private fun finishOrContinue() {
        val again: Boolean
        synchronized(lock) {
            again = hasInput && !breaker.tripped
            if (!again) { running = false; hasInput = false }
        }
        if (again) bg.post(renderRunnable)
    }

    private fun logStrokeLocked() {
        if (generation == 0) return
        if (submitted == 0 && lastSkipReason == null) return
        val renders = okCount + nullCount
        val verdict = when {
            submitted == 0 -> "NOT_USED reason=${lastSkipReason}"
            shown > 0 && !breaker.tripped -> "USED"
            breaker.tripped -> "FELL_BACK reason=${breaker.tripReason}"
            okCount == 0 -> "FELL_BACK reason=noTileFromNative(snapshot missing / op unsupported / engine busy)"
            else -> "FELL_BACK reason=notDelivered(frontBuffer busy / stroke ended)"
        }
        Log.i(
            TAG,
            "stroke#$generation $verdict submitted=$submitted coalesced=$coalesced renders=$renders ok=$okCount null=$nullCount " +
                "shown=$shown firstOkMs=$firstOkMs avgMs=${if (renders > 0) "%.2f".format(sumRenderMs / renders) else "-"} " +
                "maxMs=${"%.2f".format(maxRenderMs)} lastSkip=$lastSkipReason"
        )
    }

    fun release() {
        synchronized(lock) { logStrokeLocked(); generation++ }
        thread.quitSafely()
    }

    companion object { const val TAG = "RealInk" }
}
