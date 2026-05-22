package com.dtech.aitrader.v2.narrative.rsi;

import com.dtech.aitrader.v2.narrative.beat.Narrative;
import com.dtech.aitrader.v2.narrative.engine.DescriptiveNarrativeEngine;
import com.dtech.chartdata.model.OhlcBarDTO;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Thin facade for the RSI narrative — delegates to {@link DescriptiveNarrativeEngine} configured
 * with an {@link RsiIndicatorConfig}.
 */
@Service
public class RsiNarrativeExtractor {

    private final DescriptiveNarrativeEngine engine;

    public RsiNarrativeExtractor(DescriptiveNarrativeEngine engine) {
        this.engine = engine;
    }

    public Narrative extract(List<OhlcBarDTO> bars, String symbol, String timeframe,
                             RsiNarrativeParams params) {
        return engine.extract(bars, symbol, timeframe, new RsiIndicatorConfig(params));
    }
}
