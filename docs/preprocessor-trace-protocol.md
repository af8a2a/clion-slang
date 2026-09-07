# M4a — Preprocessor trace baseline

The optional [M4d preview extension](branch-preview.md) adds request-local macro overrides and a
separate preview fingerprint on top of M4c roots or M4e build contexts; baseline requests are unchanged.

The optional [M4c context extension](preprocessor-contexts.md) adds `contextUri` and per-instance
include tracing behind a separate capability. Requests without it retain the baseline below.

The baseline described here is now consumed by [M4b client branch display](preprocessor-branch-display.md).
Statements about absent automatic requests/UI below describe the original M4a milestone.

M4a adds a compiler-backed, versioned protocol for conditional branches. It does **not** yet
add Rider-style context selection, inactive-code highlighting, branch switching or branch-label
inlays. The separate `#include` / include-path color setting is lexical and works with stock
`slangd`, or without a language server.

## Implementation and scope

- Server patch: `patches/slang/0001-m4a-preprocessor-trace.patch`, against upstream Slang
  `5f9227cf6e5055b6a9ee742fdd729aab9162cf25`.
- Client: `SlangLanguageServer` and `SlangPreprocessorTrace`. The descriptor registers the extended
  LSP4J interface; no extra requests are sent automatically in M4a.
- Data comes from Slang's actual preprocessor state machine, not a Java expression evaluator.
- The requested **opened document is the root translation unit**. Its configured macros and search
  paths apply; macros from textual includes can affect its branches. Trace records from included
  files and imported modules are filtered out of the response.
- Opening a header directly evaluates it as its own root. This is not the context of a file that
  includes it. Reverse-include discovery and multiple shader permutations are later milestones.
- The plugin continues to use the selected/existing external `slangd`. M4a does not bundle a binary
  or restore the removed M2/M3 patch stack.

## Capability and request

The patched server advertises this in `initialize.result.capabilities`:

```json
{"experimental":{"preprocessorTrace":1}}
```

Clients must check for numeric protocol version `1` before requesting. Missing, boolean, string,
or unknown versions are unsupported; retain standard LSP behavior. The Java helper
`SlangPreprocessorTrace.isSupported` handles both Gson objects and map-shaped capabilities.

```json
{
  "jsonrpc":"2.0",
  "id":42,
  "method":"slang/textDocument/preprocessorTrace",
  "params":{"textDocument":{"uri":"file:///project/Shader.slang"}}
}
```

The request is queued alongside ordinary language-server requests and uses the current workspace
snapshot. It returns a `PreprocessorTrace` object or JSON `null` if the document is closed or no
module can be loaded. An internal exception returns JSON-RPC `InternalError`. An unavailable trace
must not be interpreted as “all branches active.” Syntax errors may prevent a usable module/trace;
M4a does not promise partial results for malformed conditionals.

## Result contract

| Field | Meaning |
| --- | --- |
| `uri` | Requested document URI. |
| `version` | Latest applied `didOpen` / `didChange` document version. |
| `directives` | Conditional directives in source order, for this root document only. |
| `inactiveRegions` | Nonempty, nonoverlapping inactive body ranges with their controlling directive. |

Each directive contains:

| Field | Meaning |
| --- | --- |
| `kind` | `if`, `ifdef`, `ifndef`, `elif`, `else`, or `endif`. |
| `range` | Entire logical directive, from column 0 of its first physical line to the end of its last physical line, excluding the terminal newline. Includes indentation, continuations and trailing comments. |
| `keywordRange` | Just the keyword (`if`, etc.), excluding `#` and whitespace. |
| `evaluated` | The condition participates in branch selection. False for a skipped conditional, an already-bypassed `elif`, `else`, and `endif`. |
| `value` | Boolean condition result; meaningful only when `evaluated` is true, otherwise false. |
| `active` | Whether the branch body following this directive is active after considering enclosing conditionals. Always false for `endif`. |
| `depth` | Nesting level; outermost is 0. |
| `parentDirective` | Current branch directive of the enclosing conditional, or -1. |
| `matchingIfDirective` | Opening `if` / `ifdef` / `ifndef` index, including self for an opening directive. |
| `previousBranchDirective` | Previous sibling branch index; -1 for openings. For `endif`, the last branch of the closed group. |

All indices refer to the returned `directives` array, never the compiler's internal arrays. They
are snapshot-local: do not persist them across edits. In a skipped outer branch, an inner condition
has `evaluated=false`, `value=false`, `active=false`, even if its expression would otherwise be true.
`ifdef` / `ifndef` may internally look up a name while skipping, but do not report that unused value.

Each inactive region has `range` and `controllingDirective`. The latter points to the innermost
inactive branch. Regions start at column 0 of the first body line and stop at column 0 of the next
conditional directive's line (or at EOF). They include comments, blank lines and skipped
nonconditional directives such as `define` and `include`. Conditional directive lines themselves
are excluded and have their own records. Adjacent conditional directives do not produce empty
regions. Separate segments need not be merged by the client.

Positions use **zero-based lines and UTF-16 code units**, with exclusive ends. LF and CRLF are
supported. Physical source positions are used even after `#line`; virtual line numbers and virtual
filenames never become editor ranges. The smoke client sends literal UTF-8 JSON, like LSP4J/Gson;
upstream Slang's handling of JSON-escaped surrogate pairs is outside this patch.

For `#if 0\n// disabled\n#endif`, the inactive range is `[1:0, 2:0)` and its controlling directive
is 0. The closing directive points to opening directive 0.

## Freshness and future UI consumers

A consumer must discard results for a different URI or document version, and clear decorations
on `null`, errors, close, server restart or unsupported capability. Includes and configuration can
change branch decisions **without changing this document's version**. Re-request on those changes
and use a client-side request generation counter to reject obsolete responses. M4a has no persistent
cache, context ID, dependency epoch, partial results or delta protocol.

Next steps are a refresh/invalidation service and inactive-region renderer, followed by explicit
root-context selection and macro permutations. Those will need a context identity covering the
root URI, configuration and dependency generation; document version alone is insufficient.

## Validation

Run the real-server suite with Python 3 (standard library only):

```powershell
python scripts/slangd-preprocessor-trace-smoke.py --slangd <path-to-patched-slangd.exe>
```

It covers capability negotiation, all six directives, nested/unevaluated branches, index remapping,
complete inactive body ranges, comments, empty bodies, LF/CRLF, multiline conditions, UTF-16,
include-defined macros, filtering of included files, `#line`, repeat requests, multiple-document
isolation, incremental edits, versions and document close. Java unit tests cover capability gating,
JSON round trips and the extension method/parameter shape.
