# Slang Language Support for CLion

[English](README.md) | 简体中文

一个面向 C++/CMake + Slang 项目的 CLion 插件。它把 CLion 的原生 LSP 客户端连接到自带增强版
`slangd`（Windows x64）或外部公版服务器，同时保留不依赖外部进程的轻量词法高亮。

## 使用展示

以下展示 CLion 中的 Slang 路径追踪和 Bindless 着色器代码。颜色由当前编辑器配色方案决定；语义高亮和参数名内嵌提示
取决于所配置的 `slangd` 与 IDE 设置。

### 路径追踪与参数提示

在路径追踪代码中区分头文件路径、资源类型、函数和注释，并在调用处显示参数名提示。

![Slang 路径追踪代码：头文件路径、StructuredBuffer、函数配色及参数名内嵌提示](docs/screenshots/path-tracing.png)

### Bindless 顶点与片元着色器

展示 Shader 入口属性、HLSL 语义和嵌套泛型纹理句柄。

![Bindless 顶点与片元着色器：Shader 属性、HLSL 语义和 DescriptorHandle 纹理类型](docs/screenshots/bindless-shaders.png)

### 缓冲区操作与原子操作

展示计算着色器中的资源句柄、字节寻址缓冲区读写，以及带参数名提示的原子操作。

![计算着色器：RWByteAddressBuffer 句柄、缓冲区读写与原子操作参数提示](docs/screenshots/bindless-buffers.png)

## 功能

- [自带版／外部公版 slangd 选择](docs/bundled-slangd.md)，应用后自动重启语言服务
- 悬停签名使用当前 Slang 配色进行语法高亮（兼容官方 slangd）
- [类型别名悬停详情](docs/type-hover.md)：展开向量/矩阵类型，展示元素类型、分量数或行列数，
  区分内建类型与用户别名；自带版已包含补丁 0006
- [字段悬停详情](docs/field-hover.md)：按 Rider 样式显示字段声明、所属 struct、大小/对齐/偏移及简短文件链接；
  自带版已包含补丁 0008，支持泛型实例和字段访问处
- [Struct 悬停详情](docs/struct-hover.md)：形参类型显示命名空间、自然布局大小/对齐/填充及简短定义链接；
  自带版已包含补丁 0007
- [Slang Rider Light](docs/rider-light-color-scheme.md)：按 Rider 导出的 C++ 配色映射的浅色预设，同时作为
  Light / IntelliJ Light 的 Slang 默认颜色；保留显式自定义颜色和深色主题
- `.slang` / `.slangh` 文件类型和图标
- Slang/HLSL 常用关键字、内建类型、属性、语义、预处理器、字符串、数字与注释的词法高亮
- 通过 `slangd` Semantic Tokens 提供语义配色，包括类型、命名空间、变量、参数、字段、
  函数、宏等 stock 类别，以及 struct、interface、enum、type parameter、method、decorator
  和标准 modifiers
- 行注释、块注释、括号匹配、引号配对，以及包含词法和语义角色的独立配色页
- 基于 JetBrains Native LSP API 的项目级 `slangd` 客户端
- 可选的 [M4b 预处理分支显示](docs/preprocessor-branch-display.md)：非激活代码灰显、活动分支标记、
  分支来源提示；自带版已包含
- 可选的 [M4c 上下文选择器](docs/preprocessor-contexts.md)：发现直接／间接包含入口，可搜索、选择并
  按文件记忆编译上下文；通过编辑器右键或状态栏使用，自带版已包含
- 可选的 [M4e 构建上下文与 Shader Variants](docs/shader-variants.md)：通过清单和 CMake 显式导出，
  按变体切换宏、include 路径与 target/profile；提供 metallic 示例，自带版已包含
