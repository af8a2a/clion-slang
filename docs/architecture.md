# Architecture

## Product direction

The plugin follows a two-layer design:

```text
CLion editor
├── lightweight lexical layer
│   └── file type, fallback colors, comments, braces, quotes
└── JetBrains Native LSP client
    └── slangd
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
- `editor/`: commenter, brace matcher, and quote handler.
- `settings/`: per-project executable selection stored in the workspace file.
- `lsp/`: executable discovery, project-wide LSP descriptor, and workspace configuration mapping.
  The optional M4a extended server interface and wire model are documented in
  [Preprocessor trace protocol](preprocessor-trace-protocol.md); stock servers need no changes.
- `navigation/`: a public-LSP-API declaration bridge for Ctrl+hover, Ctrl+click, and Ctrl+B. CLion
  2026.1's implicit LSP reference provider does not issue definition requests from its Ctrl+mouse path.
- `synth/`: support code for generated builtin-module documents returned through `slang-synth://` URIs.
- `preprocessor/`: optional M4a trace consumers for [M4b branch display](preprocessor-branch-display.md),
  including debounced refresh, synchronization/freshness guards and editor-owned decorations.
  [M4c contexts](preprocessor-contexts.md) add bounded include discovery, a searchable selector,
  workspace-persisted choices and a status-bar widget. Isolated contextual compiler traces do not
  change ordinary LSP completion/navigation/diagnostics contexts.
  [M4e variants](shader-variants.md) add a strict, versioned build-context catalog, per-file variant
  selection and fingerprint gating; `cmake/SlangShaderVariants.cmake` exports explicit shader build
  data without importing host C++ flags. Only the selected variant is sent to an isolated compiler session.
  [M4d preview](branch-preview.md) adds transactional editor macro input and an in-memory, per-target
  session anchored to the resolved root/Variant and server. Stop/close/switch invalidates the session;
  preview fingerprints and session identity reject late responses without persisting overrides.

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
options are often intentionally different. M4e provides an explicit shader-data export/manifest bridge.

## Synthetic builtin modules

`slangd` can return definition URIs such as `slang-synth://core`. The descriptor maps those URIs to a
read-only virtual document whose content is produced by:

```text
slangd --print-builtin-module <module>
```

Generated content is cached for the lifetime of the project/plugin process. A future cache can include
the resolved `slangd` version as part of its persistent key.

## Planned follow-ups

1. Differential LSP tests against the official VS Code extension using the same `slangd` binary.
2. Optional `.hlsl`/`.hlsli` ownership setting, disabled by default.
3. Extend the [Windows x64 bundled runtime](bundled-slangd.md) to additional OS/architecture pairs.
4. Compile and Reflection actions backed by `slangc`.
5. A non-blocking background cache for very large synthetic modules.

Full Grammar-Kit PSI and the browser-based Playground remain out of scope until a concrete IDE
feature requires them.
