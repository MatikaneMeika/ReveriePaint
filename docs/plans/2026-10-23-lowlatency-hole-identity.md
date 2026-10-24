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

## Implementation status (2026-10-23, round 3)

- HoleMaskGrid cell = 8 px, margin = 2 px (`debug.reverie.fb_hole_margin`), coverage cap 35 % (`debug.reverie.fb_hole_max_cov`).
- Arm → NORMAL (stroke not held) only when: mask stale/not built yet, coverage > cap, or tool not BRUSH/ERASER/SMUDGE.
  **Snap lines (perspective 2D / isometric / 2D grid): default = NORMAL path again (strict identity)** — round 3
  gates 1a (`4b630b51`) behind `debug.reverie.fb_snap_hold` (default 0; `FrontBufferFeatureFlags.snapHold`,
  `FrontBufferHolePolicy.snapForcesNormal`). With it off nothing of 1a runs: no extras backup for snap strokes, no guide
  in front packets (`frontDrawsGuide`), window draws the full guide (`windowGuideHolesOnly` = false) — byte-for-byte the
  pre-1a behaviour.
  **Why default off:** the held guide cannot be *proven* identical. The front uses the same `DynamicGuidePainter.drawWith`
  geometry, same Paint construction (separate instance for thread safety) and identity screen matrix, so AA coverage is the
  same Skia/HWUI algorithm — but (a) the masked paint gets its colour from a constant LinearGradient shader + ComposeShader
  DST_OUT instead of the paint colour (gradient/dither path may differ), (b) front pixels are semi-transparent
  premultiplied 8-bit composited by SurfaceFlinger/HWC vs blended directly in the window (±1 rounding), (c) the two
  surfaces present on independent cadences, so pen-up / E-handback can't be made atomic (≤ 1 frame linger, gap or stale
  position).
  With `fb_snap_hold=1` (A/B only): front draws the guide after the ink blit through `DST_OUT` with the hole mask
  (FrontBufferPreviewOverlay `drawGuide`), erases the old footprint by SRC restore from the backup (`erasePrevGuide`).
  Round 3 ordering fix: while the front guide is on screen (`FrontBufferPreviewOverlay.guideOnScreen`) the window draws
  the guide holes/clips only *even after release*, so the two surfaces never draw the same guide pixel (no E-mode alpha
  doubling); when the front erases it (clear / `invalidatePrevOverlay` / guide-less packet / renderer release) and the
  stroke isn't held, `RealInkController.onFrontGuideCleared` posts a window redraw with the full guide
  (`guideClearedNeedsRedraw`). Residual in ON mode: ≤ 1 frame gap/stale guide outside holes at handback/pen-up.
- **First stroke after pan/zoom** (`46c8eb4a`, 1b): the first stale frame after ≥ 50 ms quiet with no fit/snap animator
  running (CanvasTouchView :2863) rebuilds the mask immediately instead of waiting the 100 ms debounce
  (`FrontBufferHolePolicy.buildImmediately`). Rasterisation stays on the `reverie-holes` thread; a pen-down that lands
  before the rebuild finishes still falls back for that one stroke.
- **Coverage > 35 % stays a fallback (1c, justified, no code)**: the dominant case is the pixel grid
  (CanvasTouchView `drawPixelGrid` :3987, non-AA, 1/scale stroke): dilated by margin 2 it covers ~5/s of each axis, so every
  packet would hit, and the grid already disables partial invalidation (:4278). H-min there = normal full window redraw
  + front work, strictly more than the fallback. Front-composing the grid (SRC_ATOP) is not guaranteed identical
  (non-AA tie pixels, matrix/offset rounding, erase-restore from a backup that has no grid).
- **Tile COW delta** (`f28bd406`): a COW tile write prefers a spare that is the same tile's own retired bitmap
  (`DisplayTileState.obtainSpare` :237) and copies only the union of boxes written since that version
  (`model/TileDeltaLedger.kt`, K = 8; PaintViewModelTiles :146), instead of the whole 512² tile (1 MiB). History gaps,
  failed writes, grid switches → full copy (conservative; pixel-identical by construction, randomized test).
  Also benefits the normal path (every drawn tile COWs on its next write).
