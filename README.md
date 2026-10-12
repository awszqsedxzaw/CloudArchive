# CloudArchive

面向 Minecraft 1.20.1、Fabric 和 Yarn 的客户端模组项目，目标是利用服务器世界保存文件。

目前已实现离线命令行转换：任意文件 → 直接装书的木桶 → `.litematic` 投影文件，并可还原为原文件。大文件自动拆成多本书、多个木桶，不使用潜影盒。模组仅在客户端加载；当前转换通过 Gradle 命令运行，尚未接入游戏内操作。

**本项目代码由 AI 根据 awszqsedxzaw 的需求编写。项目维护者及提交署名为 awszqsedxzaw。**

## 快速使用

安装 JDK 21，克隆仓库，并在项目目录打开 PowerShell。将下面的示例 JDK 路径替换为自己的安装位置；若已配置 `JAVA_HOME`，无需重复设置。

```powershell
git clone https://github.com/awszqsedxzaw/CloudArchive.git
cd CloudArchive
$env:JAVA_HOME = 'C:\path\to\jdk-21'
```

将整个 `--args` 参数用双引号包住，其中的文件路径用单引号包住；此写法支持带空格的路径。输入、输出均支持相对路径和绝对路径。

将文件打包成投影：

```powershell
.\gradlew.bat runFileCodec "--args=pack 'data.bin' 'out\data.litematic'"
```

从投影还原：

```powershell
.\gradlew.bat runFileCodec "--args=restore 'out\data.litematic' 'out\restored.bin'"
```

用本项目的 `.gitignore` 做一个小文件测试：

```powershell
.\gradlew.bat runFileCodec "--args=pack '.gitignore' 'out\gitignore.litematic'"
.\gradlew.bat runFileCodec "--args=restore 'out\gitignore.litematic' 'out\gitignore.restored'"
(Get-FileHash .\.gitignore -Algorithm SHA256).Hash -eq (Get-FileHash .\out\gitignore.restored -Algorithm SHA256).Hash
```

最后一行应返回 `True`。还原操作本身也会检查文件长度和 SHA-256。

Linux 和 macOS 使用 `./gradlew`；例如：

```sh
./gradlew runFileCodec --args="pack 'data.bin' 'out/data.litematic'"
./gradlew runFileCodec --args="restore 'out/data.litematic' 'out/restored.bin'"
```

- 输入必须是一个文件；目录需要先自行打包。
- 输出目录不存在时自动创建；已有输出文件不会被覆盖，重复测试请换一个输出名。
- 正确后缀是 **`.litematic`**。打包时会纠正 `.litematica`，其他缺少正确后缀的输出名会追加 `.litematic`。
- 还原时显式指定输出文件名；原文件名保存在归档元数据中。
- **不兼容旧归档**：只识别 `tag.CloudArchive` 中的 `file-archive-v2`，不读取 `file-archive-v1`、`newproject`，也不递归读取潜影盒中的书。旧投影需要从原文件重新生成。

## 容量与分桶

NBT 字符串通过 Java `DataOutput.writeUTF` 写入，长度上限是 **65,535 个 modified UTF-8 字节**，并非任意 Unicode 字符都能存 65,535 个。书页还可能受到 **32,767 个字符的序列化 JSON 长度限制**。本项目在 1.20.1 中也采用较小的容量：无填充的 URL-safe Base64 全部为 ASCII，为 JSON 字符串的两个引号留出空间，再取完整四字符边界，每页最多 **32,764 个 Base64 字符**，对应 **24,573 字节原文件数据**。实际书页仍直接存 Base64，不添加 JSON 包装。

| 单位 | 上限 | 可存原文件字节数 |
| --- | --- | --- |
| 一页 | 32,764 个 Base64 字符 | 24,573 |
| 一本书 | 100 页 | 2,457,300，约 2.34 MiB |
| 一个木桶 | 27 本书 | 66,347,100，约 63.27 MiB |

