package com.magicpin.bot.repository;

import org.springframework.stereotype.Component;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class InMemoryStore {

    private final Map<String, Map<String, Object>> store = new ConcurrentHashMap<>();

    public void save(String key, Map<String, Object> data) {
        store.put(key, data);
    }

    // Existing method
    public Collection<Map<String, Object>> getAll() {
        return store.values();
    }

    // NEW — needed by TickController to resolve trigger IDs
    public Map<String, Object> getByKey(String key) {
        return store.get(key);
    }
}