# Slang symbol search (0.8.6)

Open CLion's **Search Everywhere → Symbols**, or **Navigate → Symbol** (Ctrl+Alt+Shift+N
in the default Windows keymap). Search `streamNormalStride` to find
`streamNormalStride(uint format)` in `Shaders/Features/VisibilityBuffer/StreamAttributeDecode.slang`.
Select a result and press Enter to navigate to its declaration name.

Results show a symbol-kind icon, function signature (including overload parameters), and
the containing namespace/type and file path. Search matching and scope selection use the IDE's
native Symbols UI. Namespace and type names can qualify results with `.`.

Repeated name/index callbacks are deduplicated within each query. A result's identity is its
project, canonical file URL (respecting filesystem case rules) and declaration offset, rather
than a particular PSI wrapper or signature presentation. Repeated search passes therefore keep
one row per source declaration; overloads and same-named declarations in different files remain.

### Build snapshots

By default, symbol search excludes `build/` and `cmake-build-*` directories directly beneath
the project root or any IDE content root. Experiment snapshots such as
`build/workload-1/source/Shaders/...` are distinct physical files, so identity deduplication alone
cannot remove them. This search-scope filter keeps them out even if they were already indexed.

To search generated sources or snapshots deliberately, enable **Settings → Languages & Frameworks
→ Slang → Include symbols from build/ and cmake-build-* directories** and run a new search.
The option is project-specific, defaults off on upgrade, and does not require reindexing or a
language-server restart. The selected IDE scope still applies. Source folders merely containing
the word `build` (such as `Shaders/build/`) and independent same-named source files remain searchable.
Opening a snapshot as its own project searches that project's source normally.

## Indexed declarations

- Functions and methods, including prototypes, overloads, generic functions and constructors.
- Structs, classes, interfaces, enums, namespaces, cbuffers and tbuffers.
- Struct/interface members, global variables/constants, enum members and type aliases.

Only files associated with the plugin's Slang file type (normally `.slang` and `.slangh`) are
indexed. Unopened files participate; excluded folders and files outside the selected search
scope do not. External module directories must be part of the IDE's indexable content/library
roots to appear. A slangd search path alone does not make a directory an IDE content root.

The content-dependent file index updates through IntelliJ's normal file/document indexing,
including unsaved document changes, renames and deletions. Results become available after IDE
indexing finishes. This works with bundled or external slangd and also without a running server.
Queries read the name index instead of scanning or compiling every project file per keystroke.

## Boundaries

This is a conservative, file-local declaration scanner. It does not expand macros, resolve
imports, or evaluate preprocessor conditions; declarations in both conditional branches can
appear. Local variables, parameters, calls, comments, strings, imports and include directives
are not separate search results. Operator declarations, property/accessor syntax and malformed
or macro-generated declarations are not comprehensively supported. The scanner skips executable
bodies, preserves UTF-16 offsets, checks cancellation and limits scope nesting.

The existing compiler-backed definition navigation, highlighting and hover remain separate.
No compiler runtime or patch update is needed for symbol search.

## Validation

Unit tests cover the reported four StreamAttributeDecode functions, nested containers,
overloads, generics, aliases, enum members, attributes, initializers, false call/local matches,
UTF-16/CRLF offsets, edits and index-value serialization. The actual Metallic Shader corpus
contains 126 files and produced 2,579 declarations, including exactly one `streamNormalStride`
definition with its expected signature and physical offset.

Platform tests additionally cover extension registration, unopened-file indexing, overloads,
scope filtering, unsaved renames, deletion and caret navigation. These tests are included but
could not run locally because the JetBrains test-framework Maven dependency was unavailable.
The local build and ordinary JUnit tests use CLion 2026.2.2; a live Symbols popup check is not claimed.

## 中文

安装后重启并等待索引完成，在“搜索所有 → 符号”中输入函数或类型名即可。
例如 `streamNormalStride` 会显示带参数的函数签名，回车跳转到声明。无需选择增强版 slangd。
只搜索 IDE 已索引范围中的 Slang 文件；宏展开生成的声明和局部变量不在本次支持范围内。
