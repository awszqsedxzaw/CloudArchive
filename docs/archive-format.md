# CloudArchive 归档格式

[README](../README.md) · [使用指南](usage.md) · [开发者文档](developer-guide.md) · [基准记录](benchmarks.md)

本文是 `eaf96d3` 实现的当前 `CAMD v2` 磁盘规格，描述如何从 `.litematic` 重建元数据和文件；未提交的并行开发改动不计入该基准。只支持这一当前格式，不支持旧版本或旧书字段布局。实现算法、源码入口及资源管理见开发者文档。

以下区分写入器生成的标准布局和读取器实际接受的结构；不能把未检查的外围 NBT 字段视为通过了验证。

## 1. 层次和字节流

```text
.litematic
  gzip members
    NBT root
      一个 region
        X=0 木桶：metadata books -> CAMD metadata
        X>=1 木桶：data books -> stored bytes
          RAW / Zstandard blocks -> logical bytes
            node.extents -> file bytes
```

metadata、stored 与 logical 是三段不同的字节流：

- metadata：目录、文件信息、文件区间、哈希和压缩块表。
- stored：各压缩块的存储字节直接拼接，没有 CloudArchive 块头。
- logical：各块解压后的唯一文件内容直接拼接，extent 指向此流。

文件边界不对齐块、页、书或木桶。书中的内容不能独立解释为一个文件，也不一定是一个完整 Zstandard 帧。

## 2. Litematica 外层

### 2.1 写入器生成结构

外层是 gzip 包装的标准大端 NBT，无名 root compound：

| 位置 | 标签类型 | 写入值 |
| --- | --- | --- |
| root.Version | INT | 6 |
| root.SubVersion | INT | 1 |
| root.MinecraftDataVersion | INT | 3465 |
| root.Metadata | COMPOUND | 标准 Litematica 展示元数据 |
| root.Regions | COMPOUND | 一个名为 `FileStorage` 的区域 |
| region.Position | COMPOUND | INT x/y/z = 0/0/0 |
| region.Size | COMPOUND | INT x/y/z = N/1/1 |
| region.BlockStatePalette | LIST(COMPOUND) | air、barrel，共两个元素 |
| region.BlockStates | LONG_ARRAY | 每个方块值为调色板索引 1 |
| region.Entities | 空 LIST | 无实体 |
| region.TileEntities | LIST(COMPOUND) | N 个木桶方块实体 |

木桶调色板项为 `Name="minecraft:barrel"`，Properties 中 `facing="north"`、`open="false"`；air 项为 `Name="minecraft:air"`。Litematica 状态数组至少每方块 2 bit，long 数量为 `ceil(2*N/64)`，第 i 个方块索引 1 放在第 `2*i` 位。未使用尾部位为 0。

Metadata 包含：

| 字段 | 类型/值 |
| --- | --- |
| Name | STRING，归档名称 |
| Author | STRING，`CA` |
| Description | STRING，空串 |
| RegionCount | INT，1 |
| TotalVolume、TotalBlocks | INT，N |
| TimeCreated、TimeModified | LONG，相同的创建时刻，Unix 毫秒 |
| EnclosingSize | COMPOUND，INT x/y/z=N/1/1 |

每个 TileEntities 元素含 STRING `id="minecraft:barrel"`，INT x/y/z 和 LIST(COMPOUND) Items。坐标 `(0,0,0)` 保存 metadata，`(1,0,0)` 到 `(N-1,0,0)` 保存 stored。标准输出先写坐标再写库存，木桶沿本地 X 轴连续排列。

### 2.2 gzip 和 NBT 编码

写入器每最多 1 MiB NBT 字节生成一个独立 gzip member，最多四个 worker 并行，按原始顺序拼接。使用 JDK GZIPOutputStream 的默认 Deflater 级别，当前等效于 6；小输出可能仅有一个成员。JDK/Minecraft 的 GZIPInputStream 可以连续读取这些成员。

NBT 字符串按 modified UTF-8 编码，前缀是两字节无符号长度；命名 tag 有类型 byte 和 NBT 字符串名称。list 含元素类型 byte、四字节元素数，元素只有 payload；compound 元素用 END byte 结束，不再次写类型/名字。CAMD 名称使用标准 UTF-8，不能套用 NBT modified UTF-8。

### 2.3 读取器实际校验