- Hit rects: single-pass `ComposeShader(ink, mask, DST_OUT)` SRC blit (front + backup layer); non-hit rects unchanged.
- Window invalidate: one `postInvalidateOnAnimation(union(clip overlap, hit rects))` per packet, after front submit
  (RealInkController :575–618).
- Cursor: shared HoldCursorPainter; front saveLayer + DST_OUT, window saveLayer + DST_IN; strict mode (default) drops predicted tip.
- E mode (`debug.reverie.fb_hole_mode=e`): first hit hands the stroke back to the normal path.

### Remaining per-hit costs (by code; no device numbers yet)

- Window re-record + full view redraw: on SDK 31 HWUI ignores the `invalidate(rect)` dirty rect, so every hit frame
  re-records `onDraw` (:2586) and redraws the whole view node. Splitting into a patch view / RenderNode is too invasive now.
- Tile DRAWN churn: `drawDisplayTiles` marks every visible tile DRAWN (:2774), so the next write of each forces COW
  (now delta copy) + a whole-tile texture upload (HWUI uploads per bitmap; no sub-rect upload without native).
  Resetting to UNDRAWN needs a "HWUI finished reading" signal — not done (unsafe without it).
- Backup-layer restore for guide/cursor erase (FrontBufferPreviewOverlay :1111 / :1146) = full CPU-bitmap upload per
  changed packet. Bake-based erase deferred (packet drops make the drawn state ambiguous).
- Render merger: `scheduleRender` already dedupes to one pending render (PaintViewModel :5804); no waste found.
- Not done: sub-rect texture upload, GLFrontBufferedRenderer (not evaluated in code).

## Device checklist (HONOR HEY-W09, Android 12 / SDK 31)

Compare each with low latency off (screenshots / slow-mo). Debug APK: `adb shell setprop debug.reverie.perf 1`,
`adb shell setprop debug.reverie.fb_identity_check 1` (restart app), then `adb logcat -s FbGeomLog`:
`ev=hole_arm ... arm=` shows held vs fallback per stroke, `ev=identity_mismatch` must never appear.

1. Plain brush / eraser / smudge with symmetry guides, selection ants, pixel-grid off: held, no flicker over decor.
2. Snap modes (perspective 2D, isometric, 2D grid), default: stroke falls back to the normal path (`ev=hole_arm` NORMAL)
   — must look identical to low latency off, no linger, no doubling.
   A/B: `adb shell setprop debug.reverie.fb_snap_hold 1` (restart app): stroke held, guide drawn by the front. Check
   pen-up and E mode (`fb_hole_mode e`): no alpha doubling; known residual ≤ 1 frame guide gap/stale position outside
   holes, ±1 colour rounding. Compare perceived latency ON vs OFF to decide whether ON is worth it.
3. Perspective 3D: window shows no cursor while the held stroke shows the front cursor (ABORT_FRAME quirk) — check it is
   acceptable / no stale cursor left behind.
4. First stroke right after pan / pinch-zoom / rotate (lift, wait ~0.1 s, draw): should be held (`ev=hole_arm` not a stale fallback).
   Drawing immediately (< 50 ms) may still fall back for that stroke — expected.
5. Pixel grid on (zoom ≥ 4×): falls back by design (coverage > 35 %); verify no visual difference and no added lag.
6. Long fast strokes across tile boundaries (incl. normal path, low latency off): no tile seams / stale pixels / old
   content flashes (COW delta). Also after undo/redo, layer toggle, document switch, canvas resize.
7. Latency/perf not worse than `d4281479` APK: same scene, compare perceived lag and `FbGeomLog` hit-frame timings.

## Round 4: compose — 覆盖层画进前缓冲 (默认), H-min 留作 A/B

**执行前声明**: 基于 `45b3006d` (974 测试全过) → `08816939` (纯几何 `model/FrontBufferCompose.kt` + 策略) 继续,
不重来; 不改 native、不碰 beginQuickShape/touchEnd 与 DrawingGuidePanel; 新提交只加在本分支, 不强推。
钉死: 上游 `beb49026`, fork 基线 `f2942769`, 本轮起点 `08816939`。

