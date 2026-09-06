# M4c — Context discovery and selector

M4c adds compilation-root selection to [M4b branch display](preprocessor-branch-display.md).
It does **not** change completion, navigation, diagnostic or semantic-token contexts.

## Use

1. Apply both [server patches](../patches/slang/README.md), build `slangd`, and select it under
   **Settings | Languages & Frameworks | Slang**. The plugin ZIP does not bundle the server.
2. Open a local `.slang` or `.slangh` file. Click the **Slang** status-bar widget, or choose
   **Slang Preprocessor Context…** from the editor popup / Tools menu / Find Action.
3. Type to filter roots. Each row shows a project-relative root → include → target chain.
4. Select a root; slangd preprocesses it with its macros and the saved `slangdconfig.json`.
   Target branch dimming, active marks and labels now reflect that root.

Choices are per target, shared by splits, and persisted in project workspace settings. **Auto** uses
the only discovered includer; zero/multiple candidates or an incomplete scan use the current file.
**Current file** explicitly pins the target to itself. Auto does not guess a build target or prefer
the first candidate. A missing/no-longer-discovered pinned root is retained and reported, never
silently replaced. Choose Auto to clear it. **Choose another .slang root file…** supports excluded or
external roots and macro-generated include paths that lexical discovery cannot resolve.

The status bar reports the effective root after an accepted trace, or an unavailable/not-included/
repeated-include state. The tooltip preserves text shortened in the widget. Stock slangd produces no
branch display. M4a-only slangd retains current-file display; explicit alternative roots require M4c
and never issue context parameters that an older server would silently ignore.

## Discovery and freshness

Discovery runs off the UI thread and starts from `.slang` roots in project content, honoring IDE
exclusions. The existing lexer extracts literal quoted/angle include paths, skipping comments,
strings, malformed operands, macro operands and `import`. Reverse traversal finds direct/transitive
includers and a shortest inclusion chain, terminating on cycles. Display chains longer than 64 hops
are abbreviated without losing candidate roots. Reachable external headers are read.
Search directories use `slang.additionalSearchPaths` (with `${workspaceFolder}`), plus workspace shader
directories when `slang.searchInAllWorkspaceDirectories` is enabled, otherwise open Slang directories.
All existing path alternatives remain **potential** candidates: this is not a reproduction of compiler
search order or macro evaluation. The compiler confirms whether the target is actually included.

Discovery reads open buffers, but **only open Slang documents synchronized by Native LSP** supply
unsaved contents to the compiler. Other header languages and JSON configuration use saved contents.
Macro-expanded filenames, not-yet-generated files, build definitions, `.hlsl`/`.compute` entry roots
and shader permutations are not automatically discovered.

One lazy snapshot serves all editors in an invalidation generation. Cancellable work is bounded by
8,192 files, 100,000 path probes, 1 MiB/file and 16 MiB decoded source. Limits/read failures mark the
scan incomplete and disable automatic inference; a hard limit drops the partial graph. Explicit/
current roots still work. Neither discovery scans nor compiler waits run on the UI thread.

Changing a choice immediately clears old decorations. Target/root versions, root file/document
stamps, server identity, synchronization readiness and generation guard responses. Edits, close/open,
VFS dependency/config changes, renames/directory moves and project content-root changes invalidate
discovery. Dependency disk changes still conservatively restart the shared server for ordinary cached
LSP requests. Nonstandard dependency extensions require manual restart.

## Protocol extension

M4a `experimental.preprocessorTrace: 1` is unchanged. Context requests additionally require numeric
`experimental.preprocessorContexts: 1`. The same method accepts optional `contextUri`:

```json
{
  "textDocument": { "uri": "file:///project/Shared.slangh" },
  "contextUri": "file:///project/Main.slang"
}
```

The target must be open; the local root may be open or saved/closed. Result additions:

| Field | Meaning |
| --- | --- |
| `uri`, `version` | Target identity and open-document version |
| `contextUri` | Requested root URI; target URI when omitted |
| `contextVersion` | Open root version, or `-1` for a saved/closed root |
| `status` | `ok`, `notIncluded`, or `ambiguous` |
| `occurrenceCount` | Executed target file instances under this textual root |

`ok` requires exactly one instance. Zero (including a skipped include or a target reached only via
separately preprocessed imports) returns `notIncluded`. Repeated instances return `ambiguous`.
Non-ok states return empty arrays and are **not** treated as all-active code. `#pragma once` eliminates
a second executed instance; conventional include guards can still enter a file twice and are treated
conservatively as ambiguous. Missing/unloadable roots or closed targets return `null`. Error/timeout,
invalid data or mismatched context identity/version produces no decorations.

Context requests use an isolated workspace version with the same search paths, macros and open-file
overlay. They do not open the root, install it in ordinary LSP state, or replace/publish normal
diagnostics. The preprocessor records each executed file instance and its textual root. Ranges use
the target's physical UTF-16 positions, ignoring `#line`; indices are remapped to that one instance.
Imported module preprocessing cannot masquerade as an include inheriting the caller's macros.

## Verification and acceptance

```powershell
python scripts/slangd-preprocessor-trace-smoke.py --slangd E:/path/to/slangd.exe
python scripts/slangd-preprocessor-context-smoke.py --slangd E:/path/to/slangd.exe
.\gradlew.bat test -PslangdTestPath=E:/path/to/slangd.exe
```

M4c smoke tests require the capability and cover A/B roots, direct/transitive includes, disk edits,
unsaved roots, UTF-16/CRLF/`#line`, target-local indices, skipped includes, import isolation, repeated
instances, `#pragma once` and cache isolation. The real LSP4J test also exercises the plugin transport,
unsaved target edits, missing roots and presentation validation. Unit tests cover lexical discovery,
cycles, ambiguous paths, auto policy, persistence, negotiation and freshness alongside M4b regressions.

Local build verification uses CLion 2026.2.2's SDK. The declared baseline remains 2026.1.3 / Java 21;
the configuration verifier warns about this local override. Full IDE-fixture tests remain blocked by
the unavailable JetBrains test-framework dependency. GUI acceptance is not claimed.

Manual acceptance: create A/B roots defining `FLAG` as 1/0 and including a header with `#if FLAG`.
Choose A/B and confirm the header colors switch. Check Auto/current/manual selection, search, splits,
restart persistence, unsaved edits, root deletion/rename, stock/M4a fallback and skipped/repeated
includes. Build-target/macro-set extraction, forced branch preview and repeated-instance selection
remain future milestones; M4c is not Rider's complete shader project model.

A ready-made blue/green/current-file fixture is
`src/test/testData/slang/PreprocessorContextShared.slangh` with the adjacent
`PreprocessorContextBlue.slang` and `PreprocessorContextGreen.slang` roots.