读取器要求上述三个版本 INT 和 Regions 存在，Regions 恰好一个 compound，区域名称可以不同。区域必须有 Size 和 TileEntities，Size 的 N 必须与实体数相等，y/z=1。木桶 ID、坐标及 Items 必需，X 必须唯一并覆盖 0..N-1，y/z=0。

识别字段必须类型正确且不重复。root、region、Size 和 barrel 中的未知字段跳过；root.Metadata、region.Position、调色板、BlockStates、Entities 不做内容验证，也不要求存在。书物品和书 tag 内则严格拒绝未知字段。因此：

- 生成器保证标准可视化布局，并由投影格式测试核对。
- 读取成功表示库存传输和文件校验通过，不能证明外围方块状态在游戏中可视化正确。
- compound 字段、TileEntities 和 Items 的物理顺序可以变化；坐标、Slot 和书页顺序不能改变其地址/内容意义。

NBT root 结束后要求解压输入 EOF，完成 gzip CRC、截断和 footer 检查，拒绝解压后的尾随 NBT 字节。不承诺拒绝 gzip 成员之外任意形式的压缩垃圾。

## 3. 原生书与容量

### 3.1 字段精确集合

每个 Items 元素只有以下四个标签，tag 也只有以下四个标签：

| 位置 | NBT 类型 | 值/限制 |
| --- | --- | --- |
| id | STRING | `minecraft:written_book` |
| Count | BYTE | 1 |
| Slot | BYTE | 按无符号解释，0..26 |
| tag | COMPOUND | 下列字段 |
| tag.author | STRING | `CA` |
| tag.title | STRING | X=0 为 `M`，X>0 为 `D` |
| tag.resolved | BYTE | 写入 1，读取接受任意非零值 |
| tag.pages | LIST(STRING) | 1..100 页，按内容顺序 |

不存 generation、filtered_pages、文件名、序号或自定义字段。省略的书 generation 在 Minecraft 中默认为 0，与 CAMD 头的 generation 无关。保留 resolved 避免 Minecraft 再次解析并重写书页。书页直接是 Base64 文本，没有 JSON 包装，不应由书的阅读/编辑界面修改。

### 3.2 容量来源

Minecraft 1.20.1 的 WrittenBookItem 定义 MAX_PAGES=100、MAX_PAGE_VIEW_LENGTH=32767；WritableBookItem.isValid 对页字符长度检查 32767，WrittenBookItem.resolve 对解析后的 JSON 同样检查 32767。NBT 字符串自身另有 modified UTF-8 65,535 字节限制，不能将它直接当成安全书页容量。

本格式把 32767 减去 JSON 字符串的两个引号，再向下取整为完整 Base64 四元组，得到 32764。当前页本身仍不加 JSON；这是选定的保守容量策略，不表示所有原始 NBT 编辑路径都仅能存这些字节。

| 常量 | 值 |
| --- | ---: |
| MAX_PAGE_CHARS | 32,764 |
| BYTES_PER_PAGE | 24,573 |
| MAX_PAGES_PER_BOOK | 100 |
| BYTES_PER_BOOK | 2,457,300 |
| INVENTORY_SLOTS | 27 |
| BYTES_PER_BARREL | 66,347,100 |

### 3.3 页编码与地址

Base64 字母表为：

```text
ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_
```

使用 URL-safe、无 `=` 填充的规范编码。空页、非字母表字符、长度 mod 4=1、非零尾部未使用 bit 均拒绝。mod 4=2 时最后字符低 4 bit 必须为 0，mod 4=3 时低 2 bit 必须为 0。每本书除最后一页外，解码长度必须恰好 24,573。

对存储字节偏移 q：

```text
dataBookIndex = floor(q / BYTES_PER_BOOK)
barrelX       = 1 + floor(dataBookIndex / 27)
Slot          = dataBookIndex mod 27
pageIndex     = floor((q mod BYTES_PER_BOOK) / BYTES_PER_PAGE)
offsetInPage  = q mod BYTES_PER_PAGE
```

元数据书索引就是 Slot，metadataOffset=`Slot * BYTES_PER_BOOK`。dataBookIndex=`(barrelX-1)*27 + Slot`，storedOffset=`dataBookIndex * BYTES_PER_BOOK`。

每桶实际书 Slot 必须从 0 连续且唯一，物品列表顺序不限。metadata 和 stored 分别仅最后一本书可不满；除最后数据木桶外，每个数据木桶必须满 27 本书。数据为空时无数据木桶，元数据桶始终存在且至少有一本书。

