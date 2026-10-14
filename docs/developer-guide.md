# CloudArchive 开发者文档

[README](../README.md) · [使用指南](usage.md) · [归档格式](archive-format.md) · [基准记录](benchmarks.md)

本文记录已提交编解码器的完整实现：模块边界、数据结构、算法、流式状态、校验、资源生命周期及测试。精确的磁盘字段与字节偏移集中在[归档格式](archive-format.md)，命令示例集中在使用指南，实测结果集中在基准记录。

源码核对基准为提交 `eaf96d3`，不包含工作区内未提交的并行开发改动。只支持当前 `CAMD v2` 和 Minecraft 1.20.1 书物品 NBT；格式升级不保留旧版兼容分支。归档是不可变快照，更新与服务端协作尚未实现。

## 1. 项目与源码导航

### 1.1 环境和构建

| 组件 | 配置 |
| --- | --- |
| 开发 JDK | 21 |
| Java 输出 | 17，class major version 61 |
| Gradle Wrapper | 8.5，发行包校验和位于 wrapper properties |
| Fabric Loom | 1.2.1 |
| Minecraft | 1.20.1 |
| Yarn | 1.20.1+build.1 |
| Fabric Loader | 0.14.21 |
| Fabric API | 0.83.0+1.20.1 |
| zstd-jni | 1.5.2-5，`implementation` 与 Loom `include` |

版本配置见 [gradle.properties](../gradle.properties)、[build.gradle](../build.gradle) 和 [Wrapper 配置](../gradle/wrapper/gradle-wrapper.properties)。依赖保持当前功能可用的低版本；不要为了文档或无关改动升级依赖。Zstandard 的 JNI 库随模组打包，其 Java 及原生许可见[第三方说明](../THIRD_PARTY_NOTICES.md)。

`JavaCompile` 使用 UTF-8 和 `options.release=17`。JDK 21 用于开发及 Gradle，生成的模组最低 Java 要求为 17。`org.gradle.jvmargs=-Xmx2G` 配置的是 Gradle 进程，验证任务另设 128 MiB 或 64 MiB 堆，不应把它们混为一谈。仓库没有硬编码本机 JDK 路径。

项目名为 `CloudArchive`，group 为 `awszqsedxzaw`，模组 ID 为 `cloudarchive`，包为 `awszqsedxzaw.cloudarchive`。`fabric.mod.json` 的环境是 `client`，入口 [CloudArchiveMod](../src/main/java/awszqsedxzaw/cloudarchive/CloudArchiveMod.java) 实现 `ClientModInitializer`，只记录初始化日志。当前 CLI 并不调用游戏客户端、网络或容器同步。

```powershell
$env:JAVA_HOME = 'C:\path\to\jdk-21'
.\gradlew.bat build
.\gradlew.bat genSources
.\gradlew.bat runClient
```

`build` 产物为 `build/libs/cloudarchive-0.1.0.jar` 及 sources JAR，包含许可证文件。客户端开发目录为 `run/client`；Gradle 也配置了 `run/server`，这不改变客户端模组声明。IDEA 导入 Gradle 项目并将 Gradle JVM 设为 JDK 21。

### 1.2 实现类

编解码类均位于 `src/main/java/awszqsedxzaw/cloudarchive/codec/`：

| 源码 | 责任 |
| --- | --- |
| [FileCodecCli](../src/main/java/awszqsedxzaw/cloudarchive/codec/FileCodecCli.java) | 参数解析、FILE/TREE 分流、统计输出与退出码 |
| [FileTreeCodec](../src/main/java/awszqsedxzaw/cloudarchive/codec/FileTreeCodec.java) | 路径收集、文件属性、单次源读取、去重、extent、还原与列出 |
| [TreeArchiveFormat](../src/main/java/awszqsedxzaw/cloudarchive/codec/TreeArchiveFormat.java) | CAMD 读写、长度计算、节点/区间/块表校验 |
| [DataCompression](../src/main/java/awszqsedxzaw/cloudarchive/codec/DataCompression.java) | 固定块 Zstandard/RAW 编解码与哈希检查点 |
| [LitematicaFileCodec](../src/main/java/awszqsedxzaw/cloudarchive/codec/LitematicaFileCodec.java) | NBT 投影生成、书页编解码、传输恢复和地址落盘 |
| [StreamingArchiveScanner](../src/main/java/awszqsedxzaw/cloudarchive/codec/StreamingArchiveScanner.java) | 单次 NBT 回调扫描、结构验证及书/木桶事件 |
| [ParallelGzipOutputStream](../src/main/java/awszqsedxzaw/cloudarchive/codec/ParallelGzipOutputStream.java) | 有界并行 gzip 成员生成与顺序写出 |

`FileCodecCli`、`FileTreeCodec` 和 `LitematicaFileCodec` 是 public 类，其余为包内实现。当前没有单独发布或稳定承诺的编解码 SDK。

## 2. 数据模型与地址空间

### 2.1 三种长度

实现必须区分：

| 概念 | 对应字段 | 含义 |
| --- | --- | --- |
| 文件总长度 | `Summary.contentSize` | 所有文件大小之和，重复文件重复计数 |
| 逻辑数据长度 | `Manifest.dataLength` / `ArchiveInfo.dataSize` | 去重后唯一内容拼接成的原始字节流 |
| 存储数据长度 | `Manifest.storedLength` / `ArchiveInfo.storedSize` | Zstandard/RAW 块拼接后、写进书之前的字节数 |

投影文件大小还包含 Base64、NBT、元数据及外层 gzip，与三者均不同。

