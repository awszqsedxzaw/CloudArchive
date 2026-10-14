# CloudArchive 开发者文档

本文以当前源码为准，说明 CloudArchive 1.20.1 编解码器的实现、`.litematic` 外层布局和 `CAMD v2` 元数据格式。它面向需要实现读取器、写入器、校验器或未来服务器端同步功能的开发者。

当前格式是唯一支持的格式。读取器不兼容旧版 CloudArchive 格式或旧书籍字段布局。格式升级时替换当前读写实现并拒绝旧版本，不保留兼容分支。本次单次读取和单次扫描优化没有修改磁盘上的 `CAMD v2`。

## 1. 总体设计

CloudArchive 把文件或文件树转换为一个只包含木桶的 Litematica 投影：

```text
源文件/目录树
    -> 快照、哈希和重复文件检测
    -> 去重后的逻辑数据
    -> 4 MiB 独立 RAW/Zstandard 块
    -> 数据书的 Base64 页面
    -> 1.20.1 Litematica gzip NBT
```

投影只有一个 `FileStorage` 区域，木桶沿 X 轴排列：

```text
(0, 0, 0)       元数据木桶
(1, 0, 0)       数据木桶 0
(2, 0, 0)       数据木桶 1
...
```

X=0 的木桶只存一段 `CAMD v2` 二进制元数据。X>=1 的木桶只存压缩数据的连续字节，不存文件名、节点 ID、区间表或 CloudArchive 自定义 NBT 字段。这样做有两个目的：

1. 元数据与数据的职责分离，读取器可以先解析目录，再按区间读取文件。
2. 数据木桶的内容可以被视为一个纯粹的寻址数据流，未来可以通过重新指向 extent 实现写时复制或块级更新。

当前 CLI 生成的是不可变快照，尚未实现写入、删除、追加、日志、事务或原地修改。

主要实现类：

| 类 | 作用 |
| --- | --- |
| `FileCodecCli` | `pack`、`pack-tree`、`list`、`restore` 命令行入口 |
| `FileTreeCodec` | 文件快照、目录节点、文件哈希、去重、extent 和还原 |
| `TreeArchiveFormat` | `CAMD v2` 头、节点、extent、压缩块表的读写与校验 |
| `DataCompression` / `Encoder` | 4 MiB 块编解码、流式整体哈希及去重检查点回滚 |
| `LitematicaFileCodec` | Litematica NBT、木桶、书、页面和投影文件 |
| `StreamingArchiveScanner` | 单次 NBT 扫描，立即消费页面，在书和木桶结束时提交地址信息 |
| `ParallelGzipOutputStream` | 并行生成并按顺序拼接 gzip 成员 |

## 2. Litematica 外层格式

### 2.1 固定的 Litematica 版本

读取器要求：

```text
Version              = 6
SubVersion           = 1
MinecraftDataVersion = 3465
```

投影必须只有一个区域。区域尺寸为：

```text
Size = { x: barrelCount, y: 1, z: 1 }
```

区域中的方块状态使用 `minecraft:barrel`，方块状态调色板的 0 号为 `minecraft:air`，1 号为 barrel。每个木桶实体必须位于 `(x, 0, 0)`，x 从 0 连续到 `barrelCount - 1`，不能重复、缺失或移动。

`Metadata` 中保留标准 Litematica 字段：

```text
Name
Author              = "CA"
Description         = ""
RegionCount         = 1
TotalVolume
TotalBlocks
TimeCreated
TimeModified
EnclosingSize
```

字段顺序不参与格式语义。木桶实体列表的位置顺序也不参与寻址，读取器按实体里的 X 坐标建立布局；但是 X 坐标本身是地址，不能改变。

### 2.2 gzip NBT

投影是 gzip 包装的 NBT。写入器把待写出的 NBT 字节切成最多 1 MiB 的片段，每个片段由一个 gzip worker 使用标准 gzip level 6 压缩，然后按照原始顺序写入：

```text
gzip(member 0) || gzip(member 1) || ... || gzip(member n)
```

这是标准的 concatenated gzip members。Minecraft 1.20.1 的 `GZIPInputStream`/NBT 读取路径可以连续读取这些成员。小文件可能只包含一个成员。worker 数量最多为 4，并不改变 NBT 字节顺序。

## 3. 原生书和容量

每本书是 Minecraft 原生 written book，物品和 `tag` 只允许这些字段：

