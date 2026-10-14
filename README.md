# CloudArchive

面向 Minecraft 1.20.1、Fabric 和 Yarn 的客户端模组项目。当前可通过离线命令行把文件或文件树转换为 `.litematic` 投影，并完整还原；投影中用木桶和原生成书保存数据。

**代码由 AI 根据 awszqsedxzaw 的需求编写，维护者和提交署名为 awszqsedxzaw。**

## 快速开始

构建与命令行需要 JDK 21，输出 Java 17 字节码。PowerShell：

```powershell
git clone https://github.com/awszqsedxzaw/CloudArchive.git
cd CloudArchive
$env:JAVA_HOME = 'C:\path\to\jdk-21'

# 单文件
.\gradlew.bat runFileCodec "--args=pack 'input.bin' 'out\data.litematic'"
.\gradlew.bat runFileCodec "--args=list 'out\data.litematic'"
.\gradlew.bat runFileCodec "--args=restore 'out\data.litematic' 'out\restored.bin'"

# 整棵目录树，也保留空文件、空目录
.\gradlew.bat runFileCodec "--args=pack 'data' 'out\tree.litematic'"
.\gradlew.bat runFileCodec "--args=restore 'out\tree.litematic' 'out\restored-tree'"

# 选择根目录下的多个路径
.\gradlew.bat runFileCodec "--args=pack-tree 'data' 'out\selected.litematic' 'docs\a.txt' 'images'"
```

还原目标必须不存在；文件树投影必须输出到源目录以外。路径含空格时保持示例中的引号结构。Linux/macOS 使用 `./gradlew`，详见[使用指南](docs/usage.md)。

## 当前能力与限制

- X=0 的木桶保存目录和文件元数据，其余木桶保存纯数据；书只使用 Minecraft 原生字段。
- 支持多文件、文件树、空文件、空目录和整文件去重；采用 4 MiB Zstandard/RAW 块、Base64 书页及 gzip NBT。
- 每个源文件只读取一次，投影只扫描一次，使用固定数据缓冲与磁盘临时文件处理大文件；节点索引和块表仍随规模增长。
- 每页可存 24,573 字节，一本书 100 页，一个木桶 27 本书；元数据最多占一个木桶。
- 只支持当前 `CAMD v2` 和 1.20.1 投影格式，不兼容旧格式；拒绝符号链接、特殊文件和不能安全还原的名称。
- 当前归档是不可变快照，尚无修改命令或游戏内操作。PCA、Carpet Igny Addition 等服务端协作尚未接入；游戏内粘贴和再导出流程见使用指南。
- `list` 会解压并校验全部数据和文件哈希；打包、还原和列出均需临时磁盘空间。

## 构建与测试

```powershell
.\gradlew.bat build
# 可选的大文件压力测试：64 MiB Java 堆，需数 GiB 可用磁盘
.\gradlew.bat verifyLargeFileCodec
```

`build` 已包含 128 MiB 堆下的编解码回归测试。模组产物为 `build\libs\cloudarchive-0.1.0.jar`。依赖版本、开发环境和测试覆盖见开发者文档。

## 文档

| 文档 | 内容 |
| --- | --- |
| [使用指南](docs/usage.md) | 完整命令、路径规则、容量、错误处理和游戏验证 |
| [开发者文档](docs/developer-guide.md) | 源码入口、单次读取、去重回滚、流式扫描、还原、资源管理和扩展 |
| [归档格式](docs/archive-format.md) | Litematica/NBT 布局、书页编码、CAMD 字节字段、多文件和校验规则 |
| [基准记录](docs/benchmarks.md) | 当前版本对照、大文件验证、历史实验及复现命令 |

## 许可证

采用 [MIT License](LICENSE)，版权所有者为 awszqsedxzaw；依赖许可见[第三方许可说明](THIRD_PARTY_NOTICES.md)。

投影结构参考 [Litematica](https://github.com/maruohon/litematica)，构建结构参考 [fabric-mod-template](https://github.com/Fallen-Breath/fabric-mod-template)，Minecraft 源码仅作为只读格式参考。
