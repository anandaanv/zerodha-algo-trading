package com.dtech.aitrader.v2.rules.ew;

import com.dtech.aitrader.v2.rules.Family;
import com.dtech.aitrader.v2.rules.Firing;
import com.dtech.aitrader.v2.rules.FiresOn;
import com.dtech.aitrader.v2.rules.Pass;
import com.dtech.aitrader.v2.rules.Rule;
import com.dtech.aitrader.v2.rules.SpawnAnchorMode;
import com.dtech.aitrader.v2.rules.SymbolContext;
import com.dtech.kitecon.service.copilot.dto.MarketStructurePoint;
import com.dtech.kitecon.service.copilot.dto.MarketStructurePoint.PivotType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pass-2 EW wave-labeling enumeration. Reads the Pass-1 macro anchor FACT, maps the post-anchor
 * Wk pivots onto candidate wave structures, emits one CANDIDATE firing per form.
 *
 * <p>Initial coverage (RELIANCE blessed reference {@code cde6bbc9}):
 * <ul>
 *   <li><b>Zigzag (A-B-C corrective)</b> — A = anchor→counter, B = counter→most-recent-HIGH after
 *       counter, C beginning from B-end. RELIANCE MF1 target: A=1611.8→1290, B=1290→1473.4,
 *       basePrior=0.45.</li>
 *   <li><b>Impulse (W0-W1, W2 in progress)</b> — W0 = counter (re-frames the structure as bullish
 *       from the LL), W1 = counter→most-recent-HIGH, W2 pullback in progress. RELIANCE MF2:
 *       W0=1290, W1=1290→1473.4, basePrior=0.25.</li>
 * </ul>
 *
 * <p>Per Pass-2 spec ab9bd541 §"PASS 2 — ENUMERATION":
 * <ul>
 *   <li>≥2 framings emitted when ambiguity exists, with ≥1 impulsive and ≥1 corrective (Rule 1).
 *       Both framings flagged {@link SpawnAnchorMode#SAME_ANCHOR}; RE_ANCHOR is reserved for
 *       Pass-5 feedback spawns.</li>
 *   <li>Pivot assignment cites REAL pivots (no invented levels — every {price, date} pair traces
 *       to a Wk pivot in the scan-context CSV).</li>
 *   <li>Other forms (flat, expanded-flat, triangle, WXY) will be added incrementally as the
 *       reference set expands beyond RELIANCE.</li>
 * </ul>
 *
 * <p>basePrior values come from Pass-4 magnitude classification (B=57% drives MF1 up); this rule
 * emits initial priors derived from a small lookup keyed on (form, role) — Pass-4 will adjust.
 */
@Component
@Slf4j
public class EwEnumerationRule implements Rule {

    public static final String RULE_ID = "EW_ENUMERATION";

    // Initial priors per form. Pass-4 magnitude classification + cluster confluence shift these.
    private static final double ZIGZAG_BASE_PRIOR = 0.45;
    private static final double IMPULSE_BASE_PRIOR = 0.25;

    @Override public String ruleId() { return RULE_ID; }
    @Override public Pass pass() { return Pass.P2_ENUMERATION; }
    @Override public Family family() { return Family.EW; }

    @Override
    public List<Firing> evaluate(SymbolContext ctx, List<Firing> priorFirings) {
        // Pass-1 anchor FACT must be available.
        Firing anchorFact = priorFirings.stream()
                .filter(f -> EwMacroAnchorRule.RULE_ID.equals(f.getRuleId()))
                .filter(f -> f.getFiresOn() == FiresOn.FACT)
                .findFirst().orElse(null);
        if (anchorFact == null) return List.of();
        Map<String, Object> ap = anchorFact.getPayload();
        if (!Boolean.TRUE.equals(ap.get("data_sufficient"))) return List.of();

        String anchorKind = (String) ap.get("anchor_kind");
        double anchorPrice = numberOf(ap.get("anchor_price"));
        String anchorDateStr = (String) ap.get("anchor_date");
        double counterPrice = numberOf(ap.get("counter_extreme_price"));
        String counterDateStr = (String) ap.get("counter_extreme_date");
        String roleCandidate = (String) ap.get("role_candidate");
        if (anchorDateStr == null || counterDateStr == null) return List.of();

        Instant anchorInstant = parseDate(anchorDateStr);
        Instant counterInstant = parseDate(counterDateStr);

        // Wk pivots — prefer pivotsByTf, fall back to legacy ctx.pivots.
        List<MarketStructurePoint> pivots = ctx.getPivotsByTf() != null
                ? ctx.getPivotsByTf().getOrDefault("Week", ctx.getPivots())
                : ctx.getPivots();
        if (pivots == null || pivots.isEmpty()) return List.of();

        List<Firing> emitted = new ArrayList<>();

        if ("corrective".equals(roleCandidate) && "HIGH".equalsIgnoreCase(anchorKind)) {
            // Find most-recent HIGH pivot strictly after the counter date — that's the B_end
            // CANDIDATE (per SPEC-006 1d3e3c25: tentative, awaiting Pass-5 EwWaveCompletionRule
            // confirmation — innocent until proven complete).
            MarketStructurePoint bEnd = findMostRecentOfKindAfter(pivots, counterInstant, PivotType.HIGH);

            // ── MF1: zigzag A-B-C (corrective from anchor down to counter, B bounce, C beginning)
            List<Map<String, Object>> mf1Assignment = new ArrayList<>();
            mf1Assignment.add(rolePivot("A_start", anchorDateStr, anchorPrice, STATE_COMPLETE));
            mf1Assignment.add(rolePivot("A_end", counterDateStr, counterPrice, STATE_COMPLETE));
            if (bEnd != null) {
                mf1Assignment.add(rolePivot("B_start", counterDateStr, counterPrice, STATE_COMPLETE));
                // B_end starts as CANDIDATE — Pass-5 EwWaveCompletionRule may upgrade to COMPLETE
                // (sub-structure confirms) or downgrade to IN_PROGRESS (still only B.A formed).
                mf1Assignment.add(rolePivot("B_end", toIstDate(bEnd.getTimestamp()), bEnd.getPrice(), STATE_CANDIDATE));
                mf1Assignment.add(rolePivot("C", null, null, STATE_NOT_STARTED));
            }
            emitted.add(buildCandidate(ctx, anchorFact, "zigzag", "ABC",
                    mf1Assignment, ZIGZAG_BASE_PRIOR,
                    "A from macro anchor to counter LL; B bounce to most-recent HIGH (CANDIDATE, awaits Pass-5 confirmation); C not yet started"));

            // ── MF2: impulse — re-frames the structure as bullish from the LL (counter = W0).
            if (bEnd != null) {
                List<Map<String, Object>> mf2Assignment = new ArrayList<>();
                mf2Assignment.add(rolePivot("W0", counterDateStr, counterPrice, STATE_COMPLETE));
                mf2Assignment.add(rolePivot("W1_end", toIstDate(bEnd.getTimestamp()), bEnd.getPrice(), STATE_CANDIDATE));
                mf2Assignment.add(rolePivot("W2", null, null, STATE_NOT_STARTED));
                emitted.add(buildCandidate(ctx, anchorFact, "impulse", "W1-W2",
                        mf2Assignment, IMPULSE_BASE_PRIOR,
                        "Alternate: counter LL becomes W0; bounce to most-recent HIGH is W1_end (CANDIDATE, awaits Pass-5 confirmation); W2 not yet started"));
            }
        } else if ("impulsive".equals(roleCandidate) && "LOW".equalsIgnoreCase(anchorKind)) {
            // Mirror case (not RELIANCE today): anchor is a LOW, structure rallying.
            MarketStructurePoint w2End = findMostRecentOfKindAfter(pivots, counterInstant, PivotType.LOW);
            List<Map<String, Object>> impulse = new ArrayList<>();
            impulse.add(rolePivot("W0", anchorDateStr, anchorPrice, STATE_COMPLETE));
            impulse.add(rolePivot("W1_end", counterDateStr, counterPrice, STATE_CANDIDATE));
            if (w2End != null) {
                impulse.add(rolePivot("W2_end", toIstDate(w2End.getTimestamp()), w2End.getPrice(), STATE_CANDIDATE));
                impulse.add(rolePivot("W3", null, null, STATE_NOT_STARTED));
            }
            emitted.add(buildCandidate(ctx, anchorFact, "impulse", "W1-W2-W3?",
                    impulse, ZIGZAG_BASE_PRIOR,
                    "Impulse rally from macro low; W1 to counter HIGH (CANDIDATE); W2 pullback (CANDIDATE); W3 not yet started"));

            // Alternate corrective: ABC from anchor up.
            if (w2End != null) {
                List<Map<String, Object>> zigzag = new ArrayList<>();
                zigzag.add(rolePivot("A_start", anchorDateStr, anchorPrice, STATE_COMPLETE));
                zigzag.add(rolePivot("A_end", counterDateStr, counterPrice, STATE_COMPLETE));
                zigzag.add(rolePivot("B_end", toIstDate(w2End.getTimestamp()), w2End.getPrice(), STATE_CANDIDATE));
                zigzag.add(rolePivot("C", null, null, STATE_NOT_STARTED));
                emitted.add(buildCandidate(ctx, anchorFact, "zigzag", "ABC-up",
                        zigzag, IMPULSE_BASE_PRIOR,
                        "Alternate: upward ABC from anchor; B pullback (CANDIDATE); C not yet started"));
            }
        }
        return emitted;
    }

    /** Wave-completeness state per SPEC-006 (1d3e3c25). Stringly-typed per option U3a. */
    public static final String STATE_COMPLETE = "COMPLETE";
    public static final String STATE_IN_PROGRESS = "IN_PROGRESS";
    public static final String STATE_CANDIDATE = "CANDIDATE";
    public static final String STATE_NOT_STARTED = "NOT_STARTED";

    // ── candidate factory ──────────────────────────────────────────────────────

    private Firing buildCandidate(SymbolContext ctx, Firing anchorFact, String form,
                                    String degreePlacement,
                                    List<Map<String, Object>> pivotAssignment,
                                    double basePrior, String rationale) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("form", form);
        payload.put("degree_placement", degreePlacement);
        payload.put("pivot_assignment", pivotAssignment);
        payload.put("rationale", rationale);
        payload.put("anchor_ref", anchorFact.getId());

        return Firing.builder()
                .ruleId(RULE_ID)
                .symbol(ctx.getSymbol())
                .tf(ctx.getTf())
                .asOf(ctx.getAsOf())
                .family(Family.EW)
                .pass(Pass.P2_ENUMERATION)
                .firesOn(FiresOn.CANDIDATE)
                .basePrior(basePrior)
                .spawnAnchorMode(SpawnAnchorMode.SAME_ANCHOR)
                .refs(List.of(anchorFact.getId()))
                .roundNum(1)
                .payload(payload)
                .context(ctx.getProbe())
                .build();
    }

    // ── pivot lookup helpers ───────────────────────────────────────────────────

    /** Most recent pivot of given kind whose timestamp is strictly AFTER {@code after}. */
    private static MarketStructurePoint findMostRecentOfKindAfter(
            List<MarketStructurePoint> pivots, Instant after, PivotType kind) {
        MarketStructurePoint best = null;
        for (MarketStructurePoint p : pivots) {
            if (p.getPivotType() != kind) continue;
            if (p.getTimestamp() == null || !p.getTimestamp().isAfter(after)) continue;
            if (best == null || p.getTimestamp().isAfter(best.getTimestamp())) best = p;
        }
        return best;
    }

    /**
     * Build a role entry with explicit wave-state per SPEC-006. Roles dated from real pivots
     * (anchor, counter, B_end) are typically COMPLETE or CANDIDATE; null-date roles (C, W2)
     * are NOT_STARTED.
     */
    private static Map<String, Object> rolePivot(String role, String date, Double price, String state) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", role);
        m.put("date", date);
        m.put("price", price);
        m.put("state", state);
        return m;
    }

    private static double numberOf(Object o) {
        return o instanceof Number n ? n.doubleValue() : 0.0;
    }

    private static Instant parseDate(String isoDate) {
        // anchor_date / counter_extreme_date are stamped as IST dates by Pass-1; convert via IST.
        return LocalDate.parse(isoDate).atStartOfDay(ZoneId.of("Asia/Kolkata")).toInstant();
    }

    private static String toIstDate(Instant t) {
        if (t == null) return null;
        return LocalDate.ofInstant(t, ZoneId.of("Asia/Kolkata")).toString();
    }
}
