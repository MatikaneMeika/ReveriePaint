# 前缓冲真墨原型 (REAL-INK-FRONT-BUFFER)

目标: 前缓冲不再显示"猜出来的几何", 只显示真实的东西。

## 1. 真墨模式 (frontBufferRealInkOnly, 默认开)

- 前缓冲只画 [屏幕上可见的引擎墨迹末端 → 最新真实采样点] 的回填段
  (`computePreviewStrokePath` 现有回填逻辑, 样本来自 getHistorical* + requestUnbufferedDispatch)。
- 所有运动预测器 (OPPO/vivo/华为/小米/系统 MotionEventPredictor/卡尔曼) 和悬停前瞻都跳过。
- 仍需打开设置里原有的"前缓冲预测"总开关 (它是整条前缓冲链路的开关)。
- 覆盖: `adb shell setprop debug.reverie.realink 0|1` (重进画布生效)。

## 2. 引擎草稿 dab (frontBufferEngineScratchEnabled, 默认开)

- `ReverieCore::renderScratchDabs` (ReverieCoreStroke.cpp) + JNI `renderScratchDabs`:
  在私有临时 KisPaintDevice 上用当前 preset 新建 paintop, 把回填段样本 paintLine 一遍,
  返回 RGBA tile + 文档矩形。不写任何图层, 不碰正在进行的 m_strokeOp/m_strokeDistance。
- Kotlin: `RealInkScratch` 封装 (缺符号 → 永久禁用), `CanvasTouchView.tryRenderEngineScratch`
  把文档 tile 经三点仿射贴到前缓冲 (`FrontBufferPreviewOverlay.renderScratchTile`)。
- 涂抹/混色/变形/滤镜/克隆/带蒙版笔刷不支持 (需要底层像素), 退回真实采样点 STAMP/折线。
- 连续失败 3 次本笔熔断 (`RealInkPolicy.FailureLatch`)。
- 覆盖: `adb shell setprop debug.reverie.scratch 0|1`。

## 已知限制

- 默认预编译 libreverie_jni.so 不含新 JNI, 必须 `-PbuildNative` 重编原生库草稿 dab 才生效;
  否则自动退回 1 的效果。
- 草稿 tile 以 OVER 合成在透明底上: 正片叠底等混合模式、湿边/累积与真墨仍有差异。
- 草稿每次新建 paintop, UI 线程与引擎线程并发只读 preset; 原型阶段, 需真机验证稳定性与耗时
  (PerfTrace `realink.scratch`)。
