# 空缓存客户端半径 256 的传输与热点（2026-10-04）

对照代码：`6b85623`（0.2.23）。本轮完成真实 Forge 客户端 / 专用服 socket 传输、双端 JFR 定位、算法与保存调度优化、关闭 JFR / 诊断日志的对照，以及单玩家调度参数试验。生产版本号、协议 17 和配置结构 7 保持不变。

成功标准：客户端启动前没有 `.voxy`，接收半径确认为 256 区块，完成全部目标列且覆盖无缺口；定位 CPU / 等待热点，在不改变缓存、版本、请求 ID、信用释放和主线程边界的前提下减少工作；通过构建、真实 Voxy 摄入和客户端请求生命周期验证。

## 测试条件与口径

- Windows；AMD Ryzen 7 9700X，8 核 / 16 线程，物理内存约 63.6 GiB。
- Java 21、Forge 1.20.1、Voxy 0.2.15；真实客户端 1280×720，固定原点 `(0,180,0)`，客户端与服务器位置一致。Voxy 显示半径 256 区块，即 4096 格。
- 每轮从同一服务器数据副本和无 `.voxy` 的客户端副本启动。源存档不修改。半径圆内 205,861 列，其中原版近景覆盖 313 列，实际远程应用 205,548 列。
- 距离档位 `32:0,64:1,96:2`；客户端接收缓冲 128 MiB、索引 256 MiB、请求窗口 256、占空比 1。
- 服务端 CUSTOM：4 worker、全局并发 32、单玩家并发 8、提交上限 8192/s、单玩家发送内存 32 MiB、全局发送内存 64 MiB、数据库缓存 128 MiB、带宽上限 30 Mbps、最大合批 16、Zstd 等级 1。
- 双方在同一台电脑，使用 loopback；服务器数据已预生成，仍按原配置允许少量后台缺失 / 刷新工作。本轮是缓存传输场景，不是首次世界生成速度或公网容量测试。
- “传输耗时”使用首片到末片实际到达时间；“应用完成”从首个解码操作开始到第一次接收队列 / pending / 内存 / 重试均空的 1 秒样本。脚本的 `elapsed_seconds` 包含等待稳定的 5 秒，不能拿它计算载荷吞吐。
- Mbps 为十进制 LOD 载荷，不包括 Forge 头、原版包、目录和重试。缓冲中的 MiB 为二进制。CPU 秒为进程 CPU 累计差，含 GC、渲染、原版与测量代码，不能解释为 MOD 独占成本。
- 首轮和算法消融开 JFR `profile`、普通诊断日志，用于定位。最终对照关闭两者，但保留相同 smoke 测量探针 / 每帧 CSV / 每 60 秒截图，因此也不等同于发行 jar 的零探针性能。
- 本机画面约 75 FPS，源配置 maxFps=120，保留原显示同步行为。low 使用最慢 1% / 0.1% 帧耗时的均值倒数，不用中位数证明提升。

## 最终关闭 JFR / 诊断日志的结果

以下为同一数据、空客户端、相同默认参数的一次顺序对照；对照四个生产类从 HEAD 提取单独编译，运行记录保存 class SHA-256，已核对确实加载了原版类。新版恢复当前编译类后运行，双方均正常退出。

| 指标 | 0.2.23 原版 | 本轮优化 | 优化 + 参数试验 |
| --- | ---: | ---: | ---: |
| 实际远程应用列数 | 205,548 | 205,548 | 205,548 |
| LOD 载荷 | 117.959 MB | 117.931 MB | 117.940 MB |
| 首片到末片 | 75.269 s | 62.344 s | 59.218 s |
| 平均载荷吞吐 | 12.537 Mbps | 15.133 Mbps | 15.933 Mbps |
| 首解码到接收空闲样本 | 76.426 s | 62.529 s | 61.258 s |
| 应用速度 | 2,689 列/s | 3,287 列/s | 3,355 列/s |
| 服务端平均 / p99 tick | 3.679 / 6.773 ms | 3.419 / 5.878 ms | 3.682 / 7.019 ms |
| 加载中 1% low | 53.88 FPS | 52.48 FPS | 52.55 FPS |
| 加载中 0.1% low | 46.24 FPS | 32.37 FPS | 42.02 FPS |
| 加载中最大帧耗时 | 25.27 ms | 69.10 ms | 26.30 ms |
| 客户端进程平均 CPU 核数 | 4.594 | 2.712 | 3.240 |
| 服务端进程平均 CPU 核数 | 2.190 | 1.679 | 1.963 |

