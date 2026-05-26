package com.dtech.aitrader.v2.rules.synthesis;

import com.dtech.aitrader.data.RuleFiring;
import com.dtech.aitrader.v2.rules.ContextProbeResult;
import com.dtech.aitrader.v2.rules.Family;
import com.dtech.aitrader.v2.rules.Firing;
import com.dtech.aitrader.v2.rules.FiresOn;
import com.dtech.aitrader.v2.rules.IndicatorConfluence;
import com.dtech.aitrader.v2.rules.MacroRegime;
import com.dtech.aitrader.v2.rules.Pass;
import com.dtech.aitrader.v2.rules.PriorFold;
import com.dtech.aitrader.v2.rules.Role;
import com.dtech.aitrader.v2.rules.Rule;
import com.dtech.aitrader.v2.rules.SrPosition;
import com.dtech.aitrader.v2.rules.SymbolContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pass-6 EW synthesis. Per SPEC reframe ({@code 159ba913}): the engine ENUMERATES all admissible
 * EW hypotheses (zigzag, flat, triangle, impulse, bigger-impulse, truncated-c, ...), keeps each
 * LIVE until invalidated by structural contradiction or price-action confirmation, and emits a
 * LEVEL-MAP — the (watch, invalidation) levels per live hypothesis — not a single confident
 * verdict.
 *
 * <p>Decision tree:
 * <ol>
 *   <li>Collect signature-evaluation firings (from {@link
 *       com.dtech.aitrader.v2.rules.ew.signature.EwSignatureEvaluationRule}); admitted forms
 *       become LIVE hypotheses. Cap at {@code rules.ew.verdict.max-live} (default 3).</li>
 *   <li>Collect pre-conclusion gate firings (from {@link
 *       com.dtech.aitrader.v2.rules.ew.EwPreConclusionGateRule}); each candidate has gate
 *       CONFIRMED / UNCONFIRMED.</li>
 *   <li>Collect SPEC-006 wave-completion state firings; terminal-wave state COMPLETE required for
 *       VERDICT.</li>
 *   <li>If exactly ONE live hypothesis AND its candidate's gate CONFIRMED AND wave-state COMPLETE
 *       → emit VERDICT with the structural levels (existing trigger / invalidation / target).</li>
 *   <li>Otherwise → emit WATCH carrying {@code hypotheses_live} — the level-map.</li>
 * </ol>
 *
 * <p>The old prior-ranking path is RETIRED. Per owner: validity is no-invalidation-yet, not a
 * weight. Priors are not assigned; they may be evidence-derived only and surfaced as
 * informational, never used to RANK hypotheses for verdict selection.
 */
@Component
@Slf4j
public class EwVerdictSynthesisRule implements Rule {

    public static final String RULE_ID = "EW_VERDICT_SYNTHESIS";

    /** Cap on the live hypothesis set surfaced in the level-map (owner: "~2-3, more = chaos"). */
    @Value("${rules.ew.verdict.max-live:3}")
    private int maxLive = 3;

    @Override public String ruleId() { return RULE_ID; }
    @Override public Pass pass() { return Pass.P6_SYNTHESIS; }
    @Override public Family family() { return Family.SYNTHESIS; }

    @Override
    public List<Firing> evaluate(SymbolContext ctx, List<Firing> priorFirings) {
        List<Firing> ewCandidates = priorFirings.stream()
                .filter(f -> f.getFamily() == Family.EW)
                .filter(f -> f.getFiresOn() == FiresOn.CANDIDATE)
                .toList();
        if (ewCandidates.isEmpty()) return List.of();

        Set<String> eliminated = priorFirings.stream()
                .filter(f -> f.getFiresOn() == FiresOn.ELIMINATION && f.getRefs() != null)
                .flatMap(f -> f.getRefs().stream())
                .collect(java.util.stream.Collectors.toSet());

        // Gather LIVE hypotheses across candidates (admitted by signature evaluation).
        List<LiveHypothesis> live = new ArrayList<>();
        Set<String> seenForms = new LinkedHashSet<>();
        for (Firing cand : ewCandidates) {
            if (eliminated.contains(cand.getId())) continue;
            for (Firing sigFiring : signatureFiringsFor(priorFirings, cand.getId())) {
                Map<String, Object> p = sigFiring.getPayload();
                if (p == null) continue;
                String admissionState = (String) p.get("admission_state");
                if (!"ADMITTED".equals(admissionState)) continue;
                String form = (String) p.get("form_name");
                if (form == null || seenForms.contains(form)) continue;
                seenForms.add(form);
                live.add(new LiveHypothesis(cand, sigFiring, form));
                if (live.size() >= maxLive) break;
            }
            if (live.size() >= maxLive) break;
        }

        if (live.isEmpty()) {
            log.debug("[ew-synthesis] no admitted hypotheses for {}", ctx.getSymbol());
            return List.of();
        }

        // Resolve gate + completion states for the underlying candidate(s).
        // For VERDICT eligibility we need: live.size() == 1 AND gate CONFIRMED AND wave COMPLETE
        // for THAT candidate.
        ContextProbeResult probe = resolveProbe(ctx, live.get(0).candidate);
        boolean singleLive = live.size() == 1;
        LiveHypothesis primary = live.get(0);
        String gateState = latestGateState(priorFirings, primary.candidate.getId());
        String terminalState = latestCompletionState(priorFirings, primary.candidate);
        boolean gateConfirmed = "CONFIRMED".equals(gateState);
        boolean waveComplete = "COMPLETE".equals(terminalState);

        if (singleLive && gateConfirmed && waveComplete) {
            return List.of(buildVerdict(ctx, primary, priorFirings, probe));
        }
        return List.of(buildWatch(ctx, live, priorFirings, probe, gateState, terminalState));
    }

