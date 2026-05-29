package com.dtech.aitrader.v2.rules.ew.dwell;

import com.dtech.aitrader.v2.rules.SymbolContext;
import com.dtech.algo.series.Interval;
import com.dtech.kitecon.data.Instrument;
import com.dtech.kitecon.market.fetch.DataFetchException;
import com.dtech.kitecon.repository.InstrumentRepository;
import com.dtech.kitecon.strategy.dataloader.BarsLoader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.ta4j.core.BarSeries;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Attaches dwell pivots to a {@link SymbolContext} BEFORE the multi-pass engine runs. Sibling
 * to {@code PatternContextAttacher} per separate-compute boundary ({@code 89a52589}): the
 * engine itself does not mutate context; attachers populate auxiliary collections in advance.
 *
 * <p>Per SPEC-010 Phase 1 ({@code 3663d889}) + SPEC-009 ({@code 36b585f6}) + owner constraint
 * ({@code 59fa728f}): dwell pivots are a SEPARATE explicit collection — NOT spliced into the
 * alternating reversal-pivot series. EW continues to read {@code pivotsByTf}; dwell pivots
 * flow via {@link SymbolContext#getDwellPivots()}.
 *
 * <h2>Per-TF defaults</h2>
 * Day k=0.8, N=3 (validated NIFTY 2025 daily per {@code 91b3a3f5}). Hr defaults pending
 * re-validation per Q3 of the SPEC-009 brainstorm. Week not validated yet — uses Day defaults
 * as a starting point. All knobs {@code @Value}-configurable.
 *
 * <h2>atrMult consistency</h2>
 * The no-reversal threshold {@code atrMult} must match the zigzag detector's
 * {@code ZigZagParams.atrMult} for the same TF so dwell + reversal definitions agree by
 * construction. Currently exposed as {@code @Value rules.dwell.atrMult.<tf>} with documented
 * requirement; future refinement could read directly from a shared registry.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DwellPivotAttacher {

    private final InstrumentRepository instrumentRepository;
    private final BarsLoader barsLoader;
    private final DwellPivotDetector dwellPivotDetector;

    /** Per-TF k (band width in ATR units). Default 0.8 across TFs per {@code 91b3a3f5}. */
    @Value("${rules.dwell.k.week:0.8}") private double kWeek = 0.8;
    @Value("${rules.dwell.k.day:0.8}") private double kDay = 0.8;
    @Value("${rules.dwell.k.hour:0.8}") private double kHour = 0.8;

    /**
     * Per-TF minN (minimum bars in dwell window). Day=3 validated. Hr pending Q3 re-validation
     * — starts at 4 (conservative within owner's "try 4-6" suggestion).
     */
    @Value("${rules.dwell.minN.week:3}") private int minNWeek = 3;
    @Value("${rules.dwell.minN.day:3}") private int minNDay = 3;
    @Value("${rules.dwell.minN.hour:4}") private int minNHour = 4;

    /**
     * Per-TF atrMult for the no-reversal guard. MUST match {@code ZigZagParams.atrMult} for
     * the same TF — documented requirement. Owner directive {@code 60d21c43}.
     */
    @Value("${rules.dwell.atrMult.week:3.0}") private double atrMultWeek = 3.0;
    @Value("${rules.dwell.atrMult.day:3.0}") private double atrMultDay = 3.0;
    @Value("${rules.dwell.atrMult.hour:3.0}") private double atrMultHour = 3.0;

    /**
     * Attach dwell pivots for ALL TFs present in {@code base.pivotsByTf} (or the explicit TF
     * list when overriding for tests). Returns a new context with {@code dwellPivots} populated;
     * other fields untouched.
     */
    public SymbolContext attach(SymbolContext base) {
        if (base == null) return null;
        if (base.getSymbol() == null) {
            log.warn("[dwell-attacher] base context has no symbol");
            return base;
        }

        Instrument instrument = instrumentRepository.findByTradingsymbolAndExchangeIn(
                base.getSymbol(), new String[]{"NSE"});
        if (instrument == null) {
            log.warn("[dwell-attacher] instrument not found for symbol={}", base.getSymbol());
            return base;
        }

        List<String> tfsToScan = new ArrayList<>();
        if (base.getPivotsByTf() != null) {
            for (String tf : base.getPivotsByTf().keySet()) {
                if (isSupportedTf(tf)) tfsToScan.add(tf);
            }
        }
        if (tfsToScan.isEmpty() && base.getTf() != null) tfsToScan.add(base.getTf());

        List<DwellPivot> all = new ArrayList<>();
        for (String tf : tfsToScan) {
            List<DwellPivot> perTf = detectForTf(instrument, tf);
            all.addAll(perTf);
            log.info("[dwell-attacher] symbol={} tf={} dwellPivots={}",
                    base.getSymbol(), tf, perTf.size());
        }

        return base.toBuilder().dwellPivots(all).build();
    }

    private List<DwellPivot> detectForTf(Instrument instrument, String tf) {
        Interval interval;
        try {
            interval = Interval.valueOf(tf);
        } catch (IllegalArgumentException e) {
            log.warn("[dwell-attacher] unknown TF '{}'", tf);
            return List.of();
        }
        BarSeries series;
        try {
            series = barsLoader.loadInstrumentSeries(instrument, interval);
        } catch (DataFetchException e) {
            log.warn("[dwell-attacher] bar load failed symbol={} tf={}: {}",
                    instrument.getTradingsymbol(), tf, e.getMessage());
            return List.of();
        }
        if (series == null || series.getBarCount() == 0) return List.of();

        double k = paramsFor(tf, kWeek, kDay, kHour);
        int minN = (int) paramsFor(tf, minNWeek, minNDay, minNHour);
        double atrMult = paramsFor(tf, atrMultWeek, atrMultDay, atrMultHour);

        return dwellPivotDetector.detect(series, tf, atrMult, k, minN,
                DwellPivotDetector.DEFAULT_LOOKFORWARD_BARS,
                DwellPivotDetector.DEFAULT_DIRECTION_BREAK_ATR);
    }

    /**
     * Test-only entry point: detect dwell pivots directly on a supplied series, skipping the
     * DB load. Used by {@code DwellPivotAttacherTest} and the acceptance harness.
     */
    public List<DwellPivot> detectOnSeries(BarSeries series, String tf) {
        double k = paramsFor(tf, kWeek, kDay, kHour);
        int minN = (int) paramsFor(tf, minNWeek, minNDay, minNHour);
        double atrMult = paramsFor(tf, atrMultWeek, atrMultDay, atrMultHour);
        return dwellPivotDetector.detect(series, tf, atrMult, k, minN,
                DwellPivotDetector.DEFAULT_LOOKFORWARD_BARS,
                DwellPivotDetector.DEFAULT_DIRECTION_BREAK_ATR);
    }

    private static double paramsFor(String tf, double weekVal, double dayVal, double hourVal) {
        switch (tf) {
            case "Week": return weekVal;
            case "Day": return dayVal;
            case "OneHour": return hourVal;
            default: return dayVal;
        }
    }

    private static boolean isSupportedTf(String tf) {
        return "Week".equals(tf) || "Day".equals(tf) || "OneHour".equals(tf);
    }
}