```text
源文件树
  -> 文件节点和 extent
  -> 去重后的逻辑数据流
  -> 独立 Zstandard/RAW 块
  -> 连续存储数据流
  -> Base64 页 / 书 / 木桶
  -> gzip NBT .litematic
```

文件 extent 指向逻辑数据偏移；块表的 raw/stored 前缀和建立逻辑块与存储块的关系；X/Slot/page 建立存储字节与书的关系。文件、压缩块、页、书和木桶的边界互相独立，不能假定一书对应一文件或一压缩块。

X=0 的一个木桶保存完整二进制 metadata。X>=1 的木桶仅保存 stored，没有额外文件头、块头或自定义 NBT 字段。元数据变长不会移动逻辑数据区的起点。

### 2.2 内部 records

| 类型 | 字段及用途 |
| --- | --- |
| `Extent` | `offset, length`，按列表顺序拼成文件 |
| `Block` | `codec, rawLength, storedLength`，起点由前缀和推导 |
| `Node` | `id, parentId, name, directory, size, sha256, extents` |
| `Manifest` | kind、名称、逻辑/存储长度、书数、整体哈希、generation、nextId、nodes、paths、blocks |
| `Encoded` | `storedLength, blocks`，Encoder 完成后的结果 |
| `Snapshot` | `paths, attributes, hashes`；当前 `hashes` 传入空 Map，不用于打包 |
| `DecodedArchive` | 已验证的 manifest 和对外 archive info |
| `ArchiveBook` | 扫描事件的 `x, slot, title, pageCount`；未知 X 用 -1 |

磁盘哈希为 32 字节，Java 模型使用 64 个字符的小写十六进制字符串。目录的 size 为 0、hash 为空、extents 为空；空文件有空内容 SHA-256，extents 为空。模型列表通常在边界处用 `List.copyOf` 固化，路径表用 `Map.copyOf` 固化。

ID 0 是隐式根目录。当前写入器从 1 顺序分配 ID，按根相对路径排序写节点；读取器要求 ID 正数且唯一、父目录已出现、`nextId > maxId`，允许非连续 ID 和非字典序记录。重新打包不会保留已有 ID。

## 3. 入口与 CLI 行为

### 3.1 Java 入口

| 方法 | 行为 |
| --- | --- |
| `LitematicaFileCodec.pack(input, output)` | 仅普通文件，委托 `packSingleFile` |
| `LitematicaFileCodec.restore(input, output)` | 仅 FILE，TREE 会失败 |
| `FileTreeCodec.pack(root, output)` | 全部文件树，根必须为目录 |
| `FileTreeCodec.pack(root, selections, output)` | 选定文件树，选择为空代表整树 |
| `FileTreeCodec.restore(input, output)` | 自动识别 FILE/TREE |
| `FileTreeCodec.list(input)` | 全量验证后返回目录列表 |
| `LitematicaFileCodec.normalizeSchematicPath(output)` | 规范化打包输出后缀 |

对外结果为：

- `ArchiveInfo(archiveName, dataSize, sha256, metadataBookCount, dataBookCount, barrelCount, kind, storedSize)`；`totalBookCount()` 相加两类书数，sha256 是整体逻辑哈希。
- `Summary(archive, fileCount, directoryCount, contentSize)`；目录数不包括隐式根。
- `Entry(id, parentId, path, directory, size)`；TREE 的 path 相对于根，以 `/` 表示，目录 size 为 0。
- `Listing(summary, entries)`。

`ArchiveInfo.kind` 的类型属于包内 `TreeArchiveFormat.ArchiveKind`，当前 public record 并不是完全独立的外部 API。包外调用者可以使用其余统计，但新增 SDK 前应重新设计类型的可见性。

### 3.2 参数、输出与错误

CLI 的 `pack` 用 `Files.isDirectory(..., NOFOLLOW_LINKS)` 判断目录，否则交给单文件入口。`pack-tree` 至少要 root 和 output；其余路径通过 `Path.of` 转换。`restore`、`list` 不依据后缀推断 kind。

`pack`/`restore` 要求恰好两个参数，`list` 恰好一个；数量错误抛 `IllegalArgumentException`，被统一捕获为退出码 1。独立 `help`/`--help` 为 0；无参数或未知命令打印用法并退出 2。`FileAlreadyExistsException` 显示专门的输出已存在提示；其余异常显示 `File codec failed: <message>` 并退出 1，不打印完整堆栈。

后缀规范化使用区分大小写的 `endsWith`：小写 `.litematic` 不变，小写 `.litematica` 替换，其他名称追加 `.litematic`。路径没有 fileName 时有 `resolveSibling` 回退；该回退只处理后缀，不代替后续父目录、路径和 I/O 校验。

## 4. 快照收集与路径检查

### 4.1 目录与选择

`FileTreeCodec.pack` 将 root 转为规范化绝对路径，检查链接祖先，要求 NOFOLLOW 下为目录，再用 `toRealPath()` 取得源根。输出规范化为绝对路径，先检查不存在、祖先路径，再拒绝 `destination.startsWith(source)`。

`collect` 使用 `Files.walkFileTree`，不启用 FOLLOW_LINKS。无选择时遍历 root；有选择时：

1. 拒绝绝对路径以及任何 `..` 路径组件，即使规范化后仍在根内也拒绝。
2. 解析到 root 下、再次检查 `startsWith(root)` 与现有祖先，要求路径存在。
3. 递归遍历每个选择，拒绝 link、other 及非普通文件/目录。
4. 对每个节点加入自身及根以下的全部父目录。

