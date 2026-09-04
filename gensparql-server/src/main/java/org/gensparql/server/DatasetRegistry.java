package org.gensparql.server;

import org.apache.jena.query.Dataset;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

final class DatasetRegistry implements AutoCloseable {
    private final Map<String, Dataset> datasets = new ConcurrentHashMap<>();

    String put(Dataset dataset) {
        String id = UUID.randomUUID().toString();
        datasets.put(id, dataset);
        return id;
    }

    Dataset get(String id) {
        return id == null ? null : datasets.get(id);
    }

    @Override
    public void close() {
        datasets.values().forEach(Dataset::close);
        datasets.clear();
    }
}
