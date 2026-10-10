package com.reverie.paint.core

import android.graphics.Bitmap
import android.util.Log
import com.reverie.paint.model.RealInkPolicy
import java.nio.ByteBuffer

/**
 * 引擎草稿 dab 的安全封装 (docs/REAL-INK-FRONT-BUFFER.md)。
 *
 * 预编译的 libreverie_jni.so 不含 renderScratchDabs 符号: 首次调用抛
 * [UnsatisfiedLinkError] 后整个进程永久标记不可用, 调用方自动退回 STAMP/折线预览。
 */
object RealInkScratch {
    private const val TAG = "RealInkScratch"

    @Volatile
    var nativeAvailable: Boolean = true
        private set

    private val xy = FloatArray(RealInkPolicy.MAX_SCRATCH_SAMPLES * 2)
    private val pressure = FloatArray(RealInkPolicy.MAX_SCRATCH_SAMPLES)
    private val rect = IntArray(4)

    /** 结果: tile 位图 + 文档矩形 (x, y, w, h)。位图归调用方 (交给前缓冲后不得复用)。 */
    class Tile(val bitmap: Bitmap, val docX: Int, val docY: Int)

    /**
     * 渲染 [count] 个文档坐标样本 ([docXY] 交错 x,y)。不支持/失败返回 null。
     * 仅 UI 线程调用 (共享暂存数组)。
     */
    fun render(docXY: FloatArray, pressures: FloatArray, count: Int): Tile? {
        if (!nativeAvailable || count <= 0) return null
        val n = count.coerceAtMost(RealInkPolicy.MAX_SCRATCH_SAMPLES)
        docXY.copyInto(xy, 0, 0, n * 2)
        pressures.copyInto(pressure, 0, 0, n)
        val bytes = try {
            ReverieCoreBridge.renderScratchDabs(xy, pressure, n, rect)
        } catch (e: UnsatisfiedLinkError) {
            nativeAvailable = false
            Log.w(TAG, "renderScratchDabs missing in native lib (prebuilt?), engine scratch disabled")
            return null
        } catch (t: Throwable) {
            Log.w(TAG, "renderScratchDabs failed: ${t.message}")
            return null
        } ?: return null
        val w = rect[2]
        val h = rect[3]
        if (w <= 0 || h <= 0 || bytes.size < w * h * 4) return null
        // 引擎输出直通 alpha RGBA8888; Bitmap 内部为预乘, 这里逐像素预乘 (tile 很小)。
        premultiplyInPlace(bytes)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.copyPixelsFromBuffer(ByteBuffer.wrap(bytes, 0, w * h * 4))
        return Tile(bmp, rect[0], rect[1])
    }

    internal fun premultiplyInPlace(rgba: ByteArray) {
        var i = 0
        while (i + 3 < rgba.size) {
            val a = rgba[i + 3].toInt() and 0xFF
            if (a != 255) {
                rgba[i] = ((rgba[i].toInt() and 0xFF) * a / 255).toByte()
                rgba[i + 1] = ((rgba[i + 1].toInt() and 0xFF) * a / 255).toByte()
                rgba[i + 2] = ((rgba[i + 2].toInt() and 0xFF) * a / 255).toByte()
            }
            i += 4
        }
    }
}