去重集合为 `TreeSet<Path>`，比较键是根相对路径字符串，将 `\` 替换成 `/`。重复或重叠选择只产生一条路径，父目录先于其子节点；隐藏文件没有排除规则。根本身不存节点，空树允许 nodes 为空。

遍历完成后逐路径保存 `BasicFileAttributes`，并转成固定 paths 列表。这一阶段不读文件内容。名称在打包节点时检查；目录之间的大小写冲突和整体结构在 manifest 校验时检查。

### 4.2 单文件

`packSingleFile` 要求 NOFOLLOW 下普通文件，检查源与输出祖先；输出必须不存在。快照含一个文件，父根为 `source.getParent()`，归档名称取原文件名。单文件与树共用 `packSnapshot`、压缩、元数据和投影写出实现。

### 4.3 稳定性和检查边界

`copyAndHashSource` 在打开文件前后调用 `requireUnchanged`，比较普通文件类型、size、lastModifiedTime 和 fileKey，读取中累计 long 字节数，超过预期立即失败，结束要求完全相等。两次检查也重新检查路径祖先。

这能发现可观测的属性变化及长度变化，不能提供原子快照，也不能独立发现同大小修改后恢复时间/身份属性的变化。目录遍历期间新增/删除但没有进入快照的节点不保证被发现。`fileKey` 是否可用取决于文件系统。

`rejectLinkedAncestors` 从指定路径向根检查已存在组件；缺失的输出祖先暂时忽略，后续创建。此机制不提供跨并发路径替换的系统级隔离。树还原还会在私有根下检查规范化目标路径的 `startsWith`，使用 CREATE_NEW 创建文件。

## 5. 单次源读取与去重

### 5.1 打包状态

`packSnapshot` 创建系统临时目录，主要状态为：

- `nodes`：每个文件/目录的记录。
- `ids`：源路径到节点 ID，根映射到 0。
- `seenSizes`：已处理的非零文件长度。
- `duplicateFiles`：`sha256 + ":" + size` 到已有 extent。
- `contentSize`：当前保留的唯一逻辑数据长度；与结果 Summary 的全部文件总长度不同。
- 一个 64 KiB copyBuffer、一个贯穿全树的 Encoder。

按快照顺序处理文件，输入流只打开一次。每批字节同时更新文件哈希和 `Encoder.write`；Encoder 更新逻辑整体哈希并累积压缩块。文件结束不会 flush，多个小文件共享一个 4 MiB 块。

空文件计算正常的空内容哈希但没有 extent，不进入去重表。非空唯一文件分配 `[contentSize, size]`，增加 contentSize；相同 size 和 SHA-256 的文件共享先前 extent。去重是整文件哈希判断，没有二次逐字节比较，也没有子文件块去重。

### 5.2 为什么需要回滚

文件哈希直到本次读取完成才知道。若此前没有相同非零 size，文件不可能重复，无需 checkpoint；若有相同 size，先 checkpoint 再读文件。

`Encoder.Checkpoint` 保存：

```text
buffered       当前未满块前缀长度
storedLength   已落盘的存储字节数
blockCount     已提交块表数量
digest         整体 MessageDigest 的 clone
prefix         延迟备份的前缀，起初为 null
```

整体 SHA-256 provider 必须支持 `clone()`，否则 checkpoint 抛 IOException。常规 JDK provider 支持，但实现没有备用的重读路径。

`writeBlock` 第一次可能覆盖 checkpoint 前缀时才复制 `source[0..buffered)`，最多 4 MiB。未 flush 的小候选不会复制大数组：原前缀还在 source 中，只需恢复 buffered。

文件读完后按 size/hash 查询：

| 结果 | 处理 |
| --- | --- |
| 唯一内容 | `commit()` 清除 checkpoint，保留已压缩字节，记录新 extent |
| 重复内容 | 截断 stored 到 checkpoint 长度，通道 position 复位，删除新增 Block，恢复 buffered/prefix/digest，复用已有 extent |

重复候选仍会完整读取、哈希，可能暂时压缩并落盘后回滚。此设计优先避免唯一内容的完整 staging 副本和源文件二次读取，不能消除重复候选的压缩 CPU 开销。回滚后整体哈希只覆盖最终保留的唯一逻辑数据。

### 5.3 完成流程

全部节点处理后：

1. `finish()` 写最后未满块，返回固定块表；不允许仍有 checkpoint 或重复 finish。
2. `sha256()` 在 finish 后取整体 digest；它调用 `digest()` 完成哈希，不是可重复读取的缓存。
3. 校验节点，写 metadata，generation 固定为 0，nextId 为下一可用顺序 ID。
4. `packArchive` 重新解析 metadata 并核对 kind、stored 文件大小；不执行完整解压自检。
5. 读取 metadata/stored，生成投影临时文件，关闭后移动到目标。
6. finally 清理 pack 工作目录。

NBT 列表必须先写元素数量；最终书数取决于压缩长度，元数据又需要最终哈希和块表。因此保留 stored 临时文件，生成 NBT 时顺序读取一次。这里的单次读取指源文件内容；不意味着整个管线没有后续磁盘读取。

## 6. 块压缩与解压

### 6.1 Encoder

Encoder 以 CREATE_NEW 打开 stored 的可定位 FileChannel，持有：

- 一个 4,194,304 字节 source 数组。
- 一个 `Zstd.compressBound(source.length)` 大小的 compressed 数组。
- 一个复用的 `ZstdCompressCtx`，level=3，不设额外工作线程或字典。
- blocks 列表、整体 digest、buffered、storedLength、checkpoint、finished。

`write(bytes, offset, length)` 检查范围及 finished，先更新 digest，再填满 source。每个满块调用 `compressByteArray`；仅当压缩字节数小于原文时保留 Zstandard 帧，否则原样写 RAW。FileChannel 写到 ByteBuffer 完全耗尽，然后增加一个 Block 和 storedLength，清空 buffered。

每个 Zstandard 块独立，compressed 数组跨块复用；不增加 CloudArchive 头或填充。异常由 `Zstd.isError` 和 `ZstdException` 转成 IOException。长度累计使用 `TreeArchiveFormat.add` 包装 `Math.addExact`，溢出明确失败。

`close()` 关闭 native context 和 FileChannel，不自动完成残余块；调用者须先 finish。单字节 write 创建一个单字节数组，主路径使用批量 write。`DataCompression.encode(raw, stored)` 是 transferTo Encoder 的辅助入口，当前生产打包不用完整 raw staging，测试及实验仍可使用该入口。

### 6.2 Decoder

`decode` 依块表顺序读取 stored，使用两个固定 4 MiB 数组与一个 `ZstdDecompressCtx`：

1. 每块精确读 storedLength，短读拒绝。
2. RAW 直接写原字节；Zstandard 用声明 rawLength 为输出边界，解压结果必须精确相等。
3. 用 `DigestOutputStream` 对解压后的字节计算整体 SHA-256。
4. 全部块结束要求 stored EOF，拒绝剩余物理字节，并比较整体哈希。

解码结果写到磁盘逻辑 data 文件，随后由 extent 提取文件。`decode` 不关闭调用者传入的 raw 输出流，由外层 try-with-resources 管理。块长度、codec 和两种前缀和在 metadata 解析阶段先验证。

## 7. CAMD 元数据实现

### 7.1 写入

`indexSize` 计算 `100 + archiveNameBytes + sum(nodeBytes) + 9 * blockCount`；目录为 `21 + nameBytes`，文件为 `65 + nameBytes + 16 * extentCount`。哈希转换为原始 32 字节，名称使用标准 UTF-8，整数采用 DataOutput 大端。

写入前校验 metadata 书数不超过 27、kind/节点/区间和全部块；输出后检查实际文件长度与 indexSize 完全相同。无压缩参数的 write 重载构建 RAW 块表，用于构造当前格式测试样本，不是旧格式入口。

100 字节头、节点和块表的逐字节定义见[归档格式](archive-format.md)。版本、flags 和 reserved 都明确检查，未知版本没有降级读取。

### 7.2 防止计数驱动的大分配

`read` 先检查 metadata 实际大小在 100 字节到一个木桶容量之间，再读取和验证头。将节点区长度计算为：

```text
nodeBytes = metadataLength - 100 - archiveNameLength - blockCount * 9
```

节点通过 `LimitedInputStream` 读取，只能消耗该区，不能误读后面的块表。count 不能大于 `nodeBytes / 22`，节点/块 ArrayList 初始容量最多 4,096，避免依据不可信计数预先创建巨大数组。节点名称长度须在 1..1,024 且不超过剩余节点区；extentCount 须非负并不超过 `remaining / 16`。

节点全部结束必须完全耗尽节点区，然后读取恰好 blockCount 条块记录。metadataLength 已与文件大小一致，节点区/块表布局精确覆盖整个文件。UTF-8 decoder 的 malformed/unmappable 均为 REPORT；不会静默替换非法字节。

### 7.3 语义验证

`validate` 构造 paths、directories 和 siblings 集合：ID 0 初始化为空根路径；每个 parent 必须是已出现目录；同 parent 下按 `Locale.ROOT` 小写检测名称冲突。文件 extent 要求 offset>=0、length>0、范围位于 dataLength 内，所有 length 之和等于文件大小。FILE 必须恰有一个根文件且大小等于 dataLength。

节点名称拒绝控制字符 `<32`、`<>:"/\|?*`、末尾空格/点、`.`/`..`、未配对 UTF-16 surrogate 及 Windows 保留设备名；设备名检查采用首个点前的 stem、去除其末尾空白并大写，覆盖 `COM/LPT` 的 1..9 及上标 1/2/3。归档展示名称只要求非空且 UTF-8 字节长度合法，不应用节点文件名规则，也不参与目标路径拼接。

