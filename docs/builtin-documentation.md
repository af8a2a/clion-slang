# Builtin documentation references (0.8.7)

Hovering a resolved standard-library global function or constant now shows a compact external
reference below its signature/documentation, with an inline code symbol name and an external-link
arrow, for example `abs` on Microsoft Learn ↗. The link opens the symbol's official reference page.
Existing signatures, documentation, derivative details and hover ranges remain intact.

- HLSL intrinsics with a single reference page link to Microsoft Learn.
- Slang-only functions, wave/ray builtins and constants such as `detach`, `WaveGetLaneIndex` and
  `RAY_FLAG_NONE` link to Slang Documentation where the HLSL index has no entry.
- Overload families with separate Microsoft pages (for example `asuint`) use Slang's overload-group
  page, so a link never selects an arbitrary overload.
- The offline catalog contains 391 names from the official indexes. Unknown names remain unchanged.
  Hover rendering performs no network requests; only opening a reference needs connectivity.
- The bundled server's patch 0009 marks resolved global declarations in the synthetic `core` module.
  Namespace declarations, struct members, user overloads and local variables do not receive markers.
  External/older servers work only when they expose a missing absolute synthetic `core` source path;
  relative or ambiguous origins remain unchanged. Real user files named `core` are excluded.
- This covers standard-library global functions and constants. Resource member methods and user
  variables with `SV_*` semantics are not matched merely by their spelling.

## Catalog maintenance

Run `python scripts/update-builtin-documentation.py` to refresh
`src/main/resources/documentation/builtins.json`. This is a maintainer operation, not part of builds.
Only names and actual target links are stored, not copies of documentation text. Review the resulting
catalog before release. Source indexes:

- [HLSL intrinsic functions](https://learn.microsoft.com/en-us/windows/win32/direct3dhlsl/dx-graphics-hlsl-intrinsic-functions)
- [Slang global declarations](https://docs.shader-slang.org/en/latest/external/core-module-reference/global-decls/index.html)

## Validation

`slangd-builtin-documentation-smoke.py` checks real `.slang` and `.hlsl` responses for functions,
constants, generic overloads, namespace/global/local shadowing and ranges. It is included in runtime
staging. `SlangBuiltinDocumentationTest` checks the captured responses, native CLion Markdown
conversion, link targets, user file exclusion, idempotence, UTF-8 framing and request correlation.
Interactive installed CLion popup validation remains a separate manual check.

## 中文

内建函数与常量的 hover 增加官方文档链接，采用“符号名 + 文档站点 + ↗”样式。
优先使用 Microsoft Learn；Slang 特有符号及常量使用 Slang 官方参考。
391 个名称的链接目录随插件打包，悬停不联网。同名用户函数、常量和局部变量不会误加链接。
安装新版本并使用自带增强版 slangd 可获得可靠的语义来源识别。
