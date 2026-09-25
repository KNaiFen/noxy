# Noxy / voxy-distant

Noxy（原 voxy-distant）是 [Voxy](https://github.com/MCRcortex/voxy) 的附属 Mod，它让单人游戏在原版视距外生成远景；多人游戏则由服务器生成、缓存远景，客户端按距离取回不同精度的数据，交给 Voxy 显示。靠近时细节逐步补齐，原版区块到达后接管近景。

## 为什么这样传

Voxy 将远景分为 L0～L4：一个 L0 体素约对应 1 个方块，往上每级边长翻倍，L1～L4 分别约对应 2、4、8、16 个方块。默认 `32:0,64:1,96:2` 让近处约 32 区块使用 L0、再远处使用 L1、其余使用 L2；接近远景时再请求更细的数据。这样省掉远处不易察觉的细节，并把多列数据合并压缩。服务器仍保留完整 L0～L4 缓存。本轮固定视角的最终画面中，近景细节和远景整体观感基本保持；其他视角和移动过程不能仅凭这一轮保证画质一致。

新测试用预生成并导入 LOD 的 **512×512 区块**服务端存档，让一个空缓存客户端接收原点周围**半径 256 区块**的远景。上传预算上限设为 **30 Mbps**；205,548 列远景的 LOD 载荷共 **117.93 MB**，首片到末片约 **175 秒**，平均约 **1,172 列/秒、5.38 Mbps**。从开启接收到队列完全稳定约 **488 秒**（全程平均约 **421 列/秒**），之后的覆盖审计显示半径内 **205,861 列**全部达标，其中 313 列由原版近景提供。30 Mbps 是上限，实测平均没有跑满。

旧的同种子、同半径全 L0 基准载荷约 **1,195.71 MB**：按此口径，本轮少传约 **1,077.78 MB（90.14%）**。这是不同日期与版本的参考对照，并非本轮同时测得的严格 A/B。每名新玩家少下载约 1.08 GB，服务端也少占用相应出口流量，有利于为其他玩家和正常游戏留带宽。当前服务端 LOD 数据库占 **2.06 GB**，客户端接收后 `.voxy` 缓存占 **153.34 MB**；两者覆盖范围和存储格式不同，不能用大小差当成磁盘节省率。上述时间来自本机单客户端、固定场景及已有服务端缓存，其他地图、未生成地形和多人同时接收会改变结果。

## 安装与使用

明确兼容 **Minecraft 1.20.1、Forge 47.4.0～47.x、Java 21**。客户端安装本项目 **0.2.16**、[Voxy Forge 1.20.1 0.2.15-beta](https://github.com/KNaiFen/voxy-forge-1.20.1/actions/runs/35514129065)（在构建页面下载 `voxy-forge-1.20.1` 产物；[源码仓库](https://github.com/KNaiFen/voxy-forge-1.20.1)）和 [Embeddium 0.3.31](https://modrinth.com/mod/embeddium/version/UTbfe5d1)；专用服务器只安装本项目 0.2.16。JAR、Mod ID 与配置文件仍沿用 `voxy-distant` / `voxy_distant` 名称。

从 [0.2.16 发行页](https://github.com/KNaiFen/noxy/releases/tag/0.2.16) 下载 `voxy-distant-0.2.16-forge-1.20.1.jar`。已有 `.voxy` 和服务端 LOD 缓存无需清理。安装后即可使用默认设置；在原版“选项”页点击与 Distant Horizons 按钮同位置的 **VD** 小按钮进行设置。OP 等级 2 可在其中在线调整服务器设置。两端配置文件分别为 `config/voxy_distant.toml`。

普通玩家只需下载并安装上面列出的 JAR，**不需要自行编译**。

如果要从源码编译 Noxy，先在上述 Voxy Forge 源码仓库运行 `./gradlew :1.20.1-forge:jar`，取得 `versions/1.20.1-forge/build/devlibs/voxy-0.2.15-beta+1.20.1-legacyforge.jar`。这是供 Noxy **编译**使用的开发 JAR，不能用上面下载的 Voxy 游戏安装 JAR 代替。然后切到 Noxy 源码根目录，使用 Java 21 运行 `./gradlew build -PvoxyJar=开发JAR的绝对路径`；把等号后面换成刚生成的文件的实际完整路径。编译出的 Noxy 安装 JAR 位于 `build/libs/`。Windows 可将 `./gradlew` 换成 `./gradlew.bat`。

## 致谢与许可

本项目是纯 **VibeCoding** 项目，遵循 [MIT 协议](LICENSE)。
感谢 GPT、[Distant Horizons](https://gitlab.com/distant-horizons-team/distant-horizons) 和 [Voxy](https://github.com/MCRcortex/voxy)。
