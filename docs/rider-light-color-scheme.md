# Slang Rider Light

## 使用 / Usage

根据用户提供的 `Rider_Light.icls`（Rider 2026.1.2.0.0）中的 C++ 专属属性及语言默认属性映射
的 Slang 默认预设，替代最初的截图估色，覆盖词法、语义、结构化缓冲区／泛型和预处理分支显示。
它是一套**配色**，不是新的语义分类器，也不是完整复制 Rider 的 UI 主题。

- 安装插件后，在 **Settings | Editor | Color Scheme** 选择 **Slang Rider Light**。
- CLion 内置 **Light**（包括使用该编辑器方案的 Islands Light）和 **IntelliJ Light** 也默认
  获得相同的 Slang 专属颜色；已有的显式 Slang 自定义值仍然优先。
- 在 **Editor | Color Scheme | Slang** 点击预览中的元素即可调整对应颜色。需要修改内置
  方案时可先 Duplicate，再编辑副本；勾选 **Inherit values from** 可恢复该项的原有继承链。
- 插件不会自动切换方案或重写已保存的配置；自动补充的默认值只包含 `SLANG.*`，不覆盖字体、
  字号、全局背景、选中背景或 C++ 配色。**Slang Rider Light** 从 CLion 的 **Light** 继承
  非 Slang 设置，只覆盖 `SLANG.*`；C++ 的语言默认值、注释、字符串和编辑器背景继续来自 Light。
  **Darcula / Dark / Islands Dark / High contrast** 保留原有主题继承。旧 **Default** 和其他
  第三方方案不主动注入这套颜色；需要时显式选择 **Slang Rider Light**。

Select **Slang Rider Light** under **Settings | Editor | Color Scheme**. The built-in **Light** and
**IntelliJ Light** schemes also receive these Slang-only defaults. Explicit user overrides win;
duplicate a scheme to customize it under **Color Scheme | Slang**, or enable **Inherit values from**
for a setting to restore its fallback. Installation never switches the active scheme or rewrites
saved preferences. Additive defaults contain no fonts, global editor colors, other-language keys,
or project settings. The standalone preset inherits non-Slang settings from CLion's **Light**,
not the legacy platform **Default**. C++ and editor colors keep their Light defaults. Dark/high-contrast
schemes and unrelated third-party schemes retain their existing defaults.

### C++ isolation / C++ 配色隔离（0.7.1）

0.7.0 的可选预设虽然只定义了 Slang 属性，但父方案是通用 `Default`。从 CLion 的 `Light`
切换过去时，C++ 和全局编辑器样式也随父方案改变。0.7.1 将父方案修正为 **Light**，不将 Rider
或用户导出文件中的全局／C++ 属性复制到运行时预设，Slang 的 Rider 色值保持不变。

用户提供的 `Light.icls`（CLion 2026.2.2.0.0）用于核对非 Slang 属性；其中已经包含 Slang
配色及少量自定义值，不作为新的 Slang 默认值导入，也不会被修改。非 Slang 样本见
[`ClionLightReference.xml`](../src/test/resources/colorSchemes/ClionLightReference.xml)。

- **保留当前 C++ 自定义颜色的首选方式**：继续使用 **Light** 或已有的 Light 副本。
  插件会给内置 Light 补充 Slang 默认值，已有的显式自定义仍优先，不必切换整套方案。
- 安装修正版后也可选择 **Slang Rider Light**，它保留 CLion 内置 Light 的非 Slang 配色。
  若从其他自定义方案切换过来，那套方案自己的 C++／全局覆盖并不会自动转移。
- 如果旧保存副本仍继承 `Default`，切回 **Light**，或从更新后的内置预设新建副本。
  插件不会迁移、删除或重写已有副本来强制恢复外观。

In 0.7.0, selecting the preset also switched the parent from CLion Light to the platform's legacy
Default. Version 0.7.1 fixes the parent to Light while keeping only Slang-specific overrides.
To retain an existing customized C++ appearance, keep using Light or its existing customized copy;
the plugin already supplies Slang defaults on Light. A new standalone preset inherits the built-in
Light defaults, not arbitrary custom overrides from the previously selected scheme. Old saved copies
may retain their old parent: switch back to Light or create a fresh copy of the updated preset.
No automatic migration of saved schemes is performed.

## Palette

