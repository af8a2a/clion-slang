package dev.slang.intellij.preprocessor;

/** EDT-owned scheduling state, with a clock supplied by the caller for deterministic tests. */
final class SlangServerRestartPolicy {
    static final long QUIET_MS = 2_000;
    static final long COOLDOWN_MS = 10_000;
    static final long STABLE_MS = 30_000;
    static final int MAX_RECOVERIES = 3;
    private long due = -1;
    private long lastRestart = -COOLDOWN_MS;
    private long runningSince = -1;
    private int recoveries;
    private boolean recovery;

    void changed(long now) { due = Math.max(due, now + QUIET_MS); }
    void running(long now) { if (runningSince < 0) runningSince = now; }
    void initializing() { runningSince = -1; }
    boolean failed(long now) {
        if (runningSince >= 0 && now - runningSince >= STABLE_MS) recoveries = 0;
        runningSince = -1;
        recovery = true;
        if (recoveries >= MAX_RECOVERIES) { due = -1; return false; }
        changed(now);
        return true;
    }
    void stoppedNormally() { due = -1; recovery = false; runningSince = -1; }
    boolean pending() { return due >= 0; }
    long delay(long now) { return Math.max(0, Math.max(due, lastRestart + COOLDOWN_MS) - now); }
    boolean take(long now, boolean initializing) {
        if (!pending() || initializing || delay(now) > 0) return false;
        if (recovery && recoveries >= MAX_RECOVERIES) { due = -1; return false; }
        if (recovery) recoveries++;
        else if (runningSince >= 0 && now - runningSince >= STABLE_MS) recoveries = 0;
        due = -1;
        recovery = false;
        runningSince = -1;
        lastRestart = now;
        return true;
    }
}
