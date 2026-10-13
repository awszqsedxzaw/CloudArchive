# CloudArchive

CloudArchive 是面向 Minecraft 1.20.1、Fabric 和 Yarn 的客户端模组项目。当前提供离线命令行编解码器：把一个文件或一棵文件树写入只含原生成书 NBT 的木桶，再生成 `.litematic` 投影，并从投影还原。模组本身尚未接入游戏内操作，当前也没有写入、修改或删除归档的命令。

**代码由 AI 根据 awszqsedxzaw 的需求编写；项目维护者和提交署名为 awszqsedxzaw。**

## 快速开始

需要 JDK 21。PowerShell 示例：

```powershell
git clone https://github.com/awszqsedxzaw/CloudArchive.git
cd CloudArchive
$env:JAVA_HOME = 'C:\path\to\jdk-21'
```

路径含空格时，把整个 `--args` 用双引号包住，再把各个路径用单引号包住：

```powershell
# 单文件
.\gradlew.bat runFileCodec "--args=pack 'input.bin' 'out\data.litematic'"
.\gradlew.bat runFileCodec "--args=list 'out\data.litematic'"
.\gradlew.bat runFileCodec "--args=restore 'out\data.litematic' 'out\restored.bin'"

# 整棵目录树，包含空目录和空文件
.\gradlew.bat runFileCodec "--args=pack 'data' 'out\tree.litematic'"
.\gradlew.bat runFileCodec "--args=restore 'out\tree.litematic' 'out\restored-tree'"

# 只选择根目录下的部分路径
.\gradlew.bat runFileCodec "--args=pack-tree 'data' 'out\selected.litematic' 'docs\a.txt' 'images' 'notes.txt'"
.\gradlew.bat runFileCodec "--args=list 'out\selected.litematic'"
```

`pack` 接受文件或目录；多个文件使用 `pack-tree`，选择路径相对于根目录。子目录会递归打包，重复或重叠选择会合并。目录根本身不会再嵌套一层：`data\docs\a.txt` 会还原为 `out\restored-tree\docs\a.txt`。还原目标必须不存在，现有文件和目录不会被覆盖或合并。

正确后缀是 `.litematic`。输入 `.litematica` 或没有后缀时，输出名会自动规范化为 `.litematic`。Linux/macOS 使用 `./gradlew`，例如 `./gradlew runFileCodec --args="pack 'input.bin' 'out/data.litematic'"`。

## 当前存储布局

每个投影只有一个 `FileStorage` 区域，木桶沿本地 X 轴连续排列：

```text
x = 0       元数据木桶：目录、文件信息、区间、校验和
x = 1..N    数据木桶：只存压缩后的文件数据，或无需压缩的原始字节
```

元数据木桶最多 27 本书；如果目录索引超过这个容量，打包会失败。数据木桶中的书不包含文件名、序号、区间表或自定义标记，只包含经过 Base64 编码的数据块。块类型、压缩前后长度和全部文件信息均在元数据木桶中。书使用 Minecraft 1.20.1 原生字段 `author`、`title`、`resolved` 和 `pages`；物品使用原生 `id`、`Count`、`Slot`、`tag`。作者缩为 `CA`，元数据书和数据书标题分别为 `M`、`D`；省略默认值为 0 的书本 `generation`，保留 `resolved=true`，避免游戏重新解析书页。不会在书或物品中写入 `CloudArchive` 自定义字段。

读取时，元数据书由元数据木桶的 `Slot` 定位；数据书由数据木桶的 X 坐标和 `Slot` 定位。NBT compound 字段和列表的排列顺序可以改变，但不能移动木桶、改变木桶的 X 坐标或改变书的 `Slot`。使用 Litematica 重新保存投影时，必须保留全部木桶、木桶坐标和库存槽位。

当前归档是不可变快照。节点保留稳定 ID、父目录 ID、显式数据区间、`generation` 和 `next_id`，为未来写入和修改预留；目前没有空间分配器、写时复制、日志、事务或修改命令，重新打包也不保证沿用旧 ID。