## 4. CAMD 二进制元数据

将 X=0 库存中的书按 Slot 拼接，得到 metadata 文件。整数使用 Java DataInput/DataOutput 大端序，2 字节 version/flags 按无符号解释，1 字节类型/codec 同样按无符号解释；四字节/八字节长度及 ID 为非负或正数的有符号 int/long。哈希存原始 32 字节。

```text
100-byte header || archiveName || node records || block records
```

没有额外页头、完整路径重复项、对齐填充或结束标记。

### 4.1 固定头

| 偏移 | 字节数 | 字段 | 限制 |
| ---: | ---: | --- | --- |
| 0 | 4 | magic | ASCII `CAMD`，0x43414D44 |
| 4 | 2 | version | 2 |
| 6 | 2 | feature_flags | 0 |
| 8 | 1 | kind | 0=FILE，1=TREE |
| 9 | 3 | reserved | 全为 0 |
| 12 | 8 | generation | 写入 0，读取须非负 |
| 20 | 8 | next_id | >=1 且大于所有已分配 ID |
| 28 | 4 | node_count | 非负 |
| 32 | 8 | metadata_length | 等于完整 metadata 实际字节数 |
| 40 | 8 | data_length | 去重后逻辑数据长度 |
| 48 | 4 | data_book_count | 从 stored_length 推导 |
| 52 | 4 | archive_name_length | 1..1,024 |
| 56 | 32 | data_sha256 | 全部 logical 字节的 SHA-256 |
| 88 | 8 | stored_length | 0..data_length |
| 96 | 4 | block_count | 非负 |

偏移 100 之后写 archive_name_length 个标准 UTF-8 字节，必须严格可解码。归档名称只是展示名称，不参与还原路径，也不要求符合节点文件名规则。

metadata_length 不超过 66,347,100。data_length 最大为 `BYTES_PER_BOOK * Integer.MAX_VALUE`；stored_length 不超过 data_length。理论数值上限不代表实际游戏或磁盘可支持该规模，节点、块表及木桶尺寸另有限制。

```text
metadata_book_count = ceil(metadata_length / BYTES_PER_BOOK)     # 1..27
data_book_count     = stored_length == 0 ? 0 : ceil(stored_length / BYTES_PER_BOOK)
barrel_count        = 1 + ceil(data_book_count / 27)
```

书数由压缩后的 stored_length 决定。header 没有独立 metadata_book_count，读取器根据 metadata 实际长度计算。

### 4.2 节点公共记录

每节点：

| 字节数 | 字段 |
| ---: | --- |
| 1 | type：0=directory，1=file |
| 8 | id：正数且唯一 |
| 8 | parent_id：0 或此前已出现的目录 ID |
| 4 | name_length：1..1,024 |
| name_length | name：单个路径组件，标准 UTF-8 |

ID 0 是隐式根，不占记录。目录到此结束，长度 `21 + name_length`。读取器要求父目录先出现，不要求 ID 连续或节点字典序。

当前写入器按根相对路径排序，ID 从 1 分配，next_id 为节点数+1；TREE 不额外记录根目录，FILE 有且仅有一个 parent_id=0 的文件节点。

### 4.3 文件追加记录

type=1 时追加：

| 字节数 | 字段 |
| ---: | --- |
| 8 | file_size：非负 |
| 32 | file_sha256：文件按 extent 顺序拼接后的哈希 |
| 4 | extent_count：非负 |
| 16 * extent_count | 每个 extent 的 long offset、long length |

文件节点长度为 `65 + name_length + 16 * extent_count`。extent.offset 相对于 logical 字节 0，length 必须正数，区间不得越界；长度之和必须等于 file_size。空文件没有 extent，但保留空内容哈希。

区间按记录顺序拼接，不要求递增、不要求相邻；不同文件可以共享同一区间，区间还可指向逻辑数据中重叠部分。允许存在未引用逻辑字节，整体哈希仍涵盖这些字节。FILE 的 file_size 必须等于 data_length，但读取器仍允许多个区间描述其内容。

当前打包器仅为非空文件生成一个 extent，相同 size/SHA-256 的文件复用此前 extent。当前没有分块去重或原地修改逻辑。

### 4.4 路径规则

父 ID 必须指向已经出现的目录，不能指向文件，不能形成前向引用或循环。兄弟名称按 `Locale.ROOT` 小写后必须唯一；不同父目录可重名。

