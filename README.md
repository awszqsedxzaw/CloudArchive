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
x = 1..N    数据木桶：只存文件纯字节
```

元数据木桶最多 27 本书；如果目录索引超过这个容量，打包会失败。数据木桶中的书不包含文件头、文件名、序号或自定义标记，只包含经过 Base64 编码的纯数据。书使用 Minecraft 1.20.1 原生字段 `author`、`title`、`generation`、`resolved` 和 `pages`；物品使用原生 `id`、`Count`、`Slot`、`tag`。不会在书或物品中写入 `CloudArchive` 自定义字段。

读取时，元数据书由元数据木桶的 `Slot` 定位；数据书由数据木桶的 X 坐标和 `Slot` 定位。NBT compound 字段和列表的排列顺序可以改变，但不能移动木桶、改变木桶的 X 坐标或改变书的 `Slot`。使用 Litematica 重新保存投影时，必须保留全部木桶、木桶坐标和库存槽位。

当前归档是不可变快照。节点保留稳定 ID、父目录 ID、显式数据区间、`generation` 和 `next_id`，为未来写入和修改预留；目前没有空间分配器、写时复制、日志、事务或修改命令，重新打包也不保证沿用旧 ID。

## 容量

1.20.1 也采用较小的书页容量：每页最多使用 32,764 个无填充 URL-safe Base64 字符，为序列化 JSON 的两个引号留出空间，避免超过 32,767 个字符。书页本身直接保存 Base64，不添加 JSON 包装。NBT 的 65,535 个 modified UTF-8 字节上限不能直接作为可用书页容量。

| 单位 | Base64 容量 | 原始数据容量 |
| --- | ---: | ---: |
| 一页 | 32,764 字符 | 24,573 字节 |
| 一本书 | 100 页 | 2,457,300 字节 |
| 一个木桶 | 27 本书 | 66,347,100 字节 |

非空归档使用 `ceil(数据长度 / 2,457,300)` 本数据书；空数据不需要数据书。总木桶数为 `1 + ceil(数据书数 / 27)`，因此空文件或空目录树也有一个只含元数据的木桶。文件树将元数据和所有文件内容分别计算，文件之间不补齐页、书或木桶。

例如，66,347,131 字节需要 28 本数据书、2 个数据木桶，另加 1 个元数据木桶，共 3 个木桶。实际限制还受磁盘空间、Minecraft/Litematica 内存和投影尺寸影响。

## 文件树格式

元数据木桶中的书拼接成 `CAMD` 二进制元数据文件，格式说明见 [docs/archive-format.md](docs/archive-format.md)。它包含：

- 归档类型、名称、版本、代数和下一个节点 ID；
- 文件和目录的稳定 ID、父目录、名称和类型；
- 文件长度、SHA-256 和显式数据区间；
- 纯数据总长度、数据书数量和整体 SHA-256。

文件内容按纯数据区间保存，不把元数据混入数据木桶。单文件归档也是同一套格式，只是包含一个根文件节点。文件树不保存权限、时间戳、所有者或链接关系；符号链接和特殊文件会被拒绝。

## 严格的当前格式

只支持本项目当前格式和 Minecraft 1.20.1 Litematica 结构：`Version=6`、`SubVersion=1`、`MinecraftDataVersion=3465`。**不支持任何旧格式，也不会保留旧格式兼容逻辑。** 旧投影必须从原始文件重新生成；不要把旧投影当作输入格式。

当前也不支持 1.20.5 及以后版本的物品组件格式。跨版本加载可能由游戏或 Litematica 执行数据升级，但这不是 CloudArchive 的兼容保证。

## 使用约束

- 输入和输出支持相对路径、绝对路径和带空格的路径。缺失的输出父目录会自动创建，已有输出不会覆盖。
- 文件树投影必须输出到源根目录以外；打包期间不要修改源文件。源文件会在复制时再次检查，但这不是文件系统级原子快照。
- 名称必须可安全还原到 Windows：拒绝保留名称、非法字符及同目录下仅大小写不同的名称，不会静默改名。实际路径长度受目标文件系统限制。
- `list` 会验证完整归档和每个文件的哈希，不是只读取元数据。读取先扫描布局，再按书扫描库存，避免依赖 NBT 字段顺序。
- 打包和 `list` 在系统临时目录暂存元数据及完整数据；还原在目标父目录暂存它们和恢复结果。需预留磁盘空间。正常完成或失败后清理临时数据，强制终止进程可能留下临时目录。
- CLI 不把完整归档留在堆内存，但节点索引和书本地址表会随规模增长；Litematica 在游戏内加载完整投影，其内存占用与 CLI 不同。

## 游戏内验证

1. 用 `pack` 生成新的 `.litematic`。
2. 在 Minecraft 1.20.1 的 Litematica 中加载并放置。
3. 粘贴时保留方块实体库存，不要忽略木桶库存。
4. 确认 X=0 的木桶是元数据木桶，其书标题为 `Metadata`；后续木桶中的书标题为 `Data`。
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

```powershell
.\gradlew.bat build
.\gradlew.bat verifyFileCodec
.\gradlew.bat genSources
.\gradlew.bat runClient
```

`build` 已包含 `verifyFileCodec`，只需运行一次。测试使用 128 MiB Java 堆，覆盖容量边界、原生书 NBT、列表和字段乱序、缺失/重复/移动槽位、损坏数据、旧格式拒绝、非法路径及失败清理。另测试 1,500 个文件、12,000 个目录的跨书元数据、元数据容量溢出，以及约 66 MB 的单文件和文件树完整还原。命令行编解码无需启动 Minecraft，也不需要 PCA 或 Carpet 扩展。

构建产物为 `build\libs\cloudarchive-0.1.0.jar`，开发游戏目录为 `run\client`。IntelliJ IDEA 导入 Gradle 项目后将 Gradle JVM 设为 JDK 21；仓库没有机器专用的 JDK 路径。

包名为 `awszqsedxzaw.cloudarchive`，模组 ID 为 `cloudarchive`，项目名为 `CloudArchive`。

## 参考与许可证

投影结构参考 [Litematica](https://github.com/maruohon/litematica)，构建结构参考 [fabric-mod-template](https://github.com/Fallen-Breath/fabric-mod-template)；Minecraft 源码仅作为只读格式参考。

本项目采用 [MIT License](LICENSE)，版权所有者为 awszqsedxzaw。Gradle Wrapper 和第三方依赖遵循各自许可证，见 [第三方许可说明](THIRD_PARTY_NOTICES.md)。
