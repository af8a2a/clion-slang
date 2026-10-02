package dev.slang.intellij.lsp;

import com.google.gson.*;
import org.junit.Test;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import static org.junit.Assert.*;

public class SlangInlayHintNormalizerTest {
    private static JsonObject hint(int line, int character, String label) {
        JsonObject h = new JsonObject();
        JsonObject p = new JsonObject(); p.addProperty("line", line); p.addProperty("character", character);
        h.add("position", p); h.addProperty("label", label); h.addProperty("kind", 2);
        return h;
    }
    private static JsonObject response(JsonElement result) {
        JsonObject r = new JsonObject(); r.addProperty("id", "hint-1"); r.add("result", result); return r;
    }
    private static byte[] bytes(JsonObject object) { return object.toString().getBytes(StandardCharsets.UTF_8); }
    private static void track(SlangLspRequestTracker tracker) {
        tracker.recordOutgoingPayload("{\"id\":\"hint-1\",\"method\":\"textDocument/inlayHint\"}".getBytes(StandardCharsets.UTF_8));
    }

    @Test public void realAutoExposureMacroKeepsOneHandleAndPreservesEveryUniqueHint() throws Exception {
        try (var reader = new InputStreamReader(getClass().getResourceAsStream("/inlayHints/auto-exposure-before.json"), StandardCharsets.UTF_8)) {
            JsonArray original = JsonParser.parseReader(reader).getAsJsonArray();
            assertEquals(3, macroHandles(original));
            JsonObject r = response(original.deepCopy());
            assertTrue(SlangInlayHintNormalizer.normalize(r));
            JsonArray actual = r.getAsJsonArray("result");
            assertEquals(1, macroHandles(actual));
            var seen = new HashSet<JsonElement>();
            JsonArray expected = new JsonArray();
            for (JsonElement h : original) if (seen.add(h)) expected.add(h);
            assertEquals(expected, actual);
            assertFalse(SlangInlayHintNormalizer.normalize(r));
        }
    }
    private static int macroHandles(JsonArray hints) {
        int count = 0;
        for (JsonElement e : hints) {
            var h = e.getAsJsonObject(); var p = h.getAsJsonObject("position");
            if (p.get("line").getAsInt() == 12 && p.get("character").getAsInt() == 34
                    && h.get("label").getAsString().equals("handle:")) count++;
        }
        return count;
    }

    @Test public void preservesDistinctPositionsKindsAndMetadataEvenWithSameLabel() {
        JsonArray a = new JsonArray(); JsonObject base = hint(12, 34, "handle:"); a.add(base);
        a.add(hint(12, 35, "handle:")); a.add(hint(13, 34, "handle:")); a.add(hint(12, 34, "slot:"));
        for (String key : new String[]{"tooltip", "data", "paddingRight", "textEdits", "kind"}) {
            var h = base.deepCopy(); h.add(key, switch (key) {
                case "paddingRight" -> new JsonPrimitive(true);
                case "kind" -> new JsonPrimitive(1);
                case "data" -> JsonParser.parseString("{\"resolveId\":42}");
                case "textEdits" -> JsonParser.parseString("[]");
                default -> new JsonPrimitive("documentation");
            }); a.add(h);
        }
        var r = response(a); assertFalse(SlangInlayHintNormalizer.normalize(r)); assertEquals(a, r.get("result"));
    }

    @Test public void structuredLabelsCompareCommandsAndLocationsNotJustVisibleText() {
        JsonObject h = hint(0, 4, "unused");
        h.add("label", JsonParser.parseString("[{\"value\":\"handle:\",\"command\":{\"title\":\"Open\",\"command\":\"open\",\"arguments\":[1]}}]"));
        JsonArray a = new JsonArray(); a.add(h); a.add(h.deepCopy());
        JsonObject other = h.deepCopy(); other.getAsJsonArray("label").get(0).getAsJsonObject()
                .getAsJsonObject("command").add("arguments", JsonParser.parseString("[2]")); a.add(other);
        var r = response(a); assertTrue(SlangInlayHintNormalizer.normalize(r));
        assertEquals(2, r.getAsJsonArray("result").size()); assertEquals(other, r.getAsJsonArray("result").get(1));
    }

