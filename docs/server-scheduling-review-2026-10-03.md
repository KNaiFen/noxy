# 服务端发送算法与任务合并复测（2026-10-03）

对照版本为 `537d176`（上一轮服务端优化）。本轮成功标准是减少发送选择、分片复核和任务提交中的重复计算及临时分配，同时保留移动预测顺序、老请求轮换、取消、失效、接收信用、带宽公平性和维护生命周期。

游戏结束后串行复测的结果是：实际发送检查 p99 从 0.886 ms 降到 0.668 ms，Java 分配减少 22.8%，总耗时基本持平。大队列选择算法微基准的 CPU 降幅为 89.2%。这些数据没有证明整体服务端 CPU、MSPT 或客户端 low 帧达到 99% 的改善。

## 实现及线程边界

| 路径 | 上一版行为 | 本轮修改 |
| --- | --- | --- |
| worker 完成提交 | 每个结果独立向服务器排任务；只合并后续补位，编码完成还会立即全服发送 | `ConcurrentLinkedQueue` 收集结果，`AtomicBoolean` 合并唤醒；一个 `TickTask` 最多提交 64 个回调，再统一调度和发送 |
| 请求、回执和目录查询 | 每个控制包立即触发全局调度或发送 | 主线程先应用输入、释放信用，再共用上述任务；集中推进缓存读取和发送 |
| 传输选择 | 每选择一个未开始传输，都重新扫描所有候选及其成员，反复计算预测评分 | 每轮 `flushSends` 首次需要选择时，对候选成员评分一次，再 heapify；本轮后续选择复用堆 |
| 分片有效性 | 每片重新检查整个批次成员的范围、取消和版本 | 同一个发送循环复用检查结果；新循环或版本序号变化重新检查 |
| 分片负载 | 先 `Arrays.copyOfRange` 创建临时数组，再复制到包缓冲区 | 直接把不可变编码数组的 offset/length 区间写入包缓冲区 |
| 信用阻塞的组批 | 即使没有接收信用，也全量检查 ready 并分配版本查询 Key | 先判断信用；有信用时再清理 ready，并复用其已有 Key |

完成任务的合并复用了原版服务器队列和现有 worker，没有增加常驻线程。数据库读取、快照转换、压缩和目录组装继续在后台执行；活区块快照、ticket、玩家状态、会话信用及结果提交继续由服务器线程拥有。Forge 包缓冲区编码和发送仍在该线程执行，本轮没有把整个服务端路径完全异步化。

64 是单个任务的回调数量上限，不是整个 tick 的硬时间预算；原版任务队列仍可能在同一 tick 执行多个任务。丢失唤醒检查采用先清除标志、再检查队列并重新排任务的顺序。缓存重开和独占导入的排空条件也加入完成队列，防止后台任务结束但结果尚未提交时切换生命周期。

传输评分次数从完整排空时的 O(N²K) 降为每轮 O(NK)，堆选择为 O(N log N)，N 为传输数、K 为批次成员数。实际发送仍使用 `ArrayDeque.remove/addFirst`，其中移位查找是 O(N)，所以不能把整条发送链宣称为 O(N log N)。开始发送的传输保持连续；每 20 tick 的最早传输轮换及评分相同时的原顺序均保留。每轮开始、结束时清除评分堆，避免跨轮复用位置或预测已改变的评分。

分片区间字段只影响本地编码输入；消息 ID、协议版本 17 和线上字节格式保持一致。接收端仍读取受 `FRAGMENT_BYTES` 限制的独立片段数组。这省掉了一次临时数组分配和复制，包缓冲区复制、网络栈及 socket 成本仍存在。

前置信用判断的取舍是：零信用客户端的过期 ready 可以保留更久，直到信用恢复或取消、epoch、退出清理；其分配仍计入已有玩家/全局发送内存限制。

## 游戏结束后的实际发送复测