代码优化的吞吐提高 20.7%，首尾传输耗时缩短 17.2%。相应采样窗口内客户端总 CPU 为 352.531 → 170.344 CPU 秒，服务端 171.156 → 105.281 CPU 秒；包含加载时间缩短和 Voxy 保存减少，不能据此声称稳定帧 CPU 降幅或单个算法降幅。缓存未命中分别为 29 / 0 / 0，不足远程列数的 0.02%；没有把生成吞吐当作传输吞吐。

同一无 JFR 对照中，客户端接收线程 CPU 为 43.984 → 46.406 秒，并没有下降；进程总量减少不能解释为接收线程或某个算法的 CPU 同比下降。

参数试验只将 `playerConcurrency=8→16` 和 `playerRequestsPerTick=256→1024` 一起提高，保留 worker 数、提交上限、内存和带宽。相对代码优化轮，首尾吞吐再提高 5.3%，平均 CPU 和 tick p99 都上升。这是两项参数的联合试验，未证明各自独立贡献，未修改生产默认值，也不是多玩家配置建议。

客户端覆盖 audit 三轮相同：L0=3552、L1=10912、L2=191397、缺失 / 不足=0，计入原版近景共 205861 列。收尾 mesh_queue=0；最终相同视角截图显示地形连续。几何约 77.6–77.9 MB、5385–5397 节点，存在网格收敛时间和空节点差异，未据此宣称像素逐一一致。

low 帧没有改善证据。优化轮开始接收约 1 秒时，客户端 G1 暂停 61.474 ms，与 69.096 ms 最大帧对应，拖低 0.1% low；不能删掉这帧再报告提升。排除截图前后 1 秒也仍为 0.1% low 48.33 → 33.61 FPS，说明这次长帧不是截图造成。稳定后 1% low 56.54 → 56.99 FPS，变化不足以证明收益。

## 采样确认的热点和已实施优化

### 服务端：不展开的高精度层仍逐字节读取

0.2.23 诊断轮：4 个 `Voxy Distant server` worker 有 3838 个执行样本，其中 2997 个经过缓存 `ColumnCodec.decodeLevels`，2070 个落在“不展开层”的 varint 检查调用行。真正 RocksDB 获取的累计时间远小于解码；网络批压缩累计仅约 2 秒，压缩不是主要 CPU 热点。

服务端存 L0–L4 全层，远端绝大部分只请求 L2。原代码为未请求层仍分配 long 调色板，并反复通过 FriendlyByteBuf → Netty ByteBuf 读取索引。现在保留状态 / 生物群系 / 光照 / 占据值与每个索引的边界校验，未请求层不分配 long 调色板；正常 1 / 2 字节 varint 直接读取原数组，3–5 字节合法写法沿用原 readVarInt。格式、编码输出和错误拒绝保持兼容。

只改解码及下面的元数据 buffer 复用，诊断 L2 解码 CPU 为 46.828 → 38.844 CPU 秒，总传输仍为 81.777 → 82.343 秒。这项消融确认减少服务端计算并不足以解决当时客户端保存等待。关闭 JFR 的最终对照 L2 缓存解码为 49.234 → 18.891 CPU 秒、分配 23193.7 → 22351.3 MiB，约 22.65 → 21.83 GiB。JIT 和采样开销造成幅度不同，不取最好的百分比作为稳定保证；Windows 线程 CPU 计时约有 15.625 ms 粒度，操作级累计数据配合 JFR 判断，不能当作每列的精确 CPU 延迟。

### 服务端：旧库按需元数据迁移的大 buffer 分配

未准备目录的旧库首次请求按需读压缩列前 25 字节。每次新建 Zstd 输入流默认分配较大的输入 buffer；诊断 JFR 在 `legacyMetadata` 栈归属到约 17,969 MiB 分配权重。现在使用 zstd-jni 已有 `RecyclingBufferPool.INSTANCE`，不创建自己的池或常驻解压线程；对应样本权重约 42–44 MiB。此权重是分配采样估算，主要适用于老库首次按需迁移，不能当作新库常规传输的固定收益。

