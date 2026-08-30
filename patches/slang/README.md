# M2a enhanced `slangd` semantic tokens

`0001-m2a-enhanced-semantic-tokens.patch` is the publisher-side half of the M2a roadmap. It was
developed and verified against Slang commit `5f9227cf6e5055b6a9ee742fdd729aab9162cf25`
(`v2026.4.2-29-g5f9227cf6`). Rebase and rerun both contracts before applying it to another Slang
revision.

The patch extends only the language-server protocol model and semantic-token adapter. It does not
change the parser, name resolver, type checker, or code generator.

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

Initialize-time capability negotiation is atomic. A client advertising all 17 M2a token types and
all five modifiers receives the enhanced legend. Any missing entry selects the stock 10-type,
zero-modifier legend, and refined tokens are downgraded before encoding.

Verify both paths with the same enhanced binary:

```powershell
$slangd = Join-Path $buildDir 'RelWithDebInfo\bin\slangd.exe'
.\scripts\slangd-lsp-smoke.ps1 -Slangd $slangd -SemanticContract enhanced
.\scripts\slangd-lsp-smoke.ps1 -Slangd $slangd -SemanticContract stock
```

On Windows, the M2b language-server bundle contains `slangd.exe`, its matching
`slang-compiler.dll`, and the generated `slang-glsl-module.bin`. Build with the static MSVC runtime
as shown above so a clean machine does not need the Visual C++ Redistributable. Do not replace only
the executable in an existing SDK; most semantic-token implementation code lives in the compiler
library. The generated GLSL module embeds the compiler DLL timestamp, so create or refresh it by
running the enhanced LSP smoke after the final link and package the resulting file as a version-locked
member of the same bundle.
