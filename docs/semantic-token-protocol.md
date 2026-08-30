# Semantic token protocol baseline, M2a publisher, and M3 shader taxonomy

This document freezes the M0 semantic-token contract used by the fixture and smoke tests. It
describes three profiles: the stock `slangd` behavior that must remain usable, the M2a standard
enhanced vocabulary, and the M3 Slang/HLSL-specific vocabulary negotiated by the publisher patches
in `patches/slang/`.

## Stock profile

The stock profile is the exact legend advertised by `SlangLanguageServer` 1.8 in the LSP
`initialize` response:

```text
tokenTypes     = [type, enumMember, variable, parameter, function,
                  property, namespace, keyword, macro, string]
tokenModifiers = []
full           = true
range          = false
```

Legend order is wire data: the fourth integer in each semantic-token tuple indexes this array.
The profile therefore compares both arrays exactly and in order. A server with a different legend
is not necessarily an invalid LSP server, but it does not satisfy the stock regression profile.

The corpus at `src/test/testData/slang/SemanticHighlighting.slang` has stable, mostly unique
lexemes representing the legend roles. Stock `slangd` does not necessarily emit every advertised
type for this one document, so the executable stock expectations include only tokens observed in
the baseline response. The source also contains attributes, an interface declaration, a concrete
definition, a static read-only value, builtin-library references, and writes for enhanced
publishers to classify.

`SlangLanguageServer` 1.8 also emits one synthetic `function` token over the enum's closing line.
The fixture pads that line so the stock response remains range-valid. This known anomaly is visible
in the decoded JSON output but deliberately excluded from role expectations.

## Enhanced profile

The enhanced profile is an atomic, capability-negotiated generation. Its token types retain the ten
stock names at indices 0 through 9, then append these standard refinements:

```text
class, struct, interface, enum, typeParameter, method, decorator
```

Its required modifiers are:

```text
declaration, definition, readonly, static, defaultLibrary
```

`deprecated` and `modification` are the next target modifiers. Further standard token types and
modifiers are additive, negotiated refinements. M2a deliberately uses only standard LSP names.

The M2a publisher enables this generation only when the initialize request advertises every one of
the 17 token types and all five modifiers. Missing any required name selects the stock legend,
downgrades refined types to their stock counterparts, and clears all modifier bits. This prevents
older or narrower LSP clients from receiving indices they did not declare support for.

Consumers and the executable contract treat `tokenTypesRequired` and `tokenModifiersRequired` as
sets. Expected tokens are asserted only after the advertised legend contains the names needed to
decode them.

## M2a classification rules

| Resolved AST role | Enhanced token | Modifiers |
| --- | --- | --- |
| class / struct / interface / enum declaration | `class` / `struct` / `interface` / `enum` | `definition` when it has a body, otherwise `declaration` |
| generic type parameter | `typeParameter` | `declaration` at its declaration only |
| aggregate member function | `method` | `definition` when it has a body, otherwise `declaration` |
| free function | `function` | `definition` when it has a body, otherwise `declaration` |
| aggregate field or property | `property` | `declaration` at its declaration only |
| parameter, local, or global variable | `parameter` / `variable` | `declaration` at its declaration only |
| source attribute name | `decorator` | none |
| declaration resolved to a core module or intrinsic/builtin modifier | existing role | `defaultLibrary` |
| `let` or `const`; HLSL `static` | existing role | `readonly`; `static` |

References retain the resolved token type and value modifiers but never receive `declaration` or
`definition`. `defaultLibrary` is based on the resolved declaration and its owning core module, not
identifier spelling. The emitter also verifies that a constructor declaration location contains an
actual `__init` identifier, filtering an upstream enum-witness anomaly that otherwise highlights an
enum closing brace as a function.

Stock fallback maps class, struct, interface, enum, and type parameter to `type`; method to
`function`; decorator to `type`; and every modifier bit to zero.

## M3 Slang/HLSL custom profile

M3 appends two compiler-derived token types after the complete M2a prefix:

```text
slangSemantic, slangSwizzle
```

It adds no modifier. The complete M3 legend therefore contains 19 token types and the same five
modifiers as M2a. The custom names are case-sensitive protocol identifiers and are append-only.

