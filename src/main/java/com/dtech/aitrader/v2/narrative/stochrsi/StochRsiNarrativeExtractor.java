package com.dtech.aitrader.v2.narrative.stochrsi;

import com.dtech.aitrader.v2.narrative.beat.Narrative;
import com.dtech.aitrader.v2.narrative.engine.DescriptiveNarrativeEngine;
import com.dtech.chartdata.model.OhlcBarDTO;
import org.springframework.stereotype.Service;

import java.util.List;

/** Thin facade for StochRSI narratives. */
@Service
public class StochRsiNarrativeExtractor {

    private final DescriptiveNarrativeEngine engine;

    public StochRsiNarrativeExtractor(DescriptiveNarrativeEngine engine) {
        this.engine = engine;
    }

    public Narrative extract(List<OhlcBarDTO> bars, String symbol, String timeframe,
                             StochRsiNarrativeParams params) {
        return engine.extract(bars, symbol, timeframe, new StochRsiIndicatorConfig(params));
    }
}
