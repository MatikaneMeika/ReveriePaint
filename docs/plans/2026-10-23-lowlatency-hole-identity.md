# 低延迟"孔洞恒等"(H-min) 实施计划

日期: 2026-10-23　分支: `fix/lowlatency-hole-identity-20261023`
设计依据: DESIGN_HOLE.md (方案 H-min)

## 基线 (钉死)

| 项 | SHA |
|---|---|
| 起点 (fork 分支 `fix/lowlatency-overlay-ready-gate-20261023`) | `79624361c5ce` |
| fork 基线 | `f2942769` |
| 上游 LanRhyme/ReveriePaint `main` | `beb49026` (merge-base `80be2910`) |

上游 `80be2910..beb49026` 与按住路径无交集 (渐变工具、快捷视口、io 图层偏移 `5745d8a7`、套索预览偏移 `ed32b1a8`)。
唯一要合并时留意的是 `ed32b1a8` 改了 CanvasOverlay 的覆盖层位图尺寸口径 ——
本分支只在 `CanvasDecorGeometry.overlayBitmapSize` 一处取该口径, 合并时只改这里, 孔洞掩码跟着变。

## 恒等目标

前缓冲 (置顶 SurfaceView) 每个像素 F 只取两种值: **0** 或 **"当前画布不透明像素"**。
孔洞 ⊇ 窗口层画布之上一切装饰的覆盖支撑 (线宽 + AA + 余量), 二值。孔洞内 F=0, 显示窗口层的正常画面;
孔洞外 F=0 处窗口层冻结内容就是对的 (没变的像素就没变), 变了的像素由前缓冲以不透明值覆盖。
⇒ 与关闭低延迟逐像素同图, 唯一差别是延迟。

## 条目

- **撤 L-01**: 删掉 FrontBufferPreviewOverlay 的 Picture 装饰重放 (CPU/GPU 重放装饰不再需要)。
  保留并复用纯几何: `CanvasDecorGeometry`、`CanvasDecorDraw`、`DynamicGuidePainter`、`DynamicGuideSnapshot`;
  `FrontBufferDecorRecorder` 改为孔洞掩码的录制源; `FrontBufferDecorClip` 不再使用, 删除。
- **H-1 孔洞掩码**: 与窗口同一组绘制调用 (像素网格 / 辅助线含 perspective3d·assistedDrawing=false /
  对称线 / 选区蚂蚁线按实线最大线宽) 录成 Picture, 后台线程栅格化到屏幕尺寸 ALPHA_8, 二值化 (α>0) +
  可分离膨胀 (余量); 同时建 16px 单元积分图, 每包矩形查询 O(1)。几何变化时离关键路径重建, 落笔时
  掩码未就绪/位姿不符 → 本笔走 E。前缓冲用 DST_OUT 画 A8 掩码 (无 AA、无过滤) 清孔, 不用 clipPath。
  吸附导引线与光标为逐包动态孔洞 (非 AA 的 CLEAR 描边/圆), 由 GL 线程清孔后回执 (ack),
  窗口层按住期间只按已回执的几何画, 保证"先清孔、后显示"。
- **H-2 按需重绘窗口**: 贴墨矩形 ∩ 孔洞 ≠ ∅ 时才在前缓冲呈现后 `postInvalidateOnAnimation(rect)`
  (复用 UI 光圈同一路径; 天然每 vsync 至多一次)。不命中的帧零额外工作。判定为纯函数 + 单测。
- **H-3 光标**: 光标外接圆不碰孔洞 → 前缓冲画; 碰孔洞 → 整个光标交给窗口 (前缓冲清光标孔)。
  严格恒等 (`debug.reverie.fb_strict`, 默认 1) 下关闭预测笔尖点。
- **H-4 抬笔**: 维持"窗口追上真墨的那一帧提交后再清" (FrameCommitCallback);
  另加 `debug.reverie.fb_clear_delay` 额外延迟 N 帧 (默认 0)。
- **H-5 透明文档**: 烘焙时先以 SRC 画与窗口同一棋盘格 shader, 再叠瓦片 —— 取代原来的 CLEAR, 不多一遍填充。
- **H-6 L-02 完善**: DisplayListener 稳定模式 (稳定 60Hz 也算就绪); 记录 downclockWhenIdle 与
  MainActivity 锁频的冲突与取舍; Kotlin 侧精确渲染空闲 (标志位 + renderHandler 队列空)。
- **H-7 调试**: `debug.reverie.fb_identity_check` 逐包校验; FbGeomLog 记孔洞命中率/面积/
  命中帧耗时 (拷贝/上传/窗口重绘/前缓冲呈现); `debug.reverie.fb_holes`、`debug.reverie.fb_hole_mode=hmin|e`。
  release 下全部为空操作 (E 开关除外, 它只是行为选择)。

## E 模式 (A/B)

`adb shell setprop debug.reverie.fb_hole_mode e`: 贴墨矩形一旦命中孔洞, 当场把本笔交回正常路径
(释放按住、窗口照常每帧画), 不做任何额外清孔/重绘。用于和 hmin 对比命中帧的开销。

## 现有算法优化 (每项报告 file:line 与去掉的工作)

1. 瓦片写时复制 (COW) 改增量: 旧位图退役时记 (瓦片号, 版本), 每瓦片保留最近 K=8 次写入外接框;
   COW 复用同号旧位图时只拷"自那版本以来写过的外接框并集", 取代整瓦片 1MB 拷贝。纯函数 + 单测。
2. 纹理上传粒度: 增量 COW 后被改的瓦片仍整瓦片重传 (HWUI 以位图为单位上传, 无子矩形接口, 不改 native 无解) —— 记录为剩余开销。
3. 孔洞掩码表示: 16px 单元积分图, 矩形查询 O(1)。
4. 光标/吸附线孔洞: 只在几何变化时清, 回执驱动窗口重绘, 不变不做。
5. 烘焙/贴图浪费: 轴对齐且烘焙目标已覆盖屏幕矩形时省掉 clearSlotRect 的重复清除。
6. 渲染合并调度: 见实现报告。

## 约束

不改 native、不加 `-PbuildNative`; 不碰 beginQuickShape/touchEnd 与 DrawingGuidePanel;
新文件带 SPDX 头; 纯逻辑单测; 小步提交、每步推 fork。
