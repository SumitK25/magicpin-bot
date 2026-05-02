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

    public Collection<Map<String, Object>> getAll() {
        return store.values();
    }
}