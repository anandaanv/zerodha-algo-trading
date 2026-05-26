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
import org.ta4j.core.BarSeries;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pass-4 quality classifier for Double-Bottom candidates emitted by {@link DoubleBottomDetectRule}.
 *
 * <p>Reads each active DB CANDIDATE firing's payload + the {@link ContextProbeResult}, computes
 * a composite quality score (geometry / sr / confluence / macro / volume) per the pilot's
 * conviction-component scheme, and emits a {@link FiresOn#CLASSIFICATION} firing with a
 * {@link PriorDelta} that nudges the candidate's prior up or down.
 *
 * <p>Why split detection (Pass 2) from quality (Pass 4): Pass-3 validators can ELIMINATE bad
 * candidates before quality scoring even runs, saving work and (more importantly) keeping the
 * chain clean. The quality rule never sees a candidate that another rule has already killed.
 */
@Component
@Slf4j
public class DoubleBottomQualityRule implements Rule {

    public static final String RULE_ID = "DOUBLE_BOTTOM_QUALITY";

    private static final int VOLUME_AVG_WINDOW = 20;

    @Override public String ruleId() { return RULE_ID; }
    @Override public Pass pass() { return Pass.P4_CLASSIFICATION; }
    @Override public Family family() { return Family.PATTERN; }

    @Override
    public List<Firing> evaluate(SymbolContext ctx, List<Firing> priorFirings) {
        List<Firing> candidates = priorFirings.stream()
                .filter(f -> f.getFiresOn() == FiresOn.CANDIDATE)
                .filter(f -> DoubleBottomDetectRule.RULE_ID.equals(f.getRuleId()))
                .toList();
        if (candidates.isEmpty()) return List.of();

        // Skip candidates that already have an ELIMINATION in the prior firings.
        java.util.Set<String> eliminatedIds = priorFirings.stream()
                .filter(f -> f.getFiresOn() == FiresOn.ELIMINATION && f.getRefs() != null)
                .flatMap(f -> f.getRefs().stream())
                .collect(java.util.stream.Collectors.toSet());

        List<Firing> emitted = new java.util.ArrayList<>();
        for (Firing cand : candidates) {
            if (eliminatedIds.contains(cand.getId())) continue;
            Firing classification = classify(ctx, cand);
            if (classification != null) emitted.add(classification);
        }
        return emitted;
    }

    private Firing classify(SymbolContext ctx, Firing candidate) {
        Map<String, Object> payload = candidate.getPayload();
        if (payload == null) return null;

        // Components extracted from the candidate's payload.
        double atr = ((Number) payload.getOrDefault("atr_at_breakout", 0)).doubleValue();
        double p1 = ((Number) payload.getOrDefault("p1_price", 0)).doubleValue();
        double p2 = ((Number) payload.getOrDefault("p2_price", 0)).doubleValue();
        double priceDelta = Math.abs(p1 - p2);
        // Allowable tolerance same as the detector — 1.5×ATR.
        double geometryQuality = atr > 0 ? clamp01(1.0 - priceDelta / (1.5 * atr)) : 0.0;

        ContextProbeResult probe = ctx.getProbe() != null ? ctx.getProbe() : candidate.getContext();
        SrPosition sr = probe != null ? probe.getSrPosition() : SrPosition.UNKNOWN;
        IndicatorConfluence conf = probe != null
                ? probe.getIndicatorConfluence() : IndicatorConfluence.UNKNOWN;
        MacroRegime macro = probe != null ? probe.getMacroRegime() : MacroRegime.UNKNOWN;

        Role role = resolveRole(macro, sr);
        double srAlignment = srScore(sr);
        double confluenceScore = confluenceScore(conf);
        double macroAlignment = macroScore(role);
        double volumeScore = volumeScore(ctx.getSeries());

        double quality = 0.25 * geometryQuality
                + 0.20 * srAlignment
                + 0.20 * confluenceScore
                + 0.25 * macroAlignment
                + 0.10 * volumeScore;

        // Map the [0,1] quality to a graduated delta in [-0.30, +0.30] centered on 0.5:
        // quality=0.5 → no change; quality=1.0 → +0.30; quality=0.0 → -0.30.
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
                .priorDelta(PriorDelta.graduated(delta, "DB quality=" + round(quality, 2), "quality"))
                .roundNum(1)
                .payload(classPayload)
                .context(probe)
                .role(role)
                .build();
    }

    static Role resolveRole(MacroRegime macro, SrPosition sr) {
        if (sr == SrPosition.EXTENDED_HIGH) return Role.FAKEOUT_RISK;
        if (macro == MacroRegime.DOWNTREND_STRONG || macro == MacroRegime.DOWNTREND_WEAK) return Role.REVERSAL;
        if (macro == MacroRegime.UPTREND_STRONG || macro == MacroRegime.UPTREND_WEAK) return Role.CONTINUATION;
        return Role.NEUTRAL;
    }

    static double srScore(SrPosition sr) {
        return switch (sr) {
            case AT_MAJOR_SUPPORT -> 1.0;
            case MID_RANGE -> 0.5;
            case EXTENDED_LOW -> 0.6;
            case AT_MAJOR_RESISTANCE -> 0.3;
            case EXTENDED_HIGH -> 0.1;
            default -> 0.3;
        };
    }

    static double confluenceScore(IndicatorConfluence c) {
        return switch (c) {
            case BULL_HIGH -> 1.0;
            case BULL_MIXED -> 0.7;
            case NEUTRAL -> 0.4;
            case BEAR_MIXED -> 0.2;
            case BEAR_HIGH -> 0.1;
            default -> 0.3;
        };
    }

    static double macroScore(Role role) {
        return switch (role) {
            case REVERSAL -> 1.0;
            case CONTINUATION -> 0.7;
            case NEUTRAL -> 0.5;
            case FAKEOUT_RISK -> 0.2;
        };
    }

    static double volumeScore(BarSeries series) {
        if (series == null) return 0.5;
        int endIdx = series.getEndIndex();
        int start = Math.max(series.getBeginIndex(), endIdx - VOLUME_AVG_WINDOW);
        if (endIdx <= start) return 0.5;
        double sum = 0;
        int n = 0;
        for (int i = start; i < endIdx; i++) {
            sum += series.getBar(i).getVolume().doubleValue();
            n++;
        }
        double avg = n > 0 ? sum / n : 0;
        double vNow = series.getBar(endIdx).getVolume().doubleValue();
        if (avg <= 0) return 0.5;
        double ratio = vNow / avg;
        if (ratio >= 1.5) return 1.0;
        if (ratio >= 1.0) return 0.7;
        return 0.5;
    }

    static double clamp01(double x) { return x < 0 ? 0 : x > 1 ? 1 : x; }

    private static double round(double x, int decimals) {
        double m = Math.pow(10, decimals);
        return Math.round(x * m) / m;
    }
}
