# Enhanced `slangd` semantic tokens

`0001-m2a-enhanced-semantic-tokens.patch` is the publisher-side half of the M2a roadmap. It was
developed and verified against Slang commit `5f9227cf6e5055b6a9ee742fdd729aab9162cf25`
(`v2026.4.2-29-g5f9227cf6`). Rebase and rerun all three contracts before applying it to another Slang
revision.

The patch extends only the language-server protocol model and semantic-token adapter. It does not
change the parser, name resolver, type checker, or code generator.

`0002-m3-slang-hlsl-semantic-tokens.patch` is an incremental patch on top of M2a. It adds two
compiler-resolved Slang/HLSL categories without changing the M2a wire prefix:

- `slangSemantic` for HLSL binding semantics such as `SV_DispatchThreadID`;
- `slangSwizzle` for checked scalar/vector and matrix swizzles such as `.xyz` and `._m00_m11`.

The classification uses checked base types, not identifier spelling. Tuple element selection also
uses `SwizzleExpr` internally and is deliberately excluded; a user-defined field named `xy` remains
`property`.

`0003-field-layout-hover.patch` enriches standard field Hover Markdown with the owning struct plus
the field's natural size, alignment, and offset. It uses Slang's existing `ASTNaturalLayoutContext`,
labels the values as **Natural layout**, and omits them when the type or any preceding field has an
indeterminate target-dependent layout. It does not add a custom LSP method or change the Hover wire
shape.

`0004-field-hover-presentation.patch` turns struct-field Hover signatures into a compact Rider-style
three-line definition: effective visibility and field kind, specialized type and short field name,
then the owning struct path. The opening fence is tagged `slang` so JetBrains clients can apply the
plugin highlighter. Natural-layout rows use CommonMark backslash hard breaks because CLion trims the
trailing spaces from Markdown lines, and their numeric values use inline code for theme-aware color.
User-visible field modifiers such as `nointerpolation`, `centroid`, and `precise` are preserved;
non-field declaration signatures keep their existing content and completion details are unchanged.

`0005-document-local-references.patch` adds the standard `textDocument/references` request and
advertises `referencesProvider`. It resolves the variable at the caret through Slang's checked AST,
then compares declaration identity while scanning the current document. This covers parameters,
locals, globals, and struct fields without conflating same-named declarations or shadowed locals.
`context.includeDeclaration` is honored exactly and duplicate AST paths are collapsed. The first
version is deliberately document-local: separately loaded root modules do not share stable `Decl*`
identity, so cross-document results require a source-location symbol key and workspace indexing.
The patch also completes the language-server walkers for address-of and compiler-generated detach
expressions, compile-time loops, intrinsic-asm arguments, and GPU foreach nodes; otherwise references
inside those constructs would be silently omitted.

`0006-document-variable-highlights.patch` adds the standard
`textDocument/documentHighlight` request and advertises `documentHighlightProvider`. It deliberately
reuses the reference resolver with declarations enabled, so every returned background range has the
same checked declaration identity, UTF-16 conversion, ordering, and deduplication guarantees. The
wire kind is `Text`, allowing CLion to apply its normal read-usage background without inventing a
plugin-specific color.

`0007-rider-function-hover.patch` expands standard function Hover Markdown into a Rider-style
definition. It adds a **Function** category, prints the return type before the function name, and
puts each parameter on its own indented line while preserving parameter modifiers, default values,
documentation, differentiability notes, and the existing definition location. Resource element
types keep their source-facing HLSL spelling, for example `Texture2DArray<float4>` rather than the
canonical `Texture2DArray<vector<float,4>>`. Non-function Hover and completion signatures are not
changed.

Apply the patches in numeric order. Each layer remains separate so the semantic-token baselines and
field-hover extensions can be reproduced and reviewed independently.

## Apply

Use a disposable Slang checkout or worktree so the upstream tree remains easy to update:

```powershell
$slangSource = 'D:\src\slang-m2a'
$patch = (Resolve-Path '.\patches\slang\0001-m2a-enhanced-semantic-tokens.patch').Path
$expectedCommit = '5f9227cf6e5055b6a9ee742fdd729aab9162cf25'
$actualCommit = (git -C $slangSource rev-parse HEAD).Trim()
if ($actualCommit -ne $expectedCommit) {
  throw "Expected Slang $expectedCommit, got $actualCommit"
}
git -C $slangSource submodule update --init --recursive
git -C $slangSource apply --check $patch
git -C $slangSource apply $patch
$m3Patch = (Resolve-Path '.\patches\slang\0002-m3-slang-hlsl-semantic-tokens.patch').Path
git -C $slangSource apply --check $m3Patch
git -C $slangSource apply $m3Patch
$fieldHoverPatch = (Resolve-Path '.\patches\slang\0003-field-layout-hover.patch').Path
git -C $slangSource apply --check $fieldHoverPatch
git -C $slangSource apply $fieldHoverPatch
$fieldHoverPresentationPatch = (Resolve-Path '.\patches\slang\0004-field-hover-presentation.patch').Path
git -C $slangSource apply --check $fieldHoverPresentationPatch
git -C $slangSource apply $fieldHoverPresentationPatch
$referencesPatch = (Resolve-Path '.\patches\slang\0005-document-local-references.patch').Path
git -C $slangSource apply --check $referencesPatch
git -C $slangSource apply $referencesPatch
$documentHighlightsPatch = (Resolve-Path '.\patches\slang\0006-document-variable-highlights.patch').Path
git -C $slangSource apply --check $documentHighlightsPatch
git -C $slangSource apply $documentHighlightsPatch
$functionHoverPatch = (Resolve-Path '.\patches\slang\0007-rider-function-hover.patch').Path
git -C $slangSource apply --check $functionHoverPatch
git -C $slangSource apply $functionHoverPatch
```

