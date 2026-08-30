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

Apply the patches in numeric order. M3 is intentionally a separate patch so the M2a baseline can
still be reproduced and reviewed independently.

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

On Windows, the M3 language-server bundle contains `slangd.exe`, its matching
`slang-compiler.dll`, and the generated `slang-glsl-module.bin`. Build with the static MSVC runtime
as shown above so a clean machine does not need the Visual C++ Redistributable. Do not replace only
the executable in an existing SDK; most semantic-token implementation code lives in the compiler
library. The generated GLSL module embeds the compiler DLL timestamp, so create or refresh it by
running the M3 LSP smoke after the final link and package the resulting file as a version-locked
member of the same bundle.