书本数为 `max(1, ceil(文件大小 / 2,457,300))`，木桶数为 `ceil(书本数 / 27)`。空文件也生成一本书和一个木桶。

例如 **66,347,131 字节**的文件需要 **28 本书、2 个木桶**：第一个木桶放满 27 本书，第二个木桶的第一本书存最后 31 字节。木桶在单个 `FileStorage` 区域中沿 X 轴连续排列。

每本书具有从 0 开始的全局 `index`。打包时，书所在木桶编号为 `index / 27`，槽位为 `index % 27`。还原按编号定位原文件偏移，所以调整槽位、移动木桶、改变投影中的木桶顺序都不影响还原，但必须把同一文件的全部书放在同一个投影中。

书本编号使用有符号 32 位整数；实际可处理大小还受磁盘空间、游戏内存和投影尺寸限制。

## 存储格式

输出使用 gzip 压缩的 NBT，投影版本为 `Version=6`、`SubVersion=1`，Minecraft 1.20.1 的 `MinecraftDataVersion=3465`。

区域调色板为 `[minecraft:air, minecraft:barrel]`，木桶使用索引 1，`BlockStates` 使用每方块两位的连续打包格式。索引 0 必须保留为空气，与 Litematica 加载调色板的实现一致。

当前书本物品是 `minecraft:written_book`，书直接放在木桶的 `Items` 中。书的 NBT 为：

```text
tag
├─ pages: Base64 页列表
└─ CloudArchive
   ├─ kind: "file-archive-v2"
   ├─ index: 全局书本编号
   └─ header: 仅编号 0 的书拥有
      ├─ file_name: 原文件名
      ├─ file_size: 原文件字节数
      ├─ sha256: 原文件 SHA-256
      └─ book_count: 总书本数
```

文件内容只存一份，不重复保存每本书的完整文件头，也不添加每页序号、JSON 包装或独立压缩层。Base64 在压缩前约增加三分之一大小，投影外层 gzip 会压缩编码数据；最终投影大小取决于文件内容。

归档标识为 `file-archive-v2`，还原偏移为 `index × 2,457,300`。旧版 `file-archive-v1` 每本书容量不同，读取时明确拒绝，不能混用。

打包先读取原文件计算 SHA-256，再按书写出，并在第二次读取时检查原文件是否变化。还原按书扫描 NBT，及时释放已读书本，把解码数据写到临时文件的对应偏移，再核验总长度和 SHA-256。不会把完整文件或所有书页同时留在内存中；书本索引表和木桶布局仍随规模增长。

书本缺失、重复、数据损坏或校验不匹配时，还原失败并删除临时文件，不产生部分输出。临时文件位于目标目录，操作期间需要足够的可用磁盘空间。

## 游戏内验证

请使用 **Minecraft 1.20.1** 世界验证。本工具生成和读取的是 1.20.1 的物品 NBT，不支持 1.20.5 及以后版本的物品组件格式；在新版中加载旧版投影会经过额外的数据升级和组件校验。

1. 使用 `pack` 生成新的 `.litematic`，在 Litematica 中加载并创建放置。
2. 单人世界粘贴库存时，设置 `pasteUsingCommandsInSp=false`、`pasteIgnoreInventories=false`，再执行粘贴。
3. 检查木桶数量及库存。书内应出现 `CloudArchive` 字段。
4. 选中包含所有归档书的木桶，重新保存为一个 `.litematic` 文件。
5. 使用 `restore` 还原新保存的投影，并比较 SHA-256。

普通成书校验还有每页 32,767 字符的限制，书内数据也不是书本界面所需的 JSON 文本，因此不应通过普通阅读界面查看这些页。NBT 可存储的上限与阅读、编辑和网络传输限制不同。

已用 Minecraft **1.21.8** 的原版 DataFixer 和 `WrittenBookContent` 编码器独立验证跨版本丢页原因：序列化 JSON 超过 **32,767** 个字符时，书页列表可能变为空列表，而标题、作者和归档头仍保留。当前满页 **32,764** 个 Base64 字符加两个 JSON 引号后可以通过此项校验；这一检查不代表新版游戏内粘贴和再导出已验证。

