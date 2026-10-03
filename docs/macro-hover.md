# Macro expansion hover (0.8.10)

With Bundled enhanced slangd selected, hover a macro use to see its definition, an **Expansion preview**,
and a compact definition-file link. The popup uses the current theme and the Chinese label 展开预览
when the IDE language is Chinese. For example, Metallic's gHistory shows gParams.history.data, while
gSource shows resolveDescriptor(gParams.source).

The preview comes from final preprocessor tokens emitted at that call, including nested expansion,
argument substitution, variadic forwarding, token pasting (##), stringization (#), and recursive-macro
suppression. Hover uses the definition that was active at the call, so uses before and after a
redefinition can show different results. Includes, configured defines and unsaved document edits use
the existing language-server compilation context. The client formats tokens; it does not expand macros.

Empty macro bodies display an explicit empty-expansion label. When no final expansion was recorded
(for example a macro definition rather than an invocation, inactive code, or an intermediate argument
expansion), the popup says a preview is unavailable at that location. It never substitutes a different
call's arguments or presents a raw replacement body as a fully expanded result. Previews are capped at
512 tokens per invocation and show a truncation notice. Token spacing is normalized for display.

This feature requires bundled patch 0010. Official/external servers retain their original macro hover;
the plugin does not invent expansion results for servers without this information. Slang code is never
executed by this preview. Definition navigation and existing hover ranges are preserved.

## Validation

- Real-server smoke tests cover object/function/zero-argument/variadic/empty/recursive macros,
  nested expansion, paste/stringize, redefinitions, includes, configured macros, UTF-16/CRLF positions,
  unsaved edits, and the 512-token limit using a flat initializer.
- Real Metallic AutoExposure.slang gHistory and gSource hovers were checked against their expansions.
- Plugin tests cover title/preview/file layout, escaping, theme colors, localization and correlated LSP
  responses with preserved ranges. The actual CLion documentation converter is exercised.
- An installed interactive CLion popup has not been manually verified.

## 中文

选择自带增强版 slangd 后，在宏使用处悬停可查看“宏定义 + 展开预览 + 定义文件链接”。
预览来自编译器当前调用的实际展开，支持嵌套、参数代入、变参转发、拼接和字符串化。
无可用调用展开时明确提示不可用；超长展开最多显示 512 个 token，并标注截断。
