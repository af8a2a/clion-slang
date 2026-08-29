# Slang Language Support for CLion

一个面向 C++/CMake + Slang 项目的 CLion 插件。它把 CLion 的原生 LSP 客户端连接到官方
`slangd`，同时保留不依赖外部进程的轻量词法高亮。

## 已实现

- `.slang` / `.slangh` 文件类型和图标
- Slang/HLSL 常用关键字、内建类型、属性、语义、预处理器、字符串、数字与注释的词法高亮
- 行注释、块注释、括号匹配、引号配对和独立配色页
- 基于 JetBrains Native LSP API 的 project-wide `slangd` 客户端
- Diagnostics、Completion、Hover、Signature Help、Definition、References、Semantic Tokens、
  Inlay Hints、Formatting 等标准能力（实际能力取决于所用 `slangd`）
- Ctrl+左键、Ctrl+B 与 Ctrl+悬停的定义导航；插件会直接复用当前 `slangd` 会话，规避
  CLion 2026.1 原生 LSP 在 Ctrl+鼠标路径中不发起 Definition 请求的问题，并兼容部分
  `slangd` 版本将单个定义返回为 `Location` 而非标准数组的响应形态
- `slangd` 查找顺序：项目设置的显式路径、`SLANGD_PATH`、`VULKAN_SDK`、`PATH`
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
- 运行时需要可用的 `slangd`。插件当前不捆绑 Slang 二进制。

项目使用 2026.1.4 之前的 Native LSP 类型名作为兼容入口。JetBrains 在 2026.1.4 重命名
了这些 API，但保留了旧类型供已有插件继续运行。

## 安装与使用

1. 准备与项目 Slang 编译器版本一致的 `slangd`。Slang SDK 或 Vulkan SDK 通常会提供它。
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
   `build/distributions/` 下生成的 ZIP。
4. 在 **Settings | Languages & Frameworks | Slang** 中启用自动发现，或指定
   `slangd` / `slangd.exe` 的完整路径。
5. 打开 `.slang` 或 `.slangh` 文件。Language Services 状态栏会显示 `slangd` 状态。

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

`slangd-lsp-smoke.ps1` 会打开仓库内的定义夹具并断言调用点准确返回 `twice` 的声明位置，
而不只是检查服务器是否发布了 Definition capability。

在开发沙箱中启动 CLion：

```powershell
.\gradlew.bat runIde
```

## 当前边界

- 默认不接管 `.hlsl` / `.hlsli`，避免与 CLion 未来或现有 HLSL 支持冲突。
- 不包含完整 PSI，因此本地结构重构等深度 IntelliJ 语言功能由 LSP 能力决定。
- 不捆绑各平台 `slangd`，也尚未实现 Compile、Reflection 或 Playground 工具窗口。
- `slang-synth` 首次生成大型内建模块时可能有可感知延迟，后续访问会命中缓存。

架构与后续计划见 [docs/architecture.md](docs/architecture.md)。

## 参考

- [Slang 官方 VS Code 扩展](https://github.com/shader-slang/slang-vscode-extension)
- [Slang 文档](https://docs.shader-slang.org/)
- [JetBrains Native LSP API](https://plugins.jetbrains.com/docs/intellij/language-server-protocol.html)
