package dev.slang.intellij.lsp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.HashSet;
import java.util.Set;

/** Macro expansions can produce identical hints at the same source location. */
final class SlangInlayHintNormalizer {
    private SlangInlayHintNormalizer() {}

    static boolean normalize(JsonObject response) {
        JsonElement result = response.get("result");
        if (result == null || !result.isJsonArray()) return false;
        JsonArray hints = result.getAsJsonArray();
        Set<JsonObject> seen = new HashSet<>();
        JsonArray unique = new JsonArray();
        for (JsonElement hint : hints) {
            // Compare the entire object, including label-part commands, tooltips, edits,
            // padding and resolve data. Equal text alone does not mean equal behavior.
            // State is per response: a later request must still return its hints.
            if (!hint.isJsonObject() || !isHint(hint.getAsJsonObject())
                    || seen.add(hint.getAsJsonObject())) {
                unique.add(hint);
            }
        }
        if (unique.size() == hints.size()) return false;
        response.add("result", unique);
        return true;
    }

    private static boolean isHint(JsonObject hint) {
        JsonElement position = hint.get("position");
        JsonElement label = hint.get("label");
        if (position == null || !position.isJsonObject() || label == null) return false;
        JsonObject point = position.getAsJsonObject();
        return isNumber(point.get("line")) && isNumber(point.get("character"))
                && (label.isJsonArray() || label.isJsonPrimitive() && label.getAsJsonPrimitive().isString());
    }

    private static boolean isNumber(JsonElement value) {
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber();
    }
}
