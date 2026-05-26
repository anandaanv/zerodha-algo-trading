package com.dtech.aitrader.v2.rules.patterns;

import com.dtech.aitrader.v2.rules.ContextProbeResult;
import com.dtech.aitrader.v2.rules.Family;
import com.dtech.aitrader.v2.rules.Firing;
import com.dtech.aitrader.v2.rules.FiresOn;
import com.dtech.aitrader.v2.rules.IndicatorConfluence;
import com.dtech.aitrader.v2.rules.MacroRegime;
import com.dtech.aitrader.v2.rules.Pass;
import com.dtech.aitrader.v2.rules.PriorDelta;
import com.dtech.aitrader.v2.rules.Role;
import com.dtech.aitrader.v2.rules.Rule;
import com.dtech.aitrader.v2.rules.SrPosition;
import com.dtech.aitrader.v2.rules.SymbolContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Pass-4 quality classifier for Double-Top candidates. Mirror of {@link DoubleBottomQualityRule}. */
@Component
@Slf4j
public class DoubleTopQualityRule implements Rule {

    public static final String RULE_ID = "DOUBLE_TOP_QUALITY";

    @Override public String ruleId() { return RULE_ID; }
    @Override public Pass pass() { return Pass.P4_CLASSIFICATION; }
    @Override public Family family() { return Family.PATTERN; }

    @Override
    public List<Firing> evaluate(SymbolContext ctx, List<Firing> priorFirings) {
        List<Firing> candidates = priorFirings.stream()
                .filter(f -> f.getFiresOn() == FiresOn.CANDIDATE)
                .filter(f -> DoubleTopDetectRule.RULE_ID.equals(f.getRuleId()))
                .toList();
        if (candidates.isEmpty()) return List.of();
        java.util.Set<String> eliminated = priorFirings.stream()
                .filter(f -> f.getFiresOn() == FiresOn.ELIMINATION && f.getRefs() != null)
                .flatMap(f -> f.getRefs().stream())
                .collect(java.util.stream.Collectors.toSet());

        List<Firing> out = new java.util.ArrayList<>();
        for (Firing cand : candidates) {
            if (eliminated.contains(cand.getId())) continue;
            Firing f = classify(ctx, cand);
            if (f != null) out.add(f);
        }
        return out;
    }

    private Firing classify(SymbolContext ctx, Firing candidate) {
        Map<String, Object> payload = candidate.getPayload();
        if (payload == null) return null;
        double atr = ((Number) payload.getOrDefault("atr_at_breakdown", 0)).doubleValue();
        double p1 = ((Number) payload.getOrDefault("p1_price", 0)).doubleValue();
        double p2 = ((Number) payload.getOrDefault("p2_price", 0)).doubleValue();
        double priceDelta = Math.abs(p1 - p2);
        double geometryQuality = atr > 0 ? DoubleBottomQualityRule.clamp01(1.0 - priceDelta / (1.5 * atr)) : 0.0;

        ContextProbeResult probe = ctx.getProbe() != null ? ctx.getProbe() : candidate.getContext();
        SrPosition sr = probe != null ? probe.getSrPosition() : SrPosition.UNKNOWN;
        IndicatorConfluence conf = probe != null
                ? probe.getIndicatorConfluence() : IndicatorConfluence.UNKNOWN;
        MacroRegime macro = probe != null ? probe.getMacroRegime() : MacroRegime.UNKNOWN;

        Role role = resolveRole(macro, sr);
        double srAlignment = srScoreShort(sr);
        double confluenceScore = confluenceScoreShort(conf);
        double macroAlignment = DoubleBottomQualityRule.macroScore(role);
        double volumeScore = DoubleBottomQualityRule.volumeScore(ctx.getSeries());

        double quality = 0.25 * geometryQuality
                + 0.20 * srAlignment
                + 0.20 * confluenceScore
                + 0.25 * macroAlignment
                + 0.10 * volumeScore;
        double delta = (quality - 0.5) * 0.60;

        Map<String, Object> classPayload = new LinkedHashMap<>();
        classPayload.put("quality_score", quality);
        classPayload.put("geometry", geometryQuality);
        classPayload.put("sr", srAlignment);
        classPayload.put("confluence", confluenceScore);
        classPayload.put("macro", macroAlignment);
        classPayload.put("volume", volumeScore);
        classPayload.put("role", role.name());

        return Firing.builder()
                .ruleId(RULE_ID)
                .symbol(ctx.getSymbol())
                .tf(ctx.getTf())
                .asOf(ctx.getAsOf())
                .family(Family.PATTERN)
                .pass(Pass.P4_CLASSIFICATION)
                .firesOn(FiresOn.CLASSIFICATION)
                .refs(List.of(candidate.getId()))
                .priorDelta(PriorDelta.graduated(delta, "DT quality=" + round(quality, 2), "quality"))
                .roundNum(1)
                .payload(classPayload)
                .context(probe)
                .role(role)
                .build();
    }

    static Role resolveRole(MacroRegime macro, SrPosition sr) {
        if (sr == SrPosition.EXTENDED_LOW) return Role.FAKEOUT_RISK;
        if (macro == MacroRegime.UPTREND_STRONG || macro == MacroRegime.UPTREND_WEAK) return Role.REVERSAL;
        if (macro == MacroRegime.DOWNTREND_STRONG || macro == MacroRegime.DOWNTREND_WEAK) return Role.CONTINUATION;
        return Role.NEUTRAL;
    }

    static double srScoreShort(SrPosition sr) {
        return switch (sr) {
            case AT_MAJOR_RESISTANCE -> 1.0;
            case MID_RANGE -> 0.5;
            case EXTENDED_HIGH -> 0.6;
            case AT_MAJOR_SUPPORT -> 0.3;
            case EXTENDED_LOW -> 0.1;
            default -> 0.3;
        };
    }

    static double confluenceScoreShort(IndicatorConfluence c) {
        return switch (c) {
            case BEAR_HIGH -> 1.0;
            case BEAR_MIXED -> 0.7;
            case NEUTRAL -> 0.4;
            case BULL_MIXED -> 0.2;
            case BULL_HIGH -> 0.1;
            default -> 0.3;
        };
    }

    private static double round(double x, int decimals) {
        double m = Math.pow(10, decimals);
        return Math.round(x * m) / m;
    }
}
