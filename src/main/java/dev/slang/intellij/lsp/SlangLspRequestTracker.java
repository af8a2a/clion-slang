package dev.slang.intellij.lsp;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import org.jetbrains.annotations.NotNull;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Correlates definition requests with server responses without changing JSON-RPC ids. */
final class SlangLspRequestTracker {
    private static final String DEFINITION_METHOD = "textDocument/definition";
    private static final int MAX_TRACKED_REQUESTS = 4_096;

    private final Set<String> definitionRequestIds = ConcurrentHashMap.newKeySet();

    void recordOutgoingPayload(byte @NotNull [] payload) {
        try {
            JsonElement message = JsonParser.parseString(new String(payload, StandardCharsets.UTF_8));
            if (!message.isJsonObject()) {
                return;
            }
            JsonObject request = message.getAsJsonObject();
            JsonElement method = request.get("method");
            JsonElement id = request.get("id");
            if (id != null
                    && !id.isJsonNull()
                    && isMethod(method, DEFINITION_METHOD)) {
                if (definitionRequestIds.size() >= MAX_TRACKED_REQUESTS) {
                    definitionRequestIds.clear();
                }
                definitionRequestIds.add(id.toString());
            }
        } catch (JsonParseException | IllegalStateException ignored) {
            // Tracking must never interfere with the protocol bytes forwarded to slangd.
        }
    }

    boolean consumeDefinitionResponse(@NotNull JsonObject response) {
        JsonElement id = response.get("id");
        if (id == null || id.isJsonNull() || (!response.has("result") && !response.has("error"))) {
            return false;
        }
        return definitionRequestIds.remove(id.toString());
    }

    private static boolean isMethod(JsonElement element, String expected) {
        return element != null
                && element.isJsonPrimitive()
                && element.getAsJsonPrimitive().isString()
                && expected.equals(element.getAsString());
    }
}
