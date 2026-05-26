package com.dtech.aitrader.v2.rules;

import com.dtech.chartdata.model.OhlcBarDTO;
import com.dtech.chartdata.service.ChartDataService;
import com.dtech.chartpattern.zigzag.ZigZagParams;
import com.dtech.chartpattern.zigzag.ZigZagPoint;
import com.dtech.chartpattern.zigzag.ZigZagService;
import com.dtech.kitecon.service.copilot.MarketStructureService;
import com.dtech.kitecon.service.copilot.dto.MarketStructureData;
import com.dtech.kitecon.service.copilot.dto.MarketStructurePoint;
import com.dtech.kitecon.strategy.dataloader.BarsLoader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.ta4j.core.Bar;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBarSeriesBuilder;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Builds the {@link SymbolContext} a rule sees when evaluating at {@code asOf}. This class is the
 * SINGLE CHOKEPOINT that enforces the no-leakage invariant — no bar with epoch &gt; end-of-day(asOf)
 * survives the filter, so no rule downstream can read a future bar.
 *
 * <p>Pipeline:
 * <ol>
 *   <li>Pull bars via {@link ChartDataService#getBars} (DB-only, no live fetch).</li>
 *   <li>Filter to {@code time ≤ end-of-day(asOf)} (the leakage guard).</li>
 *   <li>Dedupe by timestamp (defensive — same lesson as narrative builder).</li>
 *   <li>Cap to last N bars (default 600 daily; leaves ATR/EMA200 warmup intact).</li>
 *   <li>Build a ta4j {@link BarSeries}.</li>
 *   <li>Run {@link ZigZagService#detect} for zigzag pivots.</li>
 *   <li>Run {@link MarketStructureService#analyse} for HH/HL/LH/LL labels + BOS/CHoCH.</li>
 *   <li>Run {@link ContextProbe#compute} for the three signature-driving enums.</li>
 * </ol>
 *
 * <p>Returns {@code null} when the symbol has too little data ({@code &lt; 200} bars or no pivots).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ContextLoader {

    /** Hard cap on bars loaded into a single context — keeps probe + rule passes fast. */
    private static final int DEFAULT_BAR_CAP = 600;

    /** Below this, ATR/EMA200/ADX warmup is incomplete — probe enums become unreliable. */
    private static final int MIN_BARS_FOR_PROBE = 220;

    private final ChartDataService chartDataService;
    private final ZigZagService zigZagService;
    private final MarketStructureService marketStructureService;
    private final ContextProbe contextProbe;

    public SymbolContext build(String symbol, LocalDate asOf, String tf) {
        return build(symbol, asOf, tf, DEFAULT_BAR_CAP);
    }

    /** Build a context with an explicit lookback cap (used by tests). */
    public SymbolContext build(String symbol, LocalDate asOf, String tf, int barCap) {
        List<OhlcBarDTO> raw;
        try {
            raw = chartDataService.getBars(symbol, tf, null, null, false);
        } catch (Exception e) {
            log.warn("[ctx-loader] {} {} fetch failed: {}", symbol, tf, e.getMessage());
            return null;
        }
        if (raw == null || raw.isEmpty()) {
            log.debug("[ctx-loader] {} {} no bars", symbol, tf);
            return null;
        }

        // ── 1. LEAKAGE GUARD ─ no bar with end-of-day epoch > asOf
        long cutoffEpoch = asOf.atTime(23, 59, 59).atZone(ZoneId.of("Asia/Kolkata")).toEpochSecond();
        List<OhlcBarDTO> filtered = new ArrayList<>(raw.size());
        for (OhlcBarDTO b : raw) {
            if (b.getTime() <= cutoffEpoch) filtered.add(b);
        }
        if (filtered.isEmpty()) {
            log.debug("[ctx-loader] {} {} all bars > asOf={}", symbol, tf, asOf);
            return null;
        }

        // ── 2. Dedupe by timestamp (defensive)
        Set<Long> seen = new HashSet<>();
        List<OhlcBarDTO> deduped = new ArrayList<>(filtered.size());
        for (OhlcBarDTO b : filtered) {
            if (seen.add(b.getTime())) deduped.add(b);
        }

        // ── 3. Cap to last N
        if (deduped.size() > barCap) {
            deduped = deduped.subList(deduped.size() - barCap, deduped.size());
        }

        if (deduped.size() < MIN_BARS_FOR_PROBE) {
            log.debug("[ctx-loader] {} {} bars={} < {}; insufficient warmup",
                    symbol, tf, deduped.size(), MIN_BARS_FOR_PROBE);
            return null;
        }

        // ── 4. Build ta4j series
        BarSeries series = new BaseBarSeriesBuilder().withName(symbol + "-" + tf).build();
        for (OhlcBarDTO b : deduped) {
            Bar taBar = BarsLoader.getBar(b.getOpen(), b.getHigh(), b.getLow(), b.getClose(),
                    b.getVolume(), Instant.ofEpochSecond(b.getTime()));
            series.addBar(taBar);
        }

        // ── 5. Zigzag + structure labels
        ZigZagParams params = zigZagService.resolveParams(symbol, null);
        if (params == null) {
            params = ZigZagParams.ofDefaults(14, 2.5, 0.02, 0.7, 5, false, 1.5, 20,
                    ZigZagParams.Mode.LIVE);
        }
        List<ZigZagPoint> zigzag = zigZagService.detect(series, params);
        if (zigzag == null || zigzag.isEmpty()) {
            log.debug("[ctx-loader] {} {} zero pivots", symbol, tf);
            return null;
        }
        MarketStructureData ms = marketStructureService.analyse(zigzag, tf);
        List<MarketStructurePoint> pivots = ms.getSwingPoints() == null
                ? Collections.emptyList() : ms.getSwingPoints();

        // ── 6. ContextProbe
        ContextProbeResult probe = contextProbe.compute(series, pivots);

        return SymbolContext.builder()
                .symbol(symbol)
                .asOf(asOf)
                .tf(tf)
                .bars(deduped)
                .series(series)
                .pivots(pivots)
                .probe(probe)
                .indicators(new IndicatorAccessor(series))
                .build();
    }
}
