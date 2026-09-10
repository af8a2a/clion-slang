# Slang Rider Light

## 使用 / Usage（0.7.2）

这是一套只应用于 Slang 的 Rider 风格配色，不再注册独立的全局编辑器方案。

1. 安装修复版并重启 CLion，在 **Settings | Editor | Color Scheme** 选择 **Light** 或
   **IntelliJ Light**。无需选择旧的 Slang Rider Light。
2. 在 **Color Scheme | Slang** 中调整颜色。需要保留 C++ 自定义时，继续使用已有的 Light
   或其副本；插件只补充 `SLANG.*` 默认值，已有显式自定义优先。
3. 旧 Slang Rider Light 副本不会被自动删除或改写。若它仍被选中，请手动切回 Light。
   如果启动错误导致无法进入设置，可先禁用旧插件，进入设置切回 Light，再安装修复版。

Keep **Light** or **IntelliJ Light** selected, and customize **Color Scheme | Slang**. The plugin
adds only Slang-specific attributes; it never changes the current global scheme, C++ colors, fonts
or selection backgrounds. Existing explicit overrides are preserved. Old standalone preset users
should switch back to Light; saved copies are not automatically deleted or migrated.

## Startup failure and C++ isolation

0.7.0 的独立方案继承通用 Default，导致从 CLion Light 切换时 C++ 外观改变。0.7.1 改为继承
Light，但实际启动报出了 `PluginException: Light`，根因在 `AbstractColorsScheme.resolveParent`：
解析出的父方案不存在或不是只读方案时，该方法会抛出 `InvalidDataException`。

**0.7.2 removes the bundled scheme entirely.** The XML is now a plain attribute fragment with no
name, version or parent. Only `additionalTextAttributes` registrations for Light / IntelliJ Light
remain. This eliminates the plugin's bundled-scheme parent resolution instead of relying on startup
ordering or reverting to the wrong Default colors. Dark and unrelated schemes keep their fallbacks.

The supplied `Light.icls` is reference data, not imported into runtime settings; its existing Slang
customizations and other-language settings are not overwritten. The Rider-derived Slang RGB values
remain unchanged. The original export files are not modified.

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

[`SlangRiderLight.xml`](../src/main/resources/colorSchemes/SlangRiderLight.xml) is the single runtime
palette source: `<list><attributes>...</attributes></list>`. The platform additive loader reads the
attributes child. Do not add `bundledColorScheme`, `parent_scheme`, global colors or non-`SLANG.*`
keys. Do not inject into Default, change the active scheme, or rewrite saved user schemes.

The existing Java fallback keys remain unchanged. There are no extra LSP requests or server changes.

Regression coverage:

- Registration tests forbid a bundled scheme and a parent/name on the fragment, catching the startup
  failure path that 0.7.1's synthetic parent-inheritance test did not exercise.
- Actual platform attribute-reader tests check every mapped Slang role against the 35-attribute
  [Rider reference](../src/test/resources/colorSchemes/RiderLightReference.xml), including font styles.
- The [CLion reference](../src/test/resources/colorSchemes/ClionLightReference.xml) checks 15 non-Slang
  attributes and four editor colors remain unchanged after additive application, including overrides.
- IDE-fixture tests initialize the actual scheme manager and compare non-Slang attributes/colors
  before and after loading the fragment. They require the JetBrains test-framework dependency;
  standalone unit tests do not replace startup/GUI acceptance.

Manual acceptance after installing the ZIP:

1. Restart CLion and confirm no new `PluginException: Light`; select Light or IntelliJ Light.
2. Open C++ and Slang files side by side. C++ should retain its original scheme; Slang should use
   the Rider-derived colors. Confirm selection backgrounds and custom C++ overrides are unchanged.
3. Adjust one Slang color, restart, and confirm the explicit override survives. Switch to Darcula
   and High contrast to confirm no light-only palette is forced onto them.
4. Use a supported slangd to inspect the [buffer fixture](../src/test/testData/slang/StructuredBufferHighlighting.slang),
   then disable slangd to check lexical fallback independently.

The declared CLion 2026.1.3 / Java 21 baseline is unchanged. No GUI acceptance is claimed solely
from successful unit tests or packaging; the previous startup failure demonstrated that distinction.
