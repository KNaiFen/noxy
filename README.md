# Noxy / voxy-distant

Noxy（原 voxy-distant）是 [Voxy](https://github.com/MCRcortex/voxy) 的附属 Mod，它让 Voxy 除了在本地生成 LOD，还能从多人服务器接收预先生成或缓存的远景。并且能让客户端按距离接收所需精度的LOD，远的模糊点，靠近时再补齐细节；这样减少了需要下载的数据量和服务器出口流量：载荷**降低十倍**的同时保持画面质量基本不变（只要你不放大看🤓）。让 Voxy 远景在多人服务器上实际可用。

## 为什么这样传

Voxy 的 LOD 分为 L0～L4，每个体素的边长分别约为 1、2、4、8、16 个方块。默认设置 `32:0,64:1,96:2`：近处约 32 区块传 L0，中距离传 L1，更远处传 L2；走近时再补细节。远处少传看不清的小方块，多列 LOD 再合并压缩，画面保留近景细节和远景轮廓。

**测试前提：**同一个种子的主世界，服务端已预生成 **512×512 区块**的地形和完整 L0～L4 远景。一个空缓存客户端固定在原点，接收半径 **256 区块**内的 **205,548 列远景**；原版近景另外提供 313 列。服务端上传上限设为 **30 Mbps**。

| 同一半径 256 区块范围 | 数据量 |
| --- | ---: |
| 服务端缓存中的完整 L0～L4 远景（205,548 列） | **1,500.46 MB** |
| 客户端实际收到的默认分档远景（16 列合批） | **117.93 MB** |
| 相比完整 LOD，减少的流量载荷 | **1,382.53 MB（92.14%）** |

表中服务端数据量是这 **205,548 列**压缩后 LOD 记录的合计。如果把这些完整数据直接发给玩家，需要约 1,500.46 MB；本轮实际只发了 117.93 MB，按此口径每位玩家少用约 **1,382.53 MB（92.14%）** 的 LOD 流量载荷。整个服务端数据库还包括接收范围外的区块，占 **2.06 GB**。客户端从首片到末片约 **175 秒**，平均约 **1,172 列/秒、5.38 Mbps**；半径内连同原版近景共 **205,861 列**通过覆盖检查。30 Mbps 是上限，实测没有跑满。接收后的客户端 `.voxy` 缓存占 **153.34 MB**。这些数据来自本机单客户端和已预生成的服务端；其他地图、未生成地形或多人同时接收会改变耗时。

## 安装与使用

明确兼容 **Minecraft 1.20.1、Forge 47.4.0～47.x、Java 21**。客户端安装本项目 **0.2.16**、[Voxy Forge 1.20.1 0.2.15-beta](https://github.com/KNaiFen/voxy-forge-1.20.1/actions/runs/35514129065)（在构建页面下载 `voxy-forge-1.20.1` 产物；[源码仓库](https://github.com/KNaiFen/voxy-forge-1.20.1)）和 [Embeddium 0.3.31](https://modrinth.com/mod/embeddium/version/UTbfe5d1)；专用服务器只安装本项目 0.2.16。JAR、Mod ID 与配置文件仍沿用 `voxy-distant` / `voxy_distant` 名称。

从 [0.2.16 发行页](https://github.com/KNaiFen/noxy/releases/tag/0.2.16) 下载 `voxy-distant-0.2.16-forge-1.20.1.jar`。已有 `.voxy` 和服务端 LOD 缓存无需清理。安装后即可使用默认设置；在原版“选项”页点击与 Distant Horizons 按钮同位置的 **VD** 小按钮进行设置。OP 等级 2 可在其中在线调整服务器设置。两端配置文件分别为 `config/voxy_distant.toml`。

## 从源码编译

普通玩家下载上面的 JAR 安装即可，这部分只给开发者。Noxy 编译时要读取配套 Voxy Forge 的**开发版 JAR**；游戏安装用的 Voxy JAR 不能代替它。

先下载 Voxy Forge 源码，切到本项目使用的版本并生成开发版 JAR：

```sh
git clone https://github.com/KNaiFen/voxy-forge-1.20.1.git
cd voxy-forge-1.20.1
git checkout 2294097bca526f6a629f7955afdd6d4c45b3cef0
./gradlew :1.20.1-forge:jar
```

`git clone` 下载源码，`git checkout` 固定兼容的版本，最后一条命令编译 Minecraft 1.20.1 Forge 版。完成后，开发版 JAR 在该仓库的 `versions/1.20.1-forge/build/devlibs/voxy-0.2.15-beta+1.20.1-legacyforge.jar`。

再到 Noxy 源码根目录运行以下命令。`-PvoxyJar=` 后面填写刚生成的开发版 JAR 的**绝对路径**，不要照抄示例路径：

```sh
./gradlew build -PvoxyJar=/绝对路径/voxy-0.2.15-beta+1.20.1-legacyforge.jar
```

Noxy 构建使用 Java 21；成功后，可安装的 JAR 位于 `build/libs/`。Windows 上把 `./gradlew` 换成 `./gradlew.bat`。

## 致谢与许可

本项目是纯 **VibeCoding** 项目，遵循 [MIT 协议](LICENSE)。
感谢 [GPT（Codex）](https://chatgpt.com/codex)、[Distant Horizons](https://gitlab.com/distant-horizons-team/distant-horizons) 和 [Voxy](https://github.com/MCRcortex/voxy)。