## 压缩与速度

当前格式为 `CAMD v2`，处理顺序为：

```text
文件 → 相同内容去重 → 独立 Zstandard / RAW 块 → Base64 书页 → gzip NBT 投影
```

相同长度、相同 SHA-256 的文件复用一个数据区间，但保留各自的文件路径和节点。唯一数据按最多 4 MiB 分块，以 Zstandard 级别 3 压缩；压缩结果不比原文小时，使用 RAW 块。块跨文件边界，不为小文件单独添加头部或补齐空间。每块的元数据仅 9 字节，数据木桶中没有 CloudArchive 块头；Zstandard 帧本身的结构属于压缩数据。

投影外层使用最多 4 个线程并行生成标准 gzip 成员，按原顺序拼接；Minecraft 的 `GZIPInputStream` 和现有 NBT 读取器可以读取。读取投影时使用低内存双扫描：第一遍只解析区域和木桶坐标，跳过书页；第二遍只提取书籍并写入临时文件，避免把所有书页保存在内存中。Zstandard 压缩和解压分别在一次调用内复用上下文，各块仍是独立帧。Base64 编码复用每本书的输入、输出数组和文件通道缓冲区，只有末尾不足一页时需要裁剪输入；编码字节按页直接写入原生 NBT 字符串，避免保留整本书的字符串和重复转码。解码只做一次，规范检查通过字符查表及尾部填充位检查，不重复编码。打包源文件时每个文件只读取一次，同时生成 staging 副本和 SHA-256；之后只从 staging 复制唯一内容，重复文件直接复用 extent。这样保留属性变化检测，但不再进行独立的预哈希读取。

投影的 `Description` 为空，`Author` 缩为 `CA`；名称、版本、尺寸、方块状态、木桶坐标及真实创建/修改时间保留。NBT 必需字段名使用原生名称，不缩写。精简字段主要减少未压缩 NBT，最终 gzip 投影的收益很小。

保留 Base64 是实测后的选择：NBT 书页必须是字符串，原始字节不能直接放进 `pages`。在提供的 ZIP 前 16 MiB 上，Base64 经 gzip 后约 16.93 MB；两种直接字符映射分别约 21.20 MB、20.21 MB，也没有速度优势。这里的体积比较包含 NBT 字符串实际使用的 modified UTF-8。

此前级别 6 的 Base85 完整流程测试未带来更小的投影：虽然每页可多存约 6.65% 的字节，ZIP 和目录的最终投影反而增大约 0.61%、0.69%，两个样本的木桶总数均未减少。优化 Base64 校验后，16 MiB 样本的校验及解码中位数由 0.141 降至 0.032 秒，快于实验 Base85 的 0.058 秒。完整还原中位数在 ZIP 上由 5.44 降至 4.28 秒，在目录上由 9.06 降至 7.74 秒，分别减少约 21%、14.5%，体积基本不变。这批测量早于级别 3、编码缓冲区复用及书本字段精简，不代表最终实现的固定耗时。

压缩参数测试中，Zstandard 级别 3 的压缩阶段中位耗时减少约 72%～77%，代价是投影增大约 0.33%～0.60%；级别 9、12 只省约 0.07%～0.25%，压缩阶段约需 2～7 倍时间。gzip 级别 9 在 16 MiB 样本只省 0～6 字节。当前采用 Zstandard 3、gzip 6；参数和 Base85 实验任务、完整日志及复现方法见 [基准测试记录](docs/benchmarks.md)。

最终实现通过 `check` 和构建；一次 `GTMC.zip` 完整测试得到 174,370,899 字节投影，比此前级别 6 样本增加 0.333%，打包 6.576 秒、还原 5.943 秒，还原结果逐字节一致。这次仅作简要验证，预热方法与此前三轮对照不同，不能据此认定完整流程稳定提速。

