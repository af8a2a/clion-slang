# Enhanced slangd patches

`0001-m4a-preprocessor-trace.patch` is a standalone patch against upstream Slang commit
`5f9227cf6e5055b6a9ee742fdd729aab9162cf25` (`v2026.4.2-29-g5f9227cf6`). Do not apply it on top
of the obsolete M2/M3 patches. The base commit is pinned for reproducibility, not a claim that it
is the latest upstream version. Slang retains its upstream license. Plugin 0.8.2 ships a Windows x64
build with patches 0001–0008, matching DLLs, licenses and the complete patch series; see
[bundling and server selection](../../docs/bundled-slangd.md). The source checkout remains outside Git.

`0002-m4c-preprocessor-contexts.patch` applies **after 0001**, on the same pinned base. It adds
per-instance include tracing, isolated root compilation and `experimental.preprocessorContexts: 1`.
See [M4c contexts](../../docs/preprocessor-contexts.md) for the selector, protocol and limitations.

`0003-m4e-shader-variants.patch` applies **after 0002**. It adds isolated macro/include-path/target/profile
build environments with `experimental.preprocessorVariants: 1`; see [M4e](../../docs/shader-variants.md).

`0004-m4d-branch-preview.patch` applies **after 0003** (M4e preceded M4d in implementation order).
It adds request-local macro overrides with `experimental.preprocessorPreview: 1`; see
[M4d preview](../../docs/branch-preview.md). No compiler/session state is persisted by preview.

`0005-structured-buffer-highlighting.patch` applies **after 0004**. It adds opt-in, AST-backed
structured-buffer / generic-type-argument roles and standard type-parameter classification.
Clients that do not advertise the new vocabulary retain the exact stock semantic legend and behavior.
See [buffer highlighting](../../docs/structured-buffer-highlighting.md).

`0006-type-alias-hover.patch` applies **after 0005**. It adds semantic alias expansion,
vector/matrix dimensions and builtin origin labels to standard hover. No custom capability
or plugin upgrade is needed; see [type hover](../../docs/type-hover.md).

`0007-struct-hover.patch` applies **after 0006**. It adds struct namespace, natural
size/alignment/padding, array stride and compact definition links to standard hover,
including parameter type references. See [struct hover](../../docs/struct-hover.md).

`0008-field-hover.patch` applies **after 0007**. It adds field visibility, specialized type,
declaring struct, natural size/alignment/offset and compact links to standard hover.
Plugin 0.8.2 supplies the Rider-style presentation; see [field hover](../../docs/field-hover.md).

The protocol and limitations are documented in [preprocessor-trace-protocol.md](../../docs/preprocessor-trace-protocol.md).

## Reproduce

From the plugin repository root, create a fresh checkout and apply the patch:

```powershell
git clone https://github.com/shader-slang/slang.git .slang-m4a-source
git -C .slang-m4a-source checkout 5f9227cf6e5055b6a9ee742fdd729aab9162cf25
git -C .slang-m4a-source submodule update --init --recursive
git -C .slang-m4a-source apply --check ../patches/slang/0001-m4a-preprocessor-trace.patch
git -C .slang-m4a-source apply ../patches/slang/0001-m4a-preprocessor-trace.patch
git -C .slang-m4a-source apply --check ../patches/slang/0002-m4c-preprocessor-contexts.patch
git -C .slang-m4a-source apply ../patches/slang/0002-m4c-preprocessor-contexts.patch
git -C .slang-m4a-source apply --check ../patches/slang/0003-m4e-shader-variants.patch
git -C .slang-m4a-source apply ../patches/slang/0003-m4e-shader-variants.patch
git -C .slang-m4a-source apply --check ../patches/slang/0004-m4d-branch-preview.patch
git -C .slang-m4a-source apply ../patches/slang/0004-m4d-branch-preview.patch
git -C .slang-m4a-source apply --check ../patches/slang/0005-structured-buffer-highlighting.patch
git -C .slang-m4a-source apply ../patches/slang/0005-structured-buffer-highlighting.patch
git -C .slang-m4a-source apply --check ../patches/slang/0006-type-alias-hover.patch
git -C .slang-m4a-source apply ../patches/slang/0006-type-alias-hover.patch
git -C .slang-m4a-source apply --check ../patches/slang/0007-struct-hover.patch
git -C .slang-m4a-source apply ../patches/slang/0007-struct-hover.patch
git -C .slang-m4a-source apply --check ../patches/slang/0008-field-hover.patch
git -C .slang-m4a-source apply ../patches/slang/0008-field-hover.patch
```

