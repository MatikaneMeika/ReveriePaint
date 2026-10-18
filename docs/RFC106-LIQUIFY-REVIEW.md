# RFC106 液化与瓦片显示审查

日期：2026-10-18

基线：本地 `codex/rfc106-review`，审查时 HEAD 为 `358f8e34`

本次为静态源码审查，未修改应用代码、未运行构建或真机复现。用户反馈液化偶发黑色矩形，但尚缺设备、构建版本、预览路径及导出结果，不能将以下风险等同于已确认根因

## 优先修复的正确性问题

### 1. 已发布瓦片的就地写没有真正排除 UI 读取

`PaintViewModelTiles.kt::doRenderTiles` 将状态 CAS 为 `TILE_WRITING` 后写像素；`CanvasTouchView.kt::drawDisplayTiles` 仍然先读取位图，再调用 `markDrawn` 并绘制。`DisplayTileState.kt::markDrawn` 无条件将状态设为 `TILE_DRAWN`，不等待或避开正在写入的位图

可达时序：引擎抢到 WRITING → UI 取到同一位图并标记 DRAWN → JNI 与显示读取重叠。事后检测 `tornInPlace` 和补发 invalidation 不能撤回已读取的中间像素。位图引用与状态分开发布也应一并检查

建议先关闭已发布网格的就地写，保持 COW；未发布的 staging 网格可继续直接写。对已显示位图的池化复用还需单独验证生命周期，不能将引擎循环推进三次当成 HWUI 已消费完成的证明。首轮可用不复用退休位图的诊断配置作 A/B，再确定内存可接受的正式策略

影响：所有瓦片显示工具、前缓冲真墨、参考窗口快照、文档切换。异常可能表现为局部撕裂或矩形内容错误，具体黑色外观未复现

### 2. 写入失败后丢失恢复标记

`beginTileFrame` 会清除引擎脏区；Kotlin 通过 `needsFullWrite` 保留失败瓦片的恢复责任。但 `doRenderTiles` 连续失败超过阈值后执行 `needsFullWrite.fill(false)`，把尚未成功写入的瓦片标成无需恢复

这会使旧像素或临时液化底图持续保留；对于 staging 网格，还可能在后续局部成功时失去完整初始化的保证

建议停止自动重试时保留待补写标记，用独立退避状态限制重试；所有待恢复瓦片成功之前不得宣告液化提交画面就绪

### 3. 液化上屏确认与实际绘制对象脱节

`publishTileSnapshotIfNeeded` 为液化提交拼整图并发布 Bitmap fence，UI 实际绘制瓦片后却用 `displayBitmap` 身份消费该 fence。瓦片逐个更新，失败瓦片也可能未恢复，因此 Bitmap 身份无法证明本次提交对应的瓦片集合已经完整绘制

此外，`onTileRenderNoWork` 拼出快照并发布 fence，却未将该快照赋给 `displayBitmap`，UI 通常仍传入旧对象，可能无法及时撤下覆盖层

建议以完整成功提交的瓦片批次作为消费标识，UI 确认读取相应批次后撤下预览；处理并发更新及失败重试。若先做最小修复，应先修正无工作分支对象不匹配并禁止失败帧确认，而后单独消除整图快照

## 黑色矩形的另一条排查路径

`LiquifyGlesOverlay.Renderer.render/uploadSrc/uploadGrid` 在源纹理、underlay 和网格上传后更新尺寸或世代，但未就地确认上传成功。`awaitFrame` 取出数据后清空 pending，因此上传失败不保证自动重传。现有提交回读有 GL 错误检查，不能由此推定显示上传也受保护

建议在纹理创建、尺寸变化和源更新处验证 GL 状态，失败不更新成功世代、不继续正常确认该帧，接入现有失败回退；补点重放与事务收口必须保留。无需在每个 dab 增加同步查询

这是显示异常候选路径，尚无驱动错误日志证明它导致用户黑块

## 性能改进顺序

1. 完成正确性修复后，用瓦片提交标识替代仅用于液化 fence 的整图快照。4096² RGBA8 一张快照为 64 MiB；现有两个快照槽可能占 128 MiB，实际分配依触发情况而定。参考窗口仍可保留按需快照
2. `liquifyDrainMaterialize` 当前仍将多个 band 并成 `changed` 包围盒，再一次投影合成。记录 band 实际面积与包围盒面积，只有无效面积明显时再复用 DirtyRectList 分区域合成，保留复杂依赖扩张和回退
3. 统计包含投影合成的 drain 总耗时，判断现有预算是否低估。不要仅为满足帧预算而丢弃物化、输入或事务尾部工作

## 验证与协作边界

