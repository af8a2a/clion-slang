package dev.slang.intellij.lsp;

import org.jetbrains.annotations.NotNull;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/** Delegates process control while normalizing slangd's stdout protocol stream. */
final class SlangLspProtocolProcess extends Process {
    private final Process delegate;
    private final InputStream normalizedStdout;
    private final OutputStream trackedStdin;

    SlangLspProtocolProcess(@NotNull Process delegate) {
        this.delegate = delegate;
        SlangLspRequestTracker requestTracker = new SlangLspRequestTracker();
        normalizedStdout = new SlangLspProtocolInputStream(delegate.getInputStream(), requestTracker);
        trackedStdin = new SlangLspProtocolOutputStream(delegate.getOutputStream(), requestTracker);
    }

    @Override
    public OutputStream getOutputStream() {
        return trackedStdin;
    }

    @Override
    public InputStream getInputStream() {
        return normalizedStdout;
    }

    @Override
    public InputStream getErrorStream() {
        return delegate.getErrorStream();
    }

    @Override
    public int waitFor() throws InterruptedException {
        return delegate.waitFor();
    }

    @Override
    public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
        return delegate.waitFor(timeout, unit);
    }

    @Override
    public int exitValue() {
        return delegate.exitValue();
    }

    @Override
    public void destroy() {
        delegate.destroy();
    }

    @Override
    public Process destroyForcibly() {
        delegate.destroyForcibly();
        return this;
    }

    @Override
    public boolean supportsNormalTermination() {
        return delegate.supportsNormalTermination();
    }

    @Override
    public boolean isAlive() {
        return delegate.isAlive();
    }

    @Override
    public long pid() {
        return delegate.pid();
    }

    @Override
    public CompletableFuture<Process> onExit() {
        return delegate.onExit().thenApply(ignored -> this);
    }

    @Override
    public ProcessHandle toHandle() {
        return delegate.toHandle();
    }

    @Override
    public ProcessHandle.Info info() {
        return delegate.info();
    }

    @Override
    public Stream<ProcessHandle> children() {
        return delegate.children();
    }

    @Override
    public Stream<ProcessHandle> descendants() {
        return delegate.descendants();
    }
}
