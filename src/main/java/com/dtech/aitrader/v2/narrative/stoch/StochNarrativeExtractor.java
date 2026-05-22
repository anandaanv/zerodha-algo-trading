package com.dtech.aitrader.v2.narrative.stoch;

import com.dtech.aitrader.v2.narrative.beat.Narrative;
import com.dtech.aitrader.v2.narrative.engine.DescriptiveNarrativeEngine;
import com.dtech.chartdata.model.OhlcBarDTO;
import org.springframework.stereotype.Service;

import java.util.List;

/** Thin facade for Stochastic narratives. */
@Service
public class StochNarrativeExtractor {

    private final DescriptiveNarrativeEngine engine;

    public StochNarrativeExtractor(DescriptiveNarrativeEngine engine) {
        this.engine = engine;
    }

    public Narrative extract(List<OhlcBarDTO> bars, String symbol, String timeframe,
                             StochNarrativeParams params) {
        return engine.extract(bars, symbol, timeframe, new StochIndicatorConfig(params));
    }
}