节点名称严格 UTF-8，不能为空、`.`、`..`，不能包含控制字符 U+0000..U+001F、`< > : " / \ | ? *`、未配对 surrogate，不能以空格或点结尾。保留设备名检测使用第一个点前的 stem，去除末尾空白并转大写：

```text
CON PRN AUX NUL
COM1..COM9 LPT1..LPT9
COM/LPT 后接上标 1、2、3
```

不做 Unicode 正规化或自动改名。名称长度为 UTF-8 字节数而非字符数，完整路径长度由目标文件系统决定。权限、时间戳、所有者和链接关系不在格式内。

### 4.5 解析分区与长度校验

节点区字节数为：

```text
metadata_length - 100 - archive_name_length - 9 * block_count
```

必须非负。node_count 不得超过该长度/22，节点解析隔离在此区且必须恰好耗尽。extent_count 不得超过剩余节点字节数/16。随后读取 block_count 条记录，不能将节点尾部或不完整记录当作块表。实际文件长度、头声明和各区长度必须一致。

合法 TREE 可以为 0 个节点；FILE 必须为一个根文件。next_id 必须严格大于最大节点 ID；generation 不用于当前更新操作，只作为非负保留值。

## 5. 块表和数据木桶

节点之后有 block_count 条记录，每条 9 字节：

| 字节数 | 字段 | 限制 |
| ---: | --- | --- |
| 1 | codec | 0=RAW，1=Zstandard |
| 4 | raw_length | 1..4,194,304 |
| 4 | stored_length | 正数，RAW 等于 raw，Zstandard 小于 raw |

块序即 logical/stored 顺序：

```text
logicalStart(i) = sum(raw_length[0 .. i-1])
storedStart(i)  = sum(stored_length[0 .. i-1])
sum(raw_length)    == header.data_length
sum(stored_length) == header.stored_length
```

空 logical/stored 时块表为空。当前写入器除最后块外每块 raw_length=4 MiB；读取器接受任意合法的较小块，不要求中间块满。Zstandard 块由写入器用 level=3、无字典的独立标准帧生成；level 本身不写入 metadata，解压无需知道压缩级别。

数据木桶拼接的解码字节恰好就是各块 payload，没有重复的 codec/length 头。解码每块必须读足 stored_length，解压输出必须恰好 raw_length，stored 流不能有剩余字节。未知 codec、不符合 RAW/Zstandard 长度关系及前缀和不匹配均拒绝。

## 6. 多文件示例

根名 `sample`，包含：

```text
a.bin         100 bytes
b.bin         与 a.bin 相同
sub/
  c.bin        50 bytes，内容不同
```

当前写入器节点及区间：

| ID | parent | name | 类型 | size | extent |
| ---: | ---: | --- | --- | ---: | --- |
| 1 | 0 | a.bin | file | 100 | [0,100] |
| 2 | 0 | b.bin | file | 100 | [0,100] |
| 3 | 0 | sub | directory | 0 | 无 |
| 4 | 3 | c.bin | file | 50 | [100,50] |

全部文件总长度为 250，logical 为 `a || c`，data_length=150，next_id=5。只有一个压缩块，stored 长度 S 在 1..150，RAW 时为 150。metadata 长度为 `100 + 6 + 86 + 86 + 24 + 86 + 9 = 397` 字节，占一本 M 书；stored 占一本 D 书，共两个木桶。

data_sha256 为 `SHA256(a || c)`，a/b 的 file_sha256 相同，c 使用自己的哈希。还原只靠节点和 extent 重建文件，不根据书标题或物品排列推断文件边界。

## 7. 完整性与未来字段

有效归档须同时满足：

1. gzip/NBT 根、版本、唯一 region、尺寸和完整木桶坐标。
2. 原生书字段、作者/标题、Slot、页数、规范 Base64、中间满页/满书/满桶。
3. CAMD 头、UTF-8、ID/父目录/名称、所有长度、extent 和块表。
4. 物理 stored 总长度、精确解压长度和全部 logical SHA-256。
5. 每个文件按 extent 拼接后的 SHA-256。

metadata 本身没有额外全文件哈希，gzip CRC 和结构检查不等于加密认证。generation、next_id、稳定 ID、多 extent 和独立块为将来修改预留；当前没有分配器、日志、事务或写入协议，也不承诺重新打包沿用旧 ID。扩展这些能力时需要同时调整格式、实现和校验，格式变更不增加旧版本兼容。
