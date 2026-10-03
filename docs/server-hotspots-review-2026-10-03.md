# 服务端剩余热点与第三轮优化（2026-10-03）

对照版本：`846eacc`。本轮审查 `RemoteServer` 的 tick、缓存调度、传输选择、取消、信用、导入，以及 `PriorityState` / `ChunkPriorityMixin` 的原版队列优先级路径。

成功标准：减少跨发送循环的重复成员评分及同包取消的重复扫描，保持原选择顺序、移动/转向更新、老传输轮换、部分传输连续性、请求 ID 隔离、Abort 和信用释放；通过构建及实际 Forge 专用服集成。没有把局部基准等同于整体 CPU、MSPT 或客户端 low 帧。

## 已优化的热点

### 跨发送循环复用评分

上一版每次 `flushSends` 开始和结束都会丢弃 `sendOrder`。低带宽或等待信用时，同一批排队传输会跨多个发送循环；位置和预测形状没有变化时仍对剩余批次成员重新评分、重新建堆。

现在 Session 保留评分堆、玩家区块坐标和预测形状。位置/形状变化、新传输到达、取消删除及老传输轮换时重建；相同条件下的后续循环复用已有顺序。新传输必须使缓存失效，以保留原遍历顺序的最终平分条件。已经开始的传输在发送首片时从堆顶移除，堆不再保留已完成负载；队列排空、epoch 和独占导入清理时释放堆。

若同一稳定队列分成 P 轮发送，成员评分从每轮 O(NK) 变为整个稳定阶段 O(NK)，后续堆选择 O(log N)。玩家持续移动、频繁新传输到达仍会重建，不能套用稳定队列收益。实际发送继续使用 `ArrayDeque.remove/addFirst`，仍有 O(N) 查找和移位成本，本轮没有替换整个传输容器。

### 同包取消集中扫描

原来一个 Requests 包中的每个 Cancel 分别遍历发送队列、批次成员、编码成员和在途信用；最多 16 个取消项会重复做这些扫描。完全取消在途传输后还会再扫描发送队列定位它。

现在先处理 pending、活动 work 和 ready 的坐标直接查找；再按整个取消包处理一次编码成员、一次信用和一次发送队列。坐标索引用 `Long2IntOpenHashMap`，每个 bit 指向原包中的取消项，匹配时仍检查其 requestId；因此同一坐标的不同请求 ID 没有被合并掉。协议已经限制取消列表最多 16 项，内部路径复用该保证。

索引放在服务器线程拥有的 Session 中复用，每包清空，不再创建每坐标的 ID 集合或每包新映射。它增加一个固定小型会话对象，没有新线程或锁。批次成员扫描从 O(CNK) 降为通常 O(C + NK)，C 为取消项数；如果同一坐标含多个取消 ID，匹配该坐标时仍需遍历对应 bits。单取消、小队列或早匹配场景没有测得收益，本轮数值只覆盖下面的 16 项压力包。

尚未开始的完整取消释放排队负载与组批信用；在途完整取消发送一次 Abort，信用继续保留到回执。部分取消只记录对应成员；旧请求取消不能删除相同位置的新请求。

## 直接调用生产方法的针对性基准

新增 smoke 专用 `ServerHotspotCheck`，在 ModLauncher 初始化后的真实 Minecraft/Forge JVM 内调用生产 `selectTransfer` 和 `cancel`，没有在交付 jar 中加入基准。对照版 `RemoteServer` 从 `846eacc` 提取、编译到隔离目录，新旧使用同一 fixture，顺序运行。

共同工作量：64 个合成传输，每批 128 个成员，相同 seed 704，100 轮预热后测 2,000 轮。选择每轮更换预测方向，但一条队列排空期间保持稳定，每个模拟发送循环取 4 个传输，共 16 轮。上一版按原生产 `flushSends` 行为在每轮丢弃堆，新版沿用堆。取消使用 16 项包，8 个存在、8 个旧 ID/未命中，32 个传输尚未开始、32 个在途；取消没有完全清空这些批次，因此不测包发送成本。

计时包括合成队列的清理/重填和状态复位；这不是完整 `flushSends`、socket、真实 tick 或客户端 low 帧。前后选择顺序和取消结果的 checksum 相同。

| 2,000 轮工作量 | CPU 修改前 → 最终 | CPU 降幅 | Java 分配修改前 → 最终 | 单轮 p99 修改前 → 最终 | 单轮最大值修改前 → 最终 |
| --- | ---: | ---: | ---: | ---: | ---: |
| 分 16 轮排空相同传输队列 | 390.625 → 156.250 ms | 60.0% | 11.211 → 1.379 MiB | 0.324 → 0.155 ms | 1.711 → 0.762 ms |
| 16 项取消包处理 | 140.625 → 46.875 ms | 66.7% | 6.922 → 6.783 MiB | 0.101 → 0.077 ms | 0.832 → 0.148 ms |

Windows 线程 CPU 时间约有 15.625 ms 粒度；取消总 CPU 数值只有几个计时单位。不同实现迭代的 p99/CPU 存在波动，以上为最终一次对照，不据此声称真实游戏尾延迟已按相同比例改善。

消融过程：最初“坐标 → ID 集合”方案取消分配为 17.800 MiB；去掉集合，改位图但仍每包创建索引时为 7.845 MiB；最终复用 Session 索引为 6.783 MiB。未保留前两个方案，也没有为取消构建常驻全量反向成员索引。

