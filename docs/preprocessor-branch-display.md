# M4b — Client branch display

M4b consumes the [M4a compiler trace](preprocessor-trace-protocol.md) in the editor:

- Inactive **body ranges** are dimmed, including comments, blank lines and skipped includes/macros.
- Active `if` / `ifdef` / `ifndef` / `elif` / `else` keywords are underlined. Conditional directive
  lines keep their ordinary syntax colors; `endif` is not an active branch.
- Read-only, after-line-end labels show the previous branch at `elif` / `else` / `endif`, for example
  `#if BLUE`, `#elif GREEN`, or `#elif RED #else`. Labels never change the document or evaluate macros.

With an M4a-only server, the context is **the current file as its own compilation root**, with
configured macros/search paths. [M4c](preprocessor-contexts.md) now adds includer discovery and root
selection under an additional capability. Forced branches and shader permutations remain out of scope.

## Enable and configure

1. Build/select a `slangd` with the [standalone M4a patch](../patches/slang/README.md).
2. Install the plugin ZIP and select that executable under **Settings | Languages & Frameworks | Slang**.
3. Leave **Show preprocessor branches** enabled. The independent **Show branch source labels** option
   hides only the inline labels, retaining dimming and active-branch marks.
4. Adjust **Inactive code**, **Active branch**, and **Source label** under
   **Settings | Editor | Color Scheme | Slang | Preprocessor branches**.

Stock `slangd` remains supported: if `experimental.preprocessorTrace` is not numeric version 1,
no custom trace requests or decorations are produced. The plugin ZIP does not bundle the patched
server. The feature applies to ordinary local `.slang` / `.slangh` editors, including splits; diff,
preview, console and synthetic built-in documents are excluded.

## Refresh and ownership

`SlangBranchDisplayService` is a project service, initialized lazily on the first Slang file. It
shares the Native LSP session and does not start a second process. A 350 ms debounce coalesces edits;
each request runs off the UI thread with a 5 s timeout. Superseded requests are cancelled.

Every invalidation clears only this feature's editor-local highlighters/inlays. Semantic tokens,
diagnostics, selection colors and unrelated inlays are not removed. The overlay layer is above
ordinary semantic coloring and below warning/error/selection layers. Each split owns its own
decorations, so closing one split does not dispose another's objects.

Responses must match server identity, URI, Native LSP document version, modification stamp and the
project request generation. A root version alone is not enough when an included document changes.
Edits clear old decorations immediately; a synchronization barrier waits for Native LSP edit events
for all pending open Slang documents. Native `fileEdited` precedes the incremental notification, so
acknowledgments are **always deferred** until after the document listener cycle, even on the UI
thread. Older acknowledgments cannot release newer edits.

Closing editors, disabling display, changing colors, restarting/stopping the server or disposing the
project clears/cancels the corresponding state. `null`, timeout, error, invalid ranges, malformed
branch links and unknown protocol versions leave ordinary highlighting visible, not an invented
“all active” result. Transient failures retry on the next relevant editor/server/refresh event.

### Dependency/configuration changes

Open Slang document edits use Native LSP `didChange`. Saving an already-synchronized open Slang
document does not restart the server. Saved `slangdconfig.json` changes and VFS changes to other
shader/header dependencies trigger a debounced Slang server restart, followed by fresh requests:
the M4a server has no watched-file cache invalidation API. This intentionally trades a short server
restart for avoiding stale compiler data. Unsaved JSON configuration is not applied.

Without a dependency graph, disk invalidation is conservative across VFS shader/header events
(`slang`, `slangh`, `hlsl`, `hlsli`, `h`, `hpp`, `inc`), including external search paths. Large generated
shader trees may cause extra restarts. M4c additionally handles directory moves and content-root
changes. Nonstandard include extensions still require a manual language-server restart.

## Tests and manual acceptance

Unit tests cover source labels, nested/unevaluated branches, UTF-16 ranges, invalid/cyclic/overlapping
data rejection, empty bodies, label limits, stale response gating, synchronization callback ordering,
settings persistence and per-split decoration ownership. Payloads exceeding 8,192 directives plus
inactive regions are rejected to bound editor work.

`SlangBranchProtocolTest` additionally launches a real patched server through the **same protocol
stream wrappers and LSP4J extension interface as the plugin**, then checks that macro edits switch
the projected inactive region and that closing the document returns `null`. Opt in with:

```powershell
.\gradlew.bat test -PslangdTestPath='E:\path\to\patched\slangd.exe'
```

Without that property, the real-process test is skipped. The standalone Python M4a suite remains
available for broader server-only protocol coverage.

GUI acceptance checklist (requires a running CLion installation):

1. Open `src/test/testData/slang/PreprocessorBranches.slang` with patched slangd. `GREEN` is active;
   blue/red/fallback bodies are dimmed and branch source labels are visible.
2. Change `GREEN` to 0 and `RED` to 1; confirm old marks clear and the red branch becomes active.
3. Edit an included macro, and separately save a changed `slangdconfig.json`; confirm the root updates.
4. Split the editor, close one split, and switch light/dark color schemes. Disable labels, then the
   entire feature; confirm only the requested decorations disappear.
5. Restart the language server or select stock slangd; confirm no stale dimming/labels remain.

The local build was checked with CLion 2026.2.2's SDK. Full IDE-fixture tests are blocked because the
official JetBrains test-framework download resolves to an unavailable CloudFront endpoint. GUI
acceptance was not performed in this session. Neither should be inferred from passing headless
unit/stdio tests.