The source is the user-supplied `Rider_Light.icls`, scheme **Rider Light**, exported from
**Rider 2026.1.2.0.0**, received on 2026-09-08. Its C++ / ReSharper attributes take precedence over
generic language defaults. This replaces screenshot estimates with exported RGB and font styles.
Short hexadecimal values are left-padded, not reinterpreted: `f54d6` → `#0F54D6`, `855f` → `#00855F`,
`93a1` → `#0093A1`, `0` → `#000000`.

| Role / 类别 | Foreground | Style |
| --- | --- | --- |
| Keywords, preprocessor directives, macros, booleans / 关键字、指令、宏 | `#0F54D6` | Plain |
| General/class/interface/built-in types, namespaces, generic arguments / 一般类型、类、接口、内建类型、泛型 | `#6B2FBA` | Plain |
| Structs, enums, structured buffers, fields, enum members / 结构体、枚举、缓冲区、字段、枚举成员 | `#300073` | Plain |
| Functions, methods, intrinsics / 函数、方法、内建函数 | `#00855F` | Plain |
| Line, block and documentation comments / 各类注释 | `#248700` | Italic |
| Numbers / 数字 | `#AB2F6B` | Plain |
| Strings, include paths / 字符串、头文件路径 | `#8C6C41` | Plain |
| Attributes, decorators, HLSL semantics / 属性、修饰器、HLSL 语义 | `#6B2FBA` | Plain |
| Locals, globals, parameters, identifiers, punctuation / 变量、参数、标识符、标点 | `#383838` | Plain |
| Read-only variables / 只读变量、常量 | `#0093A1` | Bold (`DEFAULT_CONSTANT`) |
| Other built-in symbols / 其他内建符号 | `#000000` | Plain (`DEFAULT_PREDEFINED_SYMBOL`) |
| Inactive code / 非激活分支 | `#949494` | No background |
| Active branch / 激活分支 | `#0F54D6` effect | Underline only; no foreground/background override |
| Branch source labels / 分支来源提示 | `#949494` | `#EBEBEB` label background |

For example, `StructuredBuffer<HitEntry>` uses deep purple for the buffer and violet for the checked
generic argument. `#include` stays blue while `"Common.slang"` is warm brown. Plain lexical built-ins
and server-classified built-ins have the same violet color. Definitions and uses share the same
role palette; fields are distinct from local variables and parameters. Invalid characters continue
to inherit the IDE's error indication rather than being recolored as ordinary text.

### Mapping decisions / 映射边界

- Fields (including static/read-only fields) use `ReSharper.CPP_STRUCT_FIELD_IDENTIFIER`, **not** the
  generic `DEFAULT_INSTANCE_FIELD` cyan. Structs and enums use `ReSharper.STRUCT_IDENTIFIER` /
  `ReSharper.ENUM_IDENTIFIER`; class/interface/general type roles use `DEFAULT_CLASS_NAME` /
  `DEFAULT_INTERFACE_NAME` / `DEFAULT_CLASS_REFERENCE`. Stock slangd's undifferentiated `type` tokens
  thus remain violet; this palette cannot supply missing semantic categories.
- Local/global variables use `ReSharper.CPP_LOCAL_VARIABLE_IDENTIFIER` /
  `ReSharper.CPP_GLOBAL_VARIABLE_IDENTIFIER`. The export has no dedicated C++ read-only-variable
  attribute, so that Slang category uses `DEFAULT_CONSTANT` (cyan, bold). Enum members instead use
  `ReSharper.CPP_ENUM_ENUMERATOR_IDENTIFIER` (deep purple, plain).
- Slang-only categories are adaptations: structured buffers use the struct color; built-in types
  use the class-reference color; generic arguments use `ReSharper.TYPE_PARAMETER_IDENTIFIER`;
  attributes/decorators/HLSL semantics use `DEFAULT_METADATA`; intrinsics use `DEFAULT_FUNCTION_CALL`.
  Include paths use `DEFAULT_STRING`, not Rider's clickable-path underline styling.
- Inactive code uses `ReSharper.INACTIVE_PREPROCESSOR_BRANCH`; source labels borrow
  `INLINE_PARAMETER_HINT`. The active-branch underline remains plugin-specific and has no foreground
  or background. The unrelated `ReSharper.C_PREPROCESSOR_INACTIVE_BRANCH_V2` effect is not imported.