新旧版本使用相同 Java 21、Forge 专用服检查、配置和 `settings=profile` JFR，串行运行，不并行运行 CPU 微基准。对照版的 `RemoteServer` 和 `Protocol` 源码从 `537d176` 提取后编译到 `build/server-round2-baseline`，启动 classpath 和 MOD_CLASSES 均指向该独立目录；没有覆盖当前版本的产物。

检查调用真实生产 `flushSends`，通过内存中的 peer 捕获编码包：2 个可接收客户端、1 个零信用客户端，每客户端 16 条 256 KiB 单列传输，共 240 次调用。计时包含发送调用及随后处理的 fixture 回执，不包含真实客户端渲染或 socket 往返。这是轻队列的发送公平性检查，不能代表大批次压力或整体服务器 tick 尾延迟。

| 指标 | `537d176` | 本轮实现 | 本轮变化 |
| --- | ---: | ---: | ---: |
| 检查时长 | 11.81 s | 11.77 s | — |
| 可接收客户端发送字节 | 4,224,896 / 4,225,408 | 4,223,872 / 4,223,232 | 均达到 4 MiB |
| 零信用客户端发送字节 | 0 | 0 | 公平性保留 |
| 240 次发送调用总耗时 | 36.04 ms | 35.50 ms | 减少 1.5% |
| 发送调用 p99 | 0.886 ms | 0.668 ms | 本轮减少 24.6% |
| Java 分配 | 34.91 MiB | 26.94 MiB | 减少 22.8% |
| 采样到的 JVM 已用堆峰值 | 1,094.65 MiB | 1,328.12 MiB | 本轮更高 |

总耗时差异很小，不能据此宣称整体 CPU 明显下降。p99 是单轮 240 次调用的尾部分位，仍受 JIT、GC 和系统调度影响；此前非本次空闲对照的最终验证为 0.542 → 0.998 ms，因此本轮较好的 p99 尚不足以证明稳定尾延迟改善。堆峰值涵盖此前集成 fixture 的对象和垃圾回收时机，与本段累计分配是不同指标，本轮没有测出 JVM 堆峰值降低。

两版集成、配置迁移及诊断均退出 0。用户澄清前的重测数据没有用作上述空闲基准。

## 算法与复制 CPU 微基准

`tools/ServerSendCpuCheck.java` 使用线程 CPU 时间及线程累计分配，预热 100 轮。选择模型使用相同 seed 714、成员及每轮转向，保留 `ArrayDeque.remove/addFirst` 成本，每轮完整排空队列；它复现比较算法，不启动服务器，也不直接测完整生产发送路径。新旧选择 checksum 一致。

| 工作量 | 原扫描 CPU | 评分后堆选择 CPU | CPU 降幅 | 原分配 | 本轮分配 |
| --- | ---: | ---: | ---: | ---: | ---: |
| 16 传输 × 16 成员，10,000 轮 | 140.625 ms | 46.875 ms | 66.7% | 48.377 MiB | 3.363 MiB |
| 64 传输 × 128 成员，1,000 轮 | 1,015.625 ms | 109.375 ms | 89.2% | 65.816 MiB | 0.708 MiB |

负载复制使用一个复用的 `FriendlyByteBuf`，比较先切片再写入与直接区间写入，不测完整包头、连接或 socket。最初的 1,000 MiB 工作量中，本轮 CPU 读数低于计时分辨率而显示 0 ms；为避免把它错误报告成零 CPU，单独把相同工作量延长为 20,000 MiB：

| 工作量 | 原切片再写 CPU | 区间写入 CPU | CPU 降幅 | 原分配 | 本轮分配 |
| --- | ---: | ---: | ---: | ---: | ---: |
| 640,000 片，共 20,000 MiB | 1,359.375 ms | 203.125 ms | 85.1% | 20,009.768 MiB | 0.002 MiB |

负载 checksum 一致。上述分配是累计量，不是同时驻留内存。本机 Windows 线程 CPU 计时约有 15.625 ms 粒度，小工作量百分比尤其粗；复制测试仍在写包缓冲区，不能据此声称网络零拷贝或整体 CPU 降低 85.1%。

## 一致性及构建验证

