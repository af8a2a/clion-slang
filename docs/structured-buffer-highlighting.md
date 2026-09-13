# Structured buffers and generic type arguments

The plugin provides independently configurable colors for structured buffer types and checked generic
type arguments. For example:

```slang
StructuredBuffer<HitEntry> g_GBuffer;
RWStructuredBuffer<uint> g_CompactedGBuffer;
RWStructuredBuffer<uint> g_CompactedGBufferLength;
```

`StructuredBuffer` / `RWStructuredBuffer` use **Structured buffer**; `HitEntry` / `uint` use
**Generic type argument**. The variable names keep their existing value colors. The same resource
role covers `AppendStructuredBuffer`, `ConsumeStructuredBuffer`, and
`RasterizerOrderedStructuredBuffer`.

## Settings and server requirement

Under **Settings | Editor | Color Scheme | Slang | Semantic | Types**, adjust:

| Setting | Color key | Fallback inheritance outside the light preset |
| --- | --- | --- |
| Structured buffer | `SLANG.SEMANTIC.STRUCTURED_BUFFER` | Slang Struct |
| Generic type argument | `SLANG.SEMANTIC.TYPE_ARGUMENT` | Slang Type parameter |
| Type parameter (existing) | `SLANG.SEMANTIC.TYPE_PARAMETER` | Platform class reference |

The first two are independent settings. [Slang Rider Light](rider-light-color-scheme.md), also supplied
as Slang defaults for Light / IntelliJ Light, uses deep purple (`#300073`) for buffers and violet
(`#6B2FBA`) for type arguments. Other schemes keep the fallback chains above. In some schemes the
inherited colors may be similar; disable inheritance for either entry to choose distinct colors.
The color-settings preview includes the three declarations above so each category can be selected directly.

Full semantic classification requires the optional server patch
[`0005-structured-buffer-highlighting.patch`](../patches/slang/0005-structured-buffer-highlighting.patch),
applied **after patches 0001–0004** on the same pinned upstream base. See
[reproducible build instructions](../patches/slang/README.md). Install the updated plugin and select
**Bundled enhanced slangd (Windows x64)** in Slang settings; 0.8.0 includes this patch and its
matching compiler libraries. Custom external builds remain supported; see [server selection](bundled-slangd.md).

Without a server, the lexical highlighter recognizes the five resource names and uses the same
Structured buffer color. Generic built-ins retain lexical built-in coloring; arbitrary identifiers
are not guessed to be types from surrounding angle brackets. With an older server, its existing
semantic `type` coloring may override lexical resource coloring: dedicated semantic separation needs
both the updated plugin and patched server.

## Classification boundaries

- Checked generic **type arguments** receive the new role, including primitives (`uint`, `float4`),
  user types (`HitEntry`), type-parameter references (`T`), nested types (`Box<HitEntry>`) and qualified
  types (`Payloads::Hit`). Namespace qualifiers keep their namespace role.
- This applies to checked generic applications generally, including types nested inside structured
  buffers. Generic declarations such as `Box<T>` retain the distinct standard `typeParameter` role
  for `T`, as do its ordinary type references outside argument lists.
- Numeric/value arguments such as `Sized<HitEntry, ELEMENT_COUNT>` keep their existing roles; the
  value identifier is not a type argument. Comparisons, shifts, comments, strings and punctuation are
  not classified as generic types by text matching.
- Resource classification checks the resolved compiler intrinsic (`MagicTypeModifier`), not just
  spelling. A user-defined `UserTypes::StructuredBuffer<T>` is still a user type. An alias such as
  `HitBuffer` keeps normal type coloring; its `StructuredBuffer<HitEntry>` definition is classified.
- Unresolved or syntactically incomplete code retains the available server/lexical fallback. This
  does not invent declarations or change completion, navigation, diagnostics or branch-preview state.

## Wire compatibility

The plugin appends `slangStructuredBuffer` and `slangTypeArgument` to its existing 23 standard client
token types. All standard entries and all modifier positions remain unchanged. The server enables
the extension only when the client's `textDocument.semanticTokens.tokenTypes` advertises both names
and standard `typeParameter`.

An opted-in server legend retains the original ten entries in the same order, then appends:

```text
typeParameter, slangTypeArgument, slangStructuredBuffer
```

The modifier legend remains empty for this patch. Other/partial/older clients receive the exact stock
legend and stock classifications. No numeric indices are hard-coded in the plugin. Specific custom
roles take precedence over `defaultLibrary` if a later publisher additionally supplies that modifier.

The compiler walks checked generic-argument ASTs before emitting tokens, collecting type-reference
locations. Built-in scalar tokens normally omitted by stock slangd are emitted at those argument
locations. Overlapping AST views at an identical start prefer the specific resource / argument /
parameter role, producing one token per position. Positions retain the existing physical UTF-16
conversion. No extra LSP requests or independent editor overlay are introduced.

## Regression checks

```powershell
python scripts/slangd-structured-buffer-smoke.py --slangd E:/path/to/patched/slangd.exe
# Older clients keep satisfying the original stock semantic contract:
.\scripts\slangd-lsp-smoke.ps1 -Slangd E:/path/to/patched/slangd.exe -SemanticContract stock
.\gradlew.bat test -PslangdTestPath=E:/path/to/patched/slangd.exe
```

The standalone test checks all five buffers, built-in/user/nested/qualified/type-parameter arguments,
aliases and user shadowing, value arguments/comparisons, token bounds/overlap, repeated requests,
unsaved UTF-16/CRLF edits, and complete/partial/absent capability negotiation. `--dump` prints decoded
token roles. `StructuredBufferHighlighting.slang` is the reusable fixture.

Java tests cover lexical recognition and restart states, shared color keys, settings descriptors/demo,
client capability order and role/modifier mapping. `SlangStructuredBufferProtocolTest` additionally
uses the plugin's LSP4J interface and stream wrappers to verify actual server tokens reach the right
color keys (opt-in; skipped when the supplied server lacks patch 0005).

The plugin is built locally with the installed CLion 2026.2.2 SDK; the existing declared
2026.1.3 / Java 21 baseline is unchanged and not newly verified. Full IDE-fixture tests remain blocked
by the missing JetBrains test-framework dependency. GUI acceptance is manual: open the fixture,
change both colors, check live declarations and the settings preview, then test a server restart and
lexical fallback with slangd disabled.
