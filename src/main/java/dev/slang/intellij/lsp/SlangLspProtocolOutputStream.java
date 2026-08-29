package dev.slang.intellij.lsp;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;

/** Transparently forwards IDE output while observing complete JSON-RPC request frames. */
final class SlangLspProtocolOutputStream extends OutputStream {
    private static final int MAX_HEADER_BYTES = 32 * 1024;
    private static final int MAX_CONTENT_BYTES = 256 * 1024 * 1024;

    private final OutputStream delegate;
    private final SlangLspRequestTracker requestTracker;
    private byte[] pending = new byte[8 * 1024];
    private int pendingSize;

    SlangLspProtocolOutputStream(
            @NotNull OutputStream delegate,
            @NotNull SlangLspRequestTracker requestTracker
    ) {
        this.delegate = delegate;
        this.requestTracker = requestTracker;
    }

    @Override
    public synchronized void write(int value) throws IOException {
        append((byte) value);
        inspectCompleteFrames();
        // Record a completed request before slangd can observe its final byte and reply.
        delegate.write(value);
    }

    @Override
    public synchronized void write(byte @NotNull [] source, int offset, int length) throws IOException {
        if (offset < 0 || length < 0 || length > source.length - offset) {
            throw new IndexOutOfBoundsException();
        }
        append(source, offset, length);
        inspectCompleteFrames();
        delegate.write(source, offset, length);
    }

    @Override
    public synchronized void flush() throws IOException {
        inspectCompleteFrames();
        delegate.flush();
    }

    @Override
    public synchronized void close() throws IOException {
        delegate.close();
    }

    private void append(byte value) {
        ensureCapacity(pendingSize + 1);
        pending[pendingSize++] = value;
    }

    private void append(byte[] source, int offset, int length) {
        ensureCapacity(pendingSize + length);
        System.arraycopy(source, offset, pending, pendingSize, length);
        pendingSize += length;
    }

    private void ensureCapacity(int required) {
        if (required > pending.length) {
            pending = Arrays.copyOf(pending, Math.max(required, pending.length * 2));
        }
    }

    private void inspectCompleteFrames() {
        while (pendingSize != 0) {
            int bodyStart = findBodyStart(pending, pendingSize);
            if (bodyStart < 0) {
                if (pendingSize > MAX_HEADER_BYTES) {
                    pendingSize = 0;
                }
                return;
            }

            int contentLength = parseContentLength(pending, bodyStart);
            if (contentLength < 0 || contentLength > MAX_CONTENT_BYTES) {
                pendingSize = 0;
                return;
            }
            long frameEndLong = (long) bodyStart + contentLength;
            if (frameEndLong > pendingSize) {
                return;
            }

            int frameEnd = (int) frameEndLong;
            requestTracker.recordOutgoingPayload(Arrays.copyOfRange(pending, bodyStart, frameEnd));
            discardPrefix(frameEnd);
        }
    }

    private void discardPrefix(int length) {
        int remaining = pendingSize - length;
        if (remaining > 0) {
            System.arraycopy(pending, length, pending, 0, remaining);
        }
        pendingSize = remaining;
    }

    private static int findBodyStart(byte[] bytes, int length) {
        for (int index = 0; index < length - 1; index++) {
            if (bytes[index] == '\n' && bytes[index + 1] == '\n') {
                return index + 2;
            }
            if (index < length - 3
                    && bytes[index] == '\r'
                    && bytes[index + 1] == '\n'
                    && bytes[index + 2] == '\r'
                    && bytes[index + 3] == '\n') {
                return index + 4;
            }
        }
        return -1;
    }

    private static int parseContentLength(byte[] bytes, int bodyStart) {
        String headers = new String(bytes, 0, bodyStart, StandardCharsets.US_ASCII);
        for (String line : headers.split("\\r?\\n")) {
            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String name = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            if (!"content-length".equals(name)) {
                continue;
            }
            try {
                return Integer.parseInt(line.substring(colon + 1).trim());
            } catch (NumberFormatException ignored) {
                return -1;
            }
        }
        return -1;
    }
}
