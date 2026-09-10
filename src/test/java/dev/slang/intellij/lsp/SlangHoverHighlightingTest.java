package dev.slang.intellij.lsp;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

public class SlangHoverHighlightingTest {
    private static byte[] response(String kind, String text) {
        JsonObject content = new JsonObject();
        content.addProperty("kind", kind);
        content.addProperty("value", text);
        JsonObject result = new JsonObject();
        result.add("contents", content);
        result.add("range", JsonParser.parseString("{\"start\":{\"line\":2,\"character\":1},\"end\":{\"line\":2,\"character\":3}}"));
        JsonObject response = new JsonObject();
        response.addProperty("jsonrpc", "2.0");
        response.addProperty("id", "hover-1");
        response.add("result", result);
        return response.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static SlangLspRequestTracker tracker() {
        var tracker = new SlangLspRequestTracker();
        tracker.recordOutgoingPayload("{\"id\":\"hover-1\",\"method\":\"textDocument/hover\"}".getBytes(StandardCharsets.UTF_8));
        return tracker;
    }

    @Test public void highlightsOnlyLeadingSignatureAndPreservesRangeAndDocumentation() throws Exception {
        for (String newline : new String[]{"\n", "\r\n"}) {
            String original = "```" + newline + "typedef vector<float,4> Color;" + newline
                    + "```" + newline + "说明 <tag> & details\n```python\nprint(1)\n```\n```\nexample\n```";
            byte[] body = response("markdown", original);
            String frame = "Content-Length: " + body.length + "\r\n\r\n" + new String(body, StandardCharsets.UTF_8);
            String output = new String(new SlangLspProtocolInputStream(new ByteArrayInputStream(
                    frame.getBytes(StandardCharsets.UTF_8)), tracker()).readAllBytes(), StandardCharsets.UTF_8);
            int separator = output.indexOf("\r\n\r\n");
            String json = output.substring(separator + 4);
            assertEquals(json.getBytes(StandardCharsets.UTF_8).length,
                    Integer.parseInt(output.substring("Content-Length: ".length(), separator)));
            var result = JsonParser.parseString(json).getAsJsonObject().getAsJsonObject("result");
            assertEquals("```slang" + original.substring(3), result.getAsJsonObject("contents").get("value").getAsString());
            assertEquals(JsonParser.parseString(new String(body, StandardCharsets.UTF_8)).getAsJsonObject()
                    .getAsJsonObject("result").get("range"), result.get("range"));
        }
    }

    @Test public void preservesExplicitLanguagesPlaintextMalformedAndUnrelatedResponses() {
        for (String text : new String[]{"```slang\nfloat4 x;\n```", "```cpp\nint x;\n```",
                "ordinary text", "```\nunclosed", "Documentation\n```\nexample\n```"}) {
            byte[] body = response("markdown", text);
            assertArrayEquals(body, SlangLspProtocolInputStream.normalizePayload(body, tracker()));
        }
        byte[] plaintext = response("plaintext", "```\nfloat4 x;\n```");
        assertArrayEquals(plaintext, SlangLspProtocolInputStream.normalizePayload(plaintext, tracker()));
        byte[] body = response("markdown", "```\nfloat4 x;\n```");
        assertArrayEquals(body, SlangLspProtocolInputStream.normalizePayload(body, new SlangLspRequestTracker()));
        var tracker = tracker();
        byte[] error = "{\"id\":\"hover-1\",\"error\":{\"code\":-32603}}".getBytes(StandardCharsets.UTF_8);
        assertArrayEquals(error, SlangLspProtocolInputStream.normalizePayload(error, tracker));
        assertArrayEquals(body, SlangLspProtocolInputStream.normalizePayload(body, tracker));
    }
}
