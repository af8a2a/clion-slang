# Enhanced Slang 2026.19

Base: official [v2026.19](https://github.com/shader-slang/slang/releases/tag/v2026.19),
commit `0fb6b7587a94fa33d88141ec9930766eedc6f59a`.

`0001-enhanced-language-server.patch` ports the existing editor features: preprocessor
trace, contexts, variants and preview; structured-buffer/generic/member colors;
struct/field/type and macro hover; builtin provenance/reference links; and variable
name navigation. It preserves upstream's owned search-path buffers and current JSON-RPC
null-response API while merging the build-context behavior.

When a standalone `implementing` file names an explicit primary module, the language
server restores that module's top-level `using` directives in an enclosing scope.
The fragment's own directives retain precedence; sibling files and imported modules
do not export their using directives. The enclosing scope is wired before checking
fragment namespace directives, generic constraints and extension targets.

The old custom descriptor heap types and conversion-checker changes are retired.
The new baseline provides both descriptor heap objects and
`getDescriptorFromDynamicResourceHeap<T>` in its unmodified core library. Only the
editor-specific heap coloring and reference presentation are retained.

## Build (PowerShell, Visual Studio 2026)

```powershell
git clone --branch v2026.19 --depth 1 https://github.com/shader-slang/slang.git .slang-2026.19-source
git -C .slang-2026.19-source submodule update --init --depth 1 --jobs 8 external/unordered_dense external/miniz external/lz4 external/spirv-headers external/vulkan external/lua external/cmark external/fast_float external/mimalloc
git -C .slang-2026.19-source apply ../patches/slang/2026.19/0001-enhanced-language-server.patch
cmake -S .slang-2026.19-source -B .slang-2026.19-build -G "Visual Studio 18 2026" -A x64 `
  -DCMAKE_MSVC_RUNTIME_LIBRARY=MultiThreaded -DCMAKE_C_FLAGS=/utf-8 -DCMAKE_CXX_FLAGS=/utf-8 `
  -DSLANG_ENABLE_SLANGD=ON -DSLANG_ENABLE_CUDA=OFF -DSLANG_ENABLE_OPTIX=OFF `
  -DSLANG_ENABLE_NVAPI=OFF -DSLANG_ENABLE_AFTERMATH=OFF -DSLANG_ENABLE_DXIL=OFF `
  -DSLANG_ENABLE_GFX=OFF -DSLANG_ENABLE_SLANGC=OFF -DSLANG_ENABLE_SLANGI=OFF `
  -DSLANG_ENABLE_SLANGRT=OFF -DSLANG_ENABLE_SLANG_GLSLANG=OFF -DSLANG_ENABLE_SLANG_RHI=OFF `
  -DSLANG_ENABLE_TESTS=OFF -DSLANG_ENABLE_EXAMPLES=OFF -DSLANG_ENABLE_REPLAYER=OFF
cmake --build .slang-2026.19-build --target slangd slang-glsl-module --config RelWithDebInfo --parallel 8
python -B scripts/prepare-bundled-slangd.py
```

The preparation script verifies the exact base and complete tracked patch replay,
checks static CRT imports, stages matching binaries and dependency licenses, runs all
real-server smoke suites, and writes the SHA-256 manifest. `/utf-8` is required on
Chinese Windows so cmark's UTF-8 source does not fail with C4819/C2220.
See [bundled server documentation](../../../docs/bundled-slangd.md) for plugin packaging.
