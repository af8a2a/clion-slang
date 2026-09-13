package dev.slang.intellij.lsp;

import com.intellij.DynamicBundle;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.colors.EditorColorsManager;
import dev.slang.intellij.highlighting.SlangSemanticColors;
import dev.slang.intellij.highlighting.SlangSyntaxHighlighter;
import dev.slang.intellij.lang.SlangLexer;
import dev.slang.intellij.lang.SlangTokenTypes;

import java.util.Locale;
import java.util.regex.Pattern;

import static dev.slang.intellij.lsp.SlangStructHoverPresentation.*;

/** Formats compiler-provided field facts from patch 0008. Never infers offsets from source text. */
final class SlangFieldHoverPresentation {
    private static final Pattern FIELD = Pattern.compile(
            "\\A```slang\\r?\\n(?:(?<access>public|private|internal) )?(?<static>static )?field\\r?\\n"
            + "(?<type>[^\\r\\n]+?) (?<name>[^\\s=]+)(?<initializer> = [^\\r\\n]+)?\\r?\\n```\\r?\\n\\r?\\n"
            + "\\(in struct `(?<owner>[^`\\r\\n]+)`\\)\\r?\\n\\r?\\n"
            + "\\*\\*Natural field layout \\(bytes\\)\\*\\*[ \\t]*\\r?\\n"
            + "Size: (?<size>`[0-9]+`|unavailable)[ \\t]*\\r?\\n"
            + "Alignment: (?<alignment>`[0-9]+`|unavailable)[ \\t]*\\r?\\n"
            + "Offset: (?<offset>`[0-9]+`|unavailable|not applicable \\(static field\\))\\r?\\n\\r?\\n");

    private SlangFieldHoverPresentation() {}

    static String format(String markdown) {
        if (!FIELD.matcher(markdown).find()) return markdown;
        boolean hasApplication = ApplicationManager.getApplication() != null;
        var scheme = hasApplication ? EditorColorsManager.getInstance().getGlobalScheme() : null;
        return format(markdown, Style.from(scheme), Style.color(scheme, SlangSyntaxHighlighter.TYPE),
                Style.color(scheme, SlangSemanticColors.PROPERTY),
                hasApplication ? DynamicBundle.getLocale() : Locale.getDefault());
    }

    static String format(String markdown, Style style, String typeColor, String fieldColor, Locale locale) {
        var field = FIELD.matcher(markdown);
        if (!field.find()) return markdown;
        boolean chinese = "zh".equals(locale.getLanguage());
        StringBuilder html = new StringBuilder("<pre>");
        if (field.group("access") != null) html.append(colored(field.group("access"), style.keyword())).append(" ");
        if (field.group("static") != null) html.append(colored("static", style.keyword())).append(" ");
        html.append(chinese ? "字段" : "field").append("\n")
                .append(typeSignature(field.group("type"), style, typeColor)).append(" <b>")
                .append(colored(field.group("name"), fieldColor)).append("</b>")
                .append(field.group("initializer") == null ? "" : typeSignature(field.group("initializer"), style, typeColor))
                .append("\n  (")
                .append(colored("struct", style.keyword())).append(" ")
                .append(colored(field.group("owner"), style.struct())).append(chinese ? " 中)" : ")")
                .append("</pre>\n\n<p title=\"Slang natural field layout (bytes); offset within declaring struct\">");
        property(html, chinese ? "大小：" : "Size: ", field.group("size"), style, chinese);
        html.append("<br/>");
        property(html, chinese ? "对齐：" : "Alignment: ", field.group("alignment"), style, chinese);
        html.append("<br/>");
        property(html, chinese ? "偏移：" : "Offset: ", field.group("offset"), style, chinese);
        html.append("</p>\n\n");
        return appendDocumentationAndLocation(html, markdown.substring(field.end()), style.text());
    }

    private static String typeSignature(String type, Style style, String typeColor) {
        SlangLexer lexer = new SlangLexer();
        lexer.start(type);
        StringBuilder html = new StringBuilder();
        while (lexer.getTokenType() != null) {
            var token = lexer.getTokenType();
            String text = type.substring(lexer.getTokenStart(), lexer.getTokenEnd());
            String color = token == SlangTokenTypes.NUMBER_LITERAL ? style.number()
                    : token == SlangTokenTypes.KEYWORD ? style.keyword()
                    : token == SlangTokenTypes.TYPE_KEYWORD || token == SlangTokenTypes.IDENTIFIER
                        || token == SlangTokenTypes.STRUCTURED_BUFFER_TYPE ? typeColor : null;
            html.append(colored(text, color));
            lexer.advance();
        }
        return html.toString();
    }

    private static void property(StringBuilder html, String label, String value, Style style, boolean chinese) {
        if (value.startsWith("`")) {
            row(html, label, value.substring(1, value.length() - 1), style.number());
        } else {
            String text = value.equals("unavailable") ? (chinese ? "不可用" : "unavailable")
                    : (chinese ? "不适用（静态字段）" : "not applicable (static field)");
            html.append(label).append(escape(text));
        }
    }
}
