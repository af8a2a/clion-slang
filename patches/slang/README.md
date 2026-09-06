# Optional M4a slangd patch

`0001-m4a-preprocessor-trace.patch` is a standalone patch against upstream Slang commit
`5f9227cf6e5055b6a9ee742fdd729aab9162cf25` (`v2026.4.2-29-g5f9227cf6`). Do not apply it on top
of the obsolete M2/M3 patches. The base commit is pinned for reproducibility, not a claim that it
is the latest upstream version. Slang retains its upstream license; this repository does not ship
its source tree or a server binary in the plugin ZIP.

The protocol and limitations are documented in [preprocessor-trace-protocol.md](../../docs/preprocessor-trace-protocol.md).

## Reproduce

From the plugin repository root, create a fresh checkout and apply the patch:

```powershell
git clone https://github.com/shader-slang/slang.git .slang-m4a-source
git -C .slang-m4a-source checkout 5f9227cf6e5055b6a9ee742fdd729aab9162cf25
git -C .slang-m4a-source submodule update --init --recursive
git -C .slang-m4a-source apply --check ../patches/slang/0001-m4a-preprocessor-trace.patch
git -C .slang-m4a-source apply ../patches/slang/0001-m4a-preprocessor-trace.patch
```

Do not reuse an existing dirty checkout for these commands. Configure with CMake and an installed
C++ compiler. This reduced build disables GPU/runtime/test/example dependencies that the language
server does not require:

```powershell
cmake -S .slang-m4a-source -B .slang-m4a-build `
  -DSLANG_ENABLE_SLANGD=ON `
  -DSLANG_ENABLE_CUDA=OFF -DSLANG_ENABLE_OPTIX=OFF `
  -DSLANG_ENABLE_NVAPI=OFF -DSLANG_ENABLE_AFTERMATH=OFF `
  -DSLANG_ENABLE_DXIL=OFF -DSLANG_ENABLE_PREBUILT_BINARIES=OFF `
  -DSLANG_ENABLE_GFX=OFF -DSLANG_ENABLE_SLANGC=OFF `
  -DSLANG_ENABLE_SLANGI=OFF -DSLANG_ENABLE_SLANGRT=OFF `
  -DSLANG_ENABLE_SLANG_GLSLANG=OFF -DSLANG_ENABLE_SLANG_RHI=OFF `
  -DSLANG_ENABLE_TESTS=OFF -DSLANG_ENABLE_EXAMPLES=OFF -DSLANG_ENABLE_REPLAYER=OFF
cmake --build .slang-m4a-build --target slangd --config RelWithDebInfo --parallel 8
python scripts/slangd-preprocessor-trace-smoke.py `
  --slangd .slang-m4a-build/RelWithDebInfo/bin/slangd.exe
```

The verified local build used Windows x64, Visual Studio 18 / MSVC 14.51, and `RelWithDebInfo`.
Single-configuration generators require `-DCMAKE_BUILD_TYPE=RelWithDebInfo` and can use a different
output path. If Windows MSBuild reports duplicate `PATH` / `Path`, launch CMake in a shell with only
one of those variables. An upstream Debug bootstrap CRT assertion occurred locally; Debug builds
are not claimed as validated.

For offline development, the local build reused dependency checkouts under `.slang-m2a-source/external`
through `SLANG_OVERRIDE_{UNORDERED_DENSE,MINIZ,LZ4,VULKAN_HEADERS,SPIRV_HEADERS,LUA}_PATH`. Those are
dependency-only overrides; no old compiler modifications are used. A fully initialized fresh clone
does not need them.

The generated directories are ignored by Git. The plugin still selects an external server through
its existing settings. Selecting the patched executable now enables the optional
[M4b branch display](../../docs/preprocessor-branch-display.md), which consumes this unchanged protocol.
