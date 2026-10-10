package com.reverie.paint.core

import android.util.Log
import com.reverie.paint.model.RealInkPolicy

/**
 * 引擎草稿 dab 的安全封装 (docs/REAL-INK-FRONT-BUFFER.md)。
 *
 * - 原生侧只读引擎线程落笔时抓取的预设快照, paintop/设备整笔复用;
 * - 像素缓冲 [pixels] 跨事件复用 (仅在 tile 变大时扩容), 原生侧直接写预乘 RGBA,
 *   这里不再逐像素预乘、不再每事件 new Bitmap;
 * - 旧版 libreverie_jni.so 缺符号时首次 [UnsatisfiedLinkError] 后永久标记不可用,
 *   调用方自动退回 STAMP/折线预览。
 *
 * 仅 UI 线程调用 (共享暂存数组)。
 */
object RealInkScratch {
    private const val TAG = "RealInkScratch"

    @Volatile
    var nativeAvailable: Boolean = true
        private set

    private val xy = FloatArray(RealInkPolicy.MAX_SCRATCH_SAMPLES * 2)
    private val pressure = FloatArray(RealInkPolicy.MAX_SCRATCH_SAMPLES)
    private val rect = IntArray(4)

    /** 最近一次结果的预乘 RGBA 像素 (前 [width]*[height]*4 字节有效) */
    var pixels: ByteArray = ByteArray(64 * 64 * 4)
        private set
    var docX = 0; private set
    var docY = 0; private set
    var width = 0; private set
    var height = 0; private set

    /** 渲染 [count] 个文档坐标样本 ([docXY] 交错 x,y)。成功返回 true 并更新 [pixels]/矩形。 */
    fun render(docXY: FloatArray, pressures: FloatArray, count: Int): Boolean {
        if (!nativeAvailable || count <= 0) return false
        val n = count.coerceAtMost(RealInkPolicy.MAX_SCRATCH_SAMPLES)
        docXY.copyInto(xy, 0, 0, n * 2)
        pressures.copyInto(pressure, 0, 0, n)
        val out = try {
            ReverieCoreBridge.renderScratchDabs(xy, pressure, n, rect, pixels)
        } catch (e: UnsatisfiedLinkError) {
            nativeAvailable = false
            Log.w(TAG, "renderScratchDabs missing in native lib (prebuilt?), engine scratch disabled")
            return false
        } catch (t: Throwable) {
            Log.w(TAG, "renderScratchDabs failed: ${t.message}")
            return false
        } ?: return false
        val w = rect[2]
        val h = rect[3]
        if (w <= 0 || h <= 0 || out.size < w * h * 4) return false
        pixels = out
        docX = rect[0]; docY = rect[1]; width = w; height = h
        return true
    }
}
