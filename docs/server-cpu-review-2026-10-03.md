# 服务端 CPU 与主线程审查（2026-10-03）

基准代码：`d27412c`。本次审查覆盖专用服务器 `RemoteServer`、原版区块优先调度 `PriorityState`、缓存与导入路径，并核对单人内置服务器的 `GenerationController`。结论是：服务端存在随队列规模扩大的主线程重复计算，数据库 IO、LOD 转换、编码与压缩则已经在后台；本次直接修复前者，并进一步异步化目录组装。

本轮成功标准：消除请求选择的反复全扫描，减少批次选择和信用计算的重复工作，限制每 tick 的清理量；同时保持原请求优先级、老请求轮换、取消、版本失效、内存信用和传输进度。没有把算法微基准降幅当成整体服务端 CPU、MSPT 或客户端 low 帧收益。

## 已修复的主线程开销

| 路径 | 原行为 | 本次修改 |
| --- | --- | --- |
| `RemoteServer.schedule` | 每取一个请求都扫描玩家所有 pending，再次计算移动预测评分；固定位置下完整排空队列接近 O(N²) | `PendingRequests` 保留插入顺序，并缓存空间评分建立最小堆；固定位置下排空为 O(N log N)，位置或预测范围改变时 O(N) 重建 |
| `RemoteServer.flushBatch` | 为每一批完整排序所有 ready，排序比较反复评分 | 每条候选只评分一次，再 heapify，按优先级取候选；选择复杂度为 O(N + B log N)，B 是实际检查的候选数量，受预算拒绝时仍可能检查全队列 |
| 批次接收信用 | 每加一个成员就扫描已有成员，批内接近 O(K²)；每个碎片再次扫描全部成员 | 组批时增量维护最大 scratch；`Transfer` 创建时缓存最精细层级的 scratch，每碎片信用计算 O(1) |
| `RemoteServer.complete` | 每个后台完成回调都立即调用全局调度 | 同一主线程任务队列内的完成回调合并补位，仅排一个 `TickTask`；请求与回执仍可即时推进。没有固定推迟到下一 tick |
| `RemoteServer.processWork` | 每 tick 复制并排序所有 work，只用于前景/后台两类分组 | 两次线性收集，保留原类别内顺序和前景优先 |
| `savedUnloads` | 每 tick 全量检查，包括大量仍然加载的已保存区块 | 每 tick 轮换最多 128 项；不能清理的放回队尾，清理耗时不再随累计集合无界增长 |
| `scheduleDirectories` | DB 读取在后台，1024 列的版本/精度掩码组装回主线程执行 | 组装也在读取 worker 执行，主线程只提交结果与应用读取期间的稀疏变更 |
| 原版已发送区块清理 | 在 `flushSends` 中每 40 tick 清理；同 tick 多个完成回调会反复执行 | 移到 tick 路径，每 40 tick 执行一次 |
| 距离分档 | 读缓存、编码分发时逐列重复解析相同配置 | 缓存解析结果；配置内容改变后重解析 |
| 组批触发 | tick、编码完成先 `flushBatch`，随后 `flushSends` 内再次 `flushBatch` | 统一由 `flushSends` 触发，去掉这些重复调用 |

`PendingRequests` 仍在服务端主线程使用。没有为纯调度增加线程、锁或消息协议；算法改善本身就能降低该线程的计算量。保留了每 20 tick 按插入顺序轮换老请求的既有行为，以及评分相同时按距离、x、z 的顺序。

## 异步目录的一致性

worker 读取不可变查询、数据库元数据及现有 `ConcurrentHashMap` 版本表，不访问玩家或活区块。主线程 `markDirty` 在查询读取期间继续记录 `directoryChanges`；完成时只把版本比目录更新的槽位清零。

查询取消、玩家退出和 epoch 改变仍由主线程身份检查丢弃旧结果。目录排队后发生的变化继续直接清除其掩码；发送前原有 1024 槽最终版本复核也保留。因此，后台组装不允许过期目录把已失效的列误报为可用。最后这次发送复核仍有 O(1024) 主线程成本，本轮没有宣称目录整条链完全异步。

## 已经在后台的工作

- RocksDB 打开、读列、写列、失效批次、后台索引扫描、目录迁移、缓存统计与缓存重开。
- 六线程旧缓存索引读取与索引重建。
- 脱离活世界的区块快照转换、列编码、Zstd 压缩和批次压缩。
- 保存区块的 RegionFile/NBT 读取、解析与独占导入。

