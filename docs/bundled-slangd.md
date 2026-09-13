# Bundled and external slangd (0.8.2)

In **Settings | Languages & Frameworks | Slang | Language server**, choose:

- **Bundled enhanced slangd (Windows x64)**: supplied with the plugin, including patches
  0001–0008. Enables field size/alignment/offset and struct namespace/size/alignment/padding hover, type alias details,
  structured-buffer semantic roles, and preprocessor trace/context/variant/preview support.
- **External / official slangd**: use your Slang or Vulkan SDK. Enable automatic discovery
  (`SLANGD_PATH`, `VULKAN_SDK/Bin`, `VULKAN_SDK/bin`, then `PATH`), or disable it and enter
  an executable or directory. Relative paths resolve against the project directory.
  Choose this when the language server must match your project's compiler version.

The resolved path appears below the controls. Apply restarts the project's native LSP server
when its source or active external configuration changes. Built-in module navigation uses the
same selection. Switching to bundled mode or automatic discovery preserves the manual path;
a retained manual path is ignored while automatic discovery is selected.

New projects and legacy automatic-discovery configurations default to bundled mode on Windows
x64. Legacy manually configured paths or explicitly disabled discovery stay external. An explicit
source choice is saved in the project workspace settings and survives restarts. On other platforms
the default is external; selecting the Windows bundle gives an actionable error. A missing or
corrupt bundle never silently falls back to a different server on PATH.

The selected server supplies language features only. This setting does not change the compiler
invoked by your CMake/build scripts. Official servers retain their original hover and supported
capabilities; client extensions are negotiated with the server.

## Package contents

The IDE installs `runtime/windows-x86_64/` alongside the plugin's `lib/` directory:

- `slangd.exe`, its matching `slang-compiler.dll`, and `slang-glsl-module.dll`.
- `manifest.json` with binary/license/patch SHA-256 checksums, pinned source revision,
  static CRT build details and PE imports.
- `NOTICE.txt`, `licenses/`, and the complete patch series under `patches/`.

The executable and DLLs are verified before launch. Settings previews do not hash files or
launch a process. There is no startup download or runtime extraction cache; the IDE owns this
version's files and replaces them when the plugin is upgraded or uninstalled. The precompiled
GLSL module DLL avoids a timestamp-dependent `.bin` cache and writes to the plugin directory.
MSVC runtime linkage is static, so a separate Visual C++ redistributable is not required.
This is a language-server build; optional LLVM, DXIL and GPU code-generation backends are omitted.

## Rebuild the distributable

Follow [patch reproduction](../patches/slang/README.md) to obtain the pinned Slang source and
apply all eight patches. Configure with the reduced options documented there, adding the static
runtime option, then build both targets:

```powershell
cmake -S .slang-m4a-source -B .slang-m4a-build -DCMAKE_MSVC_RUNTIME_LIBRARY=MultiThreaded
cmake --build .slang-m4a-build --target slangd slang-glsl-module --config RelWithDebInfo --parallel 8
python -B scripts/prepare-bundled-slangd.py
.\gradlew.bat test buildPlugin
```

The first command above assumes the reduced build has already been configured. The staging script
accepts `--source`, `--build-dir`, `--configuration` and `--output`. It verifies the source against
the pinned commit plus the patch series using a private Git index, checks AMD64 PE files and static
CRT imports, copies license notices and patches, and runs eight real-server smoke suites against
the staged copies. It then writes `.bundled-runtime/windows-x86_64/manifest.json`.

Gradle packages that directory in both `runIde` sandboxes and `buildPlugin` archives. Use
`-PbundledSlangdDir=<directory>` for a different staged directory. Packaging fails for missing or
changed payloads, an unexpected base commit, or stale patches. There is no fallback to the obsolete
`.bundled-runtime/windows-x86_64.zip`. Generated binaries and source checkouts remain ignored by Git;
a fresh source checkout must stage its runtime before packaging.

The pinned base is `5f9227cf6e5055b6a9ee742fdd729aab9162cf25`, not a claim about the latest Slang
release. Licenses and exact patches accompany the binary in the distributable.

## 中文

安装 0.8.2 后，在 **设置 → 语言和框架 → Slang → Language server** 选择
**Bundled enhanced slangd (Windows x64)** 即可使用自带增强版，不再需要手动替换 SDK 文件。
选择 **External / official slangd** 可使用公版 SDK，继续支持自动查找和手动路径。
应用后自动重启语言服务，切换时保留手动路径。已有自动查找配置在 Windows x64 上默认迁移到
自带版；已有手动配置继续使用外部版。该选择不影响项目实际构建使用的 Slang 编译器。