Manifest 支持多个不连续、重排、共享 extent，允许逻辑数据中存在未被文件引用的字节。读取器不要求所有数据恰好使用一次；整体哈希覆盖全部逻辑数据，文件哈希覆盖各自按 extent 拼接的内容。

## 8. Litematica NBT 与书页写入

### 8.1 投影骨架

`createSchematic` 创建少量 NbtCompound/NbtList 和 BlockStates long[]；不创建任何书页、库存或完整方块实体树。生成结构为一个 `FileStorage` 区域，Size=(barrelCount,1,1)、Position=(0,0,0)，Entities 为空。

根版本固定 6/1/3465，Metadata 保留 Name、Author=CA、Description 空串、RegionCount=1、TotalVolume/TotalBlocks、时间戳和 EnclosingSize。调色板为 air 与 barrel，木桶 facing=north、open=false。

即使仅两个调色板元素，Litematica 的状态数组仍按每方块至少 2 bit 编码：

```text
longCount = ceil(2 * barrelCount / 64)
bitOffset(i) = i * 2
BlockStates[bitOffset / 64] |= 1L << (bitOffset % 64)
```

每个方块值为调色板索引 1；使用错误位宽可能生成有方块实体、却没有可见木桶的投影。当前尺寸只有 X 变化，线性索引就是 X。