这些路径没有发现“每 tick 在主线程直接读写地形缓存文件”的行为。数据库内部仍有写入与元数据 monitor，它们会影响后台工作进度；本轮没有证据证明专用服务器日常主线程正在等待这些 monitor。

## 仍占用主线程的路径及后续机会

| 路径 | 当前边界 | 后续优化方向 |
| --- | --- | --- |
| `processWork` 活区块快照 | `getChunkNow`、状态/生物群系/光照复制必须读取活世界；默认快照预算 2 ms，但预算不是整个 MOD tick 的硬上限，也不能中断单次 section 复制 | 先采真实运行的 section 复制耗时与尾延迟；减少重复复制或改由区块事件提供脱离世界的快照，再后台转换 |
| `PriorityState.tick` | 每 tick 最多检查 512 个原版范围槽位，读取区块和光照完成状态 | 由区块状态/光照事件维护待完成数量，可减少稳定场景反复检查；需要验证移动、依赖区块和加载状态变化，不能直接把活区块读取交给 worker |
| `flushSends` | 主线程仍选择传输、检查成员版本/范围、复制碎片和调用 Forge 编码发送；发送循环有次数及带宽限制 | 在大量缓存传输场景下测分片复制、批次成员检查、序列化占比；如确认为热点，再把不可变负载准备交给发送 worker，主线程保留信用、取消与会话提交 |
| `markDirty` / `flushDirty` | 同 tick 同列去重，失效持久化在后台；目录变更和客户端通知按订阅者数量增长 | 多玩家同时大面积修改地形时，评估按 region 索引查询及合并失效通知，避免每列扫所有订阅者 |
| 单人 `GenerationController` | 范围发现、快照与 ticket 在内置服线程；调用同步的 `CoverageStore.hasColumn`，与 Voxy 摄取/保存共享 coverage monitor | 这是与专用服务器不同的潜在等待路径。需单人实际 JFR 确认，再考虑不可变覆盖目录快照；本轮未改共享 coverage 生命周期 |

区块 ticket 增删、玩家/世界边界读取，以及会话、任务和信用状态的提交继续由服务端线程拥有。整体搬进异步线程会引入活世界并发读取与过期结果问题；合适的边界是主线程发布不可变状态，后台计算，再主线程小范围提交。

另外存在明确的同步生命周期操作：管理员保存配置时 `DistantConfig.saveServer` 的 TOML 读写/原子替换，独占导入准备时 `saveEverything`，关服时等待写入并关闭数据库。它们不是日常 tick 反复执行的热点。若要进一步消除管理员操作的瞬时停顿，配置文件准备可先后台执行，成功后再主线程发布配置；原版世界保存与关服持久化需要单独设计生命周期一致性。

## 验证与数值边界

### 请求选择 CPU 微基准

`tools/ServerQueueCpuCheck.java` 分别执行原全扫描和生产 `PendingRequests`，同一 seed、同一移动预测、每 20 次选择穿插老请求轮换，完整排空相同队列。预热 100 轮，测量 300 轮；使用线程 CPU 时间，两个算法的顺序 checksum 一致。

| 单队列长度 | 请求选择总数 | 原扫描 CPU | 新队列 CPU | 降幅 |
| --- | ---: | ---: | ---: | ---: |
| 256 | 76,800 | 62.500 ms | 15.625 ms | 75.0% |
| 2048 | 614,400 | 2750.000 ms | 125.000 ms | 95.45% |

默认每玩家服务端 pending 上限为 256；2048 是人工算法压力队列，不代表默认多人队列分布。Windows 的线程 CPU 计时在本机有约 15.625 ms 粒度，256 的百分比尤其粗。持续移动会重新评分，不能直接套用静止排空收益。这是请求选择计算的降幅，不是服务端整体 CPU 或 MSPT 降幅。

随机顺序回归同时覆盖 80 轮移动与转向、每轮新增/替换、取消、老请求轮换、空预测和清空，与原全扫描逐项比较同一个请求对象。

### 批次选择消融

临时基准在每批取前 16 项的同工作量下比较原完整排序与本次评分后堆选择：256 候选 CPU 312.5 → 78.125 ms，2048 候选 2859.375 → 234.375 ms，均为 10,000 批，选择顺序一致。16 候选的 CPU 都是 15.625 ms，未证明小队列收益。

