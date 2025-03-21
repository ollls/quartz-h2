package io.quartz.iouring.util;

import java.util.concurrent.atomic.AtomicInteger;

public class ReferenceCounter<T> {
    private final T ref;
    private final AtomicInteger referenceCount = new AtomicInteger(0);

    public ReferenceCounter(T ref) {
        this.ref = ref;
    }

    public T ref() {
        return ref;
    }

    public int incrementReferenceCount() {
        return referenceCount.incrementAndGet();
    }

    public int deincrementReferenceCount() {
        return referenceCount.decrementAndGet();
    }
}
