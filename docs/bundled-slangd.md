# Bundled slangd runtime

Plugin version 0.3.1 (M3) packages the patched language server instead of searching `PATH` or asking
ordinary users to select an executable. This release is deliberately limited to **Windows x86_64**.
The source descriptor declares the IntelliJ Platform 2026.1 compatibility dependencies
`com.intellij.modules.os.windows` and `com.intellij.modules.arch.x86_64`. The Marketplace artifact
and its plugin version use the `windows-x86_64` suffix. Do not upload the unsuffixed archive from an
older build.

## Build Slang

Use an x64 Visual Studio developer environment and the fixed Slang source revision plus all four
repository patches. The source worktree is expected to be exactly `HEAD` with
`0001-m2a-enhanced-semantic-tokens.patch` and then
`0002-m3-slang-hlsl-semantic-tokens.patch` and
`0003-field-layout-hover.patch` and
`0004-field-hover-presentation.patch` applied in numeric order.
Dirty submodule gitlinks are ignored because the disabled submodules do not participate in this
minimal build, but no additional tracked superproject changes are accepted.

The full minimal configuration is documented in `patches/slang/README.md`. In particular, the final
configure must select the static MSVC CRT:

```powershell
cmake -S .\.slang-m2a-source -B .\.slang-m2a-build-ninja -G Ninja `
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
cmake --build .\.slang-m2a-build-ninja --target slangd --parallel
```

After the final link, run the M3 language-server smoke test. In addition to definition and semantic
tokens, it validates a real field Hover response with natural size, alignment, and offset. It refreshes
`slang-glsl-module.bin`, whose first eight bytes record the Unix mtime of the exact
`slang-compiler.dll` used to produce it:

```powershell
.\scripts\slangd-lsp-smoke.ps1 `
  -Slangd .\.slang-m2a-build-ninja\RelWithDebInfo\bin\slangd.exe `
  -SemanticContract m3
```

## Prepare the archive

Package the version-locked executable, compiler library, and generated core module together:

```powershell
.\scripts\prepare-bundled-slangd.ps1 `
  -SlangdPath .\.slang-m2a-build-ninja\RelWithDebInfo\bin\slangd.exe `
  -SlangCompilerPath .\.slang-m2a-build-ninja\RelWithDebInfo\bin\slang-compiler.dll `
  -SlangGlslModulePath .\.slang-m2a-build-ninja\RelWithDebInfo\bin\slang-glsl-module.bin `
  -SlangSource .\.slang-m2a-source
```

The script rejects:

- a tracked source diff that is not byte-for-byte equivalent to replaying the recorded M2a, M3,
  field-layout, and field-presentation patches in order;
- a non-AMD64 PE or a PE importing the dynamic MSVC/UCRT libraries (the build must use `/MT`);
- a `slangd.exe` that does not import its matching `slang-compiler.dll`;
- a publisher binary that fails field Hover or the M3, M2a-enhanced, or stock semantic-token
  contract smoke test;
- a generated GLSL module whose recorded compiler mtime differs from the supplied DLL.

All three LSP contract profiles run automatically in isolated child PowerShell processes before the
final module timestamp and payload hashes are read. Each profile validates the same field Hover in
addition to its semantic-token negotiation. The child process is required because the smoke script
uses `exit` to report its result.

The recorded Git description uses `git describe --dirty`; the expected `-dirty` suffix denotes the
recorded publisher patch. The exact diff check rejects unrelated tracked changes. Repository URL
userinfo, query parameters, and fragments are removed before the URL is written to the manifest.

The default output is `.bundled-runtime/windows-x86_64.zip`. It contains this fixed ordered set:

```text
0001-m2a-enhanced-semantic-tokens.patch
0002-m3-slang-hlsl-semantic-tokens.patch
0003-field-layout-hover.patch
0004-field-hover-presentation.patch
LICENSE-slang.txt
LICENSES/lz4-distribution.txt
LICENSES/lz4-lib-BSD-2-Clause.txt
LICENSES/miniz-MIT.txt
LICENSES/unordered_dense-MIT.txt
manifest.json
slang-compiler.dll
slang-glsl-module.bin
slangd.exe
```

The third-party files are copied from the exact Slang source tree. Both the LZ4 repository license
partition notice and the full `lib/LICENSE` BSD-2-Clause text are included. Every payload SHA-256 is
recorded in the manifest. Entries use a fixed ZIP timestamp and STORE compression, so identical
input bytes and normalized metadata produce an identical runtime archive across Windows PowerShell
5.1 and PowerShell 7.

## Validate and build the plugin

`verifyBundledSlangdArchive` opens the ZIP before resource processing and enforces the exact entry
order, STORE method, size limit, schema 1, fixed `clion-slang-m3` profile, `windows-x64` platform,
protocol 1.2 with the ordered `semanticTokens.m2a`, `semanticTokens.m3`, and
`hover.fieldLayout.natural` feature list, exact manifest file set, and every payload SHA-256. Merely
placing a file at the expected path is not sufficient.

Build the constrained Marketplace artifact with:

```powershell
.\gradlew.bat clean buildPlugin
```

The only new distribution is:

```text
build/distributions/slang-clion-0.3.1-windows-x86_64.zip
```

Its generated plugin version is `0.3.1-windows-x86_64`, and its descriptor declares both official
OS/architecture modules. For CI or a release build, an externally produced archive can be selected
without bypassing validation:

```powershell
.\gradlew.bat clean buildPlugin `
  -PbundledSlangdArchive=C:\artifacts\windows-x86_64.zip
```

Only the suffixed Windows x86_64 ZIP should be signed or uploaded. Linux, macOS, and Windows ARM64
must receive their own bundled runtime and constrained variant before they can be published.
