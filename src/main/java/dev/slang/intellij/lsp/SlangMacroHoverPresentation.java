package dev.slang.intellij.lsp;

import com.intellij.DynamicBundle;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.psi.TokenType;
import dev.slang.intellij.highlighting.SlangSemanticColors;
import dev.slang.intellij.highlighting.SlangSyntaxHighlighter;
import dev.slang.intellij.lang.SlangLexer;
import dev.slang.intellij.lang.SlangTokenTypes;

import java.util.Locale;
import java.util.regex.Pattern;
import static dev.slang.intellij.lsp.SlangStructHoverPresentation.*;

/** Presents actual compiler expansion tokens; never expands macros in the client. */
final class SlangMacroHoverPresentation {
    private static final Pattern HEADER = Pattern.compile(
            "\\A```slang\\r?\\n#define ([A-Za-z_][A-Za-z_0-9]*)(.*?)\\r?\\n```\\r?\\n\\r?\\n"
            + "<!-- slang-macro-hover:1 -->\\r?\\n\\r?\\n", Pattern.DOTALL);
    private static final Pattern PREVIEW = Pattern.compile(
            "\\*\\*Expansion preview\\*\\*\\r?\\n\\r?\\n```slang\\r?\\n(.*?)\\r?\\n```\\r?\\n\\r?\\n", Pattern.DOTALL);

    private SlangMacroHoverPresentation() {}

    static String format(String markdown) {
        if (!HEADER.matcher(markdown).find()) return markdown;
        boolean running = ApplicationManager.getApplication() != null;
        var scheme = running ? EditorColorsManager.getInstance().getGlobalScheme() : null;
        return format(markdown, Style.from(scheme), Style.color(scheme, SlangSemanticColors.MACRO),
                Style.color(scheme, SlangSyntaxHighlighter.STRING),
                running ? DynamicBundle.getLocale() : Locale.getDefault());
    }

    static String format(String markdown, Style style, String macroColor, String stringColor, Locale locale) {
        var header = HEADER.matcher(markdown);
        if (!header.find()) return markdown;
        boolean chinese = "zh".equals(locale.getLanguage());
        StringBuilder html = new StringBuilder("<pre>");
        html.append(chinese ? "宏" : "macro").append("\n<b>")
                .append(colored(header.group(1), macroColor)).append("</b>");
        String tail = header.group(2);
        if (!tail.isEmpty()) html.append(tail.startsWith("(") ? "" : " ").append(code(tail, style, stringColor));
        html.append("</pre>\n\n");
        var preview = PREVIEW.matcher(markdown).region(header.end(), markdown.length());
        String remainder;
        if (preview.lookingAt()) {
            html.append("<h3 style=\"font-size:120%;\">").append(chinese ? "展开预览" : "Expansion preview").append("</h3>\n<pre>");
            String expanded = preview.group(1);
            html.append(expanded.isBlank() ? (chinese ? "（空展开）" : "(empty expansion)") : code(expanded, style, stringColor));
            html.append("</pre>\n\n");
            remainder = markdown.substring(preview.end());
        } else {
            remainder = markdown.substring(header.end());
        }
        if (chinese) remainder = remainder.replace("Expansion preview unavailable at this location.", "此位置无可用的实际展开预览，请在宏调用处查看。")
                .replace("Expansion preview truncated after 512 tokens.", "展开预览已截断（最多 512 个 token）。");
        return appendDocumentationAndLocation(html, remainder, style.text());
    }

    private static String code(String source, Style style, String stringColor) {
        SlangLexer lexer = new SlangLexer(); lexer.start(source);
        StringBuilder html = new StringBuilder(); String previous = "";
        while (lexer.getTokenType() != null) {
            var type = lexer.getTokenType();
            String text = source.substring(lexer.getTokenStart(), lexer.getTokenEnd());
            if (type != TokenType.WHITE_SPACE) {
                boolean tight = previous.isEmpty() || text.equals(".") || previous.equals(".")
                        || text.equals("::") || previous.equals("::") || text.equals(",")
                        || text.equals(")") || text.equals("]") || text.equals(";")
                        || previous.equals("(") || previous.equals("[")
                        || text.equals("(") && previous.matches("[A-Za-z_][A-Za-z_0-9]*") || text.equals("[");
                if (!tight) html.append(" ");
                String color = type == SlangTokenTypes.NUMBER_LITERAL ? style.number()
                        : type == SlangTokenTypes.KEYWORD || type == SlangTokenTypes.TYPE_KEYWORD ? style.keyword()
                        : type == SlangTokenTypes.STRING_LITERAL || type == SlangTokenTypes.CHARACTER_LITERAL ? stringColor
                        : style.text();
                html.append(colored(text, color)); previous = text;
            }
            lexer.advance();
        }
        return html.toString();
    }
}
