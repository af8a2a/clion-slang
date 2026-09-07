package dev.slang.intellij.preprocessor;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.nio.file.Path;

/** Request snapshots shared by splits. Deliberately has no persistence or settings dependency. */
public final class SlangPreviewState {
    public record Baseline(String selection, String root, String variantFingerprint) {}
    public record Session(Baseline baseline, Object server, SlangMacroPreview macros) {}
    private final Map<String, Session> sessions = new HashMap<>();
    private long requestRevision;

    /** Display invalidation cancels snapshots, not user input (for example on a source edit). */
    public synchronized void invalidateRequests() { requestRevision++; }
    public synchronized long requestRevision() { return requestRevision; }

    public synchronized Session start(String target, Baseline baseline, Object server, SlangMacroPreview macros) {
        var session = new Session(Objects.requireNonNull(baseline), Objects.requireNonNull(server), Objects.requireNonNull(macros));
        sessions.put(target, session);
        return session;
    }

    public synchronized Session peek(String target) { return sessions.get(target); }

    /** A changed auto root, variant contents, selection or server cannot inherit an old preview. */
    public synchronized Session current(String target, Baseline baseline, Object server) {
        return current(target, sessions.get(target), baseline, server);
    }

    public synchronized Session current(String target, Session expected, Baseline baseline, Object server) {
        return current(target, expected, baseline, server, requestRevision);
    }

    public synchronized Session current(String target, Session expected, Baseline baseline, Object server, long revision) {
        if (revision != requestRevision) return null;
        var session = sessions.get(target);
        if (session != expected) return null; // An obsolete background task must not clear a new session.
        if (session != null && (!session.baseline.equals(baseline) || session.server != server)) {
            sessions.remove(target);
            return null;
        }
        return session;
    }

    /** Identity, not record equality: even restarting identical input invalidates old responses. */
    public synchronized boolean isCurrent(String target, Session requested) { return sessions.get(target) == requested; }
    public synchronized void stop(String target) { sessions.remove(target); }
    public synchronized void closeFile(String target, String rootPath, boolean stillOpenInAnotherSplit) {
        if (!stillOpenInAnotherSplit)
            sessions.entrySet().removeIf(e -> e.getKey().equals(target)
                    || Path.of(e.getValue().baseline.root).equals(Path.of(rootPath)));
    }
    public synchronized void clear() { sessions.clear(); }
}
