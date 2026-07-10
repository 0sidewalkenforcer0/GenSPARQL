package org.gensparql.llm.cache;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * In-memory LRU cache for LLM responses.
 * Thread-safe with read-write locking.
 */
public class InMemoryResponseCache implements ResponseCache {

    private static final int DEFAULT_MAX_SIZE = 10_000;

    private final int maxSize;
    private final Map<CacheKey, CachedResponse> cache;
    private final CacheStatistics statistics;
    private final ReadWriteLock lock;

    public InMemoryResponseCache() {
        this(DEFAULT_MAX_SIZE);
    }

    public InMemoryResponseCache(int maxSize) {
        this.maxSize = maxSize;
        this.statistics = new CacheStatistics();
        this.lock = new ReentrantReadWriteLock();

        // LRU cache using LinkedHashMap with access-order
        this.cache = new LinkedHashMap<CacheKey, CachedResponse>(maxSize, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<CacheKey, CachedResponse> eldest) {
                if (size() > maxSize) {
                    statistics.recordEviction();
                    return true;
                }
                return false;
            }
        };
    }

    @Override
    public CachedResponse get(CacheKey key) {
        lock.readLock().lock();
        try {
            CachedResponse response = cache.get(key);
            if (response == null) {
                statistics.recordMiss();
                return null;
            }

            // Check if expired
            if (response.isExpired()) {
                // Remove expired entry (need to upgrade to write lock)
                lock.readLock().unlock();
                lock.writeLock().lock();
                try {
                    cache.remove(key);
                    statistics.recordMiss();
                    return null;
                } finally {
                    lock.readLock().lock();
                    lock.writeLock().unlock();
                }
            }

            statistics.recordHit();
            return response;
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public void put(CacheKey key, CachedResponse response) {
        lock.writeLock().lock();
        try {
            cache.put(key, response);
            statistics.recordPut();
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public void invalidate(CacheKey key) {
        lock.writeLock().lock();
        try {
            cache.remove(key);
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public void clear() {
        lock.writeLock().lock();
        try {
            cache.clear();
            statistics.reset();
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public int size() {
        lock.readLock().lock();
        try {
            return cache.size();
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public CacheStatistics getStatistics() {
        return statistics;
    }

    public int getMaxSize() {
        return maxSize;
    }

    @Override
    public String toString() {
        return String.format("InMemoryResponseCache{size=%d/%d, %s}",
            size(), maxSize, statistics);
    }
}