### 客户端：同一批父节点被保存线程反复截取

0.2.23 诊断轮接收仅 205548 列，却产生约 135.5 万次 Voxy 实际保存。服务端粗列共享父节点；原 Batch 第一列立即发布，其后每 8 列或 24 节点发布；每列应用完又释放 coverage monitor，保存线程就可复制这些仍会在后续列继续修改的父节点。旧 generation 的保存之后会被重排，导致大量重复序列化、保存入队和锁争用。

JFR 显示接收线程叶样本中 ConcurrentLinkedDeque 入队 867 次，WorldUpdater 粗层插入循环 882 次。普通诊断累计 coverage 获取等待为 22.510 秒；JFR 仅记录超过阈值的 monitor 事件，batchFragment 等待合计 8.249 秒，二者口径不同。

现在 Batch 按唯一节点聚合，达到原有 24 节点阈值或批次结束再发布，没有每 8 列的强制刷新或首列立即刷新。RemoteClient 在一个完整已接收批次内持有 coverage monitor，仍逐列解码、展开和校验，不把所有列一起展开成第二份常驻大对象；保存线程只在批次完成后截取父节点。批内节点容量约束和批尾 `finally` 发布 / release 保留，取消、epoch 和主线程 hooks 仍不等待这个 monitor。

消融：只改节点聚合的传输 79.594 秒，收益有限；再合并批次 monitor 后为 63.381 秒。诊断累计 coverage 等待 22.510 → 4.062 秒（-82.0%），实际保存约 135.5 万 → 57.9 万（-57.3%），存储 save 累计 wall 188.262 → 93.306 秒。并行保存累计时间不能直接当成经过的秒数。

批次 monitor 也覆盖本批 palette 解码 / completion，所以大批次时保存线程会连续等得更久；本轮真实性能覆盖默认 16 列，协议上限 128 列未作为性能场景测量。已验证主线程 tick / status / full / close hooks 不等待此锁，未把 Voxy 存储写入或 flush 移回主线程。

## 仍限制速度的环节

1. **接收单 lane 的 voxel 插入 / mip / coverage 更新。** 最终诊断轮接收线程 2459 个执行样本，650 个经过真实 Voxy insertLevel，批处理发布保存仍占较大比例。它已经是低优先级后台线程；增加服务器线程不能消除它的串行成本。可继续减少粗层插入的逐 voxel 操作、重复 mapper / NBT 调色板处理，需保持 AIR、light、child mask 和网格 generation 语义。
2. **保存与 coverage checkpoint。** 批处理已显著减重复，最终仍约 58 万次实际保存，checkpoint 的 Voxy flush / 覆盖页编码 / WAL sync 仍在同一 coverage monitor 中。JFR 最终仍采到接收等待；异步拆 checkpoint 必须保证 voxel 持久化后才能清 guard，并处理新一轮写入与关服，不能直接去锁或提前标记缓存已完成。
3. **服务端缓存调度的三次 worker / 主线程往返。** 元数据 → 主线程确认 → 缓存解码 → 主线程确认 → 编码 → 主线程入队仍占单玩家 cacheActive 槽。首轮普通日志累计 `player_cache_concurrency` 阻挡约 45 万次、发送排队峰值不到 0.5 MiB，既没有内存预算跑满，也没有网络队列长期积压。可以把缓存命中读取 / 编码连在后台链上，最后统一提交，但需保持 unchanged 快路、加入需求、移动后精度变化、Dirty 版本、取消及精确 cache 内存释放。调到 16 并发后收益只有约 5%，不支持盲目继续增加 worker。
4. **请求突发限额。** 优化客户端后 diag 中每 tick 接收限额拒绝从 3909 → 15790 次，返回 retry 后造成额外包和等待。提高 limit 与并发联合试验只有小幅收益。若改生产，需要将客户端 / 服务端的预算按实际发送时间或协商节流保持一致；本轮未改协议 / 默认限制。
5. **内存分配与 GC。** 最终无 JFR 轮客户端接收峰值仅 29.4 MiB，却有约 2.60 GiB JVM heap 峰值；覆盖页、palette、long voxel 数组、Voxy 保存快照是不同来源。G1 长暂停已实际拖低 low，不能把吞吐提速等同帧率。应先减少实测分配，再考虑 GC 参数，不能通过延迟保存确认或跳过索引校验换取表面速度。