### 8.2 手动写 NBT

输出层次为 `DataOutputStream -> BufferedOutputStream -> ParallelGzipOutputStream -> file`。写入根无名 compound，再写骨架各字段；Regions、FileStorage 及 TileEntities 手动写出。骨架字段迭代顺序不作保证，木桶 X/Y/Z 在 Items 之前写，正常读回可直接落盘。

NBT 标签规则：

| 结构 | 写法 |
| --- | --- |
| 命名标签 | 一个类型 byte、`writeUTF(name)`、value payload |
| compound | type=10 加 name，子标签，末尾一个 END byte |
| list | type=9 加 name、元素类型 byte、元素个数 int |
| list 中 compound 元素 | 直接写其子标签及 END，不重复写类型或名称 |
| list 中 string 元素 | 两字节 modified UTF-8 长度和字符串内容，不重复写类型或名称 |

TileEntities 每个元素依次写 barrel id、坐标、Items。Items 的每本书写原生 id/Count/Slot/tag；tag 写 author/title/resolved/pages，最后关闭 tag、item 和 barrel compound。库存与页面均不建 NbtList 内容树。

### 8.3 页编码

容量常量为每页 32,764 Base64 字符、24,573 字节，每书 100 页、2,457,300 字节，每桶 27 书、66,347,100 字节。详细容量来源见格式文档的 Minecraft 限制说明。

`writeBook` 一次定位通道到 bookOffset，分配最多一页的输入数组、编码输出数组和 ByteBuffer，并在这一本书内复用。JDK `Base64.getUrlEncoder().withoutPadding().encode(input, page)` 直接写到已有数组。最后不足一页时用 `Arrays.copyOf` 裁剪输入，防止编码上一页残留。

Base64 是非零 ASCII，modified UTF-8 与 ASCII 一致，因此可直接 `writeShort(encodedLength)` 再 write 编码字节，不创建页字符串，也不添加 JSON 包装。数组按书创建，并非一个跨全部书的永久缓冲；source 通道保持连续顺序读取。

## 9. 单次 NBT 扫描器

### 9.1 帧与字段

`restoreTransport` 只打开投影一次：64 KiB `GZIPInputStream` 加 64 KiB `BufferedInputStream`/DataInputStream，调用一次 `NbtIo.scan`。scanner 直接实现 `NbtScanner`，不使用 NbtCollector，不积累 NbtCompound/NbtList 或全部页字符串。

识别的帧类型如下：

| Kind | 容器及要求 |
| --- | --- |
| ROOT | compound；必须版本 6/1/3465 和 Regions |
| REGIONS | compound；必须恰好一个 compound 区域，名称不限定 |
| REGION | compound；必须 Size 和 TileEntities |
| SIZE | compound；int x/y/z，尺寸 N/1/1 |
| TILE_ENTITIES | compound list；正长度，限定最大木桶数 |
| BARREL | compound；id、int x/y/z、Items |
| ITEMS | compound list；1..27 本 |
| BOOK | compound；id、byte Count/Slot、tag |
| TAG | compound；author/title、byte resolved、pages |
| PAGES | string list；1..100 页 |

每个 Frame 保存 kind、parent、已见字段 bitmask、list length/visited、槽位 mask、相关标量及 `pending`。Field 枚举保存 NBT 类型和可选子帧类型，`1 << ordinal` 表示字段是否出现。识别字段的错误类型与重复出现立即拒绝。

根、region、Size 和 barrel 中未知字段返回 SKIP；BOOK 和 TAG 中未知字段直接拒绝。读取器因此允许区域改名和额外外围字段，但不允许书的 generation、filtered_pages 或自定义字段。Metadata、Position、BlockStatePalette、BlockStates 和 Entities 在读取路径中跳过，实际读取验证边界见格式文档。

### 9.2 回调顺序

只有容器进入 `frames` 栈。标量没有 endNested 回调，进入字段时在父帧记录 pending，在 visitString/visitByte/visitInt 中消费并清空。不可把标量也压栈，否则容器关闭会错位。

列表头检查类型与长度；startListItem 要求 index 与 visited 一致，木桶和书元素压 compound 帧，页字符串直接消费：

```text
visitString(page)
  -> PageConsumer.accept(pageIndex, pageCount, encoded)
endNested(BOOK)
  -> BookConsumer.accept(x or -1, slot, title, pageCount)
endNested(BARREL)
  -> BarrelConsumer.accept(realX)
```

TAG 结束时验证 CA、resolved 非零及必需字段，传 title 到 BOOK；PAGES 结束时传 pageCount。BOOK 结束时验证书 id、Count=1、Slot 范围和槽位唯一，再提交事件。ITEMS 结束要求 `slots == (1 << length) - 1`，证明地址从 0 连续。

BARREL 结束验证 id、y/z=0、X 在列表长度范围内且唯一，BitSet 记录 X；非元数据且非最后 X 的数据木桶须满 27 本，再调用 barrel 事件。SIZE 结束把值传 REGION；REGION 结束匹配 Size.x 与 TileEntities 长度。ROOT 结束检查版本和唯一区域，设置 rootFinished。最终 `barrelCount()` 检查根已闭合、栈为空且 BitSet cardinality 等于木桶数；范围与唯一性共同证明没有坐标空洞。

