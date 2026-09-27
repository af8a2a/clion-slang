package dev.slang.intellij.lsp;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Map;
import java.util.HashMap;
import java.util.regex.Pattern;

/** Offline reference links, gated by the resolved declaration's standard-library origin. */
final class SlangBuiltinDocumentation {
    private static final Pattern SIGNATURE = Pattern.compile("\\A```(?:slang)?\\r?\\n(.*?)\\r?\\n```[ \\t]*\\r?\\n", Pattern.DOTALL);
    private static final Pattern FUNCTION = Pattern.compile("\\Afunc ([A-Za-z_][A-Za-z_0-9]*)(?=[<(])");
    private static final Pattern VARIABLE = Pattern.compile("\\A\\(global (?:variable|value)\\) [^\\r\\n=]+?\\b([A-Za-z_][A-Za-z_0-9]*)\\s*(?:=|$)");
    private static final Pattern ORIGIN = Pattern.compile("(?m)^Defined in ((?:[^\\r\\n]+[/\\\\])?core)\\([0-9]+\\)\\r?$");
    private static final Pattern MARKER = Pattern.compile("(?m)^<!-- slang-builtin:core:([A-Za-z_][A-Za-z_0-9]*) -->\\r?\\n");
    private static final Map<String, Reference> REFERENCES = loadReferences();

    private SlangBuiltinDocumentation() {}

    static String format(String markdown) {
        var signature = SIGNATURE.matcher(markdown);
        if (!signature.find()) return markdown;
        var function = FUNCTION.matcher(signature.group(1));
        var variable = VARIABLE.matcher(signature.group(1));
        String name = function.find() ? function.group(1) : variable.find() ? variable.group(1) : null;
        if (name == null) return markdown;
        var origin = ORIGIN.matcher(markdown);
        if (!origin.find(signature.end())) return markdown;
        var marker = MARKER.matcher(markdown);
        boolean semanticBuiltin = marker.find(signature.end()) && name.equals(marker.group(1));
        // Older/official servers expose only a synthetic source path. Fail closed on
        // relative/unknown paths or real files; a user file named core is not a builtin.
        if (!semanticBuiltin && !isMissingAbsoluteCore(origin.group(1))) return markdown;
        Reference reference = REFERENCES.get(name);
        if (reference == null) return markdown;
        String link = "<p><a href=\"" + SlangStructHoverPresentation.escape(reference.url)
                + "\"><code>" + name + "</code> on " + reference.site + " ↗</a></p>";
        // Existing documentation may already contain this exact target.
        if (markdown.contains(reference.url)) return markdown;
        String result = markdown.substring(0, origin.start()).stripTrailing()
                + "\n\n---\n\n" + link + "\n" + markdown.substring(origin.end()).stripLeading();
        return MARKER.matcher(result).replaceAll("");
    }

    private static boolean isMissingAbsoluteCore(String value) {
        try {
            Path path = Path.of(value);
            return path.isAbsolute() && Files.notExists(path);
        } catch (InvalidPathException | SecurityException ignored) {
            return false;
        }
    }

    private static Map<String, Reference> loadReferences() {
        var stream = SlangBuiltinDocumentation.class.getResourceAsStream("/documentation/builtins.json");
        if (stream == null) throw new IllegalStateException("Missing builtin documentation catalog");
        try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            JsonObject symbols = JsonParser.parseReader(reader).getAsJsonObject().getAsJsonObject("symbols");
            Map<String, Reference> result = new HashMap<>();
            for (var entry : symbols.entrySet()) {
                var item = entry.getValue().getAsJsonObject();
                var microsoft = item.getAsJsonArray("microsoft");
                // Some HLSL overloads have separate pages (asuint, tex2D, ...).
                // Use Slang's overload-group page instead of guessing an overload.
                if (microsoft != null && microsoft.size() == 1) {
                    String url = microsoft.get(0).getAsString();
                    if (url.startsWith("https://learn.microsoft.com/en-us/windows/win32/direct3dhlsl/"))
                        result.put(entry.getKey(), new Reference("Microsoft Learn", url));
                } else if (item.has("slang")) {
                    String url = item.get("slang").getAsString();
                    if (url.startsWith("https://docs.shader-slang.org/en/latest/external/core-module-reference/global-decls/"))
                        result.put(entry.getKey(), new Reference("Slang Documentation", url));
                }
            }
            return Map.copyOf(result);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Cannot read builtin documentation catalog", e);
        }
    }

    private record Reference(String site, String url) {}
}
