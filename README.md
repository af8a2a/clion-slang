# Slang Language Support for CLion

一个面向 C++/CMake + Slang 项目的 CLion 插件。它把 CLion 的原生 LSP 客户端连接到随插件
发布的增强版 `slangd`，同时保留不依赖外部进程的轻量词法高亮。

## 已实现

- `.slang` / `.slangh` 文件类型和图标
- Slang/HLSL 常用关键字、内建类型、属性、语义、预处理器、字符串、数字与注释的词法高亮
- `slangd` Semantic Tokens 语义配色：类型、命名空间、变量、参数、字段、函数、宏等 stock
  类别，以及 class、struct、interface、enum、type parameter、method、decorator 和标准 modifiers
- M3 Slang/HLSL 专属语义类别：绑定 semantic（`POSITION`、`SV_*`）与向量/矩阵 swizzle
  使用独立可配置颜色，并在不支持 M3 的客户端上逐级回退到 M2a/stock 类别
- 行注释、块注释、括号匹配、引号配对，以及包含词法/语义角色的独立配色页
- 连续整行注释和多行块注释折叠；摘要会跳过分隔线并显示首个有意义的注释行
- 基于 JetBrains Native LSP API 的 project-wide `slangd` 客户端
- Diagnostics、Completion、Hover、Signature Help、Definition、References、Semantic Tokens、
  Inlay Hints、Formatting 等标准能力（实际能力取决于所用 `slangd`）
- 结构体字段 Hover 展示字段类型、所属结构体，以及 Slang `sizeof` / `alignof` 语义下的
  natural layout 大小、对齐和偏移；目标相关或无法确定的布局会安全省略
- Ctrl+左键、Ctrl+B 与 Ctrl+悬停的定义导航；插件会直接复用当前 `slangd` 会话，规避
  CLion 2026.1 原生 LSP 在 Ctrl+鼠标路径中不发起 Definition 请求的问题，并兼容部分
  `slangd` 版本将单个定义返回为 `Location` 而非标准数组的响应形态
- 变量“查找用法”与“高级查找用法”：内置 `slangd` 发布标准 `textDocument/references`，
  CLion 自动提供右键菜单与 Alt+F7；当前可靠范围是同一文档内的参数、局部/全局变量和
  结构体字段，基于解析后的声明身份排除同名遮蔽，并支持声明包含/排除
- Windows x64 内置 `slangd`：校验清单与 SHA-256 后安装到 IDE system cache；项目设置可显式
  启用高级外部覆盖，但不会隐式扫描 `SLANGD_PATH`、`VULKAN_SDK` 或 `PATH`
- `slangdconfig.json` 的 `workspace/configuration` 映射与 `${workspaceFolder}` 展开
- `slang-synth://<module>` 内建模块跳转，内容由
  `slangd --print-builtin-module <module>` 生成并缓存

插件只构建由词法 token 组成的扁平 PSI，用来给编辑器动作提供精确范围；它有意不实现
第二套 Slang 语义解析器。语义真值仍来自 Slang 编译器前端，避免随 Slang 演进而失真。

## 兼容性

- 构建基线：CLion 2026.1.3（Build 261.25134）
- 最低版本：CLion 2026.1.3；已按 2026.2 的保留兼容 API 设计，未设置人为 `until-build`
- Plugin Verifier 1.410：CLion 2026.1.5（261.27258.50）与 2026.2.1（262.9437.136）均为 Compatible
- 构建 JDK：25（输出 `--release 21` 字节码）；Gradle Wrapper 使用 Gradle 9.0.0
- M3 发行包目前仅支持 Windows x64，并通过 IDE 官方 OS/架构模块阻止在其他平台安装。
  高级外部 `slangd` 覆盖仅用于受支持平台上的调试、兼容性验证与版本二分。

项目使用 2026.1.4 之前的 Native LSP 类型名作为兼容入口。JetBrains 在 2026.1.4 重命名
了这些 API，但保留了旧类型供已有插件继续运行。

## 安装与使用

1. 从源码构建时，先按 [内置 slangd 运行时说明](docs/bundled-slangd.md) 生成
   `.bundled-runtime/windows-x86_64.zip`。直接安装发行 ZIP 的用户无需此步骤。
