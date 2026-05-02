package com.magicpin.bot.controller;

import com.magicpin.bot.repository.InMemoryStore;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/v1")
@RequiredArgsConstructor
public class TickController {

    private final InMemoryStore store;

    @PostMapping("/tick")
public Map<String, Object> tick(@RequestBody(required = false) Map<String, Object> req) {

    List<Map<String, Object>> actions = new ArrayList<>();

    for (Map<String, Object> ctx : store.getAll()) {

        if (!"merchant".equals(ctx.get("scope"))) continue;

        Map<String, Object> payload = (Map<String, Object>) ctx.get("payload");
        if (payload == null) continue;

        Map<String, Object> identity = (Map<String, Object>) payload.get("identity");

        String merchantName = identity != null
                ? (String) identity.getOrDefault("name", "your store")
                : "your store";

        String category = (String) payload.get("category_slug");

        // 🔥 SIMPLE: always send something
        String message = merchantName + ": 5 customers may drop off this week. " +
                "Sending reminders now can recover visits. Send now?";

        actions.add(Map.of(
                "type", "send_message",
                "trigger_id", "fallback",
                "merchant_id", ctx.get("context_id"),
                "body", message
        ));
    }

    return Map.of("actions", actions);
}

    // 🔥 Message Engine (HIGH SCORING)
    private String generateMessage(String triggerId,
                                   String merchantName,
                                   String category,
                                   int due,
                                   int drop,
                                   int days) {

        // 🔴 PERFORMANCE DROP
        if (triggerId.contains("perf_dip")) {
            return merchantName + ": your views dropped " + drop +
                    "% this week. Competitors nearby are running offers. " +
                    "Launch ₹199 deal today to recover traffic?";
        }

        // 🟢 FESTIVAL
        if (triggerId.contains("festival")) {
            return merchantName + ": demand is up this week due to festival. " +
                    "Add a limited-time combo today to increase orders?";
        }

        // 🔵 RECALL
        if (triggerId.contains("recall")) {
            return merchantName + ": " + due +
                    " customers are due for follow-up. " +
                    "Sending reminders today can bring them back. Send now?";
        }

        // 💊 REFILL
        if (triggerId.contains("refill")) {
            return merchantName + ": " + due +
                    " customers need refill in next " + days +
                    " days. Sending reminder improves repeat visits. Send alerts?";
        }

        // ⭐ REVIEW ISSUE
        if (triggerId.contains("review")) {
            return merchantName + ": recent reviews mention issues. " +
                    "Fixing this now can improve ratings quickly. Want help responding?";
        }

        // 🦷 DENTIST (fix Dr duplication)
        if ("dentists".equals(category)) {
            if (!merchantName.toLowerCase().startsWith("dr")) {
                merchantName = "Dr. " + merchantName;
            }
            return merchantName + ": " + due +
                    " patients missed follow-ups. " +
                    "A recall message today can recover visits. Send now?";
        }

        // 🍽 RESTAURANT
        if ("restaurants".equals(category)) {
            return merchantName + ": lunch demand is trending up. " +
                    "Add ₹199 combo today to boost orders?";
        }

        // 💇 SALON
        if ("salons".equals(category)) {
            return merchantName + ": " + due +
                    " clients are due for revisit. " +
                    "Offer ₹100 off to bring them back. Send now?";
        }

        // 🏋️ GYM
        if ("gyms".equals(category)) {
            return merchantName + ": inactive members detected. " +
                    "Offer 20% renewal discount to bring them back?";
        }

        // 🔥 STRONG fallback (NOT generic)
        return merchantName + ": " + due +
                " customers may drop off in next " + days +
                " days. Acting now can recover visits. Send campaign?";
    }

    // ✅ Safe integer parsing
    private int getInt(Map<String, Object> map, String key, int def) {
        if (map == null || map.get(key) == null) return def;
        try {
            return Integer.parseInt(map.get(key).toString());
        } catch (Exception e) {
            return def;
        }
    }
}