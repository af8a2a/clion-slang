# M4d — Temporary branch preview

M4d adds **Slang Branch Preview…** and **Stop Slang Branch Preview** to the Slang editor popup and
Tools menu. Preview overlays the selected [M4c root](preprocessor-contexts.md) or
[M4e Shader Variant](shader-variants.md) with temporary initial macro definitions/undefinitions.
Only the compiler-backed branch display changes; normal completion, navigation, semantic tokens,
diagnostics, builds and the saved configuration continue to use their original environments.

This is macro-based exploration, not unconditional execution of a chosen branch. It does not rewrite
`#if`, solve Boolean expressions, force literal `#if 0`, or override later source `#define` / `#undef`.
Compiler-provided built-ins are not guaranteed to be removable: undefines remove the **initial
workspace / Variant macro entries**. Textual include and repeated-instance rules remain those of M4c.

## Use

1. Build/select a server with patches **0001 → 0002 → 0003 → 0004**, as described in
   [patch instructions](../patches/slang/README.md). The plugin ZIP does not bundle this binary.
2. Enable **Show preprocessor branches** in Slang settings. Select the desired root or Shader Variant
   with **Slang Preprocessor Context…** (or click the Slang status-bar widget).
3. In the target `.slang` / `.slangh` editor, choose **Slang Branch Preview…**. The dialog shows the
   resolved context. Enter one `NAME=value` definition per line and one `NAME` per line to undefine.
   Names are case-sensitive object-like identifiers. No `-D`, `-U`, `#define`, shell command or
   function-like macro syntax is accepted. Blank lines are ignored.
4. Click **Start Preview**. The status bar begins with **PREVIEW (+N / −N macros)**, including while a
   request is pending or unavailable. Reopen the action to edit existing input and **Update Preview**.
5. Choose **Stop Slang Branch Preview** to discard all overrides and retrace the original selection.
   Closing the target file or choosing a context/Variant also stops its preview.

For example, explore SHaRC Query on top of a SHaRC Update Variant:

```text
Define:
SHARC_QUERY=1

Undefine:
SHARC_UPDATE
NRC_UPDATE
NRC_QUERY
```

`FLAG` or `FLAG=` is an **empty replacement**, not `FLAG=1`; use `FLAG=1` for numeric conditions.
Values preserve spaces, quotes, semicolons and subsequent `=` literally. For example,
`LABEL="a=b; c"` keeps those quotes in the replacement. Duplicate names and define/undefine conflicts
are rejected. There are at most 256 combined overrides, names are 1–128 ASCII identifier characters,
and values are single-line, at most 4096 UTF-8 bytes, with no NUL/CR/LF. Empty UI input is rejected;
use Stop to finish. No source file, manifest or workspace macro setting is edited.

**Cancel / Escape / closing the input dialog** discards the proposed edits; it does not start a new
preview or stop an already active one. **Start/Update** closes that input dialog and leaves the
explicitly marked preview session active. **Stop Slang Branch Preview** ends that session.

## Lifetime and persistence boundary

The project context service owns an in-memory `SlangPreviewState`, keyed by target file; splits share
one immutable preview session but keep separate decorations. There is no preview field in
`SlangProjectSettings`, no `PersistentStateComponent`, no remembered input, and no global LSP
configuration update. Existing root paths, Variant IDs and the manifest path retain their M4c/M4e
persistence behavior unchanged.

| Event | Result |
| --- | --- |
| Stop, or select any root/Auto/Variant (even the same choice) | Discard that target's overrides; retrace selected baseline |
| Close the last split of the target, or its open root | Discard the affected session; reopening uses no overrides |
| Close one split while another remains | Keep the shared session |
| Switch editor tabs; edit an open Slang source; change colors | Keep input and request a fresh trace when needed |
| Resolved Auto root, selection, or selected Variant fingerprint changes | Discard old preview; never transfer it to the new baseline |
| Manifest VFS change/deletion/move/rename | Conservatively discard all previews; reload without restarting slangd |
| Apply Slang settings; disable branch display | Discard all previews |
| Server stop/restart, dependency-triggered restart or project roots change | Discard all previews |
| Project/plugin disposal or IDE restart | No preview survives |

Retaining input across an edit assumes the resolved baseline does not change. Dependency/configuration
VFS events can trigger the existing conservative server restart and therefore end previews. A timeout,
missing root, `notIncluded`, `ambiguous` or rejected request clears decorations, not the saved context;
the user can edit/stop preview or retry on the next refresh. No invalid response is interpreted as
“all active.” Normal syntax and unrelated editor decorations remain intact.

