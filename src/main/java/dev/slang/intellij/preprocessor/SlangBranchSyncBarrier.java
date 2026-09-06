package dev.slang.intellij.preprocessor;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** EDT-owned state; native sync callbacks must be queued after the document listener cycle. */
final class SlangBranchSyncBarrier {
    private final Map<Object, Long> pending = new IdentityHashMap<>();

    void edited(Object document, long stamp) { pending.put(document, stamp); }

    void synchronizedLater(Object document, long observedStamp, LongSupplier currentStamp,
                           Consumer<Runnable> defer, Runnable afterSync) {
        defer.accept(() -> {
            if (currentStamp.getAsLong() == observedStamp) pending.remove(document, observedStamp);
            afterSync.run();
        });
    }

    void retain(Set<?> documents) { pending.keySet().removeIf(document -> !documents.contains(document)); }
    boolean isReady() { return pending.isEmpty(); }
    void clear() { pending.clear(); }
}