已经粘贴为空页的书无法仅凭文件头恢复原文件，需要从原文件重新生成投影。较小的容量同样适用于 1.20.1，但不等于支持新版投影的还原；读取 1.20.5 及以后版本重新导出的投影仍需要适配物品组件。

当前已验证离线多木桶格式和完整还原；**大文件在游戏中的粘贴、再导出，以及服务器库存同步仍需实际验证**。Litematica 在游戏中会把整个投影加载到内存，CLI 的低内存验证不能代表游戏内内存占用。

后续服务器方案拟使用 [PCA](https://github.com/plusls/plusls-carpet-addition) 同步容器数据、[Carpet Igny Addition](https://github.com/liuyuexiaoyu1/Carpet-Igny-Addition) 处理大数据相关的 chunk ban 限制，并结合 [Litematica](https://github.com/maruohon/litematica)。这些集成尚未实现，离线命令行转换不依赖安装它们。

## 构建与测试

| 组件 | 版本 |
| --- | --- |
| 开发 JDK | 21，通过 `JAVA_HOME` 配置 |
| 输出 Java 字节码 | 17 |
| Gradle | 8.5 |
| Fabric Loom | 1.2.1 |
| Minecraft | 1.20.1 |
| Yarn | 1.20.1+build.1 |
| Fabric Loader | 0.14.21 |
| Fabric API | 0.83.0+1.20.1 |

依赖固定在适用于当前环境的早期版本：Gradle 8.5 是首个支持在 Java 21 上运行的版本；Loom 1.2 是 Minecraft 1.20 的推荐基线；Yarn build.1 和 Fabric API 0.83.0 是对应 1.20.1 的首批版本，后者要求 Loader 至少为 0.14.21。

```powershell
.\gradlew.bat build
.\gradlew.bat verifyFileCodec
.\gradlew.bat genSources
.\gradlew.bat runClient
```

`build` 已包含 `verifyFileCodec`，无需再重复运行测试。构建产物是 `build\libs\cloudarchive-0.1.0.jar`，游戏开发目录为 `run\client`。命令行转换无需启动 Minecraft，也无需将 mod jar 放入现有游戏实例。

自动验证使用 **128 MiB Java 堆**，覆盖空文件、满页、满书、跨页、跨书、中文文件名、书页 JSON 长度、超容量书页拒绝、旧字段和旧归档版本拒绝、书本乱序、缺失、重复、SHA-256 损坏、拒绝覆盖，以及 33 个木桶时的方块位数组边界。另使用 **66,347,131 字节伪随机二进制文件**完整验证 28 本书、2 个木桶的打包与还原，并逐字节比较结果。

在 IntelliJ IDEA 中导入 Gradle 项目，将 Gradle JVM 设置为 JDK 21。命令行通过 `JAVA_HOME` 选择 JDK；仓库不包含机器专用的 JDK 路径。

包名为 `awszqsedxzaw.cloudarchive`，项目名为 `CloudArchive`，模组 ID 为 `cloudarchive`。

## 源码参考

- Minecraft 1.20.1 的 Yarn 映射源码，用于核对 NBT 和书本实现。项目通过 Gradle 获取依赖，仓库不包含 Minecraft 源码。
- 投影格式实现参考：[Litematica](https://github.com/maruohon/litematica)，包括调色板、位数组、区域尺寸和方块实体的序列化。
- 构建结构参考：[fabric-mod-template](https://github.com/Fallen-Breath/fabric-mod-template)。

## 许可证

本项目代码和文档采用 [MIT License](LICENSE)，版权所有者为 awszqsedxzaw，允许使用、修改和分发，并要求保留许可证和版权声明。

Gradle Wrapper 保留 Apache 2.0 许可证，其他依赖遵循各自的许可条款，详见 [第三方许可说明](THIRD_PARTY_NOTICES.md)。
