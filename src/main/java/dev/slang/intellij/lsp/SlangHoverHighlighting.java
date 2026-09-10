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
        // Keep explicit languages and plaintext intact. Only the first fenced signature
        // is known to be Slang; later fences may contain prose or other languages.
        if (!original.startsWith("```\n") && !original.startsWith("```\r\n")) return false;
        if (!java.util.regex.Pattern.compile("(?m)^```[ \\t]*\\r?$")
                .matcher(original.substring(3)).find()) return false;
        markup.addProperty("value", "```slang" + original.substring(3));
        return true;
    }

    private static boolean isString(JsonElement value) {
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString();
    }
}