    // ── verdict path ───────────────────────────────────────────────────────────

    private Firing buildVerdict(SymbolContext ctx, LiveHypothesis winner,
                                  List<Firing> priorFirings, ContextProbeResult probe) {
        Firing cand = winner.candidate;
        PriorFold.Result folded = PriorFold.foldChain(cand, priorFirings);

        Set<Family> contributingFamilies = new LinkedHashSet<>();
        contributingFamilies.add(cand.getFamily());
        List<String> chainRefs = new ArrayList<>();
        chainRefs.add(cand.getId());
        for (Firing f : priorFirings) {
            if (f.getRefs() != null && f.getRefs().contains(cand.getId())) {
                chainRefs.add(f.getId());
                if (f.getFamily() != null) contributingFamilies.add(f.getFamily());
            }
        }
        boolean confluent = contributingFamilies.size() >= 2;

        Levels levels = computeLevels(winner.formName, cand);
        if (levels == null) {
            // Form doesn't have a level-builder — emit WATCH instead.
            return buildWatch(ctx, List.of(winner), priorFirings, probe, "CONFIRMED", "COMPLETE");
        }

        Map<String, Object> verdictPayload = new LinkedHashMap<>();
        verdictPayload.put("winning_form", winner.formName);
        verdictPayload.put("winner_candidate_id", cand.getId());
        verdictPayload.put("live_prior", folded.livePrior());
        verdictPayload.put("contributing_families",
                contributingFamilies.stream().map(Family::name).toList());
        verdictPayload.put("confluent", confluent);
        verdictPayload.put("competitor_count", 0);
        verdictPayload.put("trigger_basis", levels.triggerBasis);
        verdictPayload.put("invalidation_basis", levels.invalidationBasis);
        verdictPayload.put("target_basis", levels.targetBasis);
        verdictPayload.put("note",
                "VERDICT path: exactly one admissible hypothesis + gate CONFIRMED + wave-state COMPLETE.");

        String baseSig = "EW_" + winner.formName.toUpperCase() + "_VERDICT";
        return Firing.builder()
                .ruleId(RULE_ID)
                .symbol(ctx.getSymbol())
                .tf(ctx.getTf())
                .asOf(ctx.getAsOf())
                .family(Family.SYNTHESIS)
                .pass(Pass.P6_SYNTHESIS)
                .firesOn(FiresOn.VERDICT)
                .refs(chainRefs)
                .roundNum(1)
                .bias(levels.bias)
                .triggerPrice(levels.trigger)
                .invalidationPrice(levels.invalidation)
                .targetPrice(levels.target)
                .role(levels.role)
                .contextSignature(confluent ? "CONFLUENT_" + baseSig : baseSig)
                .finalConviction(folded.livePrior())
                .context(probe)
                .payload(verdictPayload)
                .build();
    }

    // ── watch path (the new default) ───────────────────────────────────────────

