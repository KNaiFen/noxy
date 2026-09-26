# Noxy / voxy-distant

Noxy（原 voxy-distant）是 [Voxy](https://github.com/MCRcortex/voxy) 的附属 Mod，它让 Voxy 除了在本地生成 LOD，还能从多人服务器接收预先生成或缓存的远景。并且能让客户端按距离接收所需精度的LOD，远的模糊点，靠近时再补齐细节；这样减少了需要下载的数据量和服务器出口流量：载荷**降低十倍**的同时保持画面质量基本不变（只要你不放大看🤓）。让 Voxy 远景在多人服务器上实际可用。

## 实际表现

Voxy 的 LOD 分为 L0～L4，每个体素的边长分别约为 1、2、4、8、16 个方块。默认设置 `32:0,64:1,96:2`：近处约 32 区块传 L0，中距离传 L1，更远处传 L2；走近时再补细节。远处少传看不清的小方块，多列 LOD 再合并压缩，画面保留近景细节和远景轮廓。

测试场景：Forge 1.20.1 服务端，除 Noxy 外没装别的 MOD，预生成了 **512×512 区块**的地形和完整 L0～L4 远景 LOD。
接收客户端是空缓存，原地不动，接收半径 **256 区块**内的 **205,548** 列 LOD 远景。

| 同一半径 256 区块范围 | 数据量 |
| --- | ---: |
| 服务端缓存中的完整 LOD 数据（205,548 列） | **1,500.46 MB** |
| Noxy 默认设置下，客户端实际收到的 LOD 载荷 | **117.93 MB** |
| 相比完整 LOD，减少的流量载荷 | **1,382.53 MB（92.14%）** |

表中服务端数据量是这 **205,548列** 完整 LOD 在硬盘上占用的空间。如果把这些数据直接发给玩家，就需要约 1,500.46 MB；Noxy 优化下，本轮实际只发了 117.93 MB，按此口径每位玩家少用约 **1,382.53 MB（92.14%）** 的 LOD 流量载荷。
LOD 从首片传输到末片约 **175 秒**，平均约 **1,172 列/秒、5.38 Mbps**；半径内连同原版近景共 **205,861 列**通过覆盖检查。接收后的客户端 `.voxy` 缓存占 **153.34 MB**。
史诗地形等 MOD 可能改变实际传输量，本次测试没有覆盖这些场景。

## 安装与使用

兼容 **Minecraft 1.20.1、Forge 47.4.0～47.x、Java 21**。
下载：从 [发行页](https://github.com/KNaiFen/noxy/releases) 下载 jar 文件。
客户端安装本项目、[Voxy Forge 1.20.1 0.2.15-beta](https://github.com/KNaiFen/voxy-forge-1.20.1/actions/runs/35514129065)（在构建页面下载 `voxy-forge-1.20.1` 产物；[源码仓库](https://github.com/KNaiFen/voxy-forge-1.20.1)）和 [Embeddium 0.3.31](https://modrinth.com/mod/embeddium/version/UTbfe5d1)；
服务器只安装本项目即可。

安装后即可使用；在原版“选项”页点击与 Distant Horizons 按钮同位置的 **VD** 小按钮进行设置。OP 等级 2 可在其中在线调整服务器设置。配置文件为 `config/voxy_distant.toml`。

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

本项目为纯 **VibeCoding** 项目，遵循 [MIT 协议](LICENSE)。
感谢 [Codex](https://chatgpt.com/codex) 大人，感谢 [Distant Horizons](https://gitlab.com/distant-horizons-team/distant-horizons) 和 [Voxy](https://github.com/MCRcortex/voxy)。