The verified Windows build used CMake, Ninja, and an x64 Visual Studio developer environment:

```powershell
$buildDir = 'D:\build\slang-m2a'
cmake -S $slangSource -B $buildDir -G Ninja `
  -DCMAKE_BUILD_TYPE=RelWithDebInfo `
  -DCMAKE_MSVC_RUNTIME_LIBRARY=MultiThreaded `
  -DSLANG_SLANG_LLVM_FLAVOR=DISABLE `
  -DSLANG_ENABLE_TESTS=OFF `
  -DSLANG_ENABLE_EXAMPLES=OFF `
  -DSLANG_ENABLE_GFX=OFF `
  -DSLANG_ENABLE_SLANG_RHI=OFF `
  -DSLANG_ENABLE_REPLAYER=OFF `
  -DSLANG_ENABLE_SLANGC=OFF `
  -DSLANG_ENABLE_SLANGI=OFF `
  -DSLANG_ENABLE_SLANGRT=OFF `
  -DSLANG_ENABLE_SLANG_GLSLANG=OFF `
  -DSLANG_ENABLE_DXIL=OFF `
  -DSLANG_ENABLE_PREBUILT_BINARIES=OFF `
  -DSLANG_ENABLE_CUDA=OFF `
  -DSLANG_ENABLE_OPTIX=OFF `
  -DSLANG_ENABLE_NVAPI=OFF `
  -DSLANG_ENABLE_AFTERMATH=OFF `
  -DSLANG_ENABLE_SLANGD=ON
cmake --build $buildDir --target slangd --parallel
```

Initialize-time capability negotiation has three atomic levels. A client advertising all 19 M3
token types and all five M2a modifiers receives the M3 legend. A client with all 17 M2a types and
five modifiers, but either missing custom type, receives the M2a legend; `slangSemantic` downgrades
to `enumMember` and `slangSwizzle` to `property`. Any missing M2a entry selects the stock 10-type,
zero-modifier legend, and all refined tokens are downgraded before encoding.

Verify all three negotiation paths with the same enhanced binary:

```powershell
$slangd = Join-Path $buildDir 'RelWithDebInfo\bin\slangd.exe'
.\scripts\slangd-lsp-smoke.ps1 -Slangd $slangd -SemanticContract enhanced
.\scripts\slangd-lsp-smoke.ps1 -Slangd $slangd -SemanticContract stock
.\scripts\slangd-lsp-smoke.ps1 -Slangd $slangd -SemanticContract m3
```

Every smoke run also issues a real `textDocument/hover` request for a non-first struct field and
requires the exact `slang` definition block, three-line `public field` signature, CommonMark hard
breaks, **Natural layout**, size `4 bytes`, alignment `4 bytes`, offset `40 bytes`, and exact UTF-16
hover range. This catches both presentation regressions and offsets accidentally reported as zero,
independently of semantic-token legend negotiation.

The same run requires `referencesProvider: true`, then queries both a shadowed parameter and a
struct field. It checks exact UTF-16 `Location[]` ranges, declaration inclusion/exclusion, stable
source ordering, and that same-spelled declarations are not reported as usages. Dedicated cases
also cover `AddressOfExpr`, compiler-generated `DetachExpr`, and compile-time-loop variables/bodies.

The same run requires `documentHighlightProvider: true` and verifies that the standard highlight
array contains those declaration/use ranges without URI fields and with `DocumentHighlightKind.Text`.

It also verifies a real function Hover byte-for-byte: the **Function** category, `slang` fence,
source-facing resource/vector types, one parameter per line, definition location, and exact UTF-16
identifier range must all match the fixture.

On Windows, the M3 language-server bundle contains `slangd.exe`, its matching
`slang-compiler.dll`, and the generated `slang-glsl-module.bin`. Build with the static MSVC runtime
as shown above so a clean machine does not need the Visual C++ Redistributable. Do not replace only
the executable in an existing SDK; most semantic-token implementation code lives in the compiler
library. The generated GLSL module embeds the compiler DLL timestamp, so create or refresh it by
running the M3 LSP smoke after the final link and package the resulting file as a version-locked
member of the same bundle.
