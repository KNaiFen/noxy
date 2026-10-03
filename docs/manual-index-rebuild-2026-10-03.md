# 手动重建 Voxy LOD 覆盖索引（2026-10-03）

成功标准：移除两端启动时的全库检查与重建；客户端进入世界后可主动强制扫描当前 Voxy 存储；旧“已就绪”标记不能跳过扫描；重建不捏造服务器版本，不覆盖实时写入，不把未摄取的 AIR 当成完整覆盖；构建、真实 Voxy 持久化验证与专用服集成通过。

## 原因

删除前的 `CacheIndexStartup` 只查找已经存在的 `distant-coverage/*.bin.rocksdb` 或旧 `.bin`，不会发现只有原生 Voxy 数据、没有 VoxyDistant 覆盖库的世界。其重建函数只从覆盖页生成派生目录，没有枚举 Voxy 实际 LOD。覆盖库没有页时也会立即写入完成标记；后续启动只看该标记，因此“索引已就绪”并不表示 Voxy 缓存已经建立覆盖索引。这说明了代码缺陷，未逐一检查用户的实际存档。

## 最终行为

客户端 `/voxydistant rebuildindex` 无需 OP，只针对发起命令时的连接、维度和 Voxy WorldIdentifier。通过当前 WorldEngine 的存储 API 枚举 L0～L4，不猜服务器目录，也不直接打开第二个 Voxy 数据库。单个低优先级后台线程按覆盖页加载和合并；主线程只启动任务、检查生命周期和展示信息。枚举期间只保留原始 long 键，解码时只保留一页的精度数据，不保存全世界的解码体素或渲染页。

从非空几何所在的 octant 恢复可证实的最低精度，新增覆盖携带待校验标记，版本保持未知。Voxy 本身没有已摄取 AIR 掩码，纯空 octant 保持未知；已有可信的覆盖、AIR、版本和刷新标记保留。每页提交时与最新 live coverage 合并，未确认写入及落盘 guard 保留。重建强制替换派生目录，完成后通过接收 lane 刷新发现状态；修改已加载页时使旧网格 generation 失效。

切换维度、退出连接、服务器 WorldIdentifier 改变或关闭 Voxy 后取消任务，Voxy shutdown 钩子等待任务释放引用再关闭存储。再次执行从头扫描；重复执行中的命令不会创建第二个任务。进度和最终实际扫描数写入聊天及日志。

服务端删除启动全表迁移和 6 个索引 reader；按需区域迁移仍在已有后台缓存 worker 上执行，每步最多 64 个旧列头。新写入继续原子维护元数据；已完成的旧服务端目录标记仅作为兼容提示读取，不再推进启动扫描。

## 验证

- `./gradlew --no-daemon test smokeClasses build`：成功；21 个测试，0 失败、0 错误。
- `./gradlew --no-daemon -PclientSmoke runData`：成功。真实 transformed Voxy、SectionSerializationStorage 和 RocksDB 验证无覆盖库、旧错误完成标记、五层 LOD、负坐标 AIR、重复强制扫描、实时写入、未确认 guard、取消、重开、旧网格失效和客户端命令注册。日志含 `DISTANT_INDEX_REBUILD_PASS`。
- `python tools/server-smoke.py --diagnostics`：退出 0；生成/缓存、区域目录、版本失效、混合精度传输、批次、设置、导入及关服均通过。故障注入的只读数据库与错误 NBT 日志属于预期验证。
- 源码无旧启动索引入口、日志和 reader；构建 jar 含新客户端命令，不含旧 `CacheIndexStartup`。

未打开用户游戏或运行其真实存档；未测这条维护命令运行期间的 low 帧。后台扫描仍会使用一个线程的 CPU 和磁盘带宽，额外键列表内存随已有 LOD 键数增长。配套 Voxy 的 LMDB / Redis 后端尚未实现位置枚举，使用这些非默认后端时命令会明确失败并保留日志。

消融审查：删除原 scheduled 启动任务、6-reader 迁移、进度/计数账本及只验证已移除行为的测试；未新增通用任务调度器、配置项或永久第二套索引。必要持久化与并发检查合并进现有 Voxy 集成 harness。
