# 低延迟前缓冲：覆盖层重放 (L-01) + 就绪门控 (L-02) + 诊断日志 (L-03)

基线：fork `feat/front-buffer-panel-evasion-1.5.5` @ f2942769。分支：`fix/lowlatency-overlay-ready-gate-20261023`。
调查报告：LOWLATENCY_OVERLAY（box `/workspace/shapefill/review/LOWLATENCY_OVERLAY.md`）。
路径相对 `app/src/main/java/com/reverie/paint/`。

## 上游复核（LanRhyme/main @ beb49026，比对 80be2910..main，共 14 个提交）

只列改到 FrontBuffer*、CanvasTouchView、CanvasOverlay、PaintViewModel 静默逻辑的提交。

| 提交 | 改动 | 影响本分支？ |
|---|---|---|
| ed32b1a8 修套索选区预览偏移 | CanvasOverlay.kt:184-191（上游行号）把覆盖层位图尺寸从 `renderW/renderH` 改成 `tiles.grid → displayBitmap → docWidth`；CanvasTouchView 同类改动 3 处；PaintViewModelProjects 加载后重新激活笔刷 | **有关**。前缓冲重放的 Picture 必须和 Compose 覆盖层用**同一个**位图尺寸，否则辅助线在脏矩形里外会错开。本分支把这个口径收进 `CanvasDecorDraw.overlayBitmapWidth/Height`，Compose 覆盖层和 Picture 共用。**本分支不合入该提交**；以后合并时只改这一处即可。它也佐证了「renderW 与瓦片尺寸在进画布初期可能不一致」这一分裂假说，所以门控里加了「瓦片已按最终尺寸出图」 |
| e6fc8a5a 渐变工具 | CanvasOverlay +188 / CanvasTouchView +166（渐变预览）| 只在渐变工具下生效，画笔落笔不经过。不影响本分支；以后合并 CanvasOverlay 时可能有文本冲突 |
| 40e4ee88 快捷操作缩放/旋转 | PaintViewModel.kt +4 | 不涉及静默窗或前缓冲，不影响 |
| ad34b7d6 | 合并 #121 | 内容同上 |
| 其余 10 个 | 不涉及上述文件 | 不影响 |

上游没有改动 `frontBufferQuietUntilMs` / `NEW_CANVAS_FRONT_BUFFER_QUIET_MS` / FrontBufferPreviewOverlay / RealInkController。

## L-01 前缓冲内重放覆盖层

- 只在真墨按住（`RealInkController.held`）时生效；#81 前沿预览本来就透明，不受影响。透光台（ShiftTrace）仍然不按住，行为不变。
- 落笔时（`armRealInkHold` → `realInk.arm` 返回 true 后）在 UI 线程把以下内容录成一份 `android.graphics.Picture`（屏幕坐标，变换与 CanvasOverlay.kt:193-206 / CanvasTouchView.drawCanvas 相同）：
  - 绘图辅助线：直接调用现有的 `drawDrawingGuides`（CanvasGuidesOverlay.kt:27），经 `CanvasDrawScope` 画到 Picture 的 Canvas 上；
  - 选区蚂蚁线：从 CanvasOverlay.kt:1189-1222 抽出 `CanvasDecorDraw.drawSelectionAnts`，Compose 覆盖层和 Picture 共用；
  - 像素网格：从 CanvasTouchView.kt:2856-2903 抽出 `CanvasDecorDraw.pixelGridAlpha / fillPixelGridLines`（纯函数，有单测），视图和 Picture 共用。
- 斑马纹蒙版不录：它只在选区工具下显示（CanvasOverlay.kt:1042 `isSelectionTool`），选区工具不会按住真墨。
- 动态吸附线（CanvasTouchView.kt:3181-3320）每个 MOVE 都会变，不进 Picture。计算部分抽成 `computeDynamicGuide`，结果是两点加可选灭点（6 个 float）；绘制部分抽成 `DynamicGuidePainter`，视图和前缓冲各持一份画笔。按住期间每个 MOVE 把结果写进 `RealInkController` 的双槽快照，前缓冲在 GL 线程读出来画。
- `drawInkPacket` 中的顺序：`blitInk`（SRC 贴墨）→ 只在本帧脏矩形内、扣掉 UI 孔径 → `drawPicture(decor)` → 吸附线 → 光标/预测笔尖。备份层（擦光标用）也照这个顺序画，擦掉旧光标后不会露出没有辅助线的墨。
- 中途重录：每个按住期间的 MOVE 都比较一组引用（drawingGuide、选区轮廓 Path、hasSelection、像素网格开关、pose、renderW/H、docW/H、辅助面板开关）。有变化才重录，新录的 Picture 换一个对象发布，不复用正在被 GL 线程画的那个。
- 零额外帧：前缓冲本来就要画这一帧，没有新增 invalidate 或提交。每帧也不分配对象：裁剪 Path 和 float 数组都预先分配。
- 开关：`FrontBufferFeatureFlags.holdDecorReplay`，默认开。`adb shell setprop debug.reverie.fb_decor 0` 后重新进画布即可关闭。
- 已知限制：脏矩形内的蚂蚁线定格在落笔时的相位（相位 0），矩形外的仍在流动。