- 可选的 [M4d 强制分支预览](docs/branch-preview.md)：右键 **Slang Branch Preview…** 临时定义／取消定义宏，
  叠加于所选上下文或 Variant；**Stop Slang Branch Preview**、关闭文件或切换上下文后清除覆盖。
  不修改源码或配置，仅影响分支显示；自带版已包含
- [结构化缓冲区与泛型实参高亮](docs/structured-buffer-highlighting.md)：独立设置 `StructuredBuffer`／
  `RWStructuredBuffer` 系列与 `HitEntry`、`uint`、嵌套泛型等类型实参的颜色；自带版已包含补丁 0005
- Diagnostics、Completion、Hover、Signature Help、Definition、References、Semantic Tokens、
  Inlay Hints、Formatting 等标准能力，实际能力取决于所用 `slangd`
- 通过 Ctrl+左键、Ctrl+B 与 Ctrl+悬停进行定义导航。插件会直接复用当前 `slangd` 会话，
  规避 CLion 2026.1 原生 LSP 在 Ctrl+鼠标路径中不发起 Definition 请求的问题，并兼容
  部分服务器将单个定义返回为 `Location` 而非标准数组的响应形态
- 外部 `slangd` 支持项目手动路径，或按 `SLANGD_PATH`、`VULKAN_SDK`、`PATH` 自动查找
- `slangdconfig.json` 的 `workspace/configuration` 映射与 `${workspaceFolder}` 展开
- `slang-synth://<module>` 内建模块跳转，内容由
  `slangd --print-builtin-module <module>` 生成并缓存

插件只构建由词法 token 组成的扁平 PSI，用来给编辑器动作提供精确范围；它有意不实现
第二套 Slang 语义解析器。语义真值仍来自 Slang 编译器前端，避免随 Slang 演进而失真。

## 兼容性

- 构建基线：CLion 2026.1.3（Build 261.25134）
- 最低版本：CLion 2026.1.3；已按 2026.2 的保留兼容 API 设计，未设置人为 `until-build`
- Plugin Verifier 1.410：CLion 2026.1.5（261.27258.50）与 2026.2.1（262.9437.136）均为
  Compatible
- 构建 JDK：25（输出 `--release 21` 字节码）；Gradle Wrapper 使用 Gradle 9.0.0
- Windows x64 自带增强版 `slangd`；其他平台使用外部服务器

项目使用 2026.1.4 之前的 Native LSP 类型名作为兼容入口。JetBrains 在 2026.1.4 重命名
了这些 API，但保留了旧类型供已有插件继续运行。

## 安装与使用

1. 发布 ZIP 已包含 Windows x64 增强版 slangd。若需匹配项目 SDK 版本，可在 Slang 设置中选择外部公版。
2. 从源码构建时，先[编译并准备自带 slangd](docs/bundled-slangd.md)，再构建插件：

   ```powershell
   .\gradlew.bat clean test buildPlugin
   ```

   如果系统默认 Java 太旧，可直接使用已安装 CLion 的 JBR：

   ```powershell
   .\scripts\build-with-clion-jbr.ps1 -ClionHome 'D:\Path\To\CLion' -Tasks clean,test,buildPlugin
   ```

   `ClionHome` 只用于选择启动 Gradle 的 JBR。只有明确要用本地 IDE 作为编译 SDK 时，才额外
   传入 `-IdeSdkHome 'D:\Path\To\CLion'`；发布构建默认仍锁定 `gradle.properties` 中的
   CLion 2026.1.3 基线。

3. 在 CLion 中打开 **Settings | Plugins | ⚙ | Install Plugin from Disk...**，选择
   `build/distributions/` 下生成的 ZIP。
4. 在 **Settings | Languages & Frameworks | Slang** 中选择 **Bundled enhanced slangd (Windows x64)**
   或 **External / official slangd**；下方显示实际路径，应用后自动重启语言服务。
   升级时已有手动路径继续使用外部版，Windows x64 上原有自动查找配置默认迁移至自带版。