### 设计

前缓冲不再挖孔。每包在"墨脏区 ∪ 覆盖层旧/新足迹"的矩形并集 (≤ `FrontBufferCompose.MAX_PACKET_RECTS`) 内:
烘焙瓦片 (SRC 棋盘格 + 瓦片, 与窗口同一路径) → 按窗口同一顺序、同一函数/画笔/矩阵重画覆盖层:
像素网格 (`under` Picture) → 动态导引线 (`DynamicGuidePainter`) → 光标 (`HoldCursorPainter.drawAll`) →
绘图辅助线 (`over` Picture)。被覆盖的旧足迹由同一次烘焙"恢复", 不需要备份层。
窗口在按住期间照常冻结, 前缓冲每个像素 = 关低延迟时窗口该像素。

- 静态覆盖层 (`FrontBufferDecor` = under/over Picture + pose): 只在状态变化时重录
  (CanvasTouchView `composeDecorFor` :3746, onDraw 空闲时预录 ~:2861)。
- 动态覆盖层 (光标/导引线): 渲染线程台账 shown/base/next (RealInkController `presentCompose` :731);
  上一包被回收则 shown 回退 base, 足迹 = 旧 ∪ 新, 不变则零额外工作。
- 抬笔: `onStrokeCommitted` (:446) 在释放前补一包 `final=true` (覆盖层为空 ⇒ 清掉前缓冲上的光标/导引线,
  该处恢复成烘焙内容), 之后沿用"窗口提交后清前缓冲"。
- 验证: `debug.reverie.fb_identity_check 1` → `FbGeomLog.composeIdentityCheck`
  (FrontBufferPreviewOverlay :1198): 文档内任一像素 α≠0xFF 记 `ev=identity_mismatch mode=compose`。
- 统计: `ev=hole_end ... compose=包数 foot=足迹块数 bakePx slotPx bake=avg/max us`。
- A/B: `adb shell setprop debug.reverie.fb_hole_mode hmin` (重启 app) 回到 H-min; `e` 为整笔交还。

### 去掉的工作 (相对 H-min, 仅 compose 笔画)

- 孔洞掩码的录制/栅格化/膨胀/积分图 (`holeMasks.scheduleIfStale` 跳过, CanvasTouchView onDraw ~:2861; armRealInkHold :3660 跳过 decideArm)。
- 命中帧的窗口整视图重录重画 (`postInvalidateOnAnimation(hit)`): compose 只对 UI 遮挡区重叠失效, 与原来相同。
- 覆盖率 >35% (像素网格) 的回退: 网格现由前缓冲 `under` 重放, 不再回退。
- 吸附导引线 `fb_snap_hold` 回退: compose 下导引线由前缓冲画 (`windowGuideHolesOnly` 第 2 参, :3148)。
- 备份层恢复 (旧光标/导引线擦除): 改由烘焙恢复 (`drawComposePacket` :1105 不再走 erasePrev*)。

### 仍走 H-min 的情形 (不是 compose 的缺陷, 是两表面无法逐像素一致的情形)

- 可见选区蚂蚁线 (`composeApplies` :3729/`antsVisible` :3737): 蚂蚁线随时间流动, 前缓冲与窗口无法锁相。
- 旋转/镜像 (`FrontBufferCompose.isAxisAligned`, RealInkController :797): 窗口 GPU 双线性采样, CPU 烘焙复刻不出逐像素一致。
- 非画笔/橡皮/涂抹工具 (:3731)。

### 已知非恒等 (file:line)

1. 文档外的覆盖层 (蚂蚁线外的 UI/辅助线超出画布部分): 前缓冲只合成到文档内 (`docInner`, :797),
   文档外由 SurfaceFlinger 叠在冻结窗口上 —— 文档外窗口本就不变, 仅当覆盖层在文档外移动时有 1 帧滞后。