Do not reuse an existing dirty checkout for these commands. Configure with CMake and an installed
C++ compiler. This reduced build disables GPU/runtime/test/example dependencies that the language
server does not require:

```powershell
cmake -S .slang-m4a-source -B .slang-m4a-build `
  -DSLANG_ENABLE_SLANGD=ON -DCMAKE_MSVC_RUNTIME_LIBRARY=MultiThreaded `
  -DSLANG_ENABLE_CUDA=OFF -DSLANG_ENABLE_OPTIX=OFF `
  -DSLANG_ENABLE_NVAPI=OFF -DSLANG_ENABLE_AFTERMATH=OFF `
  -DSLANG_ENABLE_DXIL=OFF -DSLANG_ENABLE_PREBUILT_BINARIES=OFF `
  -DSLANG_ENABLE_GFX=OFF -DSLANG_ENABLE_SLANGC=OFF `
  -DSLANG_ENABLE_SLANGI=OFF -DSLANG_ENABLE_SLANGRT=OFF `
  -DSLANG_ENABLE_SLANG_GLSLANG=OFF -DSLANG_ENABLE_SLANG_RHI=OFF `
  -DSLANG_ENABLE_TESTS=OFF -DSLANG_ENABLE_EXAMPLES=OFF -DSLANG_ENABLE_REPLAYER=OFF
cmake --build .slang-m4a-build --target slangd slang-glsl-module --config RelWithDebInfo --parallel 8
python scripts/slangd-preprocessor-trace-smoke.py `
  --slangd .slang-m4a-build/RelWithDebInfo/bin/slangd.exe
python scripts/slangd-preprocessor-context-smoke.py `
  --slangd .slang-m4a-build/RelWithDebInfo/bin/slangd.exe
python scripts/slangd-shader-variants-smoke.py `
  --slangd .slang-m4a-build/RelWithDebInfo/bin/slangd.exe
python scripts/slangd-branch-preview-smoke.py `
  --slangd .slang-m4a-build/RelWithDebInfo/bin/slangd.exe
python scripts/slangd-structured-buffer-smoke.py `
  --slangd .slang-m4a-build/RelWithDebInfo/bin/slangd.exe
python scripts/slangd-type-hover-smoke.py `
  --slangd .slang-m4a-build/RelWithDebInfo/bin/slangd.exe
python scripts/slangd-struct-hover-smoke.py `
  --slangd .slang-m4a-build/RelWithDebInfo/bin/slangd.exe
python scripts/slangd-field-hover-smoke.py `
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

The generated directories are ignored by Git. The plugin can select its bundled enhanced server or
an external executable in Slang settings. The patched server enables the optional
[M4b branch display](../../docs/preprocessor-branch-display.md) and, with both patches,
the [M4c context selector](../../docs/preprocessor-contexts.md). Original M4a requests remain compatible.
With all three patches, [M4e Shader Variants](../../docs/shader-variants.md) is also available.
With the fourth patch, [M4d temporary branch preview](../../docs/branch-preview.md) is available too.
With the fifth patch and updated plugin, [structured-buffer / generic-argument colors](../../docs/structured-buffer-highlighting.md)
are independently configurable without changing older clients' semantic tokens.
With the sixth patch, [type alias hover](../../docs/type-hover.md) expands vector/matrix aliases
and distinguishes builtin origins using standard LSP hover, without a client protocol change.

With the seventh patch, [struct hover](../../docs/struct-hover.md) shows namespaces and natural
layout details at parameter type references, with compact definition file links.
