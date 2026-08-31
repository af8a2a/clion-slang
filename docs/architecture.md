# Architecture

## Product direction

The plugin follows a two-layer design:

```text
CLion editor
├── lightweight lexical layer
│   └── file type, fallback colors, comments, braces, quotes
└── JetBrains Native LSP client
    └── bundled patched slangd (external override is explicit)
        └── official Slang compiler frontend
```

This is intentionally different from porting Rider's HLSL implementation. Rider's HLSL support is
part of the ReSharper C++ backend and HLSL is not the same language as Slang. Slang-specific
interfaces, extensions, modules, generics, associated types, variadics, and compiler diagnostics are
best handled by `slangd`.

## Main components

- `lang/`: independent `Language`, `LanguageFileType`, token vocabulary, and handwritten tolerant lexer.
- `highlighting/`: static fallback colors plus Slang-specific semantic role keys. The LSP customizer
  explicitly enables semantic-token requests for Slang PSI and maps the negotiated server legend
  onto these keys when `slangd` is running.
- `editor/`: commenter, brace matcher, quote handler, and dumb-aware comment folding with concise
  summaries for consecutive line comments and multiline block comments.
- `settings/`: bundled-runtime default plus an explicit per-project advanced external override.
- `lsp/`: verified bundled-runtime installation, executable resolution, project-wide LSP descriptor,
  and workspace configuration mapping.
- `navigation/`: a public-LSP-API declaration bridge for Ctrl+hover, Ctrl+click, and Ctrl+B. CLion
  2026.1's implicit LSP reference provider does not issue definition requests from its Ctrl+mouse path.
- `synth/`: support code for generated builtin-module documents returned through `slang-synth://` URIs.

No component depends on CLion Classic/CIDR C++ PSI or on Radler internals.

## Version strategy

The checked-in implementation compiles against CLion 2026.1.3 because that IDE is available in the
development environment. It sets that build as the minimum without an artificial maximum version,
and uses the pre-2026.1.4 LSP names (`LspServerSupportProvider` and
`ProjectWideLspServerDescriptor`) in a small adapter surface. JetBrains preserves these types after
renaming them to `LspIntegrationProvider` and `ProjectWideLspClientDescriptor`, so the rest of the
plugin is independent of that rename.

When the minimum supported IDE moves past 2026.1.3, only the provider, descriptor, extension-point
registration, and restart call need to switch to the new names.

## Configuration contract

`slangdconfig.json` is preferred over an IDE-only shader project model. Flattened settings such as
`slang.predefinedMacros` and `slang.additionalSearchPaths` are converted to the value requested by
LSP `workspace/configuration`. `${workspaceFolder}` is expanded against the project root. This keeps
the configuration portable between CLion, VS Code, Visual Studio, and CI.

CMake definitions and include paths are not imported automatically: host C++ options and shader
options are often intentionally different. A future explicit import action can provide that bridge.

## Synthetic builtin modules

`slangd` can return definition URIs such as `slang-synth://core`. The descriptor maps those URIs to a
read-only virtual document whose content is produced by:

```text
slangd --print-builtin-module <module>
```

Generated content is cached for the lifetime of the project/plugin process. A future cache can include
the resolved `slangd` version as part of its persistent key.

## Enhanced semantic-token publisher

M2a keeps semantic truth in the compiler frontend and extends only `slangd`'s semantic-coloring
adapter. The reusable Slang patch under `patches/slang/` classifies resolved declarations into the
standard LSP refinements consumed by the plugin, adds declaration/value/library modifiers, and
negotiates the enhanced generation from the client's initialize capabilities. Unsupported clients
receive the stock legend and downgraded wire data from the same binary.

M3 adds a second, append-only classifier layer for roles that the standard LSP taxonomy cannot
express precisely. Checked `HLSLSemantic` and swizzle AST nodes publish `slangSemantic` and
`slangSwizzle` only when the client advertises both custom names. Otherwise they fall back to
`enumMember` and `property` while preserving all M2a refinements. No parser, checker, or codegen
changes are required.

The third publisher patch enriches standard `textDocument/hover` Markdown for instance fields of
concrete structs. It reuses Slang's `ASTNaturalLayoutContext`, so the displayed size and alignment
match the language's `sizeof` / `alignof` semantics; field offsets are accumulated with the same
alignment rule. The result is explicitly labelled **Natural layout** because it is not a target
resource layout such as cbuffer or std430. If a pointer, resource, unresolved generic, or preceding
field makes the layout indeterminate, the metadata is omitted instead of reporting a guessed zero.

The fifth publisher patch implements standard `textDocument/references` for variables. It resolves
the caret to a checked `VarDeclBase` and scans the requesting module for `DeclRefExpr` nodes that
refer to that exact declaration, so fields and shadowed locals remain distinct. Results are
document-local in protocol 1.3; cross-document lookup needs a persistent declaration key because
the current workspace creates a fresh root module for each opened document. The patch also fills
language-server AST recursion for address-of and detach wrappers, compile-time loops, intrinsic-asm
arguments, and GPU foreach nodes so valid references inside those constructs are not skipped.

The sixth publisher patch adds standard `textDocument/documentHighlight` on top of that exact
declaration-identity scan and advances the bundled contract to protocol 1.4. The client overrides
the Native LSP document-highlight gate because CLion's default support only opts in TEXT/TextMate
PSI; returned `Text` highlights then flow through the IDE's standard read-usage background styling.

## Bundled runtime

M2b introduced the Windows x64 bundled runtime; M3 refreshes it with the M3 publisher. The archive
contains `slangd.exe`, its matching `slang-compiler.dll`, and the version-locked
`slang-glsl-module.bin`. A schema-versioned manifest records the Slang revision, protocol profile,
and SHA-256 of every payload. At runtime the archive itself becomes the content-addressed bundle ID;
extraction goes through a staging directory into the IDE system cache and every cached file is
revalidated before use. ZIP traversal, absolute/alternate-data-stream paths, symlinks, case-folded
duplicates, undeclared files, and oversized inputs are rejected.

The default path is deliberately hermetic: environment variables and `PATH` are not searched. The
current artifact declares the official Windows/x86_64 compatibility modules, so IDEs on other
platforms reject it instead of falling through to an unsupported configuration. An external
executable remains available as an explicit advanced project setting for development and bisecting
on the supported platform. Both the project-wide LSP session and synthetic builtin-module provider
resolve through the same locator.

## Planned follow-ups

1. Differential LSP tests against the official VS Code extension using the same `slangd` binary.
2. Optional `.hlsl`/`.hlsli` ownership setting, disabled by default.
3. Add signed macOS and Linux native variants with the same manifest and protocol profile.
4. Compile and Reflection actions backed by `slangc`.
5. A non-blocking background cache for very large synthetic modules.

Full Grammar-Kit PSI and the browser-based Playground remain out of scope until a concrete IDE
feature requires them.