consumer 的 checked IOException 以及结构错误包装为 UncheckedIOException，在 restoreTransport 扫描边界解包。unexpected 标量回调拒绝，未知已跳过字段不走这些回调。

## 10. 传输恢复、乱序与页面解码

### 10.1 页缓冲和 Base64

RestoreState 持有一个 2,457,300 字节 book 数组和复用 ByteBuffer。每页直接写入 `pageIndex * 24,573`，不分配每页解码数组：

1. 页长度须非零、不超过 32,764，长度 mod 4 不能为 1。
2. 128 项字符表把 ASCII Base64 字母映射到 0..63，其余为 -1；非 ASCII 也为 -1。
3. 四元组合并 24 bit；两字符尾部要求第二字符低 4 bit 为 0，三字符尾部要求第三字符低 2 bit 为 0。
4. 非最后页解码长度须恰好 24,573；bookLength 由末页位置和实际长度更新。

禁止 `=`、空白、标准 Base64 的 `+`/`/` 和非规范尾部 bit。校验与解码同一循环完成，不通过重新编码比较。返回 byte[] 的 decodePage 包装仍供测试/反射实验使用，生产使用写入已有数组的重载。

### 10.2 X 已知和未知

书结束时 Slot 和 title 已知；当前 barrel 若已见 X，则：

```text
X=0: metadataOffset = Slot * BYTES_PER_BOOK
X>0: storedOffset = ((X-1)*27 + Slot) * BYTES_PER_BOOK
```

按 title M/D 检查木桶类别，用 FileChannel 定位并写书。实体/库存列表可逆序或重排，不依赖列表下标。`index >= Integer.MAX_VALUE` 拒绝，地址乘法用 long。

如果 Items 在 X 之前出现，暂存当前木桶：

- 延迟创建 `.cloudarchive-barrel-*.tmp` 的可读写 FileChannel。
- 按 `Slot * BYTES_PER_BOOK` 写当前书。
- 仅持有 `pendingLengths[27]` 和 `pendingTitles[27]`。
- BARREL 结束时用已验证 X 逐书读回同一 book 缓冲、检查 title 并写真实地址。
- 清空数组、truncate 临时文件为 0，下一个乱序木桶复用；close 关闭并删除。

最多一个当前木桶落到额外磁盘，不把 27 本书全部留在内存，也不二次扫描投影。当前写入器把 X 放在 Items 前，所以正常输出不走此回退。

### 10.3 书长度与全局一致性

metadata 和 stored 各有一个 `BookLengths(count, bytes, partialIndex)`。由于 scanner 已证明坐标/Slot 唯一，不需要逐书地址 Map。每流最多一本书未满，finish 要求它位于 `expectedCount-1`，并检查 count 与总字节数。

扫描 root 后再读一个字节，必须 EOF，拒绝解压后的尾随 NBT 并完成 gzip footer/CRC、截断检查。不要在 root END 处提前关闭读取；这项检查不承诺发现任意 gzip 成员之外的压缩垃圾。

RestoreState.finish 随后解析 CAMD，验证总木桶数、metadata 书数、data 书数、传输总长度与 stored 实际文件大小。即使元数据桶最后出现，也先按坐标重建，finish 后才需要目录索引。

## 11. 还原与列出

### 11.1 完整解码

`restoreTransport` 在工作目录创建 stored 临时文件及 CREATE_NEW 的 metadata，扫描和布局检查完成、通道关闭后，调用 DataCompression.decode 写 CREATE_NEW 的逻辑 data。stored 在 finally 删除，扫描回退文件由 RestoreState.close 删除。返回已验证 Manifest 和 ArchiveInfo，不返回完整内存数据。

SHA-256 用于发现损坏，不是带密钥认证或签名；能重写数据和 metadata 的主体也能重算校验值。

### 11.2 FILE 快路径

restoreSingle 要求 kind=FILE，一般 restore 接受两种。若文件为空且无 extent，或 extent 恰好 `[0, size]`：

1. 已通过解压及整体哈希验证，逻辑 data 就是目标文件。
2. 比较节点文件哈希与已验证的整体哈希。
3. `Files.move(work/data, destination)`，空文件也移动，不再完整复制或哈希。

合法的其他当前格式 extent 走 extractSingle：按 extent 拼接到私有 file 临时文件，计算文件哈希，成功后移动。该路径支持非连续 FILE 内容，不是旧格式兼容。

### 11.3 TREE 与 `list`

TREE 在 `work/tree` 内按父先于子创建目录和文件。路径由 Manifest.paths 得到，相对于私有根解析、normalize 后必须仍在根内。文件使用 CREATE_NEW。一个共享逻辑 data FileChannel 按每个 extent 定位；`copyExtents` 每调用分配 64 KiB ByteBuffer，按 remaining 限制读取，更新 digest 的只读视图，并可选写入输出。空 extents 得到空内容哈希。全部文件哈希成功后移动完整 tree 到目标。

重复文件恢复成各自独立普通文件，不恢复硬链接。FILE 快路径省掉复制，TREE 仍需把逻辑数据按每个文件 extent 复制到目标。

`list` 同样扫描、解压并验证整体数据，然后对每个文件遍历 extent 验证 SHA-256，输出 Entry；不生成还原树。没有只读 metadata 的快速列表模式，也不因 FILE 而略过文件校验。工作目录在系统临时目录，验证后删除。

## 12. 并行 gzip

ParallelGzipOutputStream 把连续 NBT 分成最多 1 MiB chunk，worker 数为 `min(4, availableProcessors)`。每个 worker 用：