- Font family/size/line spacing, caret-row/selection colors and other-language settings from the
  export are deliberately not imported. Only syntax font styles such as comment italics and bold
  constants are mapped. This does not install a Rider runtime dependency or modify the supplied file.

## Classification boundaries

This preset uses the existing `SLANG.*` keys; no server changes or extra LSP requests are needed.
Semantic roles still require semantic highlighting and a running `slangd`. Stock server granularity
is unchanged: a function reported as `type`, for example, cannot be corrected by a color scheme.
Dedicated structured-buffer / generic-argument roles require [patch 0005](structured-buffer-highlighting.md).
Branch overlays still require the corresponding [M4 capabilities](preprocessor-branch-display.md).

With slangd unavailable, lexical keywords, built-in/resource names, comments, strings and numeric
tokens still use the preset; user-defined fields, functions and type arguments are not guessed.
Except for `#include`, the current lexer treats a preprocessor line as a directive token. Macro-body
numbers therefore do not acquire the numeric accent merely by installing this scheme. IDE inspection
underlines, usage highlights, inlay hints and inactive-branch overlays can further affect appearance.

## Implementation and validation

[`SlangRiderLight.xml`](../src/main/resources/colorSchemes/SlangRiderLight.xml) is the single palette
source. It is registered both as a selectable `bundledColorScheme` (path without `.xml`) and as
`additionalTextAttributes` for the two supported light scheme names. The latter loader reads only
the `<attributes>` child. **Keep only `SLANG.*` keys in this resource**, with no global colors or font
options. Do not inject into `Default`: it is also the parent of unrelated third-party dark schemes.
The standalone scheme must inherit **Light**, which in turn inherits platform Default; skipping
Light loses CLion's non-Slang defaults and its scheme-specific language contributions.
Do not add startup migration code that rewrites the user's saved scheme.

This uses the platform's documented [scheme-specific defaults](https://plugins.jetbrains.com/docs/intellij/color-scheme-management.html)
and [bundled scheme registration](https://plugins.jetbrains.com/docs/intellij/creating-theme-project.html#bundling-color-schemes).
The existing Java fallback keys remain unchanged outside the supplied preset.

A [minimal reference subset](../src/test/resources/colorSchemes/RiderLightReference.xml) retains only
the 35 source attributes used by the mapping, including their original short hex values. It is test
data only, not shipped in the plugin. Original export SHA-256:
`3e66d9439c6c66f9fd05aced5eb2165c32fae756fd2d7904a8ac5811b454aae5`.

`SlangRiderLightColorSchemeTest` checks resource registration, exhaustive settings-key coverage,
palette values through the actual platform attribute reader, comment italics, branch effects,
user override / fallback behavior, dark isolation and invalid-character inheritance. It also checks
every mapped Slang role against that portable reference using the platform attribute reader,
including foreground/background, font styles and effects, without accessing a user's home directory.
The existing
settings preview includes screenshot-inspired `HitEntry` fields, `isValid`, `luminance`, buffer
declarations, include paths, and inactive/active branches, without needing a running server.

`SlangRiderLightColorSchemePlatformTest` separately checks full scheme loading and extension
registration using an IDE application. This test needs the JetBrains test-framework dependency;
it is not part of the standalone attribute-reader test run.
The portable CLion reference tests ensure non-Slang foregrounds, font styles and editor colors
remain unchanged through the declared parent and user overrides. The IDE-fixture test additionally
compares all non-Slang keys and colors in the installed Light inheritance chain against the registered
preset, including CLion's language-specific contributions.

Manual acceptance after installing the ZIP:

1. Select **Slang Rider Light**, inspect **Color Scheme | Slang**, then open
   [`StructuredBufferHighlighting.slang`](../src/test/testData/slang/StructuredBufferHighlighting.slang).
2. With the supported slangd, verify buffer / argument colors, purple fields, teal calls and warm
   include paths. Disable slangd and check lexical fallback separately.
3. Duplicate the preset, customize one Slang color, restart the IDE and confirm the override remains.
4. Switch between **Light**, **IntelliJ Light**, **Darcula** and **High contrast**; check that dark
   schemes have not received light-only colors and that usage/selection backgrounds remain usable.

Automated attribute-reader tests do not replace this GUI acceptance or claim pixel-identical Rider
rendering. The plugin's declared CLion 2026.1.3 / Java 21 baseline is unchanged.
