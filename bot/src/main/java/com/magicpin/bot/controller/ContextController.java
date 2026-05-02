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
        String key = req.get("scope") + ":" + req.get("context_id");
        store.save(key, req);
        return Map.of("accepted", true);
    }
}