package com.reverie.paint.model

/**
 * 前缓冲"真墨"策略 (docs/REAL-INK-FRONT-BUFFER.md), 纯逻辑, 无 Android 依赖。
 *
 * - 真墨模式: 前缓冲只画 [引擎可见墨迹末端 → 最新真实采样点] 的回填段, 不做任何运动预测。
 * - 引擎草稿: 回填段交给 Krita 用完整笔刷设置渲染到临时 tile (不写图层);
 *   需要读取底层像素的笔刷 (涂抹/混色/变形/滤镜/克隆) 退回真实采样点 STAMP 戳印。
 */
object RealInkPolicy {
    /** 读取底层像素、无法在空白临时设备上预览的 Krita paintop id */
    private val LAYER_READING_OPS = setOf("colorsmudge", "deformbrush", "filter", "duplicate")

    /** 草稿 dab 单次最多样本数 (与 C++ renderScratchDabs 上限一致) */
    const val MAX_SCRATCH_SAMPLES = 64

    /** 真墨模式下是否允许运动预测 */
    fun predictionAllowed(realInkOnly: Boolean): Boolean = !realInkOnly

    /** 当前笔刷能否走引擎草稿 dab */
    fun engineScratchEligible(
        realInkOnly: Boolean,
        engineScratchEnabled: Boolean,
        toolId: String,
        paintOpId: String,
    ): Boolean {
        if (!realInkOnly || !engineScratchEnabled) return false
        if (toolId != "brush") return false
        return paintOpId !in LAYER_READING_OPS
    }

    /**
     * 引擎草稿失败熔断: 连续失败 [threshold] 次后本笔停用 (退回 STAMP/折线),
     * 避免每个事件都付出失败调用的代价。成功一次即清零。
     */
    class FailureLatch(private val threshold: Int = 3) {
        var consecutiveFailures = 0
            private set
        val tripped: Boolean get() = consecutiveFailures >= threshold

        fun onSuccess() { consecutiveFailures = 0 }
        fun onFailure() { consecutiveFailures++ }
        fun reset() { consecutiveFailures = 0 }
    }
}
