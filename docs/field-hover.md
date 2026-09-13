# Field hover (0.8.2)

Select **Bundled enhanced slangd (Windows x64)** in Slang settings to enable the
compiler-backed field details. The bundled runtime includes `0008-field-hover.patch`
after patches 0001–0007. External official servers keep their existing hover format.

The popup follows the struct hover presentation and Rider field reference:

- Visibility and field kind, followed by the field type and bold field name.
- Declaring struct on an indented line, retaining namespaces and generic arguments.
- Separate size, alignment and offset rows, with numbers colored by the active scheme.
- A compact filename link retaining the exact source URI and line; documentation is preserved.

Labels follow the IDE language (Chinese or English). Types and fields use the Slang type
and property colors; keywords and numbers use their corresponding scheme attributes.
Static/const modifiers and folded constant initializers remain visible. This does not
change the selected editor scheme. Popup chrome and font settings remain owned by CLion.

## Layout meaning

Values are bytes in Slang's **natural layout**, not target-specific constant-buffer or
GPU binding layouts. Offset is relative to the declaring struct, including alignment
and preceding base/instance fields. An inherited member references its declaring base
and uses that base's offset. Generic member references use substituted type arguments.

Unknown size/alignment or an unknown preceding field is shown as unavailable; an unknown
offset is never replaced with zero. Static fields have no instance offset and explicitly
show not applicable. Methods, parameters, locals and other non-struct-field hovers retain
the existing behavior. Layout facts come from the compiler AST; the plugin only formats them.

## Validation

`scripts/slangd-field-hover-smoke.py` covers declarations and accesses, visibility,
generics, inherited fields, arrays, matrices, enums, partial/unknown layouts, static
fields and constant initializers, docs, UTF-16/CRLF ranges, source links and unsaved edits.
The user's `BuildReGIRPush.lightCount` resolves to size 4, alignment 4 and offset 0.

Presentation tests cover both locales, independent type/property colors, escaping,
unknown/static labels, unchanged stock responses, UTF-8 LSP framing and hover ranges.
They use the installed CLion documentation splitter/converter and Swing row positions.
The standalone render probe also checks real responses in light/dark palettes; it does
not replace a visual check of a live installed CLion popup.

## 中文

升级后使用自带增强版 slangd，字段声明和访问处都会显示字段类型、加粗名称、所属结构体，
以及分别成行的大小、对齐、偏移。数值采用 Slang 自然布局；无法计算时显示“不可用”，
静态字段显示“不适用（静态字段）”。公版 slangd 保留原有信息。
