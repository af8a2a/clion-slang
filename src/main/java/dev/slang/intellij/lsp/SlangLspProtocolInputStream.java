package dev.slang.intellij.lsp;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import org.jetbrains.annotations.NotNull;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Reframes slangd messages after adapting singleton definition locations and hover language tags.
 *
 * <p>Slang 1.8 emits a single {@code Location} JSON object although LSP4J's
 * definition response adapter accepts only arrays. The normalizer sits at the
 * server process boundary, before LSP4J deserialization, and leaves every
 * unrelated JSON-RPC payload byte-for-byte unchanged.</p>
 */
final class SlangLspProtocolInputStream extends InputStream {
    private static final Gson GSON = new Gson();
    private static final int MAX_HEADER_LINE_BYTES = 32 * 1024;
    private static final int MAX_CONTENT_BYTES = 256 * 1024 * 1024;

    private final InputStream delegate;
    private final SlangLspRequestTracker requestTracker;
    private byte[] frame = new byte[0];
    private int frameOffset;

    SlangLspProtocolInputStream(
            @NotNull InputStream delegate,
            @NotNull SlangLspRequestTracker requestTracker
    ) {
        this.delegate = delegate;
        this.requestTracker = requestTracker;
    }

    @Override
    public int read() throws IOException {
        if (!ensureFrame()) {
            return -1;
        }
        return frame[frameOffset++] & 0xff;
    }

    @Override
    public int read(byte @NotNull [] target, int offset, int length) throws IOException {
        if (offset < 0 || length < 0 || length > target.length - offset) {
            throw new IndexOutOfBoundsException();
        }
        if (length == 0) {
            return 0;
        }
        if (!ensureFrame()) {
            return -1;
        }

        int count = Math.min(length, frame.length - frameOffset);
        System.arraycopy(frame, frameOffset, target, offset, count);
        frameOffset += count;
        return count;
    }

    @Override
    public int available() throws IOException {
        return frame.length - frameOffset + delegate.available();
    }

    @Override
    public void close() throws IOException {
        delegate.close();
    }

    private boolean ensureFrame() throws IOException {
        if (frameOffset < frame.length) {
            return true;
        }
        byte[] next = readFrame();
        if (next == null) {
            frame = new byte[0];
            frameOffset = 0;
            return false;
        }
        frame = next;
        frameOffset = 0;
        return true;
    }

    private byte[] readFrame() throws IOException {
        List<String> headers = new ArrayList<>();
        int contentLength = -1;

        String line = readHeaderLine();
        if (line == null) {
            return null;
        }
        while (!line.isEmpty()) {
            int colon = line.indexOf(':');
            if (colon <= 0) {
                throw new IOException("Malformed LSP header: " + line);
            }
            String name = line.substring(0, colon).trim();
            String value = line.substring(colon + 1).trim();
            if ("content-length".equals(name.toLowerCase(Locale.ROOT))) {
                try {
                    contentLength = Integer.parseInt(value);
                } catch (NumberFormatException exception) {
                    throw new IOException("Invalid LSP Content-Length: " + value, exception);
                }
            } else {
                headers.add(line);
            }
            line = readHeaderLine();
            if (line == null) {
                throw new EOFException("slangd closed stdout inside an LSP header");
            }
        }

        if (contentLength < 0) {
            throw new IOException("LSP frame has no Content-Length header");
        }
        if (contentLength > MAX_CONTENT_BYTES) {
            throw new IOException("LSP frame is too large: " + contentLength + " bytes");
        }
        byte[] payload = delegate.readNBytes(contentLength);
        if (payload.length != contentLength) {
            throw new EOFException("slangd closed stdout inside an LSP payload");
        }
        byte[] normalized = normalizePayload(payload, requestTracker);

        ByteArrayOutputStream output = new ByteArrayOutputStream(normalized.length + 128);
        output.writeBytes(("Content-Length: " + normalized.length + "\r\n")
                .getBytes(StandardCharsets.US_ASCII));
        for (String header : headers) {
            output.writeBytes(header.getBytes(StandardCharsets.US_ASCII));
            output.writeBytes("\r\n".getBytes(StandardCharsets.US_ASCII));
        }
        output.writeBytes("\r\n".getBytes(StandardCharsets.US_ASCII));
        output.writeBytes(normalized);
        return output.toByteArray();
    }

    private String readHeaderLine() throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        while (true) {
            int next = delegate.read();
            if (next < 0) {
                if (output.size() == 0) {
                    return null;
                }
                throw new EOFException("slangd closed stdout inside an LSP header line");
            }
            if (next == '\n') {
                return output.toString(StandardCharsets.US_ASCII);
            }
            if (next != '\r') {
                output.write(next);
                if (output.size() > MAX_HEADER_LINE_BYTES) {
                    throw new IOException("LSP header line is too large");
                }
            }
        }
    }

    static byte @NotNull [] normalizePayload(
            byte @NotNull [] payload,
            @NotNull SlangLspRequestTracker requestTracker
    ) {
        try {
            JsonElement message = JsonParser.parseString(new String(payload, StandardCharsets.UTF_8));
            if (!message.isJsonObject()) {
                return payload;
            }
            JsonObject response = message.getAsJsonObject();
            if (requestTracker.consumeHoverResponse(response)) {
                return SlangHoverHighlighting.normalize(response)
                        ? GSON.toJson(response).getBytes(StandardCharsets.UTF_8) : payload;
            }
            if (!requestTracker.consumeDefinitionResponse(response)) {
                return payload;
            }
            JsonElement result = response.get("result");
            if (result == null || !result.isJsonObject()) {
                return payload;
            }
            JsonObject resultObject = result.getAsJsonObject();
            if (!isLocation(resultObject) && !isLocationLink(resultObject)) {
                return payload;
            }

            JsonArray singleton = new JsonArray();
            singleton.add(resultObject);
            response.add("result", singleton);
            return GSON.toJson(response).getBytes(StandardCharsets.UTF_8);
        } catch (JsonParseException | IllegalStateException exception) {
            return payload;
        }
    }

    private static boolean isLocation(@NotNull JsonObject object) {
        return isString(object.get("uri")) && isObject(object.get("range"));
    }

    private static boolean isLocationLink(@NotNull JsonObject object) {
        return isString(object.get("targetUri")) && isObject(object.get("targetRange"));
    }

    private static boolean isString(JsonElement element) {
        return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isString();
    }

    private static boolean isObject(JsonElement element) {
        return element != null && element.isJsonObject();
    }
}