```nbt
{
  id: "minecraft:written_book",
  Count: 1b,
  Slot: 0b..26b,
  tag: {
    author: "CA",
    title: "M" | "D",
    resolved: 1b,
    pages: ["URL-safe unpadded Base64", ...]
  }
}
```

其中：

* `M` 只用于元数据木桶；
* `D` 只用于数据木桶；
* `author` 固定为 `CA`；
* `resolved` 必须为 true；
* 当前格式故意省略原生书的 `generation` 字段，因为默认值就是 0；
* 不允许额外物品字段、额外 `tag` 字段或自定义 CloudArchive 字段；
* 每本书至少 1 页，最多 100 页；
* 除最后一页外，每页解码后必须正好是满页容量。

容量常量在 `LitematicaFileCodec` 中定义：

```text
MAX_PAGE_CHARS  = 32,764
BYTES_PER_PAGE  = 24,573
MAX_PAGES_PER_BOOK = 100
BYTES_PER_BOOK  = 2,457,300
INVENTORY_SLOTS  = 27
BYTES_PER_BARREL = 66,347,100
```

32,764 是为了让书页 JSON 字符串序列化后不超过 32,767 字符，并且保持完整 Base64 四元组。页面使用无填充 URL-safe Base64：

```text
ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_
```

原始字节不能直接写进 `pages`，因为 `pages` 是 NBT 字符串列表。Base64 的 ASCII 字节与 modified UTF-8 表示一致，写入器因此可以直接写入 NBT 字符串长度和 ASCII 内容，无需先创建整本书的字符串对象。

数据书地址由木桶 X 和槽位决定：

```text
dataBookIndex = (barrelX - 1) * 27 + Slot
```

元数据书地址为：

```text
metadataBookIndex = Slot
```

每个木桶最多 27 本书，书槽必须从 0 连续到该木桶实际使用的最后一个槽位。元数据固定只能放在一个木桶中，所以元数据最多占 27 本书；目录索引超过 `BYTES_PER_BARREL` 时打包失败。

数据书按存储数据的物理字节流连续切分。除最后一本书外必须写满 `BYTES_PER_BOOK` 字节；最后一本书只包含剩余字节。空存储数据不需要数据书，但仍需要一个元数据木桶。

### 3.1 页面读取校验

读取器不会把页面重新编码后再与原文本比较，而是：

1. 检查长度非零、不超过 `MAX_PAGE_CHARS`，且长度模 4 不能为 1；
2. 每组四字符通过查表验证，合并 24 bit 并直接写入复用的书缓冲区；
3. 对最后不足四字符的组，检查未使用的尾部 bit 必须为 0，再输出剩余字节；
4. 检查除最后一页外的解码长度是否为 `BYTES_PER_PAGE`。

验证和解码在同一个循环中完成，不创建每页的解码数组。解码位置为 `pageIndex * BYTES_PER_PAGE`。这同时拒绝空页、非规范 Base64、非法字符和被普通书界面改写的内容。

## 4. `CAMD v2` 元数据文件

元数据木桶的书按 `Slot` 顺序拼接，得到一个大端序二进制文件。所有整数使用 Java `DataOutput`/`DataInput` 的 big-endian 表示。

元数据文件布局：

```text
固定头 100 字节
归档名称
节点记录
压缩块表
```

`metadata_length` 必须等于拼接后文件的实际长度。元数据文件必须能完整装入 X=0 的一个木桶。

### 4.1 固定头

| 偏移 | 长度 | 字段 | 约束 |
| ---: | ---: | --- | --- |
| 0 | 4 | magic | `0x43414D44`，ASCII `CAMD` |
| 4 | 2 | version | `2` |
| 6 | 2 | feature flags | 当前必须为 `0` |
| 8 | 1 | kind | `0=FILE`，`1=TREE` |
| 9 | 3 | reserved | 必须全为 `0` |
| 12 | 8 | generation | 当前写入 `0`，读取时必须非负 |
| 20 | 8 | next_id | 必须大于所有已分配节点 ID |
| 28 | 4 | node_count | 节点记录数量 |
| 32 | 8 | metadata_length | 元数据文件总长度 |
| 40 | 8 | data_length | 去重后的逻辑数据长度 |
| 48 | 4 | data_book_count | 按 `stored_length` 计算 |
| 52 | 4 | archive_name_length | UTF-8 字节数 |
| 56 | 32 | data_sha256 | 逻辑数据整体 SHA-256 原始字节 |
| 88 | 8 | stored_length | 压缩/RAW 数据的物理存储长度 |
| 96 | 4 | block_count | 压缩块数量 |