- 首先确认反馈来自正式版还是 RFC106 测试包；旧版本的黑块不能归因于新瓦片实现
- 记录 GPU 场、GLES 网格、AGSL 或 CPU 路径，以及黑块发生于拖动还是抬笔
- 黑块消失于刷新/缩放，还是保存重开与导出仍存在，分别指向显示路径或文档写入路径，但都需结合日志确认
- 对照：瓦片与整图、关闭就地写与原实现、GPU 预览与 CPU 路径，每次仅改一个变量
- 覆盖跨瓦片边界、透明与半透明内容、选区、Alpha 锁、连续手势、立即撤销、前后台和低内存；注入单块写入失败以验证恢复标记不丢失
- 拆分提交：瓦片读写与失败恢复 → 液化上屏确认 → 纹理上传失败回退 → 离散区域合成性能优化
- Kotlin 变更执行 compileDebugKotlin，纯逻辑加单测；C++ 改动实际重建 native 库并真机回归，记录 APK/native 身份

不需要引入完整缓冲协调层、独立文档线程或新的液化引擎


## 2026-10-18 实施记录

已在该基线上落实以下范围受限的改动：

1. 已发布网格改为按帧复制引用、命中瓦片 COW；未命中的位图跨帧共享。只有未发布 staging 可原地写。移除通过引擎帧数判断 HWUI 释放的冷却池，已发布位图交 GC，只有失败且从未发布的缓冲可以复用
2. 自动重试最多三次，耗尽后保留失败瓦片的 pending 标记，下一次外部渲染请求继续修复。staging 未完整写入前不发布，也不确认液化结束
3. 液化 fence 接受实际绘制的不可变瓦片帧，沿用整图路径的 Bitmap 确认方式。去掉仅为液化确认而生成的整图快照；参考窗口快照保留。等待液化确认时要求主画布刷新，避免前缓冲截留刷新
4. 液化物化批次使用固定容量 DirtyRectList 保留离散 band，普通绘画图层场景最多八次区域合成；任何非绘画图层或需要图依赖合成的场景继续走原有包围盒/图投影路径。统计面积改为本次合成区域面积之和（重叠区域按实际调用累计）

影响复查：前缓冲只读帧引用，不依赖旧的 DRAWN 状态；主画布固定读取一份帧引用；文档切换继续沿用 discard 代际与引擎线程清理；液化开始/取消沿用原 fence cancel；JNI 方法签名、笔画事件、录制协议、液化形变、Undo 事务及引擎调度均未改变

验证：

- `:app:compileDebugKotlin` 与 `:app:testDebugUnitTest` 通过，101 个测试套件、835 项测试、0 失败（含新增恢复和上屏身份测试）
- DirtyRectList 宿主机测试 16 项通过，包含离散物化 band、容量合并覆盖完整性及连续 band 合并
- `scripts/check_architecture.sh` 与 `git diff --check` 通过
- 使用本机 NDK 25.2/CMake 3.22 尝试配置 native 构建，因缺少 `/home/xuantree/Projects/krita-source/plugins/impex/psd/psd_colormode_block.cpp` 失败；默认 Qt/Krita/KF6 开发路径也未安装，未生成或替换预编译 JNI 库
- `adb devices` 未发现设备，未执行真机回归、黑块复现、GPU 故障注入或性能 A/B

已知代价：为保证 HWUI 使用期间不再写入旧像素，普通更新会增加 COW 位图分配；收益不能仅凭删掉整图液化快照推断。真机验收必须同时测连续普通笔画/液化的帧耗时、分配与 GC、PSS 峰值、抬笔尾延迟，并复核复杂图层回退。GLES 上传错误检查仍属于后续独立修复，不在本次三个阶段内


## 本机 native 环境补齐与测试 APK

上述 native 环境缺失已解决。隔离依赖位于 `.local/native-env/`（约 1.5 GiB，Git 忽略），使用 NDK 25.2、Qt Android 6.6.3、KF6 6.6.0、`LanRhyme/krita@ef7ab16f90a025d771017e8ed54aaebb5fbf7327` 配套源码头文件。环境准备脚本来自本仓库历史 `fa0bffb7^`，副本及本机增量构建脚本保存在 `.local/native-tools/`

Krita/KF6 的配置与导出头文件按历史环境脚本生成，并保留 Android RGB8 库所需的 FLOAT16 掩膜工厂兼容设置；Krita/Qt 运行库沿用该分支现有产物，重建了全部 41 个 JNI 构建步骤。未重编 Krita 本体，符号检查不替代运行时 ABI 与行为验证

复用命令：`bash .local/native-tools/build-test-apk.sh`。该脚本增量编译 native、strip 新 JNI，并通过本地 Gradle init 脚本将它打入 APK；不会修改受 Git 跟踪的预编译库

