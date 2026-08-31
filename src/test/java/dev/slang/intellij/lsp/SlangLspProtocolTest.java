package dev.slang.intellij.lsp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class SlangLspProtocolTest {
    @Test
    public void normalizesOnlyTheMatchingDefinitionResponseAcrossChunkedFrames() throws Exception {
        SlangLspRequestTracker tracker = new SlangLspRequestTracker();
        ByteArrayOutputStream forwardedRequests = new ByteArrayOutputStream();
        SlangLspProtocolOutputStream requests = new SlangLspProtocolOutputStream(forwardedRequests, tracker);

        byte[] requestFrame = frame("""
                {"jsonrpc":"2.0","id":7,"method":"textDocument/definition","params":{
                  "textDocument":{"uri":"file:///着色器.slang"},
                  "position":{"line":8,"character":11}
                }}
                """.strip());
        writeInChunks(requests, requestFrame, 1, 9, 3, 17);
        requests.flush();
        assertArrayEquals(requestFrame, forwardedRequests.toByteArray());

        byte[] responseFrame = frame("""
                {"jsonrpc":"2.0","id":7,"result":{
                  "uri":"file:///着色器.slang",
                  "range":{"start":{"line":1,"character":6},"end":{"line":1,"character":11}}
                }}
                """.strip());
        byte[] normalizedFrame = new SlangLspProtocolInputStream(
                new ByteArrayInputStream(responseFrame),
                tracker
        ).readAllBytes();

        Payload normalized = payload(normalizedFrame);
        assertEquals(normalized.bytes().length, normalized.declaredLength());
        JsonObject message = JsonParser.parseString(
                new String(normalized.bytes(), StandardCharsets.UTF_8)
        ).getAsJsonObject();
        JsonArray result = message.getAsJsonArray("result");
        assertEquals(1, result.size());
        assertEquals("file:///着色器.slang", result.get(0).getAsJsonObject().get("uri").getAsString());
    }

    @Test
    public void leavesUnrelatedLocationShapedResponsesByteForByteUnchanged() throws Exception {
        byte[] original = frame("""
                {"jsonrpc":"2.0","id":"custom-1","result":{"uri":"file:///x.slang","range":{
                  "start":{"line":0,"character":0},"end":{"line":0,"character":1}}}}
                """.strip());

        byte[] forwarded = new SlangLspProtocolInputStream(
                new ByteArrayInputStream(original),
                new SlangLspRequestTracker()
        ).readAllBytes();

        assertArrayEquals(original, forwarded);
    }

    @Test
    public void preservesStructFieldLayoutHoverResponseByteForByte() throws Exception {
        SlangLspRequestTracker tracker = new SlangLspRequestTracker();
        ByteArrayOutputStream forwardedRequests = new ByteArrayOutputStream();
        SlangLspProtocolOutputStream requests = new SlangLspProtocolOutputStream(forwardedRequests, tracker);
        byte[] requestFrame = frame("""
                {"jsonrpc":"2.0","id":"field-hover","method":"textDocument/hover","params":{
                  "textDocument":{"uri":"file:///着色器/LightSampling.slang"},
                  "position":{"line":3,"character":12}
                }}
                """.strip());
        writeInChunks(requests, requestFrame, 2, 11, 5);
        requests.flush();
        assertArrayEquals(requestFrame, forwardedRequests.toByteArray());

        byte[] responseFrame = frame("""
                {"jsonrpc":"2.0","id":"field-hover","result":{
                  "contents":{"kind":"markdown","value":"```slang\\npublic field\\nfloat3 lightVector\\n    (in struct LightShapeSample)\\n```\\n\\n**Natural layout**\\\\\\nSize: `12` bytes\\\\\\nAlignment: `4` bytes\\\\\\nOffset: `0` bytes\\n\\n"},
                  "range":{"start":{"line":3,"character":11},"end":{"line":3,"character":22}}
                }}
                """.strip());

        assertArrayEquals(responseFrame, forward(responseFrame, tracker));
    }

    @Test
    public void preservesFindReferencesLocationArraysByteForByte() throws Exception {
        SlangLspRequestTracker tracker = new SlangLspRequestTracker();
        ByteArrayOutputStream forwardedRequests = new ByteArrayOutputStream();
        SlangLspProtocolOutputStream requests = new SlangLspProtocolOutputStream(
                forwardedRequests,
                tracker
        );
        byte[] requestFrame = frame("""
                {"jsonrpc":"2.0","id":"field-references","method":"textDocument/references","params":{
                  "textDocument":{"uri":"file:///着色器/Usage.slang"},
                  "position":{"line":8,"character":12},
                  "context":{"includeDeclaration":true}
                }}
                """.strip());
        writeInChunks(requests, requestFrame, 3, 7, 19, 2);
        requests.flush();
        assertArrayEquals(requestFrame, forwardedRequests.toByteArray());

        byte[] responseFrame = frame("""
                {"jsonrpc":"2.0","id":"field-references","result":[
                  {"uri":"file:///着色器/Usage.slang","range":{
                    "start":{"line":2,"character":9},"end":{"line":2,"character":16}}},
                  {"uri":"file:///着色器/Usage.slang","range":{
                    "start":{"line":8,"character":11},"end":{"line":8,"character":18}}}
                ]}
                """.strip());

        assertArrayEquals(responseFrame, forward(responseFrame, tracker));
    }

    @Test
    public void preservesDocumentHighlightArraysByteForByte() throws Exception {
        SlangLspRequestTracker tracker = new SlangLspRequestTracker();
        ByteArrayOutputStream forwardedRequests = new ByteArrayOutputStream();
        SlangLspProtocolOutputStream requests = new SlangLspProtocolOutputStream(
                forwardedRequests,
                tracker
        );
        byte[] requestFrame = frame("""
                {"jsonrpc":"2.0","id":"variable-highlights","method":"textDocument/documentHighlight","params":{
                  "textDocument":{"uri":"file:///着色器/Usage.slang"},
                  "position":{"line":8,"character":12}
                }}
                """.strip());
        writeInChunks(requests, requestFrame, 5, 2, 13, 7);
        requests.flush();
        assertArrayEquals(requestFrame, forwardedRequests.toByteArray());

        byte[] responseFrame = frame("""
                {"jsonrpc":"2.0","id":"variable-highlights","result":[
                  {"range":{"start":{"line":2,"character":9},"end":{"line":2,"character":16}},"kind":1},
                  {"range":{"start":{"line":8,"character":11},"end":{"line":8,"character":18}},"kind":1}
                ]}
                """.strip());

        assertArrayEquals(responseFrame, forward(responseFrame, tracker));
    }

    @Test
    public void preservesStandardDefinitionArraysAndNullResults() throws Exception {
        SlangLspRequestTracker tracker = new SlangLspRequestTracker();
        recordDefinitionRequest(tracker, "array");
        byte[] arrayResponse = frame("""
                {"jsonrpc":"2.0","id":"array","result":[{"uri":"file:///x.slang","range":{
                  "start":{"line":0,"character":0},"end":{"line":0,"character":1}}}]}
                """.strip());
        assertArrayEquals(arrayResponse, forward(arrayResponse, tracker));

        recordDefinitionRequest(tracker, "null");
        byte[] nullResponse = frame("{\"jsonrpc\":\"2.0\",\"id\":\"null\",\"result\":null}");
        assertArrayEquals(nullResponse, forward(nullResponse, tracker));
    }

    @Test
    public void normalizesAMatchingSingletonLocationLink() throws Exception {
        SlangLspRequestTracker tracker = new SlangLspRequestTracker();
        recordDefinitionRequest(tracker, "link");
        byte[] response = frame("""
                {"jsonrpc":"2.0","id":"link","result":{
                  "originSelectionRange":{"start":{"line":8,"character":6},"end":{"line":8,"character":11}},
                  "targetUri":"slang-synth://core",
                  "targetRange":{"start":{"line":2,"character":0},"end":{"line":4,"character":1}},
                  "targetSelectionRange":{"start":{"line":2,"character":6},"end":{"line":2,"character":11}}
                }}
                """.strip());

        Payload normalized = payload(forward(response, tracker));
        JsonObject message = JsonParser.parseString(
                new String(normalized.bytes(), StandardCharsets.UTF_8)
        ).getAsJsonObject();
        JsonArray result = message.getAsJsonArray("result");
        assertEquals(1, result.size());
        assertEquals("slang-synth://core", result.get(0).getAsJsonObject()
                .get("targetUri").getAsString());
    }

    @Test
    public void consumesAnErroredDefinitionRequestWithoutTouchingLaterIds() throws Exception {
        SlangLspRequestTracker tracker = new SlangLspRequestTracker();
        recordDefinitionRequest(tracker, "failed");
        byte[] error = frame("""
                {"jsonrpc":"2.0","id":"failed","error":{"code":-32603,"message":"failed"}}
                """.strip());
        assertArrayEquals(error, forward(error, tracker));

        byte[] laterLocation = frame("""
                {"jsonrpc":"2.0","id":"failed","result":{"uri":"file:///x.slang","range":{
                  "start":{"line":0,"character":0},"end":{"line":0,"character":1}}}}
                """.strip());
        assertArrayEquals(laterLocation, forward(laterLocation, tracker));
    }

    @Test
    public void normalizesALateResponseAfterCancellationAndConsumesItOnce() throws Exception {
        SlangLspRequestTracker tracker = new SlangLspRequestTracker();
        recordDefinitionRequest(tracker, "hover-9");

        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        SlangLspProtocolOutputStream output = new SlangLspProtocolOutputStream(sink, tracker);
        output.write(frame("""
                {"jsonrpc":"2.0","method":"$/cancelRequest","params":{"id":"hover-9"}}
                """.strip()));
        output.flush();

        byte[] lateResponse = frame("""
                {"jsonrpc":"2.0","id":"hover-9","result":{"uri":"file:///x.slang","range":{
                  "start":{"line":0,"character":0},"end":{"line":0,"character":1}}}}
                """.strip());
        Payload normalized = payload(forward(lateResponse, tracker));
        JsonObject message = JsonParser.parseString(
                new String(normalized.bytes(), StandardCharsets.UTF_8)
        ).getAsJsonObject();
        assertEquals(1, message.getAsJsonArray("result").size());

        assertArrayEquals(lateResponse, forward(lateResponse, tracker));
    }

    @Test
    public void recordsTheRequestBeforeADelegateCanReplySynchronously() throws Exception {
        SlangLspRequestTracker tracker = new SlangLspRequestTracker();
        AtomicBoolean requestWasVisible = new AtomicBoolean();
        JsonObject immediateResponse = JsonParser.parseString(
                "{\"jsonrpc\":\"2.0\",\"id\":99,\"result\":null}"
        ).getAsJsonObject();
        ByteArrayOutputStream eagerServer = new ByteArrayOutputStream() {
            @Override
            public synchronized void write(byte[] bytes, int offset, int length) {
                super.write(bytes, offset, length);
                requestWasVisible.set(tracker.consumeDefinitionResponse(immediateResponse));
            }
        };
        SlangLspProtocolOutputStream output = new SlangLspProtocolOutputStream(eagerServer, tracker);

        output.write(frame("""
                {"jsonrpc":"2.0","id":99,"method":"textDocument/definition","params":{}}
                """.strip()));

        assertTrue(requestWasVisible.get());
    }

    private static void recordDefinitionRequest(SlangLspRequestTracker tracker, String id) throws Exception {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        SlangLspProtocolOutputStream output = new SlangLspProtocolOutputStream(sink, tracker);
        output.write(frame("{\"jsonrpc\":\"2.0\",\"id\":\"" + id
                + "\",\"method\":\"textDocument/definition\",\"params\":{}}"));
        output.flush();
    }

    private static byte[] forward(byte[] frame, SlangLspRequestTracker tracker) throws Exception {
        return new SlangLspProtocolInputStream(new ByteArrayInputStream(frame), tracker).readAllBytes();
    }

    private static byte[] frame(String json) {
        byte[] payload = json.getBytes(StandardCharsets.UTF_8);
        byte[] header = ("Content-Length: " + payload.length + "\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII);
        byte[] frame = Arrays.copyOf(header, header.length + payload.length);
        System.arraycopy(payload, 0, frame, header.length, payload.length);
        return frame;
    }

    private static Payload payload(byte[] frame) {
        byte[] separator = "\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
        int bodyStart = -1;
        for (int index = 0; index <= frame.length - separator.length; index++) {
            if (Arrays.equals(
                    Arrays.copyOfRange(frame, index, index + separator.length),
                    separator
            )) {
                bodyStart = index + separator.length;
                break;
            }
        }
        assertTrue(bodyStart >= 0);
        String header = new String(frame, 0, bodyStart, StandardCharsets.US_ASCII);
        int declared = Integer.parseInt(header.substring(
                header.indexOf(':') + 1,
                header.indexOf("\r\n")
        ).trim());
        return new Payload(declared, Arrays.copyOfRange(frame, bodyStart, frame.length));
    }

    private static void writeInChunks(
            SlangLspProtocolOutputStream output,
            byte[] bytes,
            int... chunkSizes
    ) throws Exception {
        int offset = 0;
        int chunkIndex = 0;
        while (offset < bytes.length) {
            int length = Math.min(chunkSizes[chunkIndex % chunkSizes.length], bytes.length - offset);
            output.write(bytes, offset, length);
            offset += length;
            chunkIndex++;
        }
    }

    private record Payload(int declaredLength, byte[] bytes) {
    }
}
