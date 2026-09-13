# Struct hover

Bundled slangd includes `0007-struct-hover.patch`, applied after patches 0001–0006,
which adds Rider-inspired struct details to standard `textDocument/hover`. Hover the
**type name** in a parameter such as `UnifiedRT::Hit hit` or
`GPUDrivenPreviewParams params` to see:

- A Slang-highlighted `struct Hit` signature, preserving generic arguments.
- The containing namespace on a separate line, when present.
- Natural-layout size, alignment and padding, in bytes.
- Array stride when it differs from size.
- Existing documentation and a compact definition file/line link.

The same information is available at struct declarations and other type references.
Parameter names and function hovers retain their existing behavior. Nested type
names retain their enclosing type; namespace prefixes move to the namespace line.
The server returns standard Markdown. The IDE continues to own the native documentation popup.
Plugin 0.8.1 adapts the exact patch-0007 struct format using the IDE's supported embedded HTML:

- A bold type name and an indented namespace line, colored from the active Slang scheme.
- Separate rows for size, alignment, nonzero wasted padding, and optional array stride.
  Zero padding is hidden, matching Rider. Numeric values have no inline-code background.
- Chinese labels in a Chinese IDE, English labels otherwise. The layout paragraph retains
  `Slang natural layout (bytes)` as its title metadata; the large bold heading is removed.
- One separator above a compact code-style filename link. Its original file URI and line fragment
  are preserved; the line number is in the link title rather than the visible filename.

Explicit `<br/>` breaks survive CLion's documentation Markdown converter, which merges the
server's Markdown hard breaks. Documentation prose/examples remain unchanged, and unmatched
server formats, aliases and other hovers keep their existing presentation. This is a plugin-side
change; the bundled compiler and its layout calculations are unchanged. Native window chrome,
font preferences and actions remain controlled by CLion.

## Layout semantics

The report follows Slang's **AST natural layout**, using compiler scalar sizes and
alignment arithmetic, resolved types, substituted generic fields and instance-only
member enumeration. It does not report a target-specific constant-buffer,
structured-buffer or calling-convention ABI.

Slang natural layout does not round a struct's final size up to its alignment.
For example, `{ float value; uint8_t tag; }` has size 5, alignment 4, padding 0 and
array stride 8. `{ uint8_t tag; float value; }` has size 8, alignment 4 and padding 3.
Padding includes gaps inside nested structs and between array elements, but does
not include tail space beyond the reported size. These rules differ from C/C++
tail padding; see the [Slang structure layout reference](https://docs.shader-slang.org/en/latest/external/slang/docs/language-reference/types-struct.html#memory-layout).
The AST baseline uses a one-byte `bool`; GPU storage rules can differ.

Supported fields include fixed-size scalars, vectors, matrices, enums, arrays and
structs, including specialized generics and inherited fields. Static fields do not
occupy instance storage. Pointer/resource/interface fields, unsized arrays,
unresolved types and unsupported layouts show **Layout unavailable for this type**,
while retaining the signature, documentation and definition location. Layout work
is bounded per request; recursion and arithmetic overflow also yield unavailable.
The change does not alter compiler `sizeof`, code generation or shader layouts.

## Enable and verify

In plugin 0.8.0, select **Bundled enhanced slangd (Windows x64)** in Slang settings and Apply.
The matching compiler and module DLLs are included. See [server selection](bundled-slangd.md).
For other platforms or custom builds, follow the [patch instructions](../patches/slang/README.md).
External stock servers retain their original struct hover.

```powershell
python scripts/slangd-struct-hover-smoke.py `
  --slangd .slang-m4a-build/RelWithDebInfo/bin/slangd.exe
python scripts/slangd-type-hover-smoke.py `
  --slangd .slang-m4a-build/RelWithDebInfo/bin/slangd.exe
```

The real-server regression covers parameter references (including `inout` and
`out`), namespace shadowing, declaration hovers, static fields, nested layouts,
generic substitution, edits, exact hover ranges, source links and unknown-layout
fallback. `--dump <path>` saves actual hover Markdown for inspection.
Native popup layout and clicking definition links still require a manual CLion check.

## 中文说明

形参中悬停 `Hit`、`GPUDrivenPreviewParams` 等 struct 类型时，展示类型签名、命名空间、
大小、对齐、填充，以及简短的定义文件链接。泛型实例保留类型实参，静态成员不计入大小。
参数名本身的悬停行为保持原样。

数值明确采用 Slang 自然布局。该布局与 C/C++ 的尾部填充规则不同，因此数组步长与大小
不同时另行显示；不将这些数值当作特定 GPU 缓冲区的内存布局。无法确定布局时保留其他
信息并显示不可用。0.8.0 已自带补丁 0007；在 Slang 设置中选择自带增强版并应用即可。


## Presentation verification (0.8.1)

`SlangStructHoverPresentationTest` checks Chinese/English content, theme colors, zero/nonzero
padding, stride, generic HTML escaping, decoded filenames, URI/range preservation, and fallbacks.
It runs the actual IDE LSP documentation splitter and Markdown converter, then checks that Swing
places size and alignment on different rows. `scripts/SlangStructHoverRenderProbe.java` can be
compiled with the plugin classes and installed IDE libraries to render real hover samples in light
and dark palettes. It accepts a JSON object mapping names to raw hover Markdown and an output
folder. Those standalone renders verify converted content, not the complete installed IDE popup.

0.8.1 在插件侧调整悬停样式：类型名加粗、命名空间缩进、大小／对齐／非零填充逐行显示，
数字使用当前配色且无灰底；文件链接只显示文件名。中英文标签跟随 IDE 语言，数值仍为
Slang 自然布局；数组步长不同于大小时继续显示。无需重新编译 slangd。