提供的完整测试数据包含 `.git`、`.idea` 和 `node_modules`，未排除任何隐藏文件。以下为此前格式改进的基准，早于上面的实现优化；耗时为同机三次运行的中位数，不含 Gradle 启动和结果比较，所有还原结果均逐文件、逐字节比较通过：

| 输入 | 旧投影 → 新投影 | 打包时间 | 还原时间 |
| --- | ---: | ---: | ---: |
| `GTMC.zip`，181.50 MB | 182.62 → 173.79 MB（减少 4.83%） | 11.80 → 5.93 秒 | 7.12 → 5.74 秒 |
| `GTMC`，199.16 MB、4,826 个文件 | 188.22 → 169.78 MB（减少 9.80%） | 25.73 → 11.88 秒 | 10.73 → 8.64 秒 |

目录投影的木桶总数从 5 个降至 4 个。数据中有大量已压缩的图片、GIF 和 Git 对象，收益随输入内容变化；随机字节或已充分压缩的数据不保证缩小。完整结果和复现命令见 [基准测试记录](docs/benchmarks.md)。

## 容量

1.20.1 也采用较小的书页容量：每页最多使用 32,764 个无填充 URL-safe Base64 字符，为序列化 JSON 的两个引号留出空间，避免超过 32,767 个字符。书页本身直接保存 Base64，不添加 JSON 包装。NBT 的 65,535 个 modified UTF-8 字节上限不能直接作为可用书页容量。

| 单位 | Base64 容量 | 解码后的存储字节容量 |
| --- | ---: | ---: |
| 一页 | 32,764 字符 | 24,573 字节 |
| 一本书 | 100 页 | 2,457,300 字节 |
| 一个木桶 | 27 本书 | 66,347,100 字节 |

非空归档使用 `ceil(压缩后存储长度 / 2,457,300)` 本数据书；空数据不需要数据书。总木桶数为 `1 + ceil(数据书数 / 27)`，因此空文件或空目录树也有一个只含元数据的木桶。原始文件能装多少取决于去重和压缩效果，文件之间不补齐页、书或木桶。命令输出分别显示文件总长度、去重后长度和压缩后存储长度。

例如，压缩后仍为 66,347,131 字节的数据需要 28 本数据书、2 个数据木桶，另加 1 个元数据木桶，共 3 个木桶。实际限制还受磁盘空间、Minecraft/Litematica 内存和投影尺寸影响。

## 文件树格式

元数据木桶中的书拼接成 `CAMD` 二进制元数据文件，格式说明见 [docs/archive-format.md](docs/archive-format.md)。它包含：

- 归档类型、名称、版本、代数和下一个节点 ID；
- 文件和目录的稳定 ID、父目录、名称和类型；
- 文件长度、SHA-256 和显式数据区间；
- 去重后逻辑数据总长度、压缩后存储长度、数据书数量和逻辑数据整体 SHA-256；
- 独立压缩块的类型、原始长度和存储长度。

文件区间指向解压后的逻辑数据，压缩块表负责映射到书中的存储数据；不同文件可共享区间。单文件归档也是同一套格式，只是包含一个根文件节点。文件树不保存权限、时间戳、所有者或链接关系；符号链接和特殊文件会被拒绝。

## 严格的当前格式

只支持 `CAMD v2` 和 Minecraft 1.20.1 Litematica 结构：`Version=6`、`SubVersion=1`、`MinecraftDataVersion=3465`。**不支持任何旧格式，也不会保留旧格式兼容逻辑。** 本次优化前的投影也必须从原始文件重新生成。

当前也不支持 1.20.5 及以后版本的物品组件格式。跨版本加载可能由游戏或 Litematica 执行数据升级，但这不是 CloudArchive 的兼容保证。

## 使用约束