```text
ByteArrayOutputStream(initialCapacity = chunk.length)
  <- GZIPOutputStream(bufferSize = 65,536，默认 Deflater 级别)
```

当前默认压缩级别等效于 6。每个 chunk 生成独立标准 gzip member，成员按提交顺序拼接；读取器的 GZIPInputStream 连续解压成员，NBT 语义不变。

Future 队列最多 worker 数，提交前队列已满则 drain 最老 Future，等待完成并写到底层；完成先后不会改变字节顺序。完整 chunk 直接移交数组，尾部 chunk 复制到实际长度，提交后分配下一个 1 MiB 数组。worker 的 BAOS 和 `toByteArray()` 会短时保留额外副本，不能把队列内存简单等同为四个输入数组。

flush 提交剩余 chunk、按序 drain 全部并 flush 底层。close 幂等，先 flush，再 shutdownNow、最多等 30 秒终止，最终关闭底层。InterruptedException 恢复中断标记并转 IOException；worker 异常通过 ExecutionException 转 IOException。失败同样由上层清理未完成投影。

## 13. 临时文件、内存与失败

### 13.1 文件生命周期

| 文件/目录 | 创建位置 | 内容与结束处理 |
| --- | --- | --- |
| pack 的 `.cloudarchive-tree-*` | 系统临时目录 | metadata、stored；finally 整体清理 |
| list 的 `.cloudarchive-tree-*` | 系统临时目录 | metadata、data；finally 清理 |
| restore 的 `.cloudarchive-tree-*` | 目标父目录 | metadata、data、暂存 file/tree；成功移动输出后清理 |
| `.cloudarchive-data-*.tmp` | 读取工作目录 | stored；restoreTransport finally 删除 |
| `.cloudarchive-barrel-*.tmp` | 读取工作目录 | 当前未知坐标木桶；复用后 close 删除 |
| `.cloudarchive-archive-*.tmp` | 投影目标父目录 | 完整 gzip 输出；成功关闭后 move，失败删除 |

`deleteWorkDirectory` 在递归清理前检查规范化绝对路径、`.cloudarchive-tree-` 前缀、不是符号链接及 NOFOLLOW realPath 等于预期路径；walkFileTree 后序删除文件和目录。目录遍历不跟随链接。正常异常路径走 finally，但清理本身也可能失败。

输出移动不使用 REPLACE_EXISTING、ATOMIC_MOVE 或 fsync。`writeCompressedAtomically` 是内部方法名，实际是“先完成临时文件，再普通 Files.move”，没有断电原子性或事务持久性承诺。父目录可能在失败后保留，进程被强杀可能留下临时文件；不能按整个磁盘泛化清理。

### 13.2 内存边界

| 阶段 | 主要数据缓冲 |
| --- | --- |
| 打包压缩 | 64 KiB 源复制、4 MiB raw、compressBound compressed、可选最多 4 MiB checkpoint 前缀 |
| 写投影 | 每书约一页输入/编码缓冲、NBT 骨架与 BlockStates 数组、有界 gzip 队列 |
| 扫描传输 | 一本书 2,457,300 字节、当前页 String、两个 64 KiB 输入缓冲、Frame 栈及 27 项待定信息 |
| 块解压 | 两个 4 MiB 数组、缓冲输出 |
| extent 提取 | 每次 64 KiB ByteBuffer |

不会为整份归档或全部书构造内存树。文件节点、路径、属性、去重索引随节点数增长，Block 列表随唯一逻辑数据块数增长，BlockStates 和 BitSet 随木桶数增长；metadata 有一个木桶的格式上限，不能宣称任意规模常量内存。

JNI context、线程栈、JVM 元空间、压缩器本地内存不计入 Java heap。有限堆成功与进程 RSS 上限是不同指标；游戏的 Litematica 完整加载也有单独资源开销。

### 13.3 磁盘边界

令 U 为唯一逻辑字节数、S 为存储字节数、M 为 metadata、A 为投影文件大小、T 为全部还原文件大小：

| 操作 | 主要峰值空间组成 |
| --- | --- |
| pack | M + S + 正在生成的 A；重复候选回滚前 S 可临时增长 |
| list | M + S + U；乱序最多另加一个木桶暂存 |
| FILE restore | M + S + U；多 extent 回退另需目标文件暂存 |
| TREE restore | M + S + U + T；乱序最多另加一个木桶暂存 |

S 在解码阶段结束删除，上表是保守组成说明，不表示所有项目始终同时存在。当前没有直接从投影边解压边生成全部文件的实现，逻辑 data 临时文件支持随机 extent、去重和乱序。

## 14. 校验与测试

### 14.1 校验层次

| 层 | 检查 |
| --- | --- |
| 源与输出 | 类型/链接祖先、存在性、输出位置、文件属性及读取字节数 |
| gzip/NBT | 解压错误、CRC/截断、解压后的尾随字节、根版本、识别字段类型/重复 |
| 布局 | 唯一区域、尺寸、木桶 ID/坐标、Slot 连续/唯一、非末尾容量 |
| 书页 | 原生字段精确集合、CA/M/D、resolved、页数、Base64 和长度 |
| metadata | magic/version/flags/reserved、计数/总长、UTF-8、节点/父目录/名称、extent、块表 |
| 内容 | 块精确输入输出、stored EOF、整体 SHA-256、每文件 SHA-256 |

外围未知 NBT 字段会跳过，校验并不涵盖所有可视化状态。不要将 `list` 成功直接等同为游戏中一定可粘贴；投影生成结构另由格式测试核对。