最终 diag 中远端主线程调用样本很少，未测到 Render thread 等 coverage monitor；真实锁住后台 coverage 后主线程 tick / status / full / close hooks 合计 305 µs。服务器重 CPU 仍在 worker，主线程只提交和调度。sample 数不足以声明主线程影响为零。

## 验证和消融审查

- `./gradlew --no-daemon test smokeClasses build`：21 个现有测试通过，0 failure / error。没有新建零碎单元测试。
- `python tools/server-smoke.py --diagnostics`：专用服集成退出 0。新增 sparse decode fixture 覆盖 32 masks、1 / 2 / 3 / 5 字节索引、未请求层非法索引仍拒绝；原缓存 / 生成隔离、目录、Dirty、信用、批次取消、导入和关服验证通过。故障注入的访问拒绝 / read-only RocksDB 错误为预期。
- `./gradlew --no-daemon -PclientSmoke runData`：真实 transformed Voxy 摄入 / RocksDB / mipper / stale mesh / 索引重建通过；新增同批 16 粗列共享父节点只发布一次、覆盖结果和 checkpoint 验证通过。
- `--request-check`：真实客户端接收 lane 的 delayed single / batch、旧 requestId、Dirty、retry、Abort、epoch、主线程不等 coverage 和 close 通过，双端 exit=0。
- 所有完整传输最终 audit `insufficient=0`、队列空并正常退出。主要最终截图人工检查连续性；静态截图不证明移动时绝无闪烁。
- 保留现有 Batch，去掉 columns 计数和首列 / 8 列分支；没有新 worker、线程池、协议、常驻预编码库、第二套内存账本或吞错误。复用库自带 buffer pool；解码仅增加热路径的 1 / 2 字节读取。生产修改仅四个类，测量 fixture 仅在 smoke，不进入发行 jar。
- 第一次无 JFR 对照因窗口失去焦点自动打开 PauseScreen，16,475 列后接收主动暂停；该轮明确排除，未当作网络卡死或有效速度。测试工具的固定相机 receive 命令现在关闭失焦暂停并关闭该测试屏幕。有效新旧对照双方使用同一修正后的 harness。

## 复现与证据

```bash
./gradlew --no-daemon test smokeClasses build
python tools/lod-live-benchmark.py --profiles default --rounds 1 --rate custom --radius 256 --cold-client --acceptance --capture-interval 60 --tag cold256-repro
# 双端定位（单独报告采样开销）
python tools/lod-live-benchmark.py --profiles default --rounds 1 --rate custom --radius 256 --cold-client --profile-pipeline --acceptance --debug --capture-interval 60 --tag cold256-profile
# 仅参数试验
python tools/lod-live-benchmark.py --profiles default --rounds 1 --rate custom --radius 256 --cold-client --acceptance --player-concurrency 16 --server-requests-per-tick 1024 --capture-interval 60 --tag cold256-tuning
```

本地根目录 `build/benchmarks/lod-square256-cache128/live/`；有效运行标签：`cold256-v023-baseline`、`cold256-skipdecode`、`cold256-batchnodes`、`cold256-batchlock`、`cold256-clean-before2`、`cold256-clean-after2`、`cold256-tuned`。每轮有 `run.json`、`source.patch`、`build-hashes.json`、`receive-start.json`、`acceptance.json`、`measurement.json`、双端 samples / operations、client frames / transport / coverage、exits、screenshots；diag 轮另有双端 `pipeline.jfr`、`jfr-hotspots.csv`。

对照原类目录 `build/cold256-before-source/` / `build/cold256-before-classes/`，恢复的最终类快照 `build/cold256-final-classes/`。性能产物和临时 JFR 分析 / JVM 诊断脚本仅在 build，不纳入 Git。报告中的数值只来自明确列出的运行，不混用 README 旧数据。