## 实际发送与功能验证

最终 `./gradlew --no-daemon test smokeClasses build` 成功，26 个测试，0 失败、0 错误。`python tools/server-smoke.py --diagnostics` 退出 0；原有请求窗口、缓存/生成隔离、批次、目录、配置重开、导入故障恢复和关服检查均通过。

新增/扩展的必要检查通过：

- 24 轮移动预测逐项与原全扫描比较；中途移动玩家并转向时重建评分，稳定输入复用同一个堆，加入新传输后重新排序。
- 老传输轮换和正在发送的头部连续性保留。
- 同包包含重复坐标、不同 ID、尚未发送/正在编码/在途成员，验证旧取消保留新 pending 和新单列传输。
- 完整批次取消、部分在途取消、后续取消剩余成员、重复 Abort 回执及 epoch，验证负载与信用精确释放。

轻队列发送公平性检查仍为两个可接收 peer 加一个零信用 peer、240 次调用：最终字节为 4,223,744 / 4,223,872 / 0，总耗时 34.71 ms，p99 0.604 ms，Java 分配 26.86 MiB。上一轮空闲结果为 35.50 ms、p99 0.668 ms、26.94 MiB；不是本轮同时配对的旧版测试，且本轮中间验证曾测得 p99 1.224 ms，所以没有证明稳定整体尾延迟提升。此检查不包含真实客户端渲染、网络往返或大批次压力。

## 更深 JFR 栈确认的等待与剩余候选

本轮用 `jfr print --stack-depth 64` 分析，而非前两轮默认较短的栈输出。最终记录有 9 个含 MOD 生产路径的主线程执行样本，不能作为完整 CPU 热点排名；239 个 fixture 主动等待事件单独识别。

同时发现 6 个生产主线程 `ThreadPark`，均沿 `RemoteServer.processImport → MinecraftServer.saveEverything → ChunkStorage.flushWorker / EntityStorage.flush → CompletableFuture.join`。它们在三次独占导入准备时发生，单次等待为 22.231–128.022 ms。这纠正了先前“未采到生产等待”的范围：较短栈没有显示更下层的 MOD 调用者，本轮证据确认了管理员导入准备中的原版持久化等待；没有证据表明日常发送在等这些 IO。

本轮保留导入前的世界保存边界。直接把 `saveEverything` 放入 worker 会并发读取/修改活世界；若要消除管理员导入的停顿，需要把原版保存与异步 IO 完成拆开，并保证扫描只读取已经落盘的最新区块及取消/关服一致性，这是独立的生命周期改动。

| 剩余候选 | 当前具体成本 | 本轮判断 |
| --- | --- | --- |
| `PriorityState.tick` | 每 tick 构造前景 Area 列表，轮换最多 512 槽并调用 `getChunkNow`；玩家密集重叠时重复检查相同坐标 | 有重复扫描，但缺少完整加载/光照状态事件契约；没有通过跳过检查来改变“原版区块优先”的行为 |
| 优先级 View 发布 / `ChunkPriorityMixin` | 每个 ticket 增删复制全部 extra Area；每个 View revision 可令原版队列重新检查全部位置，每个位置又扫描前景/extra | 明确的队列规模相关候选；本轮未引入空间覆盖索引或推迟发布，避免未经验证地改变跨 mailbox 的优先级更新时间 |
| `admitGeneration` / `processWork` | 复制或收集全部 work，待生成数量在部分缓存未命中回调中全量统计；snapshot 复制活 block/biome/light | 可以进一步按阶段维护索引或减重复复制；需要覆盖取消、重开和前后台优先顺序，当前没有证据量化其日常占比 |
| `totalQueuedBytes` | 一些候选检查每次遍历全部玩家汇总发送内存 | 多玩家会放大，单玩家为常数；全局增量计数需覆盖所有 epoch/退出/导入路径，不为未证实收益增加第二套账本 |
| 目录发送 | 每份 1024 槽目录最终版本复核仍在主线程 | 后台组装已经保留；最终一致性检查仍有成本 |
| Forge 编码 / 包提交 | 不可变负载仍复制到包缓冲区，主线程发送 | 本轮未增加发送线程，取消/信用和连接线程边界保持明确 |

## 复现与消融审查

```bash
./gradlew --no-daemon test smokeClasses build
python tools/server-smoke.py --hotspots
JAVA_TOOL_OPTIONS='-XX:StartFlightRecording=filename=E:/Downloads/Code/voxy-distant/build/server-hotspots-repro.jfr,settings=profile' python tools/server-smoke.py --diagnostics
```

本地证据：`build/server-round3-hotspots-before.log`、`build/server-round3-hotspots-after.log`、`build/server-round3-hotspots-comparison.json`、`build/server-round3-final.log`、`build/server-round3-final.jfr`、`build/server-round3-final-jfr-summary.json`、`build/server-round3-final-evidence.log`、`build-server-round3-final-build.log`、`build-server-round3-final-smoke.log`。基准和采样产物不纳入 Git。

生产改动只落在 `RemoteServer`：复用原评分堆、集中取消并复用小型索引，没有新协议、配置、线程或替换原版队列。`queueTransfer` 统一三个已有入队点的缓存失效；取消成员匹配由共享函数覆盖编码、待发送和在途路径。删除了原逐项 Credit.cancel 和已不再需要的惰性堆清理。基准和一致性 fixture 仅存在于 smoke 源集，不进入 MOD。
