package com.dtech.aitrader.v2.pivots;

import com.dtech.chartdata.model.OhlcBarDTO;
import com.dtech.chartdata.service.ChartDataService;
import com.dtech.chartpattern.zigzag.ZigZagParams;
import com.dtech.chartpattern.zigzag.ZigZagPoint;
import com.dtech.chartpattern.zigzag.ZigZagService;
import com.dtech.kitecon.strategy.dataloader.BarsLoader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.ta4j.core.Bar;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBarSeriesBuilder;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds a per-symbol {@link PivotBundle} by running {@link ZigZagService} over each configured
 * timeframe's bars. Cutoff-aligned to the same {@code asOfDate} the narrative bundle uses —
 * crucial for consumers cross-referencing pivot bundles with narratives.
 *
 * <p>For each timeframe:
 * <ol>
 *   <li>Pull bars from {@link ChartDataService} (DB-only).</li>
 *   <li>Optionally trim to {@code asOfDate} so all 4 TFs share the same forward cutoff.</li>
 *   <li>Cap to the configured bar window (default 500).</li>
 *   <li>Defensive dedupe by timestamp (same backfill-race lesson as narrative builder).</li>
 *   <li>Build a {@link BarSeries} and run {@link ZigZagService#detect}.</li>
 *   <li>Convert each {@link ZigZagPoint} to a {@link PivotBundle.Pivot}.</li>
 * </ol>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PivotBundleBuilder {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final ChartDataService chartDataService;
    private final ZigZagService zigZagService;

    @Value("${pivot.bundle.bar-cap:500}")
    private int barCap;

    /**
     * Build a complete pivot bundle for a symbol across the supplied TF list. Returns
     * {@code null} if every TF came up empty (caller treats as skip, not failure).
     */
    public PivotBundle build(Long userId, String symbol,
                              List<String> tfEnums, List<String> tfLabels,
                              String forwardCutoffDate, String dateLabel) {
        Map<String, PivotBundle.TimeframePivots> map = new LinkedHashMap<>();
        for (int i = 0; i < tfEnums.size(); i++) {
            String tfEnum = tfEnums.get(i);
            String tfLabel = tfLabels.get(i);
            PivotBundle.TimeframePivots tfp = buildOneTimeframe(symbol, tfEnum, forwardCutoffDate);
            if (tfp != null) {
                map.put(tfLabel, tfp);
            }
        }
        if (map.isEmpty()) {
            log.info("[pivot-bundle] {} — all TFs empty, skipping", symbol);
            return null;
        }
        return PivotBundle.builder()
                .userId(userId)
                .symbol(symbol)
                .dateLabel(dateLabel)
                .asOfDate(forwardCutoffDate)
                .timeframes(map)
                .build();
    }

    private PivotBundle.TimeframePivots buildOneTimeframe(String symbol, String tfEnum,
                                                           String forwardCutoffDate) {
        List<OhlcBarDTO> bars;
        try {
            bars = chartDataService.getBars(symbol, tfEnum, null, null, false);
        } catch (Exception e) {
            log.warn("[pivot-bundle] {} {} fetch failed: {}", symbol, tfEnum, e.getMessage());
            return null;
        }
        if (bars == null || bars.isEmpty()) return null;

        if (forwardCutoffDate != null && !forwardCutoffDate.isBlank()) {
            long cutoffEpoch = endOfDayUtcEpoch(forwardCutoffDate);
            bars = bars.stream().filter(b -> b.getTime() <= cutoffEpoch).toList();
            if (bars.isEmpty()) return null;
        }

        // Dedupe duplicate timestamps before BarSeries build (same lesson as narrative builder).
        java.util.Set<Long> seen = new java.util.HashSet<>();
        List<OhlcBarDTO> deduped = new ArrayList<>(bars.size());
        for (OhlcBarDTO b : bars) {
            if (seen.add(b.getTime())) deduped.add(b);
        }
        bars = deduped;

        int cap = barCap > 0 ? barCap : 500;
        if (bars.size() > cap) bars = bars.subList(bars.size() - cap, bars.size());

        BarSeries series = new BaseBarSeriesBuilder().withName(symbol + "-" + tfEnum).build();
        for (OhlcBarDTO b : bars) {
            Bar taBar = BarsLoader.getBar(b.getOpen(), b.getHigh(), b.getLow(), b.getClose(),
                    b.getVolume(), Instant.ofEpochSecond(b.getTime()));
            series.addBar(taBar);
        }

        ZigZagParams params = zigZagService.resolveParams(symbol, null);
        if (params == null) {
            // Fallback to sensible defaults if the service didn't return per-symbol overrides.
            params = ZigZagParams.ofDefaults(14, 2.5, 0.02, 0.7, 5, false, 1.5, 20,
                    ZigZagParams.Mode.LIVE);
        }
        List<ZigZagPoint> pivots = zigZagService.detect(series, params);

        List<PivotBundle.Pivot> out = new ArrayList<>(pivots.size());
        for (ZigZagPoint p : pivots) {
            out.add(PivotBundle.Pivot.builder()
                    .idx(p.getBarIndex())
                    .date(LocalDate.ofInstant(p.getTimestamp(), ZoneId.of("Asia/Kolkata")).format(DATE_FMT))
                    .type(p.getType() == ZigZagPoint.Type.HIGH ? "high" : "low")
                    .price(p.getValue())
                    .atrAtPivot(p.getAtrAtPivot())
                    .build());
        }

        String bar0 = LocalDate.ofInstant(Instant.ofEpochSecond(bars.get(0).getTime()),
                ZoneId.of("Asia/Kolkata")).format(DATE_FMT);
        String lastBar = LocalDate.ofInstant(Instant.ofEpochSecond(bars.get(bars.size() - 1).getTime()),
                ZoneId.of("Asia/Kolkata")).format(DATE_FMT);
        return PivotBundle.TimeframePivots.builder()
                .barCount(bars.size())
                .lastBarDate(lastBar)
                .bar0Date(bar0)
                .pivots(out)
                .build();
    }

    private static long endOfDayUtcEpoch(String isoDate) {
        LocalDate d = LocalDate.parse(isoDate);
        return d.atTime(23, 59, 59).atZone(ZoneId.of("Asia/Kolkata")).toEpochSecond();
    }
}