    private Firing buildWatch(SymbolContext ctx, List<LiveHypothesis> live,
                                List<Firing> priorFirings, ContextProbeResult probe,
                                String gateState, String terminalState) {
        // Build hypotheses_live array — the LEVEL-MAP.
        List<Map<String, Object>> hypotheses = new ArrayList<>();
        Set<String> refs = new LinkedHashSet<>();
        for (LiveHypothesis lh : live) {
            refs.add(lh.candidate.getId());
            refs.add(lh.signatureFiring.getId());
            Map<String, Object> sigPayload = lh.signatureFiring.getPayload();
            Map<String, Object> derivedLevels = sigPayload == null
                    ? Map.of()
                    : (Map<String, Object>) sigPayload.getOrDefault("derived_levels", Map.of());

            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("form", lh.formName);
            entry.put("candidate_id", lh.candidate.getId());
            entry.put("signature_rule_id",
                    sigPayload == null ? null : sigPayload.get("signature_rule_id"));
            entry.put("why_alive",
                    sigPayload == null ? null : sigPayload.get("reasoning"));
            entry.put("watch_levels", derivedLevels.getOrDefault("watch", List.of()));
            entry.put("invalidation_levels", derivedLevels.getOrDefault("invalidation", List.of()));
            entry.put("observed_legs",
                    sigPayload == null ? null : sigPayload.get("observed_legs"));
            entry.put("provisional", true);
            hypotheses.add(entry);
        }

        Map<String, Object> watchPayload = new LinkedHashMap<>();
        watchPayload.put("live_hypotheses_count", live.size());
        watchPayload.put("live_hypotheses_capped_at", maxLive);
        watchPayload.put("hypotheses_live", hypotheses);
        watchPayload.put("gate_state", gateState);
        watchPayload.put("terminal_wave_state", terminalState);
        watchPayload.put("note", buildWatchNote(live.size(), gateState, terminalState));

        String signature = "WATCHING_EW_LEVEL_MAP_" + live.size() + "_LIVE";

        return Firing.builder()
                .ruleId(RULE_ID)
                .symbol(ctx.getSymbol())
                .tf(ctx.getTf())
                .asOf(ctx.getAsOf())
                .family(Family.SYNTHESIS)
                .pass(Pass.P6_SYNTHESIS)
                .firesOn(FiresOn.WATCH)
                .refs(new ArrayList<>(refs))
                .roundNum(1)
                .bias(RuleFiring.Bias.NEUTRAL)
                .role(Role.NEUTRAL)
                .contextSignature(signature)
                .finalConviction(0.0)
                .context(probe)
                .payload(watchPayload)
                .build();
    }

