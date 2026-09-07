# M4e — Build contexts and Shader Variants

M4e extends [M4c root selection](preprocessor-contexts.md) with explicit build/variant environments.
One root can now be viewed with different macro sets and include paths, without editing its source
or changing global `slangdconfig.json`. Only preprocessor branch display changes; ordinary completion,
navigation, diagnostics, semantic tokens and code generation are not switched.

## Quick start

1. Build/select `slangd` with [patches 0001, 0002 and 0003](../patches/slang/README.md), then install the
   plugin ZIP. M4e requires `experimental.preprocessorVariants: 1`; no server binary is bundled.
2. Copy [slang-variants.example.json](../slang-variants.example.json) to `slang-variants.json` at the
   project root, or set **Shader Variants manifest** in Slang settings to a generated JSON file.
   The setting accepts a project-relative or absolute path, including a specific build configuration.
3. Open `src/test/testData/slang/PreprocessorVariant.slang` and use **Slang Preprocessor Context…**
   from the editor popup, Tools/Find Action, or the Slang status-bar widget.
4. Select **Variant · Compute shader / Blue** or **Green**. The inactive body changes without a source
   edit. Rows are searchable by ID, label, build target/configuration, root name, entry and macro summary.
   **Open Shader Variants manifest…** opens the existing file for editing.

Variant choices are persisted per target file and shared by split editors. Choosing Auto, Current
file, a discovered includer or a manual root clears the variant choice. Auto continues M4c's root
policy; it does **not** pick the first variant, expand a permutation matrix, or follow an IDE build
profile implicitly. Declared variants are shown even when lexical discovery cannot see their include
path; the compiler confirms actual inclusion after selection.

Missing IDs/files, invalid JSON and unsupported servers are visible failures, never silent fallback
to a different macro environment. Stock/M4a/M4c-only servers retain their earlier features, but cannot
apply a selected M4e variant. Configuration changes clear old decorations; the manifest is saved data,
so unsaved JSON edits take effect only after save. Unsaved Slang source uses Native LSP synchronization.

## Manifest version 1

```json
{
  "version": 1,
  "contexts": [{
    "id": "path-trace",
    "name": "Path trace",
    "buildTarget": "shader_library",
    "configuration": "Debug",
    "root": "Shaders/PathTrace.slang",
    "entryPoint": "main",
    "stage": "compute",
    "target": "spirv",
    "profile": "spirv_1_6",
    "inheritWorkspace": false,
    "defines": {"FEATURE": "1"},
    "includePaths": ["Shaders"],
    "variants": [
      {"id": "base", "name": "Base"},
      {"id": "update", "name": "Update", "defines": {"SHARC_UPDATE": "1"}},
      {"id": "query", "name": "Query", "defines": {"SHARC_QUERY": "1"}}
    ]
  }]
}
```

| Field | Semantics |
| --- | --- |
| `id` | Required stable context/variant ID; letters, digits, `_`, `.`, `-`; selection is `context/variant` |
| `name` | Optional display name; defaults to ID |
| `root` | Context's `.slang` compilation root; required |
| `defines` | Object of macro names to **JSON strings**; `"1"`, `"0"`, `""` and quoted replacement text are distinct |
| `undefines` | Names removed from inherited/context definitions; not a source-level `#undef` injection |
| `includePaths` | Ordered search directories; variant paths precede context paths, with duplicates removed |
| `inheritWorkspace` | Context-only boolean, default **false**; controls workspace macros and search paths |
| `target`, `profile` | Actual isolated Slang session target/profile; empty retains slangd defaults (`sm_6_6` profile) |
| `entryPoint`, `stage` | Descriptive build identity, not an entry-point compile or source macro |
| `buildTarget`, `configuration` | Descriptive build identity; no automatic CLion/CMake profile synchronization |

Variant records may override entry/stage/target/profile/build target/configuration. Their definitions
override context definitions, and their undefines remove context definitions. The same layer cannot
both define and undefine a name. With inheritance enabled, the flattened variant definitions replace
same-named workspace macros, explicit undefines remove workspace macros, and workspace search paths
follow explicit paths. With inheritance disabled, workspace macros/search paths do not leak in.
Source `#define`/`#undef` still has normal compiler authority over the initial environment.

Relative paths resolve against the **manifest directory**, not the build process working directory.
`${workspaceFolder}` expands to the project root. No environment variables or generator expressions
are evaluated by the plugin. Omitted `variants` creates one `default` variant; an explicitly empty
variant list is invalid. Unknown fields, duplicate JSON keys/IDs, non-string macro values and malformed
entries reject the entire catalog. No half-valid catalog is silently used.