偏移 100 之后先写 `archive_name_length` 字节的 UTF-8 归档名称。名称必须是合法 UTF-8，最多 1,024 字节。

`data_length` 和 `data_sha256` 描述解压后的逻辑数据流。`stored_length` 和 `data_book_count` 描述写入数据木桶的压缩后物理字节流。两者不能混用。

### 4.2 节点记录

节点按根相对路径排序写入，父目录必须先于子节点。隐式根目录 ID 为 0，不占记录；实际节点 ID 从 1 开始，当前打包器顺序分配。

所有节点都有：

| 长度 | 字段 |
| ---: | --- |
| 1 | type：`0=directory`，`1=file` |
| 8 | id |
| 8 | parent_id |
| 4 | name_length |
| 可变 | name，UTF-8 |

目录记录到此结束。文件节点继续写：

| 长度 | 字段 |
| ---: | --- |
| 8 | file_size |
| 32 | file_sha256，原始 SHA-256 |
| 4 | extent_count |
| 16 | 每个 extent：`offset`，`length` |

extent 的 offset/length 指向**解压后的逻辑数据流**，不是数据木桶中的物理字节，也不是压缩块偏移。文件内容按 extent 顺序拼接，所有 extent 长度之和必须等于 `file_size`。

当前打包器对非空文件写一个连续 extent：

```text
file A -> [offset=0, length=100]
file B -> [offset=100, length=50]
```

如果 B 与 A 的文件长度和 SHA-256 都相同，B 直接复用 A 的 extent：

```text
file A -> [offset=0, length=100]
file B -> [offset=0, length=100]
```

因此节点仍然保留各自的路径、ID 和哈希，但逻辑数据只保存一份。空文件没有 extent；目录没有文件哈希和 extent。

