package com.dtech.aitrader.v2.narrative.bundle;

import com.dtech.aitrader.v2.narrative.adx.AdxIndicatorConfig;
import com.dtech.aitrader.v2.narrative.adx.AdxNarrativeParams;
import com.dtech.aitrader.v2.narrative.aroon.AroonIndicatorConfig;
import com.dtech.aitrader.v2.narrative.aroon.AroonNarrativeParams;
import com.dtech.aitrader.v2.narrative.atr.AtrIndicatorConfig;
import com.dtech.aitrader.v2.narrative.atr.AtrNarrativeParams;
import com.dtech.aitrader.v2.narrative.beat.Narrative;
import com.dtech.aitrader.v2.narrative.bollinger.BollingerIndicatorConfig;
import com.dtech.aitrader.v2.narrative.bollinger.BollingerNarrativeParams;
import com.dtech.aitrader.v2.narrative.donchian.DonchianIndicatorConfig;
import com.dtech.aitrader.v2.narrative.donchian.DonchianNarrativeParams;
import com.dtech.aitrader.v2.narrative.ema.EmaIndicatorConfig;
import com.dtech.aitrader.v2.narrative.ema.EmaNarrativeParams;
import com.dtech.aitrader.v2.narrative.engine.CompactNarrativeRenderer;
import com.dtech.aitrader.v2.narrative.engine.DescriptiveNarrativeEngine;
import com.dtech.aitrader.v2.narrative.ichimoku.IchimokuIndicatorConfig;
import com.dtech.aitrader.v2.narrative.ichimoku.IchimokuNarrativeParams;
import com.dtech.aitrader.v2.narrative.keltner.KeltnerIndicatorConfig;
import com.dtech.aitrader.v2.narrative.keltner.KeltnerNarrativeParams;
import com.dtech.aitrader.v2.narrative.macd.MacdIndicatorConfig;
import com.dtech.aitrader.v2.narrative.macd.MacdNarrativeParams;
import com.dtech.aitrader.v2.narrative.obv.ObvIndicatorConfig;
import com.dtech.aitrader.v2.narrative.obv.ObvNarrativeParams;
import com.dtech.aitrader.v2.narrative.roc.RocIndicatorConfig;
import com.dtech.aitrader.v2.narrative.roc.RocNarrativeParams;
import com.dtech.aitrader.v2.narrative.rsi.RsiIndicatorConfig;
import com.dtech.aitrader.v2.narrative.rsi.RsiNarrativeParams;
import com.dtech.aitrader.v2.narrative.stoch.StochIndicatorConfig;
import com.dtech.aitrader.v2.narrative.stoch.StochNarrativeParams;
import com.dtech.aitrader.v2.narrative.stochrsi.StochRsiIndicatorConfig;
import com.dtech.aitrader.v2.narrative.stochrsi.StochRsiNarrativeParams;
import com.dtech.aitrader.v2.narrative.vwap.VwapIndicatorConfig;
import com.dtech.aitrader.v2.narrative.vwap.VwapNarrativeParams;
import com.dtech.chartdata.model.OhlcBarDTO;
import com.dtech.chartdata.service.ChartDataService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds one narrative-compact bundle (15 indicators × one TF) for one symbol. Pulls bars
 * from {@link ChartDataService}, runs all 15 {@code IndicatorConfig}s through the shared
 * {@link DescriptiveNarrativeEngine}, and renders with {@link CompactNarrativeRenderer}.
 *
 * <p>Indicator set (15 — Williams %R excluded per dedup, owner b3ff4ca0):
 * MACD, RSI, Stochastic, StochRSI, ROC, OBV, ADX/DMI, Aroon, Bollinger, Keltner, Donchian,
 * EMA stack, ATR, VWAP, Ichimoku.
 *
 * <p>Cutoff alignment: caller supplies an optional {@code forwardCutoffDate} (ISO YYYY-MM-DD).
 * Bars at or before that date are kept; anything past is dropped. This mirrors the Python
 * dump-script behaviour so weekly bars don't leak forward of daily/hourly/15min cutoffs.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class NarrativeBundleBuilder {

    private static final int DEFAULT_BAR_CAP = 500;

    private final ChartDataService chartDataService;
    private final DescriptiveNarrativeEngine engine;
    private final CompactNarrativeRenderer renderer = new CompactNarrativeRenderer();

    @Value("${narrative.bundle.bar-cap:500}")
    private int barCap;

    /**
     * Returns the daily-bar count for a symbol — used by the dump service to pre-filter
     * symbols with insufficient history (owner b3ff4ca0 widen-to-FNO: skip {@code <250} daily).
     * Empty list = symbol unknown / no data; treat as 0.
     */
    public int countDailyBars(String symbol) {
        try {
            List<OhlcBarDTO> bars = chartDataService.getBars(symbol, "Day", null, null, false);
            return bars == null ? 0 : bars.size();
        } catch (Exception e) {
            log.warn("[narrative-bundle] {} daily-count fetch failed: {}", symbol, e.getMessage());
            return 0;
        }
    }

    /**
     * Build one bundle for one (symbol, TF) at the given cutoff. Returns {@code null} if no
     * bars are available — caller should treat as a skip, not a failure.
     */
    public NarrativeBundle build(Long userId, String symbol, String tfEnum, String tfLabel,
                                  String forwardCutoffDate, String dateLabel) {
        // 1. Load bars from DB (no live fetch — keep generation deterministic).
        List<OhlcBarDTO> bars;
        try {
            bars = chartDataService.getBars(symbol, tfEnum, null, null, false);
        } catch (Exception e) {
            log.warn("[narrative-bundle] {} {} fetch failed: {}", symbol, tfEnum, e.getMessage());
            return null;
        }
        if (bars == null || bars.isEmpty()) {
            log.info("[narrative-bundle] {} {} — no bars; skipping", symbol, tfEnum);
            return null;
        }

        // 2. Cutoff alignment — drop bars past the forward cutoff (timezone: IST).
        if (forwardCutoffDate != null && !forwardCutoffDate.isBlank()) {
            long cutoffEpochInclusive = endOfDayUtcEpoch(forwardCutoffDate);
            bars = bars.stream().filter(b -> b.getTime() <= cutoffEpochInclusive).toList();
            if (bars.isEmpty()) {
                log.info("[narrative-bundle] {} {} — all bars past cutoff {}; skipping",
                        symbol, tfEnum, forwardCutoffDate);
                return null;
            }
        }

        // 3a. Defensive dedup — upstream candle table can have duplicate-timestamp rows
        // (live backfill jobs racing); ta4j's BaseBarSeriesBuilder hard-rejects bars with
        // endTime <= prevEndTime. Drop dupes silently before the engine sees them.
        bars = dedupeByTimestamp(bars, symbol, tfEnum);

        // 3b. Cap to last N bars per owner ("500 bars is good enough").
        int cap = barCap > 0 ? barCap : DEFAULT_BAR_CAP;
        if (bars.size() > cap) {
            bars = bars.subList(bars.size() - cap, bars.size());
        }

        // 4. Run all 15 indicators through the engine.
        List<Narrative> ns = new ArrayList<>(15);
        // Full narrative (6)
        ns.add(engine.extract(bars, symbol, tfEnum, new MacdIndicatorConfig(MacdNarrativeParams.ofDefaults())));
        ns.add(engine.extract(bars, symbol, tfEnum, new RsiIndicatorConfig(RsiNarrativeParams.ofDefaults())));
        ns.add(engine.extract(bars, symbol, tfEnum, new StochIndicatorConfig(StochNarrativeParams.ofDefaults())));
        ns.add(engine.extract(bars, symbol, tfEnum, new StochRsiIndicatorConfig(StochRsiNarrativeParams.ofDefaults())));
        ns.add(engine.extract(bars, symbol, tfEnum, new RocIndicatorConfig(RocNarrativeParams.ofDefaults())));
        ns.add(engine.extract(bars, symbol, tfEnum, new ObvIndicatorConfig(ObvNarrativeParams.ofDefaults())));
        // Regime episode (6)
        ns.add(engine.extract(bars, symbol, tfEnum, new AdxIndicatorConfig(AdxNarrativeParams.ofDefaults())));
        ns.add(engine.extract(bars, symbol, tfEnum, new AroonIndicatorConfig(AroonNarrativeParams.ofDefaults())));
        ns.add(engine.extract(bars, symbol, tfEnum, new BollingerIndicatorConfig(BollingerNarrativeParams.ofDefaults())));
        ns.add(engine.extract(bars, symbol, tfEnum, new KeltnerIndicatorConfig(KeltnerNarrativeParams.ofDefaults())));
        ns.add(engine.extract(bars, symbol, tfEnum, new DonchianIndicatorConfig(DonchianNarrativeParams.ofDefaults())));
        ns.add(engine.extract(bars, symbol, tfEnum, new EmaIndicatorConfig(EmaNarrativeParams.ofDefaults())));
        // Snapshot (3)
        ns.add(engine.extract(bars, symbol, tfEnum, new AtrIndicatorConfig(AtrNarrativeParams.ofDefaults())));
        ns.add(engine.extract(bars, symbol, tfEnum, new VwapIndicatorConfig(VwapNarrativeParams.ofDefaults())));
        ns.add(engine.extract(bars, symbol, tfEnum, new IchimokuIndicatorConfig(IchimokuNarrativeParams.ofDefaults())));

        // 5. Render compact body.
        Narrative first = ns.get(0);
        String bar0 = first.getBar0Date();
        int lastIdx = first.getLastBar().getIndex();
        String lastDate = first.getLastBar().getDate();
        double lastClose = first.getLastBar().getClose();
        String body = renderer.renderMtfMemory(symbol, tfLabel, bar0, bars.size(),
                lastIdx, lastDate, lastClose, ns);

        return NarrativeBundle.builder()
                .userId(userId)
                .symbol(symbol)
                .tfEnum(tfEnum)
                .tfLabel(tfLabel)
                .body(body)
                .barCount(bars.size())
                .lastBarDate(lastDate)
                .dateLabel(dateLabel)
                .build();
    }

    /**
     * Strip duplicate-timestamp bars (keep first occurrence). Upstream candle inserts can
     * race during live backfill — same (token, ts) row inserted twice. ta4j throws if a bar's
     * end-time is &le; the previous bar's, so a single duplicate kills the whole indicator
     * pipeline for that symbol/TF. Logging-only when dupes are detected; no exception.
     */
    private static List<OhlcBarDTO> dedupeByTimestamp(List<OhlcBarDTO> bars,
                                                      String symbol, String tfEnum) {
        java.util.Set<Long> seen = new java.util.HashSet<>();
        java.util.List<OhlcBarDTO> out = new java.util.ArrayList<>(bars.size());
        int dropped = 0;
        for (OhlcBarDTO b : bars) {
            if (seen.add(b.getTime())) {
                out.add(b);
            } else {
                dropped++;
            }
        }
        if (dropped > 0) {
            log.warn("[narrative-bundle] {} {} dropped {} duplicate-timestamp bar(s)",
                    symbol, tfEnum, dropped);
        }
        return out;
    }

    /**
     * Returns the epoch-second value just-before-midnight (23:59:59) IST of the given ISO date.
     * Bars whose epoch-second &le; this remain — anything past is dropped. Used for cutoff
     * alignment (weekly bars that span into the next intraday week leak forward; this trims them).
     */
    private static long endOfDayUtcEpoch(String isoDate) {
        // Parse YYYY-MM-DD as IST end-of-day → UTC epoch.
        java.time.LocalDate d = java.time.LocalDate.parse(isoDate);
        java.time.ZonedDateTime endOfDay = d.atTime(23, 59, 59)
                .atZone(java.time.ZoneId.of("Asia/Kolkata"));
        return endOfDay.toEpochSecond();
    }
}
