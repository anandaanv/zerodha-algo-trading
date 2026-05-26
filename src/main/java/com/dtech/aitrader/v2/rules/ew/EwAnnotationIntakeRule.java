package com.dtech.aitrader.v2.rules.ew;

import com.dtech.aitrader.v2.rules.AnnotationEntry;
import com.dtech.aitrader.v2.rules.Family;
import com.dtech.aitrader.v2.rules.Firing;
import com.dtech.aitrader.v2.rules.FiresOn;
import com.dtech.aitrader.v2.rules.Pass;
import com.dtech.aitrader.v2.rules.Rule;
import com.dtech.aitrader.v2.rules.SymbolContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pass-1 Rule 0.35 — promotes trader annotations of weight ≥ {@link #minWeight} into FACT firings
 * the enumeration (Pass-2) and prior-classification (Pass-4) rules can consume.
 *
 * <p>RELIANCE blessed reference {@code cde6bbc9}: the weight-3 "on weekly appears to be in wave
 * 4C; 2 of C or 4 of C; right value is lower" annotation must surface as a FACT, and the Pass-4
 * EwAnnotationPriorRule lifts the corrective MF1's prior because of it.
 *
 * <p>Default {@code min_weight=2} per spec ab9bd541; configurable via
 * {@code rules.ew.annotation.min-weight}.
 */
@Component
@Slf4j
public class EwAnnotationIntakeRule implements Rule {

    public static final String RULE_ID = "EW_ANNOTATION_INTAKE";

    @Value("${rules.ew.annotation.min-weight:2}")
    private int minWeight = 2;

    @Override public String ruleId() { return RULE_ID; }
    @Override public Pass pass() { return Pass.P1_STRUCTURAL; }
    @Override public Family family() { return Family.EW; }

    @Override
    public List<Firing> evaluate(SymbolContext ctx, List<Firing> priorFirings) {
        List<AnnotationEntry> annotations = ctx.getAnnotations();
        if (annotations == null || annotations.isEmpty()) return List.of();

        List<Firing> out = new ArrayList<>();
        for (AnnotationEntry a : annotations) {
            if (a == null || a.weight() < minWeight) continue;

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("text", a.text());
            payload.put("weight", a.weight());
            payload.put("timestamp",
                    a.timestamp() != null ? a.timestamp().toString() : null);
            payload.put("price_level", a.priceLevel());

            out.add(Firing.builder()
                    .ruleId(RULE_ID)
                    .symbol(ctx.getSymbol())
                    .tf(ctx.getTf())
                    .asOf(ctx.getAsOf())
                    .family(Family.EW)
                    .pass(Pass.P1_STRUCTURAL)
                    .firesOn(FiresOn.FACT)
                    .roundNum(1)
                    .payload(payload)
                    .context(ctx.getProbe())
                    .build());
        }
        return out;
    }
}
