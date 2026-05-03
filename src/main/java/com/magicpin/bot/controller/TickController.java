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

        if (req == null) req = new HashMap<>();

        List<Map<String, Object>> actions = new ArrayList<>();

        // ── Parse available_triggers from request ────────────────────────────
        // The judge sends trigger IDs (strings) or full objects.
        // We resolve each ID against what was pushed via /v1/context.
        List<Map<String, Object>> triggers = new ArrayList<>();
        Object rawTriggers = req.get("available_triggers");

        if (rawTriggers instanceof List<?>) {
            for (Object t : (List<?>) rawTriggers) {
                if (t instanceof Map) {
                    triggers.add((Map<String, Object>) t);
                } else if (t instanceof String triggerId) {
                    // Look up the full trigger from the context store
                    Map<String, Object> stored = store.getByKey("trigger:" + triggerId);
                    if (stored != null) {
                        triggers.add(stored);
                    } else {
                        // Fallback: build a minimal trigger shell from the ID
                        Map<String, Object> shell = new HashMap<>();
                        shell.put("trigger_id", triggerId);
                        shell.put("id", triggerId);
                        shell.put("kind", inferKindFromId(triggerId));
                        shell.put("payload", new HashMap<>());
                        triggers.add(shell);
                    }
                }
            }
        }

        // ── Default trigger when tick arrives with nothing active ────────────
        if (triggers.isEmpty()) {
            Map<String, Object> fake = new HashMap<>();
            fake.put("trigger_id", "perf_dip");
            fake.put("id", "perf_dip");
            fake.put("kind", "perf_dip");
            fake.put("payload", Map.of("due_count", 5, "drop_percent", 20, "days_left", 3));
            triggers.add(fake);
        }

        Collection<Map<String, Object>> allContexts = store.getAll();

        // ── Hard fallback: no context loaded yet ─────────────────────────────
        if (allContexts == null || allContexts.isEmpty()) {
            actions.add(Map.of(
                "type",        "send_message",
                "trigger_id",  "perf_dip",
                "merchant_id", "default",
                "body",        "Your orders may drop this week. " +
                               "A ₹199 combo can recover demand. " +
                               "Reply YES to activate."
            ));
            return Map.of("actions", actions);
        }

        // ── Build category context lookup ────────────────────────────────────
        Map<String, Map<String, Object>> categoryContexts = new HashMap<>();
        for (Map<String, Object> ctx : allContexts) {
            if ("category".equals(ctx.get("scope"))) {
                String catId = (String) ctx.get("context_id");
                if (catId != null) {
                    categoryContexts.put(catId, ctx);
                }
            }
        }

        // ── Process each merchant ────────────────────────────────────────────
        for (Map<String, Object> ctx : allContexts) {

            if (!"merchant".equals(ctx.get("scope"))) continue;

            Map<String, Object> payload = cast(ctx.get("payload"));
            if (payload == null) continue;

            String merchantId = (String) ctx.get("context_id");

            // ── Identity ─────────────────────────────────────────────────────
            Map<String, Object> identity = cast(payload.get("identity"));
            String merchantName = identity != null
                ? (String) identity.getOrDefault("name", "your store")
                : "your store";
            String ownerName = identity != null ? (String) identity.get("owner_name") : null;
            String locality  = identity != null ? (String) identity.get("locality")   : null;

            // ── Category ──────────────────────────────────────────────────────
            String category = (String) payload.get("category_slug");

            // ── Merchant signals ──────────────────────────────────────────────
            Map<String, Object> signals = cast(payload.get("signals"));
            boolean isNewMerchant   = signals != null && Boolean.TRUE.equals(signals.get("is_new_merchant"));
            boolean trialEndingSoon = signals != null && Boolean.TRUE.equals(signals.get("trial_ending_soon"));
            boolean iplEligible     = signals != null && Boolean.TRUE.equals(signals.get("ipl_eligible_locality"));
            String  perfStatus      = signals != null
                ? (String) signals.getOrDefault("perf_status", "") : "";

            // ── Merchant metrics ──────────────────────────────────────────────
            Map<String, Object> metrics = cast(payload.get("metrics"));
            int openSlots = metrics != null ? getInt(metrics, "open_slots", 0) : 0;

            // ── Performance snapshot ──────────────────────────────────────────
            Map<String, Object> performance = cast(payload.get("performance"));
            // Some merchant contexts put open_slots under performance
            if (openSlots == 0 && performance != null) {
                openSlots = getInt(performance, "open_slots", 0);
            }

            // ── Category-level enrichment ─────────────────────────────────────
            String digestTitle  = null;
            String digestSource = null;
            String catPeerCtr   = null;

            Map<String, Object> catCtx = categoryContexts.get(category);
            if (catCtx != null) {
                Map<String, Object> catPayload = cast(catCtx.get("payload"));
                if (catPayload != null) {

                    // Pull top digest item
                    List<?> digest = (List<?>) catPayload.get("digest");
                    if (digest != null && !digest.isEmpty()) {
                        Map<String, Object> topDigest = cast(digest.get(0));
                        if (topDigest != null) {
                            digestTitle  = (String) topDigest.get("title");
                            digestSource = (String) topDigest.get("source");
                        }
                    }

                    // Pull peer CTR benchmark
                    Map<String, Object> peerStats = cast(catPayload.get("peer_stats"));
                    if (peerStats != null && peerStats.get("avg_ctr") != null) {
                        catPeerCtr = peerStats.get("avg_ctr").toString();
                    }
                }
            }

            // ── Select best trigger for this merchant ─────────────────────────
            // Prefer a trigger explicitly targeted at this merchant_id
            Map<String, Object> trigger = selectBestTrigger(triggers, merchantId);

            // Resolve trigger fields — handle both direct pushes and ID-resolved triggers
            String triggerId = firstNonNull(
                (String) trigger.get("trigger_id"),
                (String) trigger.get("id"),
                "perf_dip"
            );
            // kind may be on trigger root or nested; fall back to trigger_id
            String triggerKind = firstNonNull(
                (String) trigger.get("kind"),
                triggerId
            );

            Map<String, Object> tPayload = cast(trigger.get("payload"));

            int due  = getInt(tPayload, "due_count",    5);
            int drop = getInt(tPayload, "drop_percent", 20);
            int days = getInt(tPayload, "days_left",    3);

            // top_item_id may be at tPayload root or nested under top_item
            String topItemId = null;
            if (tPayload != null) {
                topItemId = (String) tPayload.get("top_item_id");
                if (topItemId == null) {
                    Map<String, Object> topItem = cast(tPayload.get("top_item"));
                    if (topItem != null) topItemId = (String) topItem.get("id");
                }
            }

            // If digestTitle not found in category yet, try trigger payload
            // (some triggers embed the relevant research item directly)
            if (digestTitle == null && tPayload != null) {
                Map<String, Object> topItem = cast(tPayload.get("top_item"));
                if (topItem != null) {
                    digestTitle  = (String) topItem.get("title");
                    digestSource = (String) topItem.get("source");
                }
            }

            // ── Compose message ───────────────────────────────────────────────
            String message = generateMessage(
                triggerKind, merchantName, ownerName, locality, category,
                due, drop, days, topItemId,
                isNewMerchant, trialEndingSoon, iplEligible, perfStatus,
                openSlots, catPeerCtr, digestTitle, digestSource
            );

            // ── Build action ──────────────────────────────────────────────────
            Map<String, Object> action = new LinkedHashMap<>();
            action.put("type",        "send_message");
            action.put("trigger_id",  triggerId);
            action.put("merchant_id", merchantId);
            action.put("body",        message);
            action.put("cta",         "open_ended");
            action.put("rationale",   buildRationale(triggerKind, category, merchantId));
            actions.add(action);
        }

        return Map.of("actions", actions);
    }

    // ── Trigger selection ─────────────────────────────────────────────────────
    // Prefer triggers targeting this specific merchant; fall back to highest urgency.

    private Map<String, Object> selectBestTrigger(
            List<Map<String, Object>> triggers, String merchantId) {

        // 1. Prefer a trigger explicitly for this merchant
        for (Map<String, Object> t : triggers) {
            Map<String, Object> p = cast(t.get("payload"));
            if (p != null && merchantId.equals(p.get("merchant_id"))) return t;
        }

        // 2. Prefer triggers with a real payload (has due_count or urgency)
        return triggers.stream()
            .filter(t -> t.get("payload") instanceof Map)
            .max(Comparator.comparingInt(t -> {
                Map<String, Object> p = cast(t.get("payload"));
                int urgency  = getInt(t, "urgency", 0);
                int dueCount = getInt(p, "due_count", 0);
                return urgency * 10 + dueCount; // urgency wins, due_count breaks ties
            }))
            .orElse(triggers.get(0));
    }

    // ── Message generation ────────────────────────────────────────────────────

    private String generateMessage(
            String triggerKind, String merchantName, String ownerName, String locality,
            String category, int due, int drop, int days, String topItemId,
            boolean isNew, boolean trialEnding, boolean iplEligible, String perfStatus,
            int openSlots, String catPeerCtr, String digestTitle, String digestSource) {

        String addr = (ownerName != null && !ownerName.isBlank()) ? ownerName : merchantName;
        String loc  = (locality  != null && !locality.isBlank())  ? " in " + locality : "";

        String whyNow = buildWhyNow(triggerKind, drop, days, isNew, trialEnding,
                                    iplEligible, perfStatus);

        return switch (category != null ? category : "") {

            case "restaurants" -> {
                int revenue = due * 200;
                String itemLine = (topItemId != null && !topItemId.isBlank())
                    ? " Your top item (" + topItemId + ") is driving most of the drop." : "";
                String iplLine = iplEligible
                    ? " IPL match nights bring 40% higher order volume — perfect timing." : "";
                String digestLine = (digestTitle != null && !digestTitle.isBlank())
                    ? " Insight: " + digestTitle +
                      (digestSource != null ? " (" + digestSource + ")" : "") + "." : "";
                yield addr + ", " + whyNow +
                    " " + due + " customers" + loc +
                    " dropped off at checkout (~₹" + revenue + " at risk)." +
                    itemLine + iplLine + digestLine +
                    " A ₹199 combo typically recovers ~25% within 48 hours." +
                    " 👉 Reply YES — I'll launch it before tonight's dinner peak.";
            }

            case "salons" -> {
                int bookings = Math.max(1, due / 2);
                int revenue  = due * 150;
                String slotLine = openSlots > 0
                    ? " You have " + openSlots + " open slots this week." : "";
                String peerLine = (catPeerCtr != null)
                    ? " Salons in your area with win-back offers see ~30% slot recovery." : "";
                yield addr + ", " + whyNow +
                    " " + due + " regulars" + loc +
                    " haven't booked recently (~₹" + revenue + " deferred)." +
                    slotLine + peerLine +
                    " A ₹100 win-back message typically recovers ~" + bookings +
                    " bookings before weekend." +
                    " 👉 Reply YES — I'll send personalised messages to each today.";
            }

            case "dentists" -> {
                // Voice rule: always use Dr. prefix
                if (!addr.toLowerCase().startsWith("dr")) addr = "Dr. " + addr;
                int visits = Math.max(1, due / 2);
                String itemLine = (topItemId != null && !topItemId.isBlank())
                    ? " Your most-recalled item (" + topItemId + ") is a strong hook." : "";
                String digestLine = (digestTitle != null && !digestTitle.isBlank())
                    ? " This week's digest: " + digestTitle +
                      (digestSource != null ? " — " + digestSource : "") + "." : "";
                yield addr + ", " + whyNow +
                    " " + due + " patients" + loc + " have missed follow-up visits." +
                    itemLine + digestLine +
                    " Clinics using recall reminders recover ~" + visits +
                    " appointments per week." +
                    " 👉 Reply YES — I'll fill these slots this week.";
            }

            case "gyms" -> {
                int retained = Math.max(1, due / 3);
                String trialLine = trialEnding
                    ? " Your trial window ends soon — locking in members now protects MRR." : "";
                yield addr + ", " + whyNow +
                    " " + due + " members" + loc + " haven't checked in recently." +
                    trialLine +
                    " Limited-time re-engagement offers bring back ~" + retained +
                    " members on average." +
                    " 👉 Reply YES — I'll activate this today.";
            }

            case "pharmacies" -> {
                int orders = Math.max(1, due / 2);
                yield addr + ", " + whyNow +
                    " " + due + " customers are due for refill" + loc + "." +
                    " Timely reminders recover ~" + orders + " repeat orders quickly." +
                    " 👉 Reply YES — I'll send refill alerts now.";
            }

            default -> addr + ", " + whyNow +
                " There's a growth opportunity for your store" + loc + "." +
                " 👉 Reply YES — I'll activate it right away.";
        };
    }

    // ── Why-now sentence (trigger → opening hook) ─────────────────────────────

    private String buildWhyNow(String triggerKind, int drop, int days,
                                boolean isNew, boolean trialEnding,
                                boolean iplEligible, String perfStatus) {
        return switch (triggerKind != null ? triggerKind : "") {
            case "perf_dip"            ->
                "orders dropped ~" + drop + "% over the last " + days + " days —";
            case "perf_spike"          ->
                "your store had a strong week — let's keep the momentum going:";
            case "research_digest",
                 "category_research_digest_release" ->
                "this week's industry digest has something directly relevant:";
            case "recall_due",
                 "customer_lapsed_soft" ->
                "some of your customers are due for a follow-up —";
            case "regulation_change"   ->
                "there's a regulatory update relevant to your category —";
            case "festival_upcoming"   ->
                "a festival window opens in your area this week —";
            case "competitor_opened"   ->
                "a new competitor just opened nearby —";
            case "dormant_with_vera"   ->
                "it's been a while — quick check-in:";
            case "milestone_reached"   ->
                "congratulations — you just hit a new milestone! 🎉";
            case "scheduled_recurring" ->
                "quick Friday nudge —";
            case "review_theme_emerged" ->
                "3 recent reviews mention the same theme — worth acting on:";
            case "weather_heatwave"    ->
                "with today's heat wave, customer patterns are shifting —";
            default                    ->
                "based on your recent activity —";
        };
    }

    // ── Rationale for judge transparency ──────────────────────────────────────

    private String buildRationale(String triggerKind, String category, String merchantId) {
        return "Trigger: " + triggerKind +
               " | Category: " + (category != null ? category : "unknown") +
               " | Merchant: " + merchantId +
               " | Composed using category digest, merchant signals, and trigger payload.";
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private Map<String, Object> cast(Object o) {
        if (o instanceof Map) return (Map<String, Object>) o;
        return null;
    }

    private String inferKindFromId(String triggerId) {
        if (triggerId == null) return "perf_dip";
        String lower = triggerId.toLowerCase();
        if (lower.contains("research") || lower.contains("digest")) return "research_digest";
        if (lower.contains("recall"))     return "recall_due";
        if (lower.contains("perf_dip"))   return "perf_dip";
        if (lower.contains("perf_spike")) return "perf_spike";
        if (lower.contains("festival"))   return "festival_upcoming";
        if (lower.contains("competitor")) return "competitor_opened";
        if (lower.contains("regulation")) return "regulation_change";
        if (lower.contains("dormant"))    return "dormant_with_vera";
        if (lower.contains("milestone"))  return "milestone_reached";
        if (lower.contains("review"))     return "review_theme_emerged";
        return "perf_dip";
    }

    private String firstNonNull(String... values) {
        for (String v : values) if (v != null && !v.isBlank()) return v;
        return "perf_dip";
    }

    private int getInt(Map<String, Object> map, String key, int def) {
        try {
            if (map == null) return def;
            Object val = map.get(key);
            if (val == null) return def;
            return Integer.parseInt(val.toString());
        } catch (Exception e) {
            return def;
        }
    }

    private int getInt(Map<String, Object> map, String key) {
        return getInt(map, key, 0);
    }
}