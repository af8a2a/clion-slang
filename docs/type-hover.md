# Type alias hover

Patch `0006-type-alias-hover.patch` adds Rider-inspired type details to the standard
`textDocument/hover` response. The existing CLion native LSP popup displays the result;
no new plugin extension, global color scheme, custom protocol or persisted setting is needed.

For example, hovering `uint2` shows a `typedef` heading and `vector<uint, 2> uint2`,
followed by **Built-in type alias**, element type `uint`, and component count `2`.
`float3x4` expands to `matrix<float, 3, 4>` with separate row and column counts.
Slang's own spelling (`uint`, not C++ `unsigned int`) is retained.

The compiler's resolved declaration and substituted type supply the information.
User aliases receive a **Type alias** label, even when named `uint2`; aliases to
non-vector/matrix types still show their underlying type. Existing documentation,
definition location and hover range are retained. Non-alias hover is unchanged.
This is not a memory-layout report: matrix storage order, stride and byte size are
not inferred from its dimensions. The IDE controls popup fonts, colors and chrome;
this does not reproduce Rider's proprietary popup pixel for pixel.

## Enable

Apply patch 0006 after patches 0001–0005 using the [server build instructions](../patches/slang/README.md).
Select the rebuilt `slangd` in the plugin's existing language-server settings, then
restart the language server (or CLion). Keep its matching compiler DLL next to the
executable. The plugin does not bundle this server. Unpatched official servers keep
their existing hover behavior; no upgrade of the plugin ZIP is needed for this change.

```powershell
python scripts/slangd-type-hover-smoke.py --slangd .slang-m4a-build/RelWithDebInfo/bin/slangd.exe
```

The real-server regression covers built-in vector/matrix aliases, user aliases,
qualified shadowing, documentation, hover ranges and function fallback. Native IDE
popup visual acceptance still requires a manual hover check in CLion.

## 中文说明

### Hover syntax highlighting (plugin 0.7.3)

The client labels slangd's leading unlabelled Markdown signature fence as `slang`.
CLion's native documentation renderer then uses the registered Slang syntax highlighter
and active editor color scheme. This works with stock servers as well as patch 0006.
Install plugin 0.7.3 for this client-side enhancement; the alias expansion described
above still requires the patched server independently.

Explicit fence languages, plaintext responses, documentation prose and subsequent
example fences are not rewritten. Only responses correlated to `textDocument/hover`
are adapted; hover ranges and UTF-8 frame lengths are preserved. This is lexical
signature highlighting, not a second semantic-token request for the popup. It does
not change C++ colors or force the IDE's documentation highlighting preferences.

插件 0.7.3 会为 slangd 悬停签名中缺失的语言标签补上 `slang`，由 CLion 原生渲染器按当前
Slang 配色进行词法高亮。该功能不需要更新 slangd；类型别名展开仍单独依赖补丁 0006。
正文和示例保持原样，不修改 C++ 配色。安装新插件并重启后，可悬停检查 `typedef`、`float4`
和泛型中的数字是否分别着色；若 IDE 关闭了文档签名高亮，需要在 IDE 中重新启用。

此增强基于 slangd 已解析的类型声明，为向量/矩阵别名展示展开签名、元素类型、
分量数或行列数，并区分内建类型与用户别名。用户自定义的同名 `uint2` 不会误标为内建类型。
保留原有文档和定义位置，不推测矩阵内存布局，也不修改 C++ 配色。

应用第六个补丁并重新构建 slangd，在插件设置中选用该服务器后重启语言服务即可。
本次不需要更新插件 ZIP；官方未打补丁的 slangd 保持原有悬停信息。

## Struct types

Patch 0007 adds [struct hover details](struct-hover.md), including function parameter type
references, namespace, natural layout and compact definition links. Alias behavior above is retained.
