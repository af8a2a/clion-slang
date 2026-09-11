# Struct hover

Optional server patch `0007-struct-hover.patch`, applied after patches 0001–0006,
adds Rider-inspired struct details to standard `textDocument/hover`. Hover the
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
The IDE continues to own popup rendering, colors and chrome. This is standard
Markdown content, not a replacement for the IDE's native documentation UI.

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

Follow the [server build instructions](../patches/slang/README.md), including patch
0007, then use the rebuilt executable in the plugin's existing language-server
settings. Keep the matching compiler DLL beside it and restart the language server.
No new plugin ZIP, protocol capability or setting is required. Stock servers retain
their original struct hover.

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
信息并显示不可用。应用补丁 0007、重新构建并重启语言服务器即可，无需重新安装插件。
