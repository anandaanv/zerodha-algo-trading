package com.dtech.aitrader.v2.narrative.macd;

import com.dtech.aitrader.v2.narrative.beat.MacdParams;
import com.dtech.aitrader.v2.narrative.beat.Narrative;
import com.dtech.aitrader.v2.narrative.engine.DescriptiveNarrativeEngine;
import com.dtech.chartdata.model.OhlcBarDTO;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Thin facade preserving the original public API while delegating to the generic
 * {@link DescriptiveNarrativeEngine} + {@link MacdIndicatorConfig}.
 *
 * <p>Phase 2 refactor: every line of MACD-specific logic that used to live here moved to
 * {@link MacdIndicatorConfig}; the shared pipeline moved to {@link DescriptiveNarrativeEngine}.
 * Output is byte-identical to the pre-refactor version (validated by Phase1c/Phase1d/MultiStock
 * tests).
 */
@Service
public class MacdNarrativeExtractor {

    private final DescriptiveNarrativeEngine engine;

    public MacdNarrativeExtractor(DescriptiveNarrativeEngine engine) {
        this.engine = engine;
    }

    public Narrative extract(List<OhlcBarDTO> bars, String symbol, String timeframe,
                             MacdNarrativeParams params) {
        Narrative generic = engine.extract(bars, symbol, timeframe, new MacdIndicatorConfig(params));
        // The engine leaves the indicator-specific params block null. Fill it in here so the
        // emitted JSON still includes {fast, slow, signal} under "params".
        return Narrative.builder()
                .indicator(generic.getIndicator())
                .params(MacdParams.builder()
                        .fast(params.getFastPeriod())
                        .slow(params.getSlowPeriod())
                        .signal(params.getSignalPeriod())
                        .build())
                .symbol(generic.getSymbol())
                .timeframe(generic.getTimeframe())
                .bar0Date(generic.getBar0Date())
                .lastBar(generic.getLastBar())
                .calcNote(generic.getCalcNote())
                .tiers(generic.getTiers())
                .verificationSlices(generic.getVerificationSlices())
                .build();
    }
}