## L-02 事件门控替代固定 3.2s 静默

- 纯类 `model/FrontBufferReadyGate`（有单测）。落笔时在 `armRealInkHold` 里求值**一次**，结果锁存到这一笔结束（`strokeFbReady`）。真墨按住和 #81 预览都读这个锁存值。所以门控前开始的一笔整笔走普通路径，中途不会切换。
- 条件（全部为真才就绪；位掩码写进日志）：
  1. `TILES_FINAL`：已发布的瓦片网格尺寸 == renderW/H == coreW/H，且 `!displayBufferInvalid`。〔现有信号〕另外新增 `DisplayTileState.generation`（每发布一次网格就加 1），只用于日志。
  2. `RENDER_IDLE`〔**推断信号**〕：native 的 `m_nativeProjectionValid` 和合并器忙闲没有 JNI 访问器，暴露它要改 native，本任务禁止。所以用 Kotlin 侧的代理：渲染线程每帧结束时记一次 `!renderPendingDirty() && !retryNeeded`（现有 JNI），再加上 UI 侧 `!renderScheduled && !displayBufferInvalid`。
  3. `GEOM_STABLE`：前缓冲 SurfaceView 的 `getLocationInWindow`、宽高、视图宽高、fitScale、缩放、平移、旋转，**连续 2 帧**不变。用 Choreographer 帧回调采样，只在进画布的静默窗内运行（窗外反正已经保底放行），不触发重绘。
  4. `REFRESH`：`display.refreshRate >= 面板最高刷新率 - 5`（沿用 `checkAndRestoreHighRefreshRate` 的判据，CanvasTouchView.kt:2487）。面板最高不超过 60Hz 时视为满足。
  5. `NOT_LOADING`：`!isBlockingLoading`。
  6. `FALLBACK`：`now >= frontBufferQuietUntilMs`（进画布 + 3.2s），满足时直接放行。和旧行为等价，所以最坏情况也不会比现在晚。
- 开关：`FrontBufferFeatureFlags.readyGate`，默认开。`setprop debug.reverie.fb_ready_gate 0` 回到纯 3.2s 计时。

## L-03 诊断

debug 版 `FbGeomLog.strokeBegin` 每次落笔多打一行 `ev=stroke_ready`（release 是同签名空实现）。字段：
- `entry_ms`：距进画布的毫秒数；
- `sv`：SurfaceView 的窗口坐标和尺寸；
- `hz`：当前/最高刷新率；
- `render`：renderW×H；
- `core`：coreW×H；
- `tiles`：瓦片网格尺寸；
- `gen`：瓦片 generation；
- `pose`：投影参数；
- `dbinv`：displayBufferInvalid；
- `busy`：渲染忙（推断）；
- `loading`：isBlockingLoading；
- `stable`：几何连续稳定的帧数；
- `ready`：门控结果；
- `cond`：每一项条件是否满足。

## 实施补记 (L-01/L-02/L-03 落地, 2026-10-23)

### 与辅助线面板改动的兼容
1. 参考线可见性只看 `guide.mode != OFF`, 不看 `assistedDrawing`; 前缓冲重放的参考线
   与 `CanvasGuidesOverlay` 共用 `CanvasDecorGeometry` 判定。单测:
   `CanvasDecorGeometryTest` 覆盖 perspective3d + assistedDrawing=false ⇒ 可见。
2. 辅助线面板 (Tool.DRAWING_GUIDE + QuickShapeCollapsibleDrawer) 打开时经
   `overlayPanelsOpen` (含 drawingGuidePanelOpen) 让 wantsHold=false, 不走前缓冲,
   因此无需 UiPanelSlot 避让。若以后把该面板移出 `overlayPanelsOpen`, 必须改为注册
   UiPanelSlot 进入 clipOutUi, 否则前缓冲会盖住面板。
3. QuickShape 在笔画进行中不画预览 (只有 TOP_HUD_TOAST 提示), 不在重放范围内;
   `beginQuickShape` (UP 处理) 未改动。

### 落地差异
- 门控信号中 render idle (renderScheduled / follow-up / in-flight) 与刷新率
  (display.refreshRate ≥ 最高模式 − 5Hz) 为推断信号, 日志 `inferred=[idle,hz]` 标注。
- 工程加载路径的“进画布时刻”为加载结束时刻 (静默窗起点)。
- 开关: `debug.reverie.fb_decor` / `debug.reverie.fb_ready_gate` (默认 1, 进程内只读一次)。
- 未测量: Picture 录制耗时、GL 线程向 sc 软件 drawPicture 的耗时; 需真机 trace。
