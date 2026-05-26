package com.dtech.aitrader.v2.rules;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Runs all registered {@link Rule}s against a {@link SymbolContext} in PASS ORDER (P0 → P6).
 * Each pass sees all firings emitted by prior passes; firings are append-only and immutable.
 *
 * <p>Per SPEC-004 ({@code 4e185036}): one global pass ordering with rules from many families
 * coexisting in each pass. Pass-2 candidates flow into Pass-3 validators flow into Pass-4
 * classifiers etc., communicated through the {@code priorFirings} list that grows as the engine
 * advances.
 *
 * <p>Path A scope: SINGLE ROUND only (round_num = 1 for every firing). The feedback / macro-revisit
 * machinery (SPEC-004-A) lands in a later increment.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MultiPassEngine {

    /**
     * Execute every rule in pass order. Rules within the same pass run in their input order
     * (Spring's bean ordering by name unless callers re-sort).
     *
     * @return the full list of firings emitted across all passes, with stable IDs assigned. Never
     *         {@code null}; empty when no rule fires.
     */
    public List<Firing> run(SymbolContext ctx, List<Rule> rules) {
        if (rules == null || rules.isEmpty()) return List.of();

        Map<Pass, List<Rule>> byPass = groupByPass(rules);
        List<Firing> allFirings = new ArrayList<>();
        Set<String> seenDigests = new HashSet<>();

        for (Pass p : Pass.values()) {  // P0..P6 by declared order
            List<Rule> rulesInPass = byPass.getOrDefault(p, List.of());
            if (rulesInPass.isEmpty()) continue;

            for (Rule rule : rulesInPass) {
                List<Firing> emitted;
                try {
                    emitted = rule.evaluate(ctx, Collections.unmodifiableList(allFirings));
                } catch (Exception e) {
                    log.warn("[engine] {} pass={} rule={} threw: {}",
                            ctx.getSymbol(), p, rule.ruleId(), e.getMessage());
                    continue;
                }
                if (emitted == null || emitted.isEmpty()) continue;
                for (Firing f : emitted) {
                    Firing stamped = withDigestId(f);
                    if (stamped == null) continue;
                    // Engine-level dedupe — two firings with identical content (same digest)
                    // are the same hypothesis; keep one, drop later duplicates. This mirrors
                    // the DB-level unique-index UPSERT and makes within-run idempotency a
                    // single mechanism (owner O1, ada56b20).
                    if (!seenDigests.add(stamped.getId())) {
                        log.debug("[engine] dedupe — duplicate firing rule={} digest={}",
                                rule.ruleId(), stamped.getId());
                        continue;
                    }
                    allFirings.add(stamped);
                }
            }
        }
        return allFirings;
    }

    /**
     * Stamp the firing with its content-addressable digest as id. If the firing already carries
     * an id (tests do this), respect it as-is. Otherwise compute the digest and rebuild the
     * firing with id=digest.
     */
    private static Firing withDigestId(Firing f) {
        if (f == null) return null;
        if (f.getId() != null && !f.getId().isBlank()) return f;
        String digest = FiringDigest.compute(
                f.getRuleId(), f.getSymbol(), f.getAsOf(), f.getRefs(), f.getPayload());
        return f.toBuilder().id(digest).build();
    }

    /** LinkedHash preserves Pass enum order, which equals declaration order. */
    private static Map<Pass, List<Rule>> groupByPass(List<Rule> rules) {
        Map<Pass, List<Rule>> out = new LinkedHashMap<>();
        for (Rule r : rules) {
            Pass p = r.pass() == null ? Pass.P6_SYNTHESIS : r.pass();
            out.computeIfAbsent(p, k -> new ArrayList<>()).add(r);
        }
        return out;
    }
}