## Protocol

Numeric `experimental.preprocessorPreview: 1` is required **in addition to** `preprocessorTrace: 1`
and `preprocessorContexts: 1`. Variant previews additionally require `preprocessorVariants: 1`.
Stock and older M4a/M4c/M4e servers do not receive preview parameters; the action is disabled with a
capability explanation. Existing requests without preview keep their behavior.

The same `slang/textDocument/preprocessorTrace` request accepts optional `preview`:

```json
{
  "textDocument": {"uri": "file:///project/Shared.slangh"},
  "contextUri": "file:///project/Entry.slang",
  "preview": {
    "version": 1,
    "fingerprint": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
    "defines": [{"name": "MODE", "value": "2"}],
    "undefines": ["DISABLED_FEATURE"]
  }
}
```

When a Variant is selected, its **unchanged** `buildContext` is included alongside `preview`.
Preview does not carry paths, target/profile, inheritance or entry-point options. `contextUri` may
be omitted for a current-file preview; this still creates an isolated session. The target must be
open; the root may be open/unsaved or saved/closed.

Precedence in the initial environment is **workspace (if inherited) → Variant → preview**, followed
by normal source preprocessing. Preview undefines remove workspace and Variant definitions; preview
defines can reintroduce names undefined by a Variant. Include paths, target/profile, filesystem
overlay and baseline inheritance policy are untouched. The isolated workspace is never installed in
ordinary LSP module/completion/diagnostics caches. There is no server-side preview session, stop RPC,
or recovery RPC: omitting `preview` is sufficient to restore the baseline, even after an error.

Results add `previewFingerprint`, echoing the input fingerprint (empty without preview), independently
of the M4e `contextFingerprint`. Fingerprints must be 64 lowercase hexadecimal characters; the client
computes SHA-256 over canonical sorted macro inputs. They are correlation identities, not security
attestations. All four preview object fields are required; unknown fields and malformed shapes use
the existing JSON-RPC argument-error path. Following upstream Slang marshalling, null macro lists are
normalized to empty lists and `preview: null` is treated as absent. Empty wire lists are a valid no-op.
Unsupported versions, invalid fingerprints, conflicting/invalid macros or limits return
`status: "invalidContext"`, `contextError`, both identities, and empty directive/region arrays.

Acceptance requires both identities, target/root versions and stamps, server identity, synchronization
barrier and request generation, plus the **exact current preview session object**. Stopping and
restarting identical input therefore rejects an old reply. Background work from an obsolete session
cannot clear a newer session. Edits/Stop/switch immediately dispose owned overlays and cancel pending
requests; late responses cannot put old preview coloring back.

## Regression and manual acceptance

```powershell
python scripts/slangd-branch-preview-smoke.py --slangd E:/path/to/patched/slangd.exe
python scripts/slangd-preprocessor-trace-smoke.py --slangd E:/path/to/patched/slangd.exe
python scripts/slangd-preprocessor-context-smoke.py --slangd E:/path/to/patched/slangd.exe
python scripts/slangd-shader-variants-smoke.py --slangd E:/path/to/patched/slangd.exe
.\gradlew.bat test -PslangdTestPath=E:/path/to/patched/slangd.exe
```

`SlangMacroPreviewTest` covers input validation, literal values, byte/count limits and deterministic
identity. `SlangPreviewStateTest` covers lifecycle, split retention, obsolete-task/response rejection,
root/Variant/server changes and settings serialization. `SlangPreprocessorTraceTest` covers capability
gating, old wire compatibility and independent preview identity. `SlangBranchProtocolTest` uses the
plugin's LSP4J interface/stream wrappers and verifies compiler results become the expected ranges.
The M4d Python suite also checks real workspace/Variant precedence, source authority, UTF-16/CRLF/
`#line`, invalid inputs, skipped/repeated includes, unsaved roots and exact restoration/isolation.

Manual CLion acceptance remains necessary: select the blue Variant from `slang-variants.example.json`,
preview `MODE=2`, check the inactive body changes, then Stop and confirm blue returns. Test Cancel while
editing, rapid update/Stop/switch with a request pending, closing one/all splits, closing the root,
manifest edits, source edits, server restart, disabling display, IDE restart, and an older server.
Confirm workspace XML, JSON manifest, sources and normal diagnostics are not modified by preview.

The local build uses installed CLion 2026.2.2 / JBR 25 with the existing declared 2026.1.3 / Java 21
baseline; that older baseline is not newly verified. Full IDE-fixture execution remains subject to
the unavailable JetBrains test-framework dependency. Passing headless/stdio checks is not GUI acceptance.