2. 文档边缘半覆盖像素 (`docInnerRect` 向内取整): 边缘那一列像素不重放 under/over, 显示冻结窗口值。
3. CPU 烘焙 vs GPU 采样 (缩放非整数倍时) — 早已存在, 与 H-min 相同。
4. 光标领先/EMA 与窗口不同 — 早已存在。
5. UI 遮挡区重叠处的双重绘制 — 早已存在。
6. 导引线快照读到撕裂 (`guideSnapshot.read`, :777) → 该包按 NONE 处理, 下一包补上。
7. 末包尽力而为 (:451): 槽位/内存失败时退回原来的抬笔清除, 1–2 帧内追上。
8. 末包瓦片可能未含最后几笔 dab: 窗口随后追上 (与原抬笔一致)。
9. 笔画中途位姿变化 → `handBack` (:749), 交还正常路径。
10. 透明文档且无棋盘格画笔时烘焙为 CLEAR ⇒ 文档内透明, 恒等检查会报出 (应不出现; 若出现即为缺陷)。
11. 缓冲尺寸变化清前缓冲 — 早已存在。
12. 3D 透视辅助: 窗口第 2.5 段中止整帧, 光标不画; compose 下前缓冲同样不画 (`dynamicGuideAbortsFrame` :3758)。

### 开销 (按代码, 尚无真机数字)

每包: 墨脏区烘焙 (与 H-min 相同) + 足迹块烘焙 (仅光标/导引线变化时; 光标 ~2r+4 方块 × 对称数) +
两段 Picture 在矩形并集内重放 (被 clip 掉的绘制只做裁剪测试)。无每命中窗口重绘, 无掩码重建。
真机用 `hole_end` 的 `bake=avg/max` 与 hmin 的命中帧耗时对比; 若 compose 更慢, 用 `fb_hole_mode=hmin` 回退。

## Round 5: compose 对称延迟 (足迹合并)

**执行前声明**: 上游 `beb49026` 未变 (merge-base `80be2910`); fork 基线 `f2942769`; 本轮起点 `6b7d300c` (compose 默认)。
真机 SM_X710 `arm=COMPOSE` 证据: `foot≈4/包` (旧+新光标 × 主+1 对称)、`bake≈453µs`、`pending=2 baked=2`。
相对隔壁 ready-gate 纯贴墨, 多出的是每包光标足迹重烤。

### 去掉的工作 (file:line)

1. **按 tip 并新旧光标足迹** (`FrontBufferCompose.appendCursorTransitionBoxes`, `RealInkController.presentCompose` ~:782):
   1 对称时 2 块而非分别 append 的 4 块; 并集覆盖新旧全部像素 → 恒等。
2. **丢掉已被墨脏区包含的足迹** (`dropContainedAfter` ~:798): 尖端光标常落在镜像脏区内 → foot→0。
3. **与墨相交的足迹并进墨块** (`absorbOverlappingAfter` ~:799): 少一次独立 `bakeRect`; 墨块撑大后全部按屏幕反算源。
4. **光标 half+2 → half+1** (~:784): `HoldCursorPainter.half` 已含 +2, 环 2.2px AA 只需再 +1。
5. **吸收后烘焙容忍无瓦片边距** (presentCompose bake 循环): 只要求墨前缀至少一块画到瓦片, 避免撑大后误失败。

### 预期 (按代码, 非编造真机数)

- 对称运笔且光标落在 dab 脏区上: `foot` 从 ~4/包 → ~0/包, `bakePx` 接近纯墨两块, bake 均值应明显低于 453µs, `pending` 回 0–1。
- 细笔光标大于 dab: foot 保留 1–2 块 (并合后), 仍少于旧的 4。
- 静态辅助线仍每包在脏区 `drawPicture` (SRC 贴墨会盖住线, 必须重画才能恒等); 此项不是本轮目标。

### A/B

- 默认 compose。`adb shell setprop debug.reverie.fb_hole_mode hmin` 回挖孔; 与隔壁 ready-gate 包比对称手感。
- `adb shell setprop debug.reverie.perf 1` 后看 `ev=hole_end ... compose=… foot=… bake=…`。
