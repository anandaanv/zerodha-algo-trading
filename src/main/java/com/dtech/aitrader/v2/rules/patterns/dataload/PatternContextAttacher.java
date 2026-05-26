package com.dtech.aitrader.v2.rules.patterns.dataload;

import com.dtech.aitrader.v2.rules.SymbolContext;
import com.dtech.algo.series.Interval;
import com.dtech.kitecon.data.Instrument;
import com.dtech.kitecon.market.fetch.DataFetchException;
import com.dtech.kitecon.repository.InstrumentRepository;
import com.dtech.kitecon.service.copilot.dto.MarketStructurePoint;
import com.dtech.kitecon.strategy.dataloader.BarsLoader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.ta4j.core.BarSeries;

import java.util.List;
import java.util.Map;

/**
 * Pattern system's own data loader per owner correction ({@code 89a52589}): pattern computation
 * stays a separate code path from EW. This service is called AFTER {@code ScanContextLoader} has
 * built the base context (EW data), and ATTACHES the bar series + pivots that pattern Pass-2
 * rules (DT, DB, HnS, etc.) need to detect on. The pattern system is now self-contained — its
 * inputs are populated by its OWN code, not by hacking the EW loader.
 *
 * <p>Owner principle: "separate compute, shared storage, called together." Both EW and pattern
 * write firings into the same {@link SymbolContext} (and downstream firing store) — confluence
 * rules ({@code EwExhaustionAtTargetRule}) read cross-family. But the COMPUTE paths (this loader
 * vs {@code ScanContextLoader}) are independent code.
 *
 * <p>PHASE-A note: pivots come from the scan-context bundle's {@code pivotsByTf} map (shared
 * data, not shared compute — both EW and pattern read the same canonical zigzag output). Bars
 * are loaded from the candle repository (DB). A future refinement could re-detect pivots from
 * the bar series via a pattern-owned zigzag detector — that would fully decouple pivot data too.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PatternContextAttacher {

    private final InstrumentRepository instrumentRepository;
    private final BarsLoader barsLoader;
    private final CandleSwingExtractor candleSwingExtractor;

    /**
     * Augment a SymbolContext with bar series + pivots aligned to a target TF, so pattern
     * Pass-2 rules can detect on real bars. Returns the input context unchanged if the bars
     * can't be loaded — the caller checks {@code getSeries()} to decide whether to run pattern
     * rules.
     *
     * @param base           base context (typically from {@code ScanContextLoader.loadById})
     * @param targetTfLabel  "Week" | "Day" | "OneHour" — TF the pattern rules run on
     * @return new SymbolContext with {@code series} + {@code pivots} populated, or {@code base}
     *         on lookup failure
     */
    public SymbolContext attach(SymbolContext base, String targetTfLabel) {
        if (base == null) return null;
        if (base.getSymbol() == null) {
            log.warn("[pattern-loader] base context has no symbol");
            return base;
        }

        Interval interval;
        try {
            interval = Interval.valueOf(targetTfLabel);
        } catch (IllegalArgumentException e) {
            log.warn("[pattern-loader] unknown TF label '{}' — supported: Week / Day / OneHour", targetTfLabel);
            return base;
        }

        Instrument instrument = instrumentRepository.findByTradingsymbolAndExchangeIn(
                base.getSymbol(), new String[]{"NSE"});
        if (instrument == null) {
            log.warn("[pattern-loader] instrument not found for symbol={}", base.getSymbol());
            return base;
        }

        BarSeries series;
        try {
            series = barsLoader.loadInstrumentSeries(instrument, interval);
        } catch (DataFetchException e) {
            log.warn("[pattern-loader] bar load failed symbol={} interval={}: {}",
                    base.getSymbol(), interval, e.getMessage());
            return base;
        }
        if (series == null || series.getBarCount() == 0) {
            log.warn("[pattern-loader] empty series for symbol={} interval={}", base.getSymbol(), interval);
            return base;
        }

        // Pivots from the bundle's pivotsByTf for the matching TF label. Shared DATA (zigzag
        // output), independent COMPUTE (this loader runs in its own code path).
        Map<String, List<MarketStructurePoint>> byTf = base.getPivotsByTf();
        List<MarketStructurePoint> pivots = (byTf != null) ? byTf.get(targetTfLabel) : null;
        if (pivots == null) pivots = List.of();

        log.info("[pattern-loader] attached symbol={} interval={} bars={} pivots={}",
                base.getSymbol(), interval, series.getBarCount(), pivots.size());

        return base.toBuilder()
                .series(series)
                .pivots(pivots)
                .build();
    }

    /**
     * Candle-swing variant per owner direction {@code 4a322dbe}: substrate-independent endpoint
     * where pattern pivots come from local-extremum candle highs/lows, NOT the smoothed zigzag.
     * Same interface as {@link #attach(SymbolContext, String)} but the {@code pivots} field on
     * the returned context is the {@link CandleSwingExtractor} output for the requested TF's bar
     * series. EW context ({@code pivotsByTf}) is left untouched — only the pattern engine's
     * own {@code pivots} field is rebased on candles.
     *
     * @param base           base context (typically from {@code ScanContextLoader.loadById})
     * @param targetTfLabel  "Week" | "Day" | "OneHour" — TF for both bar load and swing extraction
     * @return context with {@code series} loaded + {@code pivots} rebased on candle swings,
     *         or {@code base} on lookup failure
     */
    public SymbolContext attachWithCandleSwings(SymbolContext base, String targetTfLabel) {
        return attachWithCandleSwings(base, targetTfLabel,
                CandleSwingExtractor.DEFAULT_LOOKBACK_N,
                CandleSwingExtractor.DEFAULT_MIN_SWING_ATR);
    }

    public SymbolContext attachWithCandleSwings(SymbolContext base, String targetTfLabel,
                                                  int lookbackN, double minSwingAtr) {
        if (base == null) return null;
        if (base.getSymbol() == null) {
            log.warn("[pattern-loader] base context has no symbol");
            return base;
        }
        Interval interval;
        try {
            interval = Interval.valueOf(targetTfLabel);
        } catch (IllegalArgumentException e) {
            log.warn("[pattern-loader] unknown TF label '{}'", targetTfLabel);
            return base;
        }
        Instrument instrument = instrumentRepository.findByTradingsymbolAndExchangeIn(
                base.getSymbol(), new String[]{"NSE"});
        if (instrument == null) {
            log.warn("[pattern-loader] instrument not found for symbol={}", base.getSymbol());
            return base;
        }
        BarSeries series;
        try {
            series = barsLoader.loadInstrumentSeries(instrument, interval);
        } catch (DataFetchException e) {
            log.warn("[pattern-loader] bar load failed symbol={} interval={}: {}",
                    base.getSymbol(), interval, e.getMessage());
            return base;
        }
        if (series == null || series.getBarCount() == 0) {
            log.warn("[pattern-loader] empty series for symbol={} interval={}", base.getSymbol(), interval);
            return base;
        }

        List<MarketStructurePoint> candleSwings =
                candleSwingExtractor.extract(series, lookbackN, minSwingAtr);
        log.info("[pattern-loader-candle] attached symbol={} interval={} bars={} candle-swings={}",
                base.getSymbol(), interval, series.getBarCount(), candleSwings.size());

        return base.toBuilder()
                .series(series)
                .pivots(candleSwings)
                .build();
    }
}
