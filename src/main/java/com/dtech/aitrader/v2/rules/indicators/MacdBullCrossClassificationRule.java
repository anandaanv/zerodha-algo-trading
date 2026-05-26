package com.dtech.aitrader.v2.rules.indicators;

import com.dtech.aitrader.v2.rules.Family;
import com.dtech.aitrader.v2.rules.Firing;
import com.dtech.aitrader.v2.rules.FiresOn;
import com.dtech.aitrader.v2.rules.Pass;
import com.dtech.aitrader.v2.rules.PriorDelta;
import com.dtech.aitrader.v2.rules.Rule;
import com.dtech.aitrader.v2.rules.SymbolContext;
import com.dtech.aitrader.v2.rules.patterns.DoubleBottomDetectRule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pass-4 cross-family classifier: if a {@link MacdBullCrossFactRule} fact fired at this asOf AND
 * a {@link DoubleBottomDetectRule} candidate is active, emit a {@link FiresOn#CLASSIFICATION}
 * firing that boosts the DB candidate's prior by a fixed +0.10 (momentum confirmation).
 *
 * <p>This is the architectural mechanism for cross-family CONFLUENCE — an INDICATOR firing
 * directly references a PATTERN candidate, and Pass-6 synthesis folds the chain to detect that
 * two families agreed in the same context.
 */
@Component
@Slf4j
public class MacdBullCrossClassificationRule implements Rule {

    public static final String RULE_ID = "MACD_BULL_CROSS_CLASSIFICATION";

    /** Momentum confirmation boost — modest because we don't want it to dominate geometry quality. */
    private static final double MOMENTUM_DELTA = +0.10;

    @Override public String ruleId() { return RULE_ID; }
    @Override public Pass pass() { return Pass.P4_CLASSIFICATION; }
    @Override public Family family() { return Family.INDICATOR; }

    @Override
    public List<Firing> evaluate(SymbolContext ctx, List<Firing> priorFirings) {
        boolean crossToday = priorFirings.stream()
                .anyMatch(f -> f.getFiresOn() == FiresOn.FACT
                        && MacdBullCrossFactRule.RULE_ID.equals(f.getRuleId()));
        if (!crossToday) return List.of();

        // Find active LONG-side pattern candidates (currently just DB).
        java.util.Set<String> eliminated = priorFirings.stream()
                .filter(f -> f.getFiresOn() == FiresOn.ELIMINATION && f.getRefs() != null)
                .flatMap(f -> f.getRefs().stream())
                .collect(java.util.stream.Collectors.toSet());

        List<Firing> activeDbCandidates = priorFirings.stream()
                .filter(f -> f.getFiresOn() == FiresOn.CANDIDATE)
                .filter(f -> DoubleBottomDetectRule.RULE_ID.equals(f.getRuleId()))
                .filter(f -> !eliminated.contains(f.getId()))
                .toList();
        if (activeDbCandidates.isEmpty()) return List.of();

        List<Firing> out = new java.util.ArrayList<>();
        for (Firing cand : activeDbCandidates) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("source", "MACD_BULL_CROSS confirmation");
            payload.put("delta", MOMENTUM_DELTA);

            out.add(Firing.builder()
                    .ruleId(RULE_ID)
                    .symbol(ctx.getSymbol())
                    .tf(ctx.getTf())
                    .asOf(ctx.getAsOf())
                    .family(Family.INDICATOR)
                    .pass(Pass.P4_CLASSIFICATION)
                    .firesOn(FiresOn.CLASSIFICATION)
                    .refs(List.of(cand.getId()))
                    .priorDelta(PriorDelta.graduated(MOMENTUM_DELTA,
                            "MACD bull-cross momentum confirmation", "confluence"))
                    .roundNum(1)
                    .payload(payload)
                    .context(ctx.getProbe())
                    .build());
        }
        return out;
    }
}