产物：`build/test-apks/ReveriePaint-rfc106-liquify-debug.apk`

- 独立包名：`com.reverie.paint.rfc106liquify`，版本 `1.5.1-test`，debug 签名，arm64-v8a
- APK：116,410,164 字节，SHA-256 `b52ac3fe1e3fa07b5466028689cba4a23df84edf47c9046fae92ca1af1129fa6`
- 新 JNI SHA-256：`ce76a6807c3b08ac58e909a8f03c58b94ed7b2f0f18d573de5c5b9afc443a807`，与 APK 内一致，325 个 JNI 导出均保留，未发现未解析强引用
- 完整 Gradle `assembleDebug` 与 JVM 测试任务成功；APK 签名与 ZIP 检查通过，118 个 arm64 库齐全，依赖闭包无缺失
- 其余运行库与仓库内容一致；AGP 对 KF6Archive、quazip、track_prediction 的常规 strip 已分别归一化比对通过
- 核验报告：`build/test-apks/apk-verification.json` 与 `native-verification.json`
- 仍未连接真机，未宣称黑块消除或性能收益已验收

## 本阶段最终核对

用户已反馈第二版连续撤销/重做真机测试稳定。本条是用户回归结果，未补填设备、时长、次数及 CPU/GPU 路径，也不代替性能 A/B 或黑矩形专项验证

对照 #106 作者最新液化说明（https://github.com/LanRhyme/ReveriePaint/issues/106#issuecomment-6234571428）：首轮保留液化算法、线程及事务，仅核查脏区接入、静态底图失效和包围盒造成的合成面积扩大。本地完成情况：

| 范围 | 实施/检查结论 | 验证边界 |
| --- | --- | --- |
| CPU 物化及 GPU 提交脏区 | 两条路径经 markRegionDirty → addDirtyRegion 进入 DirtyRectList | 已源码核对；未新增两条路径的真机像素对照 |
| 静态底图失效 | markRegionDirty 按区域作废；撤销/重做 markDirty 整体作废 | 已源码核对；未报告复杂图层专项真机覆盖 |
| CPU 物化包围盒扩大 | 普通绘画图层批次保留最多八块离散区域；复杂场景保留原合成 | native 与宿主机测试通过，未测实机耗时收益 |
| 液化上屏确认 | 用实际瓦片帧确认，去除仅为液化结束拼接的整图快照 | 实现及构建完成；参考窗口快照保留 |
| 空闲追赶与刷新 | tick 去重，物化后检查脏区并刷新，包含最后一批 pending=false 的情况 | 837 项 JVM 测试包含调度门控；用户未单独确认预览延迟变化 |
| 历史稳定性 | 开放液化事务拒绝历史恢复，历史操作前同步，追加命令诊断 | 用户确认连续撤销/重做稳定；最初非法释放根因未通过 allocator 证据独立证实 |
| 瓦片读写与失败恢复 | 已发布帧不可变，失败标记保留，staging 完整后发布 | 编译与逻辑测试通过；长期分配/GC 与低内存故障注入待测 |

结论：约定的小范围实现已完成，可进入协作评审与有限场景验收；定量性能验收尚未完成。需要同设备、同文档、同操作对照 #106 原分支与第二版，观察预览延迟、抬笔尾延迟、PSS/GC 和合成面积；尤其检查严格 COW 增加的分配是否抵消显示与合成收益

GLES 纹理上传错误检查、GPU 分块源/回读、扩大准入及新的液化算法均不是本次已完成项。本次作为相对 `rfc106/perf-pipeline` 的源码增量提交，是否合入由上游评审决定


## 提交与构建边界

本次提交包含源码、纯逻辑测试、宿主机脏区测试及本审查记录；不包含 APK、原始崩溃日志、`.local` 构建依赖或预编译 JNI。测试 C++ 合成和历史保护必须重新编译 native（`buildNative` 或等效 CMake 构建），默认预编译模式不能验证这些新逻辑

最终验证包为第二版 `ReveriePaint-rfc106-liquify-v2.apk`，SHA-256 `7813859adc49b7bc3b449034403eab9c2be0d8d2d71b02daff986682a186d243`；JNI SHA-256 `9ad7e25c069d4283849dba2820eab74523d6e0337d521fdf0fee795585693f97`。用户确认连续撤销/重做稳定；预览延迟和黑矩形专项结果仍保持待验收

AI 辅助声明：Codex 参与源码审查、代码实现、测试与文档整理，真机验收反馈由用户提供；不代替提交者在 PR 模板中进行本人审阅与硬件信息声明

提交前补充检查：`:app:lintDebug` 使用仓库既有 `lint-baseline.xml` 通过，报告 252 条警告与 4 条提示，baseline 未修改；未将通过表述为全仓库零告警
