/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

/**
 * 工具槽（`toolBrushStates`）里记录的预设，如何映射到当前预设表（#136 A）。
 *
 * 背景：`presetIndex` 指向的是 native 表里「文件名排序后的位置」。导入、删除、ABR 批量入库
 * 都会重建这张表，同一个索引就换了主人——实测：`idx=0` 十分钟前是 `Airbrush (Lur_Noise's...)`，
 * 删了几个预设之后变成 `Airbrush_eraser`，于是「橡皮」莫名其妙变成喷漆样式。
 *
 * 因此工具槽以**名字**为身份（`ToolBrushState.presetName`），索引只在解析时临时用：
 * 1. 名字命中 → 用它（重排安全）；
 * 2. 名字缺失（老数据，prefs 里只有 `pi`）→ 退回索引，并把命中的名字回填，下次即按名字走；
 * 3. 都不命中（预设已被删除）→ null，由调用方决定默认笔，绝不拿「表里的第一个」顶替。
 *
 * 纯函数，JVM 单测直接可用。
 */
internal object ToolPresetMemory {

    fun resolve(
        storedName: String,
        storedIndex: Int,
        presets: List<BrushPresetInfo>,
    ): BrushPresetInfo? {
        if (storedName.isNotBlank()) {
            presets.firstOrNull { it.name == storedName }?.let { return it }
            // 名字失效（被删除/改名）：不要退回索引——那个位置现在可能是另一支笔。
            return null
        }
        if (storedIndex < 0) return null
        return presets.firstOrNull { it.index == storedIndex }
    }

    /**
     * 冷启动恢复上次使用的那支笔。
     *
     * 与 [resolve] 的区别只在于多一层「旧数据」兜底：工具槽里既没有名字也没有索引记录时
     * （很早的版本只写过 `last_brush_preset_index`），才用那个更老的键。
     *
     * 之所以单独抽出来：冷启动这条路径原来直接拿持久化的整数索引去 `selectBrushPreset`，
     * 只校验「这个索引上有没有笔」，不校验「还是不是那支笔」。导入/删除预设重排之后，
     * 重启 App 就会选中别的笔，并且把这个错索引连同错名字一起写回工具槽 —— 之后连按名字
     * 解析也救不回来（名字已经被覆盖成错的那支）。这段逻辑要能被 JVM 单测覆盖，所以放在这里。
     *
     * [rejectEraser] 为 true 时（当前工具不是橡皮）拒掉解析出的橡皮笔，由调用方换默认画笔。
     */
    fun resolveColdStart(
        storedName: String,
        storedIndex: Int,
        legacyIndex: Int,
        presets: List<BrushPresetInfo>,
        rejectEraser: Boolean,
    ): BrushPresetInfo? {
        val candidate = resolve(storedName, storedIndex, presets)
            ?: resolve("", legacyIndex, presets)
            ?: return null
        if (rejectEraser && candidate.isEraserLike()) return null
        return candidate
    }

    /** 与设置/面板里的判定保持一致：分组、`a)_Eraser*` 前缀、名字里含 Eraser */
    private fun BrushPresetInfo.isEraserLike(): Boolean =
        group == "橡皮擦" ||
            name.startsWith("a)_Eraser", ignoreCase = true) ||
            name.contains("Eraser", ignoreCase = true)
}
