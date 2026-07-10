package org.gensparql.llm.cache;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Statistics for cache operations.
 */
public class CacheStatistics {

    private final AtomicLong hits = new AtomicLong(0);
    private final AtomicLong misses = new AtomicLong(0);
    private final AtomicLong puts = new AtomicLong(0);
    private final AtomicLong evictions = new AtomicLong(0);

    public void recordHit() {
        hits.incrementAndGet();
    }

    public void recordMiss() {
        misses.incrementAndGet();
    }

    public void recordPut() {
        puts.incrementAndGet();
    }

    public void recordEviction() {
        evictions.incrementAndGet();
    }

    public long getHits() {
        return hits.get();
    }

    public long getMisses() {
        return misses.get();
    }

    public long getPuts() {
        return puts.get();
    }

    public long getEvictions() {
        return evictions.get();
    }

    public long getTotalAccesses() {
        return hits.get() + misses.get();
    }

    public double getHitRate() {
        long total = getTotalAccesses();
        return total == 0 ? 0.0 : (double) hits.get() / total;
    }

    public void reset() {
        hits.set(0);
        misses.set(0);
        puts.set(0);
        evictions.set(0);
    }

    @Override
    public String toString() {
        return String.format("CacheStatistics{hits=%d, misses=%d, hitRate=%.2f%%, puts=%d, evictions=%d}",
            hits.get(), misses.get(), getHitRate() * 100, puts.get(), evictions.get());
    }
}
