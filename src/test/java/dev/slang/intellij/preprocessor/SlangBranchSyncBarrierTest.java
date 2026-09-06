package dev.slang.intellij.preprocessor;

import org.junit.Test;

import java.util.ArrayDeque;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.*;

public class SlangBranchSyncBarrierTest {
    @Test public void earlyNativeNotificationDoesNotLeaveDocumentWaitingForever() {
        var barrier = new SlangBranchSyncBarrier();
        var queue = new ArrayDeque<Runnable>();
        Object document = new Object();
        var refreshes = new AtomicInteger();
        barrier.synchronizedLater(document, 2, () -> 2, queue::add, refreshes::incrementAndGet);
        // Native fileEdited is observed first, then our DocumentListener.
        barrier.edited(document, 2);
        assertFalse(barrier.isReady());
        assertEquals(0, refreshes.get());
        queue.remove().run();
        assertTrue(barrier.isReady());
        assertEquals(1, refreshes.get());
    }

    @Test public void oldAcknowledgmentCannotReleaseNewerDependencyEdits() {
        var barrier = new SlangBranchSyncBarrier();
        var queue = new ArrayDeque<Runnable>();
        var current = new AtomicLong(2);
        Object document = new Object();
        barrier.edited(document, 2);
        barrier.synchronizedLater(document, 2, current::get, queue::add, () -> {});
        current.set(3);
        barrier.edited(document, 3);
        queue.remove().run();
        assertFalse(barrier.isReady());
        barrier.synchronizedLater(document, 3, current::get, queue::add, () -> {});
        queue.remove().run();
        assertTrue(barrier.isReady());
    }

    @Test public void closingDocumentsAndRestartingClearTheBarrier() {
        var barrier = new SlangBranchSyncBarrier();
        Object root = new Object();
        Object include = new Object();
        barrier.edited(root, 1);
        barrier.edited(include, 2);
        barrier.retain(Set.of(root));
        assertFalse(barrier.isReady());
        barrier.clear();
        assertTrue(barrier.isReady());
        barrier.edited(root, 2);
        barrier.retain(Set.of());
        assertTrue(barrier.isReady());
    }
}
