package com.dtech.kitecon.elliott;

import com.dtech.chartpattern.zigzag.ZigZagPoint;
import com.dtech.kitecon.service.copilot.dto.MarketStructurePoint;
import org.ta4j.core.Bar;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public interface ImpulseLabelStrategy {
    List<ImpulseLabeler.LabeledPivot> label(
        List<ZigZagPoint> pivots,
        List<Bar> bars,
        Map<Instant, Integer> tsToIdx,
        List<MarketStructurePoint> structurePoints);

    String name();
}