Accepted targets are `spirv`, `dxil`, `hlsl`, `glsl`, `cpp`, `cuda`, `ptx`, or empty. The server checks
profile names using its own compiler. This does not certify all target/profile combinations or compile
entry points. Slang preprocessing is shared across entry points/targets and does not automatically
invent target-specific preprocessor macros; supply the actual build's explicit macros when needed.
See [Slang's compilation model](https://shader-slang.org/slang/user-guide/compiling.html).

Limits: 1 MiB saved UTF-8 JSON, nesting depth 12, 512 flattened variants, 256 definitions/undefines/
include paths per effective context, bounded identifier/string sizes. Macro names are object-like
identifiers; function-like macro definitions and arbitrary compiler flags/capabilities are not imported.
The popup abbreviates long summaries; the manifest retains full values. Selected IDs remain stored
through catalog errors/removals so the failure can be corrected or explicitly cleared.

## Build-side export with CMake

Include [SlangShaderVariants.cmake](../cmake/SlangShaderVariants.cmake) from a CMake 3.21+ project.
Call the helper using the same explicit data used by the real shader command/session:

```cmake
include(path/to/SlangShaderVariants.cmake)
set(shader_defines "SHARC_UPDATE=1" "FEATURE=1")
set(shader_paths "${PROJECT_SOURCE_DIR}/Shaders")

# Reuse shader_defines/shader_paths in your actual slangc command or runtime manifest.
slang_ide_add_variant(
    ID path_trace_update NAME "SHaRC Update"
    BUILD_TARGET shader_library
    ROOT "${PROJECT_SOURCE_DIR}/Shaders/PathTrace.slang"
    ENTRY_POINT main STAGE compute TARGET spirv PROFILE spirv_1_6
    DEFINES ${shader_defines}
    INCLUDE_PATHS ${shader_paths})

slang_ide_export_variants(
    OUTPUT "${CMAKE_CURRENT_BINARY_DIR}/slang-variants-$<CONFIG>.json")
```

Each call exports one context with an implicit `default` variant. `CONFIGURATION` defaults to
`$<CONFIG>`; `BUILD_TARGET` is optional metadata. `UNDEFINES` and the `INHERIT_WORKSPACE` option are also
supported. `DEFINES NAME` means an empty macro replacement, matching Slang's CLI; use `NAME=1` when
that is what the shader build supplies. Quotes, equals signs, semicolons and paths are JSON-escaped.
Only `$<CONFIG>` is supported inside exported string values; resolve other generator expressions in
your own exporter. Use configuration-specific output names for multi-config builds, then select the
desired file in Slang settings. Regeneration is picked up through VFS events or the next context use.

The helper runs during configure/generate and never compiles a shader, runs a command from JSON, or
copies host C++ target definitions. Ordinary CMake `compile_commands.json` does not cover custom shader
commands; runtime session permutations also cannot be recovered from it. That is why M4e provides an
explicit data bridge instead of guessing from C++ flags. See the [CMake maintainer explanation](https://discourse.cmake.org/t/is-it-possible-to-export-compile-commands-for-custom-commands/9889/2).

## metallic example

[metallic-slang-variants.example.json](metallic-slang-variants.example.json) models the inspected
`ScenePathTracePass.cpp` / `SlangCompiler.h` usage: `ScenePathTrace.slang`, `scenePathTraceMain`,
`spirv_1_6`, and Base / SHaRC Update / SHaRC Query / NRC Update / NRC Query.

This example explicitly disables optional RTXCR/NTC/cooperative-vector features. It is **not** a dump
of the live application's state: enabling those features requires the actual runtime macro values
and SDK search paths. Additional compiler capabilities, runtime constants, OpenPBR, guide/realtime
entry roots and reflection/launch parameters are outside this example. The metallic project was only
read during verification; no source/configuration files were modified there.

## Wire extension and freshness

Numeric `experimental.preprocessorVariants: 1` is required in addition to both older capabilities.
`slang/textDocument/preprocessorTrace` retains `textDocument` and `contextUri`, and accepts optional
`buildContext` containing `version: 1`, `fingerprint`, `inheritWorkspace`, `defines` (name/value array),
`undefines`, absolute `includePaths`, `target` and `profile`. Results add `contextFingerprint` and
`contextError`; rejected environments return `status: "invalidContext"` and empty arrays.

The fingerprint is a client-generated SHA-256 identity echoed by the server, **not** a security
attestation or compiler cache key. It covers manifest path, stable IDs, root, entry/stage, build target/
configuration, target/profile, macros, undefines, ordered paths and inheritance policy. Macro-map order
does not change identity. Display acceptance requires this fingerprint plus M4c root/target versions,
document/file stamps, server identity and request generation. The saved catalog is rechecked after
the request, so a changed/removed definition cannot install its obsolete result.

Only the selected variant is compiled, in a fresh isolated workspace/session; no Cartesian product,
global macro mutation, diagnostics replacement, process execution from catalog data, or persistent
variant compile cache is introduced. M4c skipped/repeated-include safeguards continue to apply.

## Verification

```powershell
python scripts/slangd-shader-variants-smoke.py --slangd E:/path/to/slangd.exe
# Optional read-only real-project regression:
python scripts/slangd-shader-variants-smoke.py --slangd E:/path/to/slangd.exe --metallic E:/metallic
cmake -S src/test/testData/cmake-variants -B build/variant-export-test
.\gradlew.bat test -PslangdTestPath=E:/path/to/slangd.exe
```

The CMake-export JUnit check additionally accepts Gradle
`-PslangVariantsTestPath=<generated Debug JSON>` (or `-Dslang.test.cmakeVariants=...` with direct JUnit).
Tests cover strict catalog validation, escaping, flattening/precedence, identity changes, persistence,
real LSP4J transport/presentation, actual compiler macro/path switching, inheritance/undefines,
invalid target/profile/options, and ordinary-session isolation. M4a/M4c regression suites also pass.
The local metallic run returned correct decisions for all five modes (80 conditional directives per
trace; approximately 0.6–1.6 s/request on this machine, not a performance guarantee).

The plugin was built against the installed CLion 2026.2.2 SDK; declared 2026.1.3 / Java 21 compatibility
was not newly verified. Full IDE-fixture tests remain blocked by the unavailable JetBrains test
dependency. GUI acceptance remains manual: check popup selection, split editors, saved/unsaved edits,
manifest regeneration/deletion, project restart, and old-server fallback.

[M4d branch preview](branch-preview.md) now adds temporary macro overrides without rewriting the
selected Variant or manifest. Automatic active-CMake-profile
tracking, compile-command import, runtime capture/export integration, compiler capabilities, full
LSP-context switching and repeated-include occurrence selection remain separate follow-ups.
