package com.dtech.aitrader.v2.rules;

import lombok.Builder;
import lombok.Value;

/**
 * Frozen snapshot of the cross-cutting context every rule reads when scoring a firing. Computed
 * once per {@link SymbolContext} so the three enum derivations don't get repeated by each rule.
 *
 * <p>The {@code (macroRegime, srPosition, indicatorConfluence)} triple is the source of the
 * {@code context_signature} on every firing — eval queries group by signature.
 */
@Value
@Builder
public class ContextProbeResult {
    MacroRegime macroRegime;
    SrPosition srPosition;
    IndicatorConfluence indicatorConfluence;
}
