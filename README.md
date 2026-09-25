# Noxy / voxy-distant

Noxy（原 voxy-distant）是 [Voxy](https://github.com/MCRcortex/voxy) 的附属 Mod，**纯 vibe coding 项目**。它让单人游戏在原版视距外生成远景；多人游戏则由服务器生成、缓存远景，客户端按距离取回不同精度的数据，交给 Voxy 显示。靠近时细节逐步补齐，原版区块到达后接管近景。

## 为什么这样传

传统做法把整个远景范围都按最高精度 L0 发送，远处看不出的小细节也会占用服务器出口。本项目近处传细节、远处传较粗的 LOD，再把多列数据合并压缩。服务器仍保留完整远景缓存，需要更细画面时客户端可以继续请求；节省的是**发给每位玩家的网络流量**，并非声称服务器磁盘缓存缩小。

在一份已预生成的半径 **256 区块**存档上，单客户端从空缓存接收全部远景：全 L0 方案的 LOD 载荷约 **1,196 MB**；使用 `32:0,64:1,96:2` 分档和 16 列合批后约 **118 MB**，减少约 **90%**。这意味着同一份远景无需为每位新玩家重复发送约 1.08 GB 的最高精度数据，有限的上传带宽更容易留给其他玩家和正常游戏流量。相同设置下，合批还让载荷从约 167 MB 降到 118 MB，传输从约 108 秒缩短到 99 秒。

测试服务器的上传预算设为 **30 Mbps**。另一轮 0.2.16 实测中，单客户端从空缓存接收半径 **128 区块**的默认分档远景，LOD 载荷约 **54.7 MB**，首片到末片约 **39 秒**，接收、应用和自动覆盖检查完成约 **47 秒**。这些是本机隔离服务器、已有服务端缓存、固定场景下的结果；30 Mbps 是配置上限，并不表示传输全程跑满，未生成地形、其他地图和多人同时接收会改变耗时。

## 安装与使用

明确兼容 **Minecraft 1.20.1、Forge 47.4.0～47.x、Java 21**。客户端需要本项目 **0.2.16**、配套的 **[Voxy Forge 1.20.1](https://github.com/KNaiFen/voxy-forge-1.20.1) 0.2.15-beta** 和 **Embeddium 0.3.31**；专用服务器只安装本项目 0.2.16，不安装 Voxy 或 Embeddium。客户端和服务器必须使用**同一版 Voxy Distant JAR**：0.2.16 的网络协议为 12，不与 0.2.15 扩展混用。JAR、Mod ID 与配置文件仍沿用 `voxy-distant` / `voxy_distant` 名称。

从 [0.2.16 发行页](https://github.com/KNaiFen/noxy/releases/tag/0.2.16) 下载 `voxy-distant-0.2.16-forge-1.20.1.jar`；Voxy 和 Embeddium 请从各自渠道获取。已有 `.voxy` 和服务端 LOD 缓存无需清理。安装后即可使用默认设置；在原版“选项”页点击与 Distant Horizons 按钮同位置的 **VD** 小按钮，可调整本地生成、远景接收与诊断日志。OP 等级 2 可在其中在线调整服务器设置。两端配置文件分别为 `config/voxy_distant.toml`。

源码构建需要 Java 21 及与上述 Voxy 版本匹配的开发命名空间 JAR：`./gradlew build -PvoxyJar=/你的路径/voxy-dev.jar`。开发 JAR 不随本仓库分发，也不能用于游戏安装。

## 致谢与许可

本项目遵循 [MIT 协议](LICENSE)。感谢 GPT、[Distant Horizons](https://gitlab.com/distant-horizons-team/distant-horizons) 和 [Voxy](https://github.com/MCRcortex/voxy)。
