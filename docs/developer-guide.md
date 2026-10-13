# CloudArchive 开发者文档

本文以当前源码为准，说明 CloudArchive 1.20.1 编解码器的实现、`.litematic` 外层布局和 `CAMD v2` 元数据格式。它面向需要实现读取器、写入器、校验器或未来服务器端同步功能的开发者。

当前格式是唯一支持的格式。读取器不兼容旧版 CloudArchive 格式、旧书籍字段布局或旧投影文件。格式版本升级时应新增明确的版本分支；不要让新读取器猜测旧数据的含义。

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
| `DataCompression` | 4 MiB 块的 Zstandard/RAW 编解码 |
| `LitematicaFileCodec` | Litematica NBT、木桶、书、页面和投影文件 |
| `ArchiveScanner` | 单次 NBT 扫描，收集投影结构、区域布局和书库存 |
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

1. 查表检查每个字符是否属于 64 个 URL-safe Base64 字符；
2. 检查长度不超过 `MAX_PAGE_CHARS`；
3. 检查长度模 4 不能为 1；
4. 对最后一个字符检查未使用的尾部 bit 必须为 0；
5. 调用 URL-safe Base64 decoder 一次；
6. 检查除最后一页外的解码长度是否为 `BYTES_PER_PAGE`。

这同时拒绝空页、非规范 Base64、非法字符和被普通书界面改写的内容。

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
4. 在一次顺序读取中把源文件复制到临时 staging 文件并计算 SHA-256；
5. 检查复制前后的文件属性，发现大小、修改时间或 file key 变化时失败；
6. 对 staging 中的逻辑数据做 4 MiB 分块压缩；
7. 写 `CAMD v2` 元数据和块表；
8. 生成木桶 NBT 并原子移动到目标 `.litematic`。

### 5.2 文件树

`FileTreeCodec.pack` 先收集根目录、选中的路径及其所有父目录。`pack-tree` 的选择路径相对于根目录；重复选择和重叠选择通过集合合并。

收集阶段会：

1. 拒绝绝对选择路径和逃出根目录的 `..`；
2. 递归发现目录与普通文件；
3. 按根相对路径排序；
4. 保存每个节点的属性。

写入阶段对每个文件只顺序读取一次：同时写入临时 staging 文件并计算 SHA-256，
然后使用 `hash + size` 作为重复文件候选键。首次出现的内容从 staging 追加到逻辑数据流；
重复文件不再读取源文件，只复用首次内容的 extent。所有文件仍保留独立节点和独立文件哈希。

完成逻辑数据后，整体 SHA-256 是写入逻辑数据流时计算的摘要。它覆盖去重后的唯一数据，不重复计算相同文件。

## 6. 还原和 `list` 流程

### 6.1 单遍 NBT 扫描

Litematica NBT 的 compound 字段没有可靠顺序。当前 `ArchiveScanner` 使用一次
`NbtIo.scanCompressed` 调用把投影解压并收集为内存对象；扫描结束后在同一个对象上验证
Litematica 版本、唯一 region、区域尺寸和木桶坐标，再按木桶 X 坐标和书槽位提取书籍。
这样不会为了先确定布局而再次解压 gzip 投影。

读取器不依赖 compound 字段顺序、木桶实体列表顺序或书物品列表顺序，但依赖木桶 X
坐标和书 `Slot`，因为它们是地址。该实现会保留一次扫描得到的投影 NBT 对象，
因此读取大投影需要更多堆内存；它不建立第二份大型结构，也不会重复解压。
当前验证任务堆上限为 512 MiB；开发者处理更大的投影时应按书页总量增加堆内存。

### 6.2 传输数据恢复

书扫描结束后：

1. 按槽位从单次扫描得到的书籍重建元数据文件；
2. 读取并严格验证 `CAMD v2`；
3. 检查元数据书数量、每一本书长度、数据书数量和每一本书长度；
4. 将数据书重建为 `stored_length` 字节的物理存储文件；
5. 按块表解压到输出逻辑数据文件；
6. 对解压后的逻辑数据计算整体 SHA-256；
7. 整体校验通过后才进入文件/目录还原。

临时文件位于系统临时目录或输出父目录的受保护临时目录。失败时删除临时文件；输出目标已存在时不会覆盖。

### 6.3 文件还原

对于每个文件节点，按 extent 列表从逻辑数据文件复制字节，边复制边计算文件 SHA-256，最后与节点中的哈希比较。目录按父先于子创建。树还原先在私有临时目录中完成，全部成功后才移动到目标。

`list` 不只读取目录索引，它同样解压并验证整体逻辑数据，再对每个文件 extent 计算哈希。因此 `list` 可以发现压缩块损坏、数据篡改和文件区间损坏。

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
读取 gzip NBT（单次扫描）
    -> 验证 Litematica 版本、唯一 FileStorage 和木桶布局
    -> 按 X 坐标和槽位重建 metadata/stored 两个字节流
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
```

`check` 包含 `verifyFileCodec`，覆盖：

* 原生书 NBT 字段和页面容量；
* 多书、多木桶和元数据跨页；
* 文件树、空目录、空文件和重复文件去重；
* RAW 回退、Zstandard 跨块还原和损坏数据拒绝；
* NBT 字段/列表乱序；
* 缺书、重复书、移动木桶和旧格式拒绝；
* 路径安全、源文件变更检测和失败清理；
* 标准 gzip 多成员解码；
* 128 MiB 堆限制下的完整往返。

当前验证覆盖离线编解码和格式正确性，不等同于 Minecraft 游戏内粘贴、服务器容器同步或 Litematica 再导出的兼容性保证。
