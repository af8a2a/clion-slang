package dev.slang.intellij.lsp;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** Supplies the missing language of slangd's leading signature, not of documentation examples. */
final class SlangHoverHighlighting {
    private SlangHoverHighlighting() {}

    static boolean normalize(JsonObject response) {
        JsonElement result = response.get("result");
        if (result == null || !result.isJsonObject()) return false;
        JsonElement contents = result.getAsJsonObject().get("contents");
        if (contents == null || !contents.isJsonObject()) return false;
        JsonObject markup = contents.getAsJsonObject();
        if (!isString(markup.get("kind")) || !"markdown".equals(markup.get("kind").getAsString())
                || !isString(markup.get("value"))) return false;
        String original = markup.get("value").getAsString();
        String documented = SlangMacroHoverPresentation.format(SlangBuiltinDocumentation.format(original));
        String styled = SlangFieldHoverPresentation.format(SlangStructHoverPresentation.format(documented));
        // Reference links and leading-signature highlighting must both run in the same pass.
        if ((styled.startsWith("```\n") || styled.startsWith("```\r\n"))
                && java.util.regex.Pattern.compile("(?m)^```[ \\t]*\\r?$")
                    .matcher(styled.substring(3)).find()) {
            styled = "```slang" + styled.substring(3);
        }
        if (styled.equals(original)) return false;
        markup.addProperty("value", styled);
        return true;
    }

    private static boolean isString(JsonElement value) {
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString();
    }
}