    @Test public void malformedNullAndUniqueResultsRemainByteForByteUnchanged() {
        for (String source : new String[]{"null", "[]", "{}", "[null,null]", "[{\"label\":\"x\"},{\"label\":\"x\"}]",
                "[{\"position\":{},\"label\":\"x\"},{\"position\":{},\"label\":\"x\"}]"}) {
            var r = response(JsonParser.parseString(source)); byte[] b = bytes(r);
            var tracker = new SlangLspRequestTracker(); track(tracker);
            assertArrayEquals(b, SlangLspProtocolInputStream.normalizePayload(b, tracker));
        }
    }

    @Test public void onlyCorrelatedInlayResponsesChangeAndRepeatedRequestsStillReturnHints() {
        JsonArray a = new JsonArray(); a.add(hint(0, 4, "handle:")); a.add(hint(0, 4, "handle:"));
        byte[] b = bytes(response(a)); var tracker = new SlangLspRequestTracker();
        assertArrayEquals(b, SlangLspProtocolInputStream.normalizePayload(b, tracker));
        for (int i = 0; i < 3; i++) {
            track(tracker);
            var r = JsonParser.parseString(new String(SlangLspProtocolInputStream.normalizePayload(b, tracker), StandardCharsets.UTF_8)).getAsJsonObject();
            assertEquals(1, r.getAsJsonArray("result").size());
            assertArrayEquals(b, SlangLspProtocolInputStream.normalizePayload(b, tracker));
        }
    }

    @Test public void serverRequestsAndErrorsDoNotLeakResponseTracking() {
        var tracker = new SlangLspRequestTracker(); track(tracker);
        var request = response(new JsonArray()); request.addProperty("method", "workspace/configuration");
        assertFalse(tracker.consumeInlayHintResponse(request));
        var error = new JsonObject(); error.addProperty("id", "hint-1"); error.add("error", JsonParser.parseString("{\"code\":-32800}"));
        assertTrue(tracker.consumeInlayHintResponse(error)); assertFalse(tracker.consumeInlayHintResponse(error));
        track(tracker); var numeric = new JsonObject(); numeric.addProperty("id", 1); numeric.add("result", new JsonArray());
        assertFalse(tracker.consumeInlayHintResponse(numeric));
    }

    @Test public void reframesUtf8PayloadAndRetainsHeadersAndHintEdits() throws Exception {
        JsonArray a = new JsonArray(); var h = hint(2, 8, "句柄:"); h.addProperty("tooltip", "参数说明");
        h.add("textEdits", JsonParser.parseString("[{\"range\":{\"start\":{\"line\":2,\"character\":8},\"end\":{\"line\":2,\"character\":8}},\"newText\":\"test\"}]"));
        a.add(h); a.add(h.deepCopy()); byte[] b = bytes(response(a));
        byte[] frame = ("Content-Length: " + b.length + "\r\nX-Test: preserved\r\n\r\n" + new String(b, StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8);
        var tracker = new SlangLspRequestTracker(); track(tracker);
        String result = new String(new SlangLspProtocolInputStream(new ByteArrayInputStream(frame), tracker).readAllBytes(), StandardCharsets.UTF_8);
        int start = result.indexOf("\r\n\r\n") + 4; String json = result.substring(start);
        assertEquals(json.getBytes(StandardCharsets.UTF_8).length, Integer.parseInt(result.substring(16, result.indexOf("\r\n"))));
        assertTrue(result.contains("X-Test: preserved\r\n"));
        var actual = JsonParser.parseString(json).getAsJsonObject().getAsJsonArray("result");
        assertEquals(1, actual.size()); assertEquals(h, actual.get(0));
    }
}