最初的独立 `RankedReady` 包装会使分配明显增加：2048 候选 10,000 批约 783.15 MiB。消融后移除了包装对象，直接复用 `Ready` 的主线程评分字段，降到约 158.16 MiB。原排序约 132.83 MiB；大队列的新实现仍有堆数组分配取舍，未宣称所有队列规模的分配都降低。此基准是临时算法评估，未测编码、预算拒绝和实际 tick，不作为服务端整体性能结论。

### 实际专用服务器功能验证

最终 `./gradlew --no-daemon test smokeClasses build` 成功，26 个测试，0 失败、0 错误；仅有已有 Forge API 弃用警告。

`python tools/server-smoke.py --diagnostics` 最终退出 0，通过请求窗口/全局溢出、16/128 列混合精度批次、碎片化、信用拆批与释放、取消/失效/epoch、缓存传输与生成隔离、32 目录查询、配置重开、导入和关服检查。

发送公平性检查使用真实服务端发送实现、两个可接收客户端及一个零信用客户端：

| 指标 | 修改前 | 最终实现 |
| --- | ---: | ---: |
| 检查时长 | 11.81 s | 11.82 s |
| 可接收客户端发送字节 | 4,225,024 / 4,224,896 | 4,224,896 / 4,225,664 |
| 零信用客户端发送字节 | 0 | 0 |
| 240 次发送调用总耗时 | 37.67 ms | 39.94 ms |
| 发送调用 p99 | 0.690 ms | 0.542 ms |
| Java 分配 | 34.85 MiB | 35.00 MiB |

该检查没有大量 ready/pending 压力，结果用于证明发送公平性、进度与限流保留。总耗时和分配基本同一量级，单轮 p99 有波动，不能据此宣称低帧或服务端 tick 尾延迟提升。检查在服务器线程中主动 park 50 ms 的行为属于 fixture，不是 MOD 生产等待。

修改前与最终验证都录制了 `settings=profile` 的 JFR。未采到包含 MOD 生产服务端路径的主线程 `JavaMonitorEnter` / `ThreadPark` 等待样本；这不证明所有场景零阻塞。执行样本较少，且部分栈被截断；fixture 在主线程直接调用 codec/converter 的样本不能用来指控生产转换同步执行。没有用这些样本估算整体 MSPT 百分比。

功能日志中的配置文件 AccessDenied、只读 RocksDB 与坏 NBT 异常是既有故障注入，最终故障恢复检查通过。

### 复现

```bash
./gradlew --no-daemon test smokeClasses build
python tools/server-smoke.py --diagnostics
JAVA_TOOL_OPTIONS='-XX:StartFlightRecording=filename=E:/Downloads/Code/voxy-distant/build/server-review-repro.jfr,settings=profile' python tools/server-smoke.py --diagnostics
```

请求 CPU 基准需要与项目运行相同的 Java 21 / Minecraft classpath。以下命令复用工作区已有的客户端启动清单；该清单由此前的本地基准生成：

```bash
python - <<'PY'
import json, subprocess
from pathlib import Path
root=Path.cwd()
args=json.loads((root/'build/benchmarks/lod-square256-cache128/client-launch.json').read_text())['args']
cp=str(root/'build/classes/java/main')+';'+args[args.index('-cp')+1]
out=root/'build/server-queue-cpu'
out.mkdir(exist_ok=True)
subprocess.run([str(Path(args[0]).with_name('javac.exe')),'-proc:none','-encoding','UTF-8','-cp',cp,'-d',str(out),str(root/'tools/ServerQueueCpuCheck.java')],check=True)
for size in (256,2048):
    for mode in ('scan','heap'):
        subprocess.run([args[0],'-cp',str(out)+';'+cp,'dev.voxydistant.server.ServerQueueCpuCheck',mode,str(size),'300'],check=True)
PY
```

本地证据：`build/server-queue-cpu-comparison.json`、`build/server-ready-cpu-reused-comparison.log`、`build/server-review-before.log`、`build/server-review-verified.log`、`build/server-review-verified.jfr`、`build-server-review-verified-build.log`。这些构建/采样产物不纳入 Git。

## 最终消融审查

保留了一个用于双顺序请求队列的类及必要的顺序回归，没有新增全服异步 actor、额外常驻线程、协议消息或配置开关。移除了每候选临时评分包装和重复组批触发。调度合并使用原版任务队列，不把快速缓存补位固定延迟一 tick。所有活世界读取与状态提交的线程归属保持明确。
