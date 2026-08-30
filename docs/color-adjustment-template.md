# Metallic-oriented color adjustment template

The Slang Color Scheme preview is a calibration template derived from the first-party shader
workload under `E:\metallic\Shaders`. It is not a fixed palette. Every key inherits from the active
IDE scheme until the user overrides it, so Darcula, Light, high-contrast, and third-party themes
keep their own visual foundation.

## Workload snapshot

The design sampled 36 first-party shader files with about 15,000 lines: 27 `.slang`, four `.hlsli`,
and five shader-specific `.h` includes. Approximate syntactic occurrences explain the order used in
the preview:

| Construct | Occurrences | Template treatment |
| --- | ---: | --- |
| vector/color/matrix swizzle | 1,933 | dedicated `Swizzle` color in the main compute pass |
| resource and descriptor types | about 947 | shown as `Built-in or resource type` |
| `static const` values | 232 | shown as read-only values |
| preprocessor directives | 584 | retained in the first line and lexical settings |
| `[[vk::binding]]` | 109 | shown as shader attributes beside resources |
| HLSL binding semantics | 88 | dedicated `Binding semantic` color |
| `[shader]` entry-point attributes | 63 | shown with the compute entry point |
| `uniform` parameters | 52 | represented by the push-parameter declaration |

The main preview therefore resembles a Metallic compute pass: descriptor-backed textures, a push
struct, compile-time constants, helper intrinsics, `.xy`/`.rgb` component access,
`SV_DispatchThreadID`, and a writable output. A compact tail retains interface, class, enum,
namespace, generic, method, and static-method roles even though they are uncommon in this corpus.
Every semantic color exposed by the plugin appears at least once.

## Adjustment order

Use **Settings | Editor | Color Scheme | Slang** and tune in this order:

1. Separate **Shader | Binding semantic** from **Shader | Swizzle**. Both occur inside dense
   expressions, so they should be distinguishable without requiring bold text.
2. Separate **Types | Built-in or resource type** from user **Struct/Class/Interface** types. This
   makes descriptor declarations and user data layouts readable at a glance.
3. Make **Read-only variable** visibly different from ordinary variables. Metallic uses many
   `static const` configuration values.
4. Separate **Built-in intrinsic** from user functions and methods. Calls such as `dot`, `saturate`,
   and `lerp` are frequent inside math-heavy code.
5. Keep attributes, macros, and preprocessor directives noticeable but less dominant than dataflow
   symbols. They are structurally important and visually repetitive.

Prefer hue differences for the first four relationships and reserve font weight or italics for
personal accessibility needs. The plugin intentionally does not install explicit RGB values through
`additionalTextAttributes` or a bundled `.icls`: those overrides would only fit a few named base
schemes and can conflict with custom themes.

## Protocol boundary

The current protocol does not distinguish a global resource variable from another `variable`, nor a
shader entry point from another `function`. The labels **Variable or global resource** and
**Function or shader entry point** describe that honest shared setting. The client never guesses
roles from `g` prefixes or identifier spelling.

If separate colors become necessary, add compiler-resolved `slangResource` and `slangEntryPoint`
token types in a future protocol generation, with portable `variable` and `function` fallbacks.
