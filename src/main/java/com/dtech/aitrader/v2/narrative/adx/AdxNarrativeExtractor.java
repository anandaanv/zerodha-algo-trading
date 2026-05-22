package com.dtech.aitrader.v2.narrative.adx;

import com.dtech.aitrader.v2.narrative.beat.Narrative;
import com.dtech.aitrader.v2.narrative.engine.DescriptiveNarrativeEngine;
import com.dtech.chartdata.model.OhlcBarDTO;
import org.springframework.stereotype.Service;

import java.util.List;

/** Thin facade for ADX/DMI narratives. */
@Service
public class AdxNarrativeExtractor {

    private final DescriptiveNarrativeEngine engine;

    public AdxNarrativeExtractor(DescriptiveNarrativeEngine engine) {
        this.engine = engine;
    }

    public Narrative extract(List<OhlcBarDTO> bars, String symbol, String timeframe,
                             AdxNarrativeParams params) {
        return engine.extract(bars, symbol, timeframe, new AdxIndicatorConfig(params));
    }
}