    private static String buildWatchNote(int liveCount, String gateState, String terminalState) {
        StringBuilder sb = new StringBuilder("LEVEL-MAP emitted (no confident verdict). ");
        sb.append(liveCount).append(" live hypothes").append(liveCount == 1 ? "is" : "es").append(". ");
        if (!"CONFIRMED".equals(gateState)) {
            sb.append("Pre-conclusion gate ").append(gateState)
                    .append(" — price has not broken the directional reference; thesis unconfirmed. ");
        }
        if (!"COMPLETE".equals(terminalState) && terminalState != null) {
            sb.append("Terminal wave state ").append(terminalState).append(". ");
        }
        sb.append("Per SPEC reframe 159ba913: hypotheses stay alive until invalidated; engine surfaces watch + invalidation levels per hypothesis.");
        return sb.toString();
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private record LiveHypothesis(Firing candidate, Firing signatureFiring, String formName) {}

    private static List<Firing> signatureFiringsFor(List<Firing> priorFirings, String candidateId) {
        List<Firing> out = new ArrayList<>();
        for (Firing f : priorFirings) {
            if (!"EW_SIGNATURE_EVALUATION".equals(f.getRuleId())) continue;
            if (f.getRefs() == null || !f.getRefs().contains(candidateId)) continue;
            out.add(f);
        }
        return out;
    }

    private static String latestGateState(List<Firing> priorFirings, String candidateId) {
        Firing latest = null;
        for (Firing f : priorFirings) {
            if (!"EW_PRE_CONCLUSION_GATE".equals(f.getRuleId())) continue;
            if (f.getRefs() == null || !f.getRefs().contains(candidateId)) continue;
            if (latest == null || isLater(f, latest)) latest = f;
        }
        if (latest == null || latest.getPayload() == null) return null;
        Object s = latest.getPayload().get("gate_state");
        return s == null ? null : s.toString();
    }

    private static String latestCompletionState(List<Firing> priorFirings, Firing candidate) {
        String terminalRole = terminalRoleFor(candidate);
        if (terminalRole == null) return null;
        Firing latest = null;
        for (Firing f : priorFirings) {
            if (f.getFiresOn() != FiresOn.CONFIRMATION) continue;
            if (f.getFamily() != Family.EW) continue;
            if (f.getRefs() == null || !f.getRefs().contains(candidate.getId())) continue;
            if (f.getPayload() == null) continue;
            if (!terminalRole.equals(f.getPayload().get("role"))) continue;
            if (!(f.getPayload().get("new_state") instanceof String)) continue;
            if (latest == null || isLater(f, latest)) latest = f;
        }
        if (latest != null) {
            return (String) latest.getPayload().get("new_state");
        }
        // Fallback: candidate's initial state from pivot_assignment.
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> assignment =
                (List<Map<String, Object>>) candidate.getPayload().get("pivot_assignment");
        if (assignment == null) return null;
        for (Map<String, Object> e : assignment) {
            if (terminalRole.equals(e.get("role"))) {
                Object st = e.get("state");
                return st == null ? null : st.toString();
            }
        }
        return null;
    }

    private static String terminalRoleFor(Firing candidate) {
        String form = (String) candidate.getPayload().get("form");
        if ("zigzag".equals(form)) return "B_end";
        if ("impulse".equals(form)) return "W1_end";
        return null;
    }

    private static boolean isLater(Firing a, Firing b) {
        int ar = a.getRoundNum() == null ? 0 : a.getRoundNum();
        int br = b.getRoundNum() == null ? 0 : b.getRoundNum();
        if (ar != br) return ar > br;
        int ap = a.getPass() == null ? 0 : a.getPass().order;
        int bp = b.getPass() == null ? 0 : b.getPass().order;
        if (ap != bp) return ap > bp;
        return a.getId() != null && b.getId() != null && a.getId().compareTo(b.getId()) > 0;
    }

    private ContextProbeResult resolveProbe(SymbolContext ctx, Firing candidate) {
        ContextProbeResult probe = ctx.getProbe() != null ? ctx.getProbe() : candidate.getContext();
        if (probe == null) {
            probe = ContextProbeResult.builder()
                    .macroRegime(MacroRegime.UNKNOWN)
                    .srPosition(SrPosition.UNKNOWN)
                    .indicatorConfluence(IndicatorConfluence.UNKNOWN)
                    .build();
        }
        return probe;
    }

    // ── structural levels (verdict path only) ──────────────────────────────────

    @SuppressWarnings("unchecked")
    private static Levels computeLevels(String form, Firing candidate) {
        Map<String, Object> p = candidate.getPayload();
        List<Map<String, Object>> assignment = (List<Map<String, Object>>) p.get("pivot_assignment");
        if (assignment == null) return null;
        if ("zigzag".equals(form) || "truncated-c".equals(form)) return zigzagLevels(assignment);
        if ("impulse".equals(form)) return impulseLevels(assignment);
        return null;
    }

    private static Levels zigzagLevels(List<Map<String, Object>> assignment) {
        Double aStart = priceOf(assignment, "A_start");
        Double aEnd = priceOf(assignment, "A_end");
        Double bEnd = priceOf(assignment, "B_end");
        if (aStart == null || aEnd == null || bEnd == null) return null;
        double aMag = Math.abs(aEnd - aStart);
        boolean down = aStart > aEnd;
        RuleFiring.Bias bias = down ? RuleFiring.Bias.SHORT : RuleFiring.Bias.LONG;
        double target = down ? bEnd - aMag : bEnd + aMag;
        return new Levels(bias, bEnd, aStart, target,
                "B_end (current bounce extreme)",
                "A_start (macro origin)",
                "C target = B_end ± |A magnitude|",
                Role.REVERSAL);
    }

    private static Levels impulseLevels(List<Map<String, Object>> assignment) {
        Double w0 = priceOf(assignment, "W0");
        Double w1End = priceOf(assignment, "W1_end");
        if (w0 == null || w1End == null) return null;
        double w1Mag = Math.abs(w1End - w0);
        boolean bullish = w1End > w0;
        RuleFiring.Bias bias = bullish ? RuleFiring.Bias.LONG : RuleFiring.Bias.SHORT;
        double target = bullish ? w1End + 1.618 * w1Mag : w1End - 1.618 * w1Mag;
        return new Levels(bias, w1End, w0, target,
                "W1_end",
                "W0 (impulse origin)",
                "W3 target = W1_end ± 1.618 × |W1|",
                Role.CONTINUATION);
    }

    private record Levels(RuleFiring.Bias bias, double trigger, double invalidation, double target,
                            String triggerBasis, String invalidationBasis, String targetBasis,
                            Role role) {}

    private static Double priceOf(List<Map<String, Object>> assignment, String role) {
        for (Map<String, Object> m : assignment) {
            if (role.equals(m.get("role"))) {
                Object p = m.get("price");
                if (p instanceof Number n) return n.doubleValue();
                return null;
            }
        }
        return null;
    }
}
