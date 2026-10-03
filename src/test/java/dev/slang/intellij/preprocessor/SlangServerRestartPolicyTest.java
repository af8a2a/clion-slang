package dev.slang.intellij.preprocessor;

import org.junit.Test;
import static org.junit.Assert.*;

public class SlangServerRestartPolicyTest {
    @Test public void changesWaitForQuietAndDoNotRestartAnInitializingServer() {
        var policy = new SlangServerRestartPolicy();
        policy.changed(0);
        policy.changed(1_500);
        assertFalse(policy.take(2_000, false));
        assertFalse(policy.take(3_500, true));
        assertTrue(policy.pending());
        assertTrue(policy.take(3_500, false));
        assertFalse(policy.take(5_000, false));
    }
    @Test public void modelUpdatesAreRateLimitedAcrossRestarts() {
        var policy = new SlangServerRestartPolicy();
        policy.changed(0);
        assertTrue(policy.take(2_000, false));
        policy.initializing();
        policy.changed(3_000);
        policy.running(4_000);
        assertEquals(7_000, policy.delay(5_000));
        assertFalse(policy.take(5_000, false));
        assertTrue(policy.take(12_000, false));
    }
    @Test public void transientRunningDoesNotResetTheRecoveryBudget() {
        var policy = new SlangServerRestartPolicy();
        for (int i = 0; i < 3; i++) {
            long now = i * 20_000L;
            policy.initializing();
            policy.running(now);
            assertTrue(policy.failed(now + 1));
            assertTrue(policy.take(now + 2_001, false));
        }
        policy.running(60_000);
        assertFalse(policy.failed(60_001));
        assertFalse(policy.pending());
        policy.changed(61_000); // Root events must not bypass exhausted recovery attempts.
        assertFalse(policy.take(63_000, false));
        assertFalse(policy.pending());
    }
    @Test public void stableSessionRenewsRecoveryBudget() {
        var policy = new SlangServerRestartPolicy();
        for (int i = 0; i < 3; i++) {
            assertTrue(policy.failed(i * 20_000L));
            assertTrue(policy.take(i * 20_000L + 2_000, false));
        }
        policy.running(50_000);
        assertTrue(policy.failed(80_000));
        assertTrue(policy.take(82_000, false));
    }
    @Test public void normalStopCancelsQueuedWork() {
        var policy = new SlangServerRestartPolicy();
        policy.changed(0);
        policy.stoppedNormally();
        assertFalse(policy.pending());
        assertFalse(policy.take(20_000, false));
    }
    @Test public void crashDuringRefreshRetainsRecoveryAndQuietPeriod() {
        var policy = new SlangServerRestartPolicy();
        policy.changed(1_000);
        assertTrue(policy.failed(2_000));
        assertFalse(policy.take(3_000, false));
        assertTrue(policy.take(4_000, false));
    }
}