5. 打开 `.slang` 或 `.slangh` 文件。Language Services 状态栏会显示 `slangd` 状态。

语义颜色可在 **Settings | Editor | Color Scheme | Slang | Semantic** 中单独调整。服务器
不可用时，插件会保留本地 Lexer 提供的基础颜色；使用 stock `slangd` 时显示其现有十类，
增强版服务器发布更细分类和 modifiers 后会自动使用对应颜色。

使用 Rider 风格的 Slang 配色时，保持 **Settings | Editor | Color Scheme** 为 **Light** 或
**IntelliJ Light**，在 **Slang** 子页调整即可。只补充 Slang 属性，不改变 C++ 与编辑器颜色。
0.7.2 已取消独立的 **Slang Rider Light** 全局方案，修复启动时父方案解析异常；旧预设用户
请切回 Light。已有自定义方案不会被重写，详见[迁移说明](docs/rider-light-color-scheme.md)。

本机开发时可避免下载另一份 CLion SDK：

```powershell
.\gradlew.bat -PlocalIdePath='D:\Path\To\CLion' test buildPlugin
```

## 项目配置

在项目根目录或源文件的父目录放置 `slangdconfig.json`。键名与官方 Slang 编辑器扩展保持
一致，例如：

```json
{
  "slang.predefinedMacros": ["VULKAN=1", "USE_BINDLESS=1"],
  "slang.additionalSearchPaths": [
    "${workspaceFolder}/Shaders",
    "${workspaceFolder}/Shaders/Lib"
  ],
  "slang.searchInAllWorkspaceDirectories": true,
  "slang.workspaceFlavor": "standard",
  "slang.inlayHints.deducedTypes": true,
  "slang.inlayHints.parameterNames": true
}
```

仓库中的 `slangdconfig.example.json` 可直接复制后修改。

## 验证

```powershell
.\gradlew.bat test
.\gradlew.bat verifyPluginProjectConfiguration
.\gradlew.bat buildPlugin
.\gradlew.bat verifyPlugin
slangc -no-codegen .\src\test\testData\slang\Basic.slang
.\scripts\slangd-lsp-smoke.ps1
```

`slangd-lsp-smoke.ps1` 会打开仓库内的定义夹具并断言调用点准确返回 `twice` 的声明位置，
同时打开语义高亮语料、调用 `textDocument/semanticTokens/full`、解码相对五元组并校验
UTF-16 范围、legend 和 token 合同，而不只是检查服务器是否发布了对应 capability。
默认执行当前官方服务器的 `stock` 合同；增强版服务器可使用：

```powershell
.\scripts\slangd-lsp-smoke.ps1 -SemanticContract enhanced -AsJson
```

JSON 输出会记录解析后的 `slangd` 路径、可执行文件 SHA-256、`serverInfo`、完整 legend 和
解码后的 token，适合作为 CI 差分产物。协议合同与演进规则见
[docs/semantic-token-protocol.md](docs/semantic-token-protocol.md)。

在开发沙箱中启动 CLion：

```powershell
.\gradlew.bat runIde
```

## 当前边界

- 默认不接管 `.hlsl` / `.hlsli`，避免与 CLion 未来或现有 HLSL 支持冲突。
- 不包含完整 PSI，因此本地结构重构等深度 IntelliJ 语言功能由 LSP 能力决定。
- 自带 `slangd` 当前仅支持 Windows x64；尚未实现 Compile、Reflection 或 Playground 工具窗口。
- `slang-synth` 首次生成大型内建模块时可能有可感知延迟，后续访问会命中缓存。

架构与后续计划见 [docs/architecture.md](docs/architecture.md)。

## 参考

- [Slang 官方 VS Code 扩展](https://github.com/shader-slang/slang-vscode-extension)
- [Slang 文档](https://docs.shader-slang.org/)
- [JetBrains Native LSP API](https://plugins.jetbrains.com/docs/intellij/language-server-protocol.html)