### 14.2 回归任务

`check` 依赖 `verifyFileCodec`，`build` 包含 check，所以一般只运行 build：

```powershell
.\gradlew.bat build --console=plain
# 仅运行编解码回归时：
.\gradlew.bat verifyFileCodec --console=plain
# 独立大文件压力测试，不随 build 执行：
.\gradlew.bat verifyLargeFileCodec --console=plain
```

测试通过 JavaExec 执行自检类，没有另建 JUnit 层：

| 测试源码 | 主要覆盖 |
| --- | --- |
| [FileCodecSelfTest](../src/test/java/awszqsedxzaw/cloudarchive/codec/FileCodecSelfTest.java) | 空文件、页/书/木桶边界、Unicode 名称、原生 NBT、2-bit 状态数组、Base64 字符与尾部、乱序、损坏与 gzip 完整性 |
| [TreeCodecSelfTest](../src/test/java/awszqsedxzaw/cloudarchive/codec/TreeCodecSelfTest.java) | 整树/选择、空目录、重复文件、1,500 文件、12,000 目录跨书索引、容量溢出、非连续 extent、ID/generation、非法路径与多桶还原 |
| [CompressionSelfTest](../src/test/java/awszqsedxzaw/cloudarchive/codec/CompressionSelfTest.java) | Zstandard/RAW、跨块、哈希/块损坏、跨多块及未 flush 回滚、相同大小不同内容 |
| [LargeFileCodecSelfTest](../src/test/java/awszqsedxzaw/cloudarchive/codec/LargeFileCodecSelfTest.java) | 64 MiB 堆下 512 MiB 随机输入和 2,147,483,681 字节高度可压缩输入，全字节往返 |

常规任务限制 128 MiB 堆，包含约 66 MB 单文件/树的跨木桶检查。可选符号链接 fixture 在宿主不能创建链接时报告跳过，不能据此认定该主机完成了所有链接分支验证。源稳定性保证以第 4 节的属性检查为准，不应由测试名称推出更强保证。

测试采用完整 `Files.mismatch`/目录结构比较，不只依赖被测实现返回的哈希。格式测试会用 Minecraft NbtIo 读取生成样本并断言标准投影字段；不等于游戏内服务器同步测试。

### 14.3 基准任务

| 任务 | 测量范围 |
| --- | --- |
| `benchmarkFileCodec` | 当前实现完整 pack/restore、字节比较；旧投影仅取大小 |
| `benchmarkCodecComparison` | 编译基线源码快照，交替独立 128 MiB JVM 比较旧/当前调用 |
| `benchmarkPageEncoding` | 原生 NBT 字符串编码与 gzip 的实验比较 |
| `benchmarkBase85` | 独立实验副本的 Base64/Base85 完整流程，非正式格式 |
| `benchmarkCompressionTuning` | Zstandard context/线程/级别及 gzip 参数 |

这些任务属于测试代码，不是发布接口。完整命令、样本、有效历史结果、计时边界及局限见[基准记录](benchmarks.md)。改文档无需重跑多 GiB 性能测试；改变实现时选择与风险相符的回归，性能结论应来自相同输入和交替对照。

## 15. 扩展设计与维护

格式已保留 ID、parentId、nextId、generation、多 extent 和独立压缩块，但尚无空间分配器、日志、事务、写时复制、冲突检测或垃圾回收。generation 当前写 0，读取可保留非负值；不是已实现的并发版本协议。

未来修改文件需要同时处理：

1. 新数据块的位置、块表与逻辑偏移；现有独立块没有显式物理地址，前缀和会受块表变化影响。
2. 共享 extent 的引用，避免修改一个文件破坏重复文件。
3. 元数据的代数、ID 和提交恢复方案，且仍须考虑单元数据木桶容量。
4. 空闲/废弃数据回收及投影中连续地址、完整中间书/桶的约束。
5. 服务器容器同步、页保真、库存和世界坐标到投影本地坐标的转换。

实现第三方读写器时，遵循格式文档；保留页顺序，用 X/Slot 寻址，按块表解压后再解释 extent。不要按物品列表下标寻址、将逻辑 extent 当存储偏移或省略哈希验证。

格式变化应同时更新 TreeArchiveFormat、NBT/书校验、格式测试、使用限制和文档，并明确替换当前格式；不要增加旧版本读取路径。性能优化应继续保持有界内容缓冲、单次源读取和单次投影扫描，索引规模限制需要单独考虑。

## 16. 参考来源

Litematica 参考实现为 [LitematicaSchematic](https://github.com/maruohon/litematica/blob/master/src/main/java/fi/dy/masa/litematica/schematic/LitematicaSchematic.java) 和 [LitematicaBlockStateContainer](https://github.com/maruohon/litematica/blob/master/src/main/java/fi/dy/masa/litematica/schematic/container/LitematicaBlockStateContainer.java)。上游默认分支可能变化，本文描述的是本项目 Minecraft 1.20.1 写入结构，不能直接用新版物品组件替换。

Minecraft/Yarn 1.20.1 的关键参考类是 WrittenBookItem、WritableBookItem、NbtString、NbtCompound、NbtList、NbtIo 和 NbtScanner。书页上限来自书验证/解析逻辑，NBT 字符串上限来自 DataInput.readUTF/DataOutput.writeUTF；metadata 名称的标准 UTF-8 与 NBT 的 modified UTF-8 必须区分。本地 Minecraft 参考源码保持只读，映射 build.10 的名称与项目 build.1 不同时以项目依赖源码为准。
