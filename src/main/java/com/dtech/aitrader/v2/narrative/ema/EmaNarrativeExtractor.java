package com.dtech.aitrader.v2.narrative.ema;

import com.dtech.aitrader.v2.narrative.beat.Narrative;
import com.dtech.aitrader.v2.narrative.engine.DescriptiveNarrativeEngine;
import com.dtech.chartdata.model.OhlcBarDTO;
import org.springframework.stereotype.Service;

import java.util.List;

/** Thin facade for EMA-stack narratives. */
@Service
public class EmaNarrativeExtractor {

    private final DescriptiveNarrativeEngine engine;

    public EmaNarrativeExtractor(DescriptiveNarrativeEngine engine) {
        this.engine = engine;
    }

    public Narrative extract(List<OhlcBarDTO> bars, String symbol, String timeframe,
                             EmaNarrativeParams params) {
        return engine.extract(bars, symbol, timeframe, new EmaIndicatorConfig(params));
    }
}
