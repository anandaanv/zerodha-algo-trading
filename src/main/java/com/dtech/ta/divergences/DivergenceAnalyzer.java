package com.dtech.ta.divergences;

import com.dtech.ta.trendline.ActiveTrendlineAnalysis;
import org.springframework.stereotype.Service;
import org.ta4j.core.BarSeries;

import java.util.ArrayList;
import java.util.List;

@Service
public class DivergenceAnalyzer {

    // Change the return type to List<Divergence> to return all detected divergences
    public List<Divergence> detectTripleDivergences(BarSeries series) {
        List<DivergenceDetector> detectors = new ArrayList<>();
        detectors.add(new MACDDivergenceDetector(series, new ActiveTrendlineAnalysis()));
        detectors.add(new RSIDivergenceDetector(series));
        detectors.add(new StochasticDivergenceDetector(series));

        List<Divergence> allDivergences = new ArrayList<>();
        for (DivergenceDetector detector : detectors) {
            List<Divergence> divergences = detector.detectDivergences();
            allDivergences.addAll(divergences);
        }
        return allDivergences;
    }
}