父 ID 必须指向已经出现的目录，兄弟节点不允许同名，也不允许仅大小写不同。名称不能是 `.`, `..`，不能包含 `/`、`\`、Windows 非法字符、控制字符或保留设备名，也不能以空格或点结束。符号链接和特殊文件不会被打包。

`FILE` 类型必须只有一个 parent_id=0 的根文件节点，并且该节点大小等于 `data_length`。`TREE` 类型可以包含目录、空目录和空文件。

### 4.3 压缩块表

节点记录之后紧接 `block_count` 条固定 9 字节记录：

| 长度 | 字段 | 含义 |
| ---: | --- | --- |
| 1 | codec | `0=RAW`，`1=Zstandard` |
| 4 | raw_length | 解压后的长度，1 到 4 MiB |
| 4 | stored_length | 物理长度，必须大于 0 |

块按逻辑数据顺序排列，块起点不单独存储，而是前面记录长度的前缀和：

```text
logicalOffset(i) = sum(raw_length[0..i-1])
storedOffset(i)  = sum(stored_length[0..i-1])
```

当前参数：

```text
block size = 4 * 1024 * 1024
Zstandard level = 3
```

每块独立压缩，没有共享字典。压缩结果不小于原文时选择 RAW：

```text
codec = 0: stored_length == raw_length
codec = 1: stored_length < raw_length
```

块的物理数据按 `storedOffset` 顺序直接拼接到临时存储文件，再按书容量切分进数据木桶。数据木桶中没有块头；codec 和两个长度全部位于元数据木桶。

读取器使用一个复用的 `ZstdDecompressCtx` 依次解压独立帧；写入器使用一个复用的 `ZstdCompressCtx` 处理所有块。解压后必须严格得到 `raw_length`，不能有物理尾随字节，所有 raw_length 之和必须等于 `data_length`，所有 stored_length 之和必须等于 `stored_length`。

## 5. 打包流程

### 5.1 单文件

`LitematicaFileCodec.pack` 委托给 `FileTreeCodec.packSingleFile`：

1. 拒绝符号链接、特殊文件和链接祖先；
2. 读取文件属性；
3. 建立单文件快照，根节点 parent_id=0；
4. 在一次顺序读取中计算文件 SHA-256，并直接把字节写入 `DataCompression.Encoder`；
5. Encoder 同时更新逻辑数据整体 SHA-256，将满 4 MiB 的块压缩写入 stored 临时文件；
6. 检查读取前后的大小、修改时间和 file key，再完成最后不足 4 MiB 的块；
7. 写 `CAMD v2` 元数据和块表；
8. 顺序读取压缩后的 stored 文件生成木桶 NBT，完成后移动到目标 `.litematic`，不覆盖已有文件。

不创建完整原始数据副本或每文件 staging 文件，也不为了自检把刚压缩的数据完整解压一次。写投影前仍解析并校验元数据、块表和 stored 文件大小；还原端执行完整解压及哈希校验。

### 5.2 文件树

`FileTreeCodec.pack` 先收集根目录、选中的路径及其所有父目录。`pack-tree` 的选择路径相对于根目录；重复选择和重叠选择通过集合合并。

收集阶段会：

1. 拒绝绝对选择路径和逃出根目录的 `..`；
2. 递归发现目录与普通文件；
3. 按根相对路径排序；
4. 保存每个节点的属性。

写入阶段对每个文件只打开一次内容输入流，使用 64 KiB 缓冲区同时更新文件哈希并写入同一个 Encoder。压缩块跨文件边界，不在文件结束时强制 flush。节点、属性、已出现长度和 `hash + size -> extent` 索引留在内存中。

文件哈希直到读取完毕才知道，因此用可回滚的压缩状态保留去重能力：

1. 如果此前出现过相同的非零文件长度，在读取前建立检查点；否则必然不是重复内容，无需检查点。
2. 检查点记录 `buffered`、`storedLength`、块表条数和整体 SHA-256 状态的副本。SHA-256 provider 必须支持克隆；不支持时明确失败。
3. 读取并压缩候选文件。只有候选导致第一次块 flush 时，才备份检查点原有的缓冲区前缀，最多 4 MiB。没有 flush 的小候选不复制大缓冲区。
4. 如果 `hash + size` 首次出现，提交检查点，记录 `[contentSize, fileSize]` extent 并增加唯一逻辑长度。
5. 如果内容已存在，将 stored 文件截断并定位回检查点，删除新增块记录，恢复原缓冲区前缀、长度及整体哈希状态，再复用已有 extent。

重复文件仍读取并计算自身哈希，也可能暂时压缩和写出后回滚；没有二次读取源文件。与先完整落盘再去重相比，这避免了所有唯一数据的额外读写，代价是重复候选的临时压缩工作。整体 SHA-256 最终只覆盖保留下来的唯一逻辑数据。

### 5.3 为什么仍有压缩临时文件

NBT 列表必须在内容之前写出元素数量。木桶、书和页数取决于最终压缩长度，目录索引又包含最终哈希、extent 和块表。因此先完成一次源读取和压缩，再读取 stored 文件生成 NBT；这里重读的是压缩结果，不是源文件或完整原始副本。内存占用与当前块和有界 gzip 队列相关，stored 文件可远大于 Java 堆。

## 6. 还原和 `list` 流程

### 6.1 单次 NBT 扫描

`restoreTransport` 只打开投影一次，使用 64 KiB 的 `GZIPInputStream` 输入缓冲区和 64 KiB 的 `BufferedInputStream`，调用一次 Minecraft 原生 `NbtIo.scan`。较大的输入缓冲区减少默认 512 字节 gzip 缓冲导致的 inflater 调用。

`StreamingArchiveScanner` 直接实现 `NbtScanner`，不继承 `NbtCollector`，也不构造 `NbtCompound`/`NbtList` 内容树。它只保留当前嵌套容器的 Frame 栈、当前书的原生标量字段、当前木桶的槽位 mask 及已见 X 坐标的 `BitSet`。根、区域、尺寸、木桶、书和 tag 通过不同 Frame 类型区分；所需字段检查 NBT 类型及重复出现。

Minecraft 的 scanner 在 compound/list 结束时调用 `endNested`，标量读取完不会调用它。因此只有容器压栈；标量字段用父 Frame 的 `pending` 字段标识，在 `visitString`/`visitByte`/`visitInt` 中消费。页面字符串在 `visitString` 时立即交给 `PageConsumer`，不保存在 Frame 中。

书页顺序具有数据含义，不能改变。其余 compound 字段、库存列表和木桶实体列表顺序都不可靠，读取器等到所需地址字段齐全时再提交内容：

1. 页到达时直接校验并解码到复用的 2,457,300 字节书缓冲区，最多保留当前一页字符串。
2. 书结束时，`Slot`、标题及全部书字段已知。木桶 X 若在 `Items` 之前出现，就直接按 X/Slot 定位写入 metadata 或 stored 文件。
3. 木桶 X 若在 `Items` 之后出现，就把当前书写入一个临时文件，位置为 `Slot * BYTES_PER_BOOK`；只保留 27 个长度和标题条目。
4. 木桶结束时坐标齐全，将该木桶暂存的书按真实 X/Slot 归位。暂存文件截断后供下一木桶复用；额外磁盘空间最多一个木桶容量。

暂存回退不重新扫描投影，也不要求元数据木桶先出现。写入器先写坐标再写库存，因此其正常输出不使用木桶暂存文件。区域名称可变，但必须恰好一个区域；当前写入器使用 `FileStorage`。

### 6.2 传输完整性和解压

每个木桶的槽位 mask 验证槽位唯一且从 0 连续；坐标 BitSet 验证木桶坐标唯一、位于实体列表长度以内，扫描结束再验证全部坐标出现。除最后一个数据木桶外，每个数据木桶必须满 27 本。

metadata 和 stored 分别使用一个 `BookLengths`，只记录书数、总字节数、唯一未满书的索引。坐标和槽位已经证明地址唯一，所以无需为每本书保存 Map 条目。扫描完成后：

1. 根版本、唯一区域、尺寸和全部木桶验证完毕。
2. 继续读取解压流直到 EOF，拒绝尾随 NBT 字节，并使 gzip CRC、长度和截断错误得到检查。
3. 解析并严格验证 metadata 文件中的 `CAMD v2`。
4. 核对木桶数、书数、总长度、最后未满书的位置与 metadata 声明一致；检查 stored 文件实际长度。
5. 按块表使用固定缓冲区和复用的 Zstandard context 解压到逻辑数据临时文件，同时验证整体 SHA-256。
6. 整体校验通过后才还原文件或列出目录。

临时文件位于系统临时目录或输出父目录的私有临时目录。失败时清理临时数据；输出目标已存在时不会覆盖。正常关闭和读取到 EOF 对完整性检查同样必要，不能在根 compound 结束后直接停止读取 gzip 流。

### 6.3 文件还原

标准 `FILE` 归档只有一个连续 `[0, fileSize]` extent，或空文件没有 extent。此时已校验的逻辑数据就是目标文件，再比较节点哈希与已验证的整体哈希，直接移动逻辑数据文件到目标，省去完整二次复制和哈希。保留多个 extent 的当前格式仍有效，使用按 extent 复制和文件哈希校验的路径，不是旧格式兼容。

`TREE` 的每个文件按 extent 列表从逻辑数据文件复制字节，同时计算并核对文件 SHA-256。目录按父先于子创建。树还原先在私有临时目录中完成，全部成功后才移动到目标。

`list` 同样解压、验证整体逻辑数据，再对每个文件 extent 计算哈希，能发现压缩块、数据和文件区间损坏；投影仍然只扫描一次。

### 6.4 内存和磁盘边界

打包器主要缓冲为一个 4 MiB 原始块、一个 compressBound 大小的压缩块、可选的最多 4 MiB 检查点前缀及有界并行 gzip 队列。还原扫描阶段复用一个约 2.46 MB 书缓冲区；解压阶段使用两个 4 MiB 数组。它们不会按归档字节数累计。

节点、路径、去重索引和块表仍随文件数或块数增长，坐标 BitSet 随木桶数增长；不是对任意规模承诺绝对常量内存。元数据本身受一个木桶容量限制。Zstandard 原生 context、线程栈及 JVM 其他内存不计入 Java heap。

打包磁盘暂存 metadata 和 stored；读取暂存 metadata、stored 及解压后的逻辑数据，字段乱序最多额外暂存当前一个木桶。目录还原还需目标目录空间。强制终止进程可能留下临时目录。

## 7. 严格校验规则

实现会拒绝以下情况：

* 不支持的 Litematica 版本、多个 region 或区域尺寸不匹配；
* 木桶缺失、重复、坐标移动或 y/z 不为 0；
* 元数据木桶缺失、超过 27 本书或书槽不连续；
* 数据书缺失、重复、槽位错误或容量不匹配；
* 书物品 ID、Count、Slot、作者、标题、resolved、pages 字段不正确；
* 自定义 NBT 字段或旧 `generation` 字段；
* 空页、非法 Base64、非规范尾部 bit 或不完整中间页；
* `CAMD` magic、版本、长度、计数、UTF-8、哈希或保留字段不正确；
* 节点父目录不存在、ID 重复、兄弟名称冲突或文件 extent 越界；
* 压缩 codec 未知、块长度不匹配、Zstandard 解压长度错误或物理尾随字节；
* 源文件在打包期间发生变化；
* 输出目录/文件已存在，或路径涉及符号链接/特殊文件。

## 8. 未来修改设计

当前格式已经为更新操作保留了几个关键结构：

* 节点有稳定 ID、`parent_id` 和 `next_id`；
* 头部有 `generation`；
* 文件内容通过显式 extent 引用逻辑数据区；
* extent 可以是多个不连续区间；
* 压缩块是独立帧，块表记录了每个块的逻辑长度和物理长度；
* 数据与元数据分离，数据木桶不携带路径信息。

这些字段目前只是格式能力，不代表更新功能已经存在。未来实现更新时需要额外设计：

1. 空间分配器：决定新块写入空闲区间还是追加到末尾；
2. extent 重写：文件变更后只替换受影响的逻辑区间；
3. generation 冲突检测：防止两个客户端覆盖同一快照；
4. 事务或写时复制：避免更新中途留下不可恢复的元数据；
5. 垃圾回收：回收被旧节点和旧 extent 放弃的块；
6. 服务器同步：确保桶、书槽、投影导出和容器同步保持一致。

在这些机制实现前，开发者不应把 `generation`、`next_id` 或多个 extent 当作可写协议使用，也不应直接修改书页内容。

## 9. 扩展读取器时的建议

实现第三方读取器时，建议遵循以下顺序：

```text
读取 gzip NBT（一次扫描，不保留内容树）
    -> 页面立即解码，书/木桶结束后按 X 和 Slot 重建 metadata/stored
    -> 坐标未知时仅暂存当前木桶到磁盘
    -> 验证唯一区域、布局及 gzip EOF/CRC
    -> 解析 CAMD v2
    -> 验证 block table
    -> 逐块解压并验证 data_sha256
    -> 根据 node.extents() 还原文件
    -> 验证每个 file_sha256
