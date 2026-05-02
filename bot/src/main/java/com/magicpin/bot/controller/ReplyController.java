package com.magicpin.bot.controller;

import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/v1")
public class ReplyController {

    @PostMapping("/reply")
    public Map<String, Object> reply(@RequestBody Map<String, Object> req) {

        String text = (String) req.get("text");

        if (text != null && text.toLowerCase().contains("yes")) {
            return Map.of("text", "Great! Campaign started.");
        }

        return Map.of("text", "Okay, try another offer?");
    }
}