- 输入和输出支持相对路径、绝对路径和带空格的路径。缺失的输出父目录会自动创建，已有输出不会覆盖。
- 文件树投影必须输出到源根目录以外；打包期间不要修改源文件。源文件会在复制时再次检查，但这不是文件系统级原子快照。
- 名称必须可安全还原到 Windows：拒绝保留名称、非法字符及同目录下仅大小写不同的名称，不会静默改名。实际路径长度受目标文件系统限制。
- `list` 会验证完整归档和每个文件的哈希，不是只读取元数据。读取使用低内存双扫描，同时保持 NBT 字段顺序无关。
- 打包在系统临时目录暂存元数据、去重后的逻辑数据及压缩数据；还原和 `list` 暂存压缩数据与解压后的逻辑数据，还原结果在目标父目录中暂存。需预留磁盘空间。正常完成或失败后清理临时数据，强制终止进程可能留下临时目录。
- CLI 不把完整归档留在堆内存，但节点索引和书本地址表会随规模增长；Litematica 在游戏内加载完整投影，其内存占用与 CLI 不同。

## 游戏内验证

1. 用 `pack` 生成新的 `.litematic`。
2. 在 Minecraft 1.20.1 的 Litematica 中加载并放置。
3. 粘贴时保留方块实体库存，不要忽略木桶库存。
4. 确认 X=0 的木桶是元数据木桶，其书标题为 `M`；后续木桶中的书标题为 `D`，作者均为 `CA`。
5. 重新导出时保留全部木桶、原本地 X 坐标和每本书的 `Slot`。
6. 用 `restore` 还原并比较 SHA-256。

书页是传输数据，不是普通阅读界面中的 JSON 文本，不应通过书界面编辑。离线编解码、跨木桶、跨页和数据校验已经测试；大文件在游戏中的粘贴、再导出以及服务器容器同步仍需实际验证。

预定的服务端协作依赖包括 [PCA](https://github.com/plusls/plusls-carpet-addition)、[Carpet Igny Addition](https://github.com/liuyuexiaoyu1/Carpet-Igny-Addition) 和 [Litematica](https://github.com/maruohon/litematica)，目前尚未实现集成。

## 构建与测试

| 组件 | 版本 |
| --- | --- |
| JDK | 21 |
| 输出字节码 | Java 17 |
| Gradle | 8.5 |
| Fabric Loom | 1.2.1 |
| Minecraft | 1.20.1 |
| Yarn | 1.20.1+build.1 |
| Fabric Loader | 0.14.21 |
| Fabric API | 0.83.0+1.20.1 |
| zstd-jni（随模组打包） | 1.5.2-5 |

```powershell
.\gradlew.bat build
.\gradlew.bat verifyFileCodec
.\gradlew.bat genSources
.\gradlew.bat runClient
```

`build` 已包含 `verifyFileCodec`，只需运行一次。测试覆盖容量边界、原生书 NBT、列表和字段乱序、缺失/重复/移动槽位、损坏数据、旧格式拒绝、非法路径及失败清理。另测试 1,500 个文件、12,000 个目录的跨书元数据、元数据容量溢出、约 66 MB 的单文件和文件树完整还原，以及 Zstandard 跨块还原、RAW 回退、重复文件共享区间、块描述损坏和标准 gzip 解码。验证任务堆上限为 128 MiB；命令行编解码无需启动 Minecraft，也不需要 PCA 或 Carpet 扩展。

构建产物为 `build\libs\cloudarchive-0.1.0.jar`，开发游戏目录为 `run\client`。IntelliJ IDEA 导入 Gradle 项目后将 Gradle JVM 设为 JDK 21；仓库没有机器专用的 JDK 路径。

包名为 `awszqsedxzaw.cloudarchive`，模组 ID 为 `cloudarchive`，项目名为 `CloudArchive`。

## 参考与许可证

投影结构参考 [Litematica](https://github.com/maruohon/litematica)，构建结构参考 [fabric-mod-template](https://github.com/Fallen-Breath/fabric-mod-template)；Minecraft 源码仅作为只读格式参考。

本项目采用 [MIT License](LICENSE)，版权所有者为 awszqsedxzaw。Gradle Wrapper 和第三方依赖遵循各自许可证，见 [第三方许可说明](THIRD_PARTY_NOTICES.md)。