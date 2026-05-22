package com.dtech.aitrader.v2.narrative.bollinger;

import com.dtech.aitrader.v2.narrative.beat.Narrative;
import com.dtech.aitrader.v2.narrative.engine.DescriptiveNarrativeEngine;
import com.dtech.chartdata.model.OhlcBarDTO;
import org.springframework.stereotype.Service;

import java.util.List;

/** Thin facade for Bollinger narratives. */
@Service
public class BollingerNarrativeExtractor {

    private final DescriptiveNarrativeEngine engine;

    public BollingerNarrativeExtractor(DescriptiveNarrativeEngine engine) {
        this.engine = engine;
    }

    public Narrative extract(List<OhlcBarDTO> bars, String symbol, String timeframe,
                             BollingerNarrativeParams params) {
        return engine.extract(bars, symbol, timeframe, new BollingerIndicatorConfig(params));
    }
}
