package dev.slang.intellij.lsp;

import com.intellij.DynamicBundle;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.openapi.editor.colors.EditorColorsScheme;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import dev.slang.intellij.highlighting.SlangSemanticColors;
import dev.slang.intellij.highlighting.SlangSyntaxHighlighter;

import java.awt.Color;
import java.net.URI;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Presentation of the exact struct-hover format emitted by patch 0007; no type/layout inference. */
final class SlangStructHoverPresentation {
    private static final Pattern HEADER = Pattern.compile(
            "\\A```slang\\r?\\nstruct (?<type>[^\\r\\n]+)\\r?\\n```\\r?\\n\\r?\\n"
            + "(?:\\(namespace `(?<namespace>[^`\\r\\n]+)`\\)\\r?\\n\\r?\\n)?");
    private static final Pattern LAYOUT = Pattern.compile(
            "\\*\\*Natural layout \\(bytes\\)\\*\\*[ \\t]*\\r?\\n"
            + "Size: `(?<size>[0-9]+)`[ \\t]*\\r?\\n"
            + "Alignment: `(?<alignment>[0-9]+)`[ \\t]*\\r?\\n"
            + "Padding: `(?<padding>[0-9]+)`\\r?\\n\\r?\\n"
            + "(?:Array stride: `(?<stride>[0-9]+)`\\r?\\n\\r?\\n)?");
    private static final Pattern UNAVAILABLE = Pattern.compile("Layout unavailable for this type\\.\\r?\\n\\r?\\n");
    private static final Pattern FOOTER = Pattern.compile(
            "\\r?\\n---\\r?\\n\\r?\\n\\[(?:\\\\.|[^\\r\\n])*\\]\\(<(?<uri>file:[^<>\\r\\n]+)>\\)\\s*\\z");

    private SlangStructHoverPresentation() {}

    static String format(String markdown) {
        Parsed parsed = parse(markdown);
        if (parsed == null) return markdown;
        boolean hasApplication = ApplicationManager.getApplication() != null;
        EditorColorsScheme scheme = hasApplication ? EditorColorsManager.getInstance().getGlobalScheme() : null;
        Locale locale = hasApplication ? DynamicBundle.getLocale() : Locale.getDefault();
        return render(parsed, Style.from(scheme), locale);
    }

    static String format(String markdown, Style style, Locale locale) {
        Parsed parsed = parse(markdown);
        return parsed == null ? markdown : render(parsed, style, locale);
    }

    private static Parsed parse(String markdown) {
        Matcher header = HEADER.matcher(markdown);
        if (!header.find()) return null;
        Matcher layout = LAYOUT.matcher(markdown).region(header.end(), markdown.length());
        if (layout.lookingAt()) {
            return new Parsed(header.group("type"), header.group("namespace"), layout.group("size"),
                    layout.group("alignment"), layout.group("padding"), layout.group("stride"), markdown.substring(layout.end()));
        }
        Matcher unavailable = UNAVAILABLE.matcher(markdown).region(header.end(), markdown.length());
        return unavailable.lookingAt() ? new Parsed(header.group("type"), header.group("namespace"),
                null, null, null, null, markdown.substring(unavailable.end())) : null;
    }

    private static String render(Parsed parsed, Style style, Locale locale) {
        boolean chinese = "zh".equals(locale.getLanguage());
        // Raw pre/br markup survives CLion's doc Markdown conversion. Markdown hard breaks do not.
        // Avoid a leading fence, which CLion would split into a separately bordered definition block.
        StringBuilder html = new StringBuilder("<pre>");
        html.append(colored("struct", style.keyword())).append(" ")
                .append("<b>").append(colored(parsed.type(), style.struct())).append("</b>");
        if (parsed.namespace() != null) {
            html.append("\n  (").append(colored("namespace", style.keyword())).append(" ")
                    .append(colored(parsed.namespace(), style.namespace())).append(chinese ? " 中)" : ")");
        }
        html.append("</pre>\n\n<p title=\"Slang natural layout (bytes)\">");
        if (parsed.size() == null) {
            html.append(chinese ? "此类型的布局信息不可用。" : "Layout unavailable for this type.");
        } else {
            row(html, chinese ? "大小：" : "Size: ", parsed.size(), style.number());
            html.append("<br/>");
            row(html, chinese ? "对齐：" : "Alignment: ", parsed.alignment(), style.number());
            if (!parsed.padding().equals("0")) {
                html.append("<br/>");
                row(html, chinese ? "浪费的填充空间：" : "Wasted padding: ", parsed.padding(), style.number());
            }
            if (parsed.stride() != null) {
                html.append("<br/>");
                row(html, chinese ? "数组步长：" : "Array stride: ", parsed.stride(), style.number());
            }
        }
        html.append("</p>\n\n");
        // Keep user documentation (including examples) intact. Only the final compiler-generated link changes.
        return appendDocumentationAndLocation(html, parsed.remainder(), style.text());
    }

    static String appendDocumentationAndLocation(StringBuilder html, String remainder, String textColor) {
        Matcher footer = FOOTER.matcher(remainder);
        if (footer.find()) {
            String link = definitionLink(footer.group("uri"), textColor);
            if (link != null) {
                html.append(remainder, 0, footer.start()).append("\n---\n\n").append(link).append("\n");
                return html.toString();
            }
        }
        return html.append(remainder).toString();
    }

    static void row(StringBuilder html, String label, String number, String color) {
        html.append(label).append(colored(number, color));
    }

    private static String definitionLink(String target, String color) {
        try {
            URI uri = URI.create(target);
            String path = uri.getPath();
            if (!"file".equals(uri.getScheme()) || path == null || path.endsWith("/")
                    || uri.getFragment() == null || !uri.getFragment().matches("L[0-9]+")) return null;
            String file = path.substring(path.lastIndexOf('/') + 1);
            return "<a href=\"" + escape(target) + "\" title=\"" + escape(file + ":" + uri.getFragment().substring(1))
                    + "\"><code style=\"font-size:100%;\">" + colored(file, color) + "</code></a>";
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    static String colored(String text, String color) {
        return color == null ? escape(text) : "<span style=\"color:" + color + "\">" + escape(text) + "</span>";
    }

    static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    record Style(String keyword, String struct, String namespace, String number, String text) {
        static Style from(EditorColorsScheme scheme) {
            return new Style(color(scheme, SlangSyntaxHighlighter.KEYWORD), color(scheme, SlangSemanticColors.STRUCT),
                    color(scheme, SlangSemanticColors.NAMESPACE), color(scheme, SlangSyntaxHighlighter.NUMBER),
                    scheme == null ? null : hex(scheme.getDefaultForeground()));
        }

        static String color(EditorColorsScheme scheme, TextAttributesKey key) {
            if (scheme == null) return null;
            var attributes = scheme.getAttributes(key);
            return hex(attributes == null ? null : attributes.getForegroundColor());
        }

        private static String hex(Color color) {
            return color == null ? null : String.format(Locale.ROOT, "#%06x", color.getRGB() & 0xffffff);
        }
    }

    private record Parsed(String type, String namespace, String size, String alignment, String padding,
                          String stride, String remainder) {}
}