2. 构建插件：

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
   `build/distributions/slang-clion-0.4.0-windows-x86_64.zip`。
4. 默认直接使用插件内置 `slangd`。只有调试或兼容性需要时，才在
   **Settings | Languages & Frameworks | Slang** 中启用 **Use external slangd (advanced)**
   并指定 `slangd.exe` 的完整路径。
5. 打开 `.slang` 或 `.slangh` 文件。Language Services 状态栏会显示 `slangd` 状态。

语义颜色可在 **Settings | Editor | Color Scheme | Slang | Semantic** 中单独调整。预览采用
基于 Metallic 实际 shader 用法设计的主题无关校准模板，说明与建议的调整顺序见
[颜色调整模板](docs/color-adjustment-template.md)。服务器不可用时，插件会保留本地 Lexer
提供的基础颜色；使用 stock slangd 时显示其现有十类，增强版 slangd 发布更细分类和
modifiers 后会自动使用对应颜色。

本机开发时可避免下载另一份 CLion SDK：

```powershell
.\gradlew.bat -PlocalIdePath='D:\Path\To\CLion' test buildPlugin
```

## 项目配置

在项目根目录（或源文件的父目录）放置 `slangdconfig.json`。键名与官方 Slang 编辑器扩展
保持一致，例如：

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

`slangd-lsp-smoke.ps1` 会打开仓库内的定义夹具，断言调用点准确返回 `twice` 的声明位置，
并检查同名遮蔽参数和结构体字段的 References、声明包含/排除与精确 UTF-16 范围，同时覆盖
`__getAddress`、编译器生成的 detach 节点和 `$for` 编译期循环；随后
打开语义高亮语料、调用 `textDocument/semanticTokens/full`、解码相对五元组并校验
UTF-16 范围、legend 和 token 合同，而不只是检查服务器是否发布了对应 capability。
默认执行当前官方服务器的 `stock` 合同；增强版服务器可使用：

```powershell
.\scripts\slangd-lsp-smoke.ps1 `
  -Slangd 'D:\path\to\enhanced\slangd.exe' `
  -SemanticContract enhanced `
  -AsJson
.\scripts\slangd-lsp-smoke.ps1 `
  -Slangd 'D:\path\to\enhanced\slangd.exe' `
  -SemanticContract m3 `
  -AsJson
```

JSON 输出会记录解析后的 `slangd` 路径、可执行文件 SHA-256、`serverInfo`、完整 legend 和
解码后的 token，适合作为 CI 差分产物。协议合同与演进规则见
[docs/semantic-token-protocol.md](docs/semantic-token-protocol.md)。

Publisher 侧实现以五层可重放补丁保存在 [`patches/slang/`](patches/slang/README.md)。M2a
增加标准细分类型与 modifiers；M3 追加 `slangSemantic` 和 `slangSwizzle`；字段 Hover 的两层
补丁依次追加 natural layout 信息和 Rider 风格的分层、着色展示；第五层加入文档内语义
References。Initialize 能力协商按 M3 → M2a → stock 逐级选择 legend，构建和三协议验证
命令见该目录说明。

在开发沙箱中启动 CLion：

```powershell
.\gradlew.bat runIde
```

## 当前边界

- 默认不接管 `.hlsl` / `.hlsli`，避免与 CLion 未来或现有 HLSL 支持冲突。
- 不包含完整 PSI，因此本地结构重构等深度 IntelliJ 语言功能由 LSP 能力决定。
- 变量查找用法当前只返回请求文档内的结果；跨 import/include 的工程级索引留待后续阶段。
- 内置 `slangd` 暂只有 Windows x64 变体；也尚未实现 Compile、Reflection 或 Playground
  工具窗口。
- `slang-synth` 首次生成大型内建模块时可能有可感知延迟，后续访问会命中缓存。

架构与后续计划见 [docs/architecture.md](docs/architecture.md)。

## 参考

- [Slang 官方 VS Code 扩展](https://github.com/shader-slang/slang-vscode-extension)
- [Slang 文档](https://docs.shader-slang.org/)
- [JetBrains Native LSP API](https://plugins.jetbrains.com/docs/intellij/language-server-protocol.html)
