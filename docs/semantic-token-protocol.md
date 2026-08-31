# Semantic token protocol baseline

This document freezes the M0 semantic-token contract used by the fixture and smoke tests. It
describes two profiles: the `slangd` behavior that must remain usable today and the enhanced
vocabulary that a future publisher can negotiate.

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

The enhanced profile is a capability target, not a second hard-coded legend. Its required token
types are the ten stock names plus these standard refinements:

```text
class, struct, interface, enum, typeParameter, method, decorator
```

Its required modifiers are:

```text
declaration, definition, readonly, static, defaultLibrary
```

`deprecated` and `modification` are the next target modifiers. Further standard token types and
modifiers are additive, negotiated refinements. Shader binding semantics and swizzles are reserved
for a later custom/M3 profile; M0 must not invent non-standard token names for them.

Consumers treat `tokenTypesRequired` and `tokenModifiersRequired` as sets. The publisher chooses
their wire order and may advertise additional LSP-standard entries. Expected tokens are asserted
only after the advertised legend contains the names needed to decode them.

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
`schemaVersion` is an integer. Version 1 has exactly two profiles:

- `legend.tokenTypesExact` and `tokenModifiersExact` are ordered arrays for the stock profile.
- `legend.tokenTypesRequired` and `tokenModifiersRequired` are unordered required subsets for the
  enhanced profile.
- Each `expectedTokens` item has string `text` and `type` fields. `modifiers`, when present, is an
  array of required modifier names. Optional `line` is a zero-based LSP line number. Optional
  `minimumCount` is a positive integer and defaults to one.

Contract readers reject unsupported `schemaVersion` values, missing profiles, malformed legends,
and invalid expectation values. Unknown fields are reserved for forward-compatible metadata. A
schema-shape change increments `schemaVersion`; changing only fixture expectations does not.

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

The plugin advertises the complete LSP 3.17 standard token vocabulary and modifiers, then decodes
the server-provided legend by name. Stock roles and enhanced refinements map to Slang-specific
`TextAttributesKey` entries exposed under `Editor | Color Scheme | Slang | Semantic`.

`defaultLibrary` takes precedence and selects built-in type, intrinsic, or other built-in symbol
colors. `readonly` and `static` select dedicated value/member colors. Unknown future token names
fall back to the Slang semantic identifier key, while a missing server keeps the lexical layer
unchanged.