| Checked AST/source role | M3 token | M2a and stock fallback |
| --- | --- | --- |
| HLSL binding semantic such as `POSITION`, `SV_VertexID`, or `SV_Position` | `slangSemantic` | `enumMember` |
| Vector or matrix component selection such as the `xyz` in `value.xyz` | `slangSwizzle` | `property` |

Classification must use `HLSLSemantic` and swizzle AST nodes plus their exact source locations. It
must not infer either role from an identifier spelling: a user field named `xyz`, for example,
remains a property.

Capability negotiation has three levels. A client advertising all 19 types and five modifiers gets
the M3 legend. A client satisfying M2a but missing either custom name gets the 17-type M2a legend and
portable fallbacks. A client that does not satisfy M2a gets the exact stock 10/0 legend. Missing M3
support must never discard otherwise available M2a refinement.

Attributes, intrinsic functions, and built-in resource types remain `decorator`, `function +
defaultLibrary`, and `type + defaultLibrary` respectively. M3 does not create duplicate custom wire
types for roles already represented accurately by the standard vocabulary.

## Wire and validation rules

- The `initialize` response is authoritative. Decode token type indices and modifier bits against
  that response, never against an assumed global LSP order.
- Semantic token `data` is a sequence of five-integer tuples:
  `[deltaLine, deltaStart, length, tokenType, tokenModifiers]`. Its length must be divisible by five.
- Positions use the negotiated `positionEncoding` (`utf-16` for the baseline server). Reconstruct
  each token against the exact document version requested and reject out-of-range spans, token type
  indices, or modifier bits.
- When enhanced requirements are unavailable, retain stock semantic highlighting where compatible;
  otherwise fall back to the plugin's lexical layer. Never reinterpret an unknown numeric index.

## Machine-readable contract

`src/test/testData/lsp/semantic-tokens-contract.json` is the executable form of this document.
`schemaVersion` is an integer. Version 3 has exactly three profiles:

- `legend.tokenTypesExact` and `tokenModifiersExact` are ordered arrays for the stock profile.
- `legend.tokenTypesRequired` and `tokenModifiersRequired` are unordered required subsets for the
  enhanced and M3 profiles.
- Each `expectedTokens` item has string `text` and `type` fields. `modifiers`, when present, is an
  array of required modifier names; `forbiddenModifiers` lists names that must be absent. Optional
  `line` and `character` are zero-based LSP UTF-16 coordinates. Optional `minimumCount` is a
  positive integer and defaults to one.

Required and forbidden modifier sets must be disjoint and contained in the profile legend. Contract
readers reject unsupported `schemaVersion` values, missing profiles, malformed legends, and invalid
expectation values. The `enhanced` smoke intentionally omits M3 names to exercise the middle
fallback; the `m3` smoke advertises both custom names. Unknown fields are reserved for
forward-compatible metadata. A schema-shape change increments `schemaVersion`; changing only
fixture expectations does not.

Every smoke result records the resolved server executable path, its SHA-256 digest, and the LSP
`serverInfo` value. The digest identifies the launcher binary; reproducible CI must additionally
pin the complete Slang SDK/package because `slangd` may load compiler libraries beside it.

## Evolution and compatibility

Within one published enhanced legend generation, entries are append-only. Removing, renaming, or
reordering a token type changes numeric indices; doing so for a modifier changes bit positions.
Either operation requires a new incompatible profile/version and an explicit migration. A consumer
persists names, not numeric positions, and discards cached token data whenever the server, legend,
document version, or position encoding changes.

## CLion consumer

The plugin advertises the complete LSP 3.17 standard token vocabulary and modifiers plus the two M3
custom types, then decodes the server-provided legend by name. Stock roles, enhanced refinements,
shader semantics, and swizzles map to Slang-specific `TextAttributesKey` entries exposed under
`Editor | Color Scheme | Slang | Semantic`.

`defaultLibrary` takes precedence and selects built-in type, intrinsic, or other built-in symbol
colors. `readonly` and `static` select dedicated value/member colors. Unknown future token names
fall back to the Slang semantic identifier key, while a missing server keeps the lexical layer
unchanged.
