# CloudArchive 使用指南

[README](../README.md) · [开发者文档](developer-guide.md) · [归档格式](archive-format.md) · [基准记录](benchmarks.md)

当前功能是离线文件编解码器，运行命令无需启动 Minecraft，也不需要服务器或 PCA。Fabric 客户端入口目前只初始化模组，尚无游戏内菜单、命令或容器同步功能。

## 1. 环境与命令写法

需要 JDK 21；项目通过 Gradle Wrapper 使用 Gradle 8.5，无需另行安装 Gradle。首次运行需要下载构建依赖：

```powershell
$env:JAVA_HOME = 'C:\path\to\jdk-21'
.\gradlew.bat runFileCodec --args=help
```

PowerShell 中将整个 `--args=...` 用双引号包住，含空格的内部路径用单引号包住。内部引号由 Gradle 解析：

```powershell
.\gradlew.bat runFileCodec "--args=pack 'C:\files\my data.bin' 'C:\files\out\data.litematic'"
```

相对路径相对于项目目录。Linux/macOS 用 `./gradlew`：

```sh
./gradlew runFileCodec --args="pack 'my data.bin' 'out/data.litematic'"
```

以下示例中的源文件、目录须已存在，输出须选择新的路径。

## 2. 命令

| 命令 | 行为 |
| --- | --- |
| `help` 或 `--help` | 打印用法 |
| `pack <input> <output>` | 普通文件生成 FILE 归档，目录生成 TREE 归档 |
| `pack-tree <root> <output> [relative-path ...]` | 打包根目录下的选择；不指定选择时打包整个根目录 |
| `list <archive>` | 验证全部数据和文件哈希，然后列出文件树 |
| `restore <archive> <destination>` | 自动识别 FILE/TREE，还原为一个文件或目录 |

### 2.1 单文件

```powershell
.\gradlew.bat runFileCodec "--args=pack '.gitignore' 'out\gitignore.litematic'"
.\gradlew.bat runFileCodec "--args=list 'out\gitignore.litematic'"
.\gradlew.bat runFileCodec "--args=restore 'out\gitignore.litematic' 'out\gitignore-restored'"
Get-FileHash -Algorithm SHA256 -LiteralPath .gitignore
Get-FileHash -Algorithm SHA256 -LiteralPath out\gitignore-restored
```

两个 SHA-256 应相同。FILE 归档记录原文件名，但还原后的文件名由 `<destination>` 指定。

### 2.2 文件树

```powershell
.\gradlew.bat runFileCodec "--args=pack 'data' 'out\data.litematic'"
.\gradlew.bat runFileCodec "--args=restore 'out\data.litematic' 'out\restored-data'"
```

保留所选目录结构、空文件和空目录，包括隐藏文件。根目录不再嵌套一层：`data\docs\a.txt` 还原为 `out\restored-data\docs\a.txt`。

只选择部分路径：

```powershell
.\gradlew.bat runFileCodec "--args=pack-tree 'data' 'out\selected.litematic' 'docs\a.txt' 'images' 'notes.txt'"
```

选择路径必须相对于 `data`，不能是绝对路径，也不能包含任何 `..` 路径组件。目录选择会递归包含子节点；重复和重叠选择合并，所需的父目录自动加入。没有选择参数时等价于整树打包。

### 2.3 查看输出统计

`pack` 和 `list` 显示以下几种长度，均为字节：

| 输出 | 含义 |
| --- | --- |
| `Packed ... bytes` / `content bytes` | 全部文件大小之和，重复文件分别计数 |
| `Unique data` | 去重后、解压后的逻辑数据长度 |
| `stored data after compression` | Zstandard/RAW 块的存储长度，尚未计入 Base64、NBT 和 gzip |
| `Metadata` / `data` / `total` | 元数据书、数据书和木桶数量 |

最终 `.litematic` 的文件大小应通过文件系统查看，不能把 `stored data` 当成投影大小。`list` 执行完整验证，因此大归档的列出时间和临时磁盘需求接近解码。

## 3. 输出与路径规则

正确后缀为 `.litematic`。打包时的规范化区分大小写：

| 指定输出 | 实际输出 |
| --- | --- |
| `data.litematic` | `data.litematic` |
| `data.litematica` | `data.litematic` |
| `data` | `data.litematic` |
| `data.bin` | `data.bin.litematic` |
| `data.LITEMATIC` | `data.LITEMATIC.litematic` |

`restore` 和 `list` 使用传入的实际文件路径，不自动纠正后缀。

输出父目录不存在时自动创建。现有输出不覆盖、不合并，包括现有空目录。树归档必须输出到源根目录以外，以免把输出纳入源树。