`./gradlew --no-daemon test smokeClasses build` 成功，26 个测试，0 失败、0 错误。当前生产 jar 包含本轮改动；之后仅生成了复测证据和本报告，没有再次改动生产源码。

新增的核心行为检查均在真实 Forge 专用服 harness 中通过：

- 4 个后台线程并发提交 128 个结果只新增一个服务器任务；每次最多提交 64 个，验证主线程归属、每 worker 顺序及 129 个回调各执行一次。
- 单列和批次分别比较非零数组 offset、长度 1/127/128/32768、首片/续片的线上字节，并验证片段解码。
- 24 轮移动预测、64 传输 × 8 成员，逐项与原全扫描顺序比较，覆盖老请求轮换和已开始传输连续性。
- 真实 4 MiB 批次正在分片发送时，下一轮版本失效触发 Abort，回执只释放一次信用。

原有请求窗口/溢出、混合精度批次、缓存与生成隔离、目录查询、配置重开、导入故障恢复和关服检查也通过。必要的 fixture 时序修正是：目录检查开始后不再重复缓存隔离断言；导入检查在合并请求任务被执行后，再断言存在活动缓存读取。前一个修正也应用到独立旧版 harness，不放宽生产行为断言。

两份空闲 JFR 未采到带生产服务端路径的主线程 `JavaMonitorEnter` / `ThreadPark` 等待事件；fixture 公平性循环主动 park 约 50 ms 的 240/239 个事件被单独识别。生产执行样本只有旧版 2 个、新版 1 个，而且默认栈深有限，不能用这几条样本估计 MSPT 或证明所有场景无阻塞。坏 NBT、配置 AccessDenied 和只读 RocksDB 错误是既有故障注入，最终恢复验证通过。

## 复现与证据

```bash
./gradlew --no-daemon test smokeClasses build
JAVA_TOOL_OPTIONS='-XX:StartFlightRecording=filename=E:/Downloads/Code/voxy-distant/build/server-round2-repro.jfr,settings=profile' python tools/server-smoke.py --diagnostics
```

微基准复用工作区已有 Java 21 / Minecraft 启动清单，按顺序执行：

```bash
python - <<'PY'
import json, subprocess
from pathlib import Path
root=Path.cwd()
args=json.loads((root/'build/benchmarks/lod-square256-cache128/client-launch.json').read_text())['args']
cp=str(root/'build/classes/java/main')+';'+args[args.index('-cp')+1]
out=root/'build/server-send-cpu'
out.mkdir(exist_ok=True)
subprocess.run([str(Path(args[0]).with_name('javac.exe')),'-proc:none','-encoding','UTF-8','-cp',cp,'-d',str(out),str(root/'tools/ServerSendCpuCheck.java')],check=True)
for work in [('selection','10000','16','16'),('selection','1000','64','128'),('fragment','20000')]:
    for mode in ('scan','optimized'):
        subprocess.run([args[0],'-cp',str(out)+';'+cp,'dev.voxydistant.server.ServerSendCpuCheck',work[0],mode,*work[1:]],check=True)
PY
```

本地证据（不纳入 Git）：`build/server-round2-idle-before.log`、`build/server-round2-idle-after.log`、`build-server-round2-idle-before.log`、`build-server-round2-idle-after.log`、`build/server-round2-idle-comparison.json`、`build/server-round2-idle-cpu.log`、`build/server-round2-idle-fragment-extended.log`、`build/server-round2-idle-before.jfr`、`build/server-round2-idle-after.jfr`、`build/server-round2-idle-jfr-summary.json`、`build/server-round2-idle-waits.json`、`build/server-round2-idle-verification.log`、`build-server-round2-final-build.log`。

## 消融审查

去掉了每片临时数组、每个完成回调重复的全服发送、无信用时的 ready 全扫描和临时评分包装；评分直接复用既有 Transfer 字段。保留原发送队列和生命周期，仅增加一个完成队列及一个合并标志。没有引入全服 actor、额外线程、配置项或新协议。本轮最明确的实际收益是分配减少；大队列算法收益需要与真实负载的队列分布分开评价。