```

不要：

* 按 NBT 字段出现顺序解析；
* 用木桶实体列表下标代替实体 X 坐标；
* 用物理 `stored_length` 偏移解释 extent；
* 把数据书当作独立文件或独立块；
* 忽略最后一页之外的页面长度；
* 通过“兼容”方式接受未知书字段；
* 只校验元数据而跳过压缩数据和文件哈希。

## 10. 构建与验证

项目使用 JDK 21 构建，但输出 Java 17 字节码：

```powershell
$env:JAVA_HOME = 'C:\applications\Java\jdk-21'
.\gradlew.bat check
.\gradlew.bat build
.\gradlew.bat verifyLargeFileCodec
```

`check` 包含 `verifyFileCodec`，覆盖：

* 原生书 NBT 字段和页面容量；
* 多书、多木桶和元数据跨页；
* 文件树、空目录、空文件和重复文件去重；
* 压缩检查点跨多个块的回滚、未 flush 的小回滚、同长度不同内容；
* RAW 回退、Zstandard 跨块还原和损坏数据拒绝；
* NBT 字段/列表乱序；
* 缺书、重复书、移动木桶和旧格式拒绝；
* 路径安全、源文件变更检测和失败清理；
* 标准 gzip 多成员解码、截断、CRC 损坏和尾随 NBT 拒绝；
* 128 MiB 堆限制下的完整往返。

`verifyLargeFileCodec` 是独立的 64 MiB 堆测试，不随普通 `build` 运行。它生成 512 MiB 不可压缩随机数据及 2,147,483,681 字节的高度可压缩文件，打包、还原并逐字节比较。测试需要数 GiB 可用磁盘空间，结束后清理生成数据。性能对照和复现步骤见 [benchmarks.md](benchmarks.md)。

当前验证覆盖离线编解码和格式正确性，不等同于 Minecraft 游戏内粘贴、服务器容器同步或 Litematica 再导出的兼容性保证。
