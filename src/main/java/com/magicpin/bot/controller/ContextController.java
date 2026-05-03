package com.magicpin.bot.controller;

import com.magicpin.bot.repository.InMemoryStore;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/v1")
@RequiredArgsConstructor
public class ContextController {

    private final InMemoryStore store;

    @PostMapping("/context")
    public Map<String, Object> save(@RequestBody Map<String, Object> req) {
        String scope     = (String) req.get("scope");
        String contextId = (String) req.get("context_id");

        // Key format must be "scope:context_id" — TickController looks up "trigger:trg_xxx"
        String key = scope + ":" + contextId;
        store.save(key, req);

        return Map.of("accepted", true);
    }
}