package com.magicpin.bot.controller;

import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/v1")
public class MetadataController {

    @GetMapping("/metadata")
    public Map<String, Object> meta() {
        return Map.of(
                "name", "Sumit Bot",
                "version", "1.0"
        );
    }
}