只保存文件字节、名称、目录和哈希，不保存权限、时间戳、所有者或硬链接关系。符号链接及特殊文件会被拒绝，路径的现有祖先也会检查链接。节点名称须满足 Windows 安全规则；非法字符、保留设备名、末尾空格或点及同目录下仅大小写不同的名称均拒绝。名称最多 1,024 个 UTF-8 字节，完整路径长度仍受目标文件系统限制。

打包期间应保持源数据稳定。实现核对读取前后的大小、修改时间、文件身份及实际读入长度，但并非文件系统级原子快照。完整名称规则和检查边界见[归档格式](archive-format.md)与[开发者文档](developer-guide.md)。

## 4. 存储容量与资源

木桶沿投影本地 X 轴排列，X=0 保存元数据，X>=1 保存压缩后的纯数据。元数据最多占一个木桶，超过容量会失败。文件可以跨页、跨书、跨木桶；文件边界无需补齐。

| 单位 | 最大存储容量 |
| --- | ---: |
| 一页 | 32,764 个 Base64 字符，解码为 24,573 字节 |
| 一本书 | 100 页，2,457,300 字节 |
| 一个木桶 | 27 本书，66,347,100 字节 |

以上是传输字节容量，原文件能装多少取决于去重和压缩效果。数据书数为 `ceil(stored_length / 2,457,300)`；总木桶数为 `1 + ceil(数据书数 / 27)`。空文件或空树仍有一个元数据木桶。存储长度为 66,347,131 字节时，需要 28 本数据书、2 个数据木桶，加元数据木桶共 3 个木桶。

CLI 使用磁盘临时文件，数据缓冲不会按文件体积累计；文件节点、块表和坐标位图仍随规模增长。打包需要元数据、压缩数据和正在生成的投影空间；还原与 `list` 需要元数据、压缩数据和解压后的逻辑数据空间。树还原还需完整目标目录空间，字段乱序最多额外暂存一个木桶。打包及 `list` 使用系统临时目录，还原使用目标父目录。正常退出或抛出异常会尝试清理；强制终止可能留下临时目录。

64 MiB 堆的大文件验证结果见[基准记录](benchmarks.md)。Java 堆上限不等于进程总内存；游戏内 Litematica 加载完整投影的内存需求也与 CLI 不同。

## 5. 错误处理

CLI 退出码为 0 表示成功，1 表示参数数量、格式、路径或 I/O 等操作错误，2 表示无命令或未知命令。Gradle 会将 CLI 非零退出码显示为任务失败。

| 提示或现象 | 处理 |
| --- | --- |
| `Output already exists` | 选择新的输出文件或目录 |
| `Tree output must be outside the source root` | 将投影放到源目录之外 |
| `Source changed while packing` | 停止对源文件的修改后重新打包 |
| `Metadata needs more than one barrel` | 减少索引规模，拆成多个归档 |
| magic/version、书字段或哈希错误 | 检查归档是否完整；旧格式必须重新打包 |
| 磁盘空间或移动失败 | 检查系统临时目录与目标磁盘空间、权限及文件占用 |

当前只支持 `CAMD v2`、Litematica `Version=6`、`SubVersion=1`、`MinecraftDataVersion=3465` 和当前书字段布局。没有旧格式兼容，也不支持 1.20.5 之后的物品组件结构。游戏或 Litematica 的跨版本升级不属于本项目的兼容保证。

## 6. 游戏内验证

1. 用当前 `pack` 生成 `.litematic`，在 Minecraft 1.20.1 的 Litematica 中加载并放置。
2. 粘贴时保留方块实体库存，不要忽略木桶库存。
3. 检查 X=0 木桶中的书标题为 `M`，其余木桶中的书为 `D`，作者均为 `CA`。
4. 重新导出时保留全部木桶、原本地 X 坐标和各书的 `Slot`；NBT 字段、木桶实体列表与书物品列表可以重新排序，书页顺序必须保留。
5. 用 `restore` 还原；单文件比较 SHA-256，文件树还应比较路径、节点类型和每个文件的字节。

书页保存 Base64 传输数据，不应通过阅读或编辑界面修改。区域名称可变，但只能有一个区域。改变木桶地址、库存槽位或加入额外书字段会破坏当前格式约束。

离线回归测试覆盖编解码和布局；大文件的游戏内粘贴、再导出、服务器同步仍需实际验证。规划中的协作依赖为 [PCA](https://github.com/plusls/plusls-carpet-addition)、[Carpet Igny Addition](https://github.com/liuyuexiaoyu1/Carpet-Igny-Addition) 和 [Litematica](https://github.com/maruohon/litematica)，目前尚未实现集成。
