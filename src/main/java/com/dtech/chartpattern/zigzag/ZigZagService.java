package com.dtech.chartpattern.zigzag;

import com.dtech.algo.series.Interval;
import com.dtech.chartpattern.config.ChartPatternProperties;
import com.dtech.chartpattern.persistence.ZigZagConfig;
import com.dtech.chartpattern.persistence.ZigZagConfigRepository;
import com.dtech.chartpattern.persistence.ZigZagSnapshot;
import com.dtech.chartpattern.persistence.ZigZagSnapshotRepository;
import com.dtech.kitecon.data.Instrument;
import com.dtech.kitecon.repository.CandleRepository;
import com.dtech.kitecon.service.DataFetchService;
import com.dtech.kitecon.strategy.dataloader.BarsLoader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.ta4j.core.Bar;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBarSeriesBuilder;
import org.ta4j.core.num.DoubleNumFactory;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class ZigZagService {

    private static final Map<Interval, Duration> PIVOT_TTL = Map.ofEntries(
            Map.entry(Interval.OneMinute,      Duration.ofMinutes(1)),
            Map.entry(Interval.ThreeMinute,    Duration.ofMinutes(3)),
            Map.entry(Interval.FiveMinute,     Duration.ofMinutes(5)),
            Map.entry(Interval.FifteenMinute,  Duration.ofMinutes(15)),
            Map.entry(Interval.ThirtyMinute,   Duration.ofMinutes(30)),
            Map.entry(Interval.OneHour,        Duration.ofMinutes(60)),
            Map.entry(Interval.FourHours,      Duration.ofMinutes(240)),
            Map.entry(Interval.Day,            Duration.ofHours(24)),
            Map.entry(Interval.Week,           Duration.ofDays(7))
    );

    private final ZigZagConfigRepository configRepository;
    private final ZigZagSnapshotRepository snapshotRepository;
    private final ChartPatternProperties properties;
    private final CandleRepository candleRepository;
    private final DataFetchService dataFetchService;

    public ZigZagParams resolveParams(String tradingSymbol, Interval interval) {
        Optional<ZigZagConfig> cfg = configRepository.findByTradingSymbolAndInterval(tradingSymbol, interval);
        ChartPatternProperties.ZigZagDefaults d = properties.getZigzag();

        int atrLen = cfg.map(ZigZagConfig::getAtrLength).orElse(d.getAtrLength());
        double atrMult = cfg.map(ZigZagConfig::getAtrMult).orElse(d.getAtrMult());
        double pctMin = cfg.map(ZigZagConfig::getPctMin).orElse(d.getPctMin());
        double hyst = cfg.map(ZigZagConfig::getHysteresis).orElse(d.getHysteresis());
        int minBars = cfg.map(ZigZagConfig::getMinBarsBetweenPivots).orElse(d.getMinBarsBetweenPivots());
        boolean dynamicPctEnabled = cfg.map(ZigZagConfig::isDynamicPctEnabled).orElse(d.isDynamicPctEnabled());
        double volMult = cfg.map(ZigZagConfig::getVolMult).orElse(d.getVolMult());
        int rvolWindow = cfg.map(ZigZagConfig::getRvolWindow).orElse(d.getRvolWindow());
        ZigZagParams.Mode mode = "BACKTEST".equalsIgnoreCase(d.getMode()) ? ZigZagParams.Mode.BACKTEST : ZigZagParams.Mode.LIVE;
        return ZigZagParams.ofDefaults(atrLen, atrMult, pctMin, hyst, minBars, dynamicPctEnabled, volMult, rvolWindow, mode);
    }

    public List<ZigZagPoint> detect(BarSeries series, ZigZagParams params) {
        if (series == null || series.isEmpty()) return Collections.emptyList();
        int n = series.getBarCount();
        double[] tr = new double[n];
        double[] atr = new double[n];

        // Compute True Range and Wilder ATR
        for (int i = 0; i < n; i++) {
            Bar b = series.getBar(i);
            double high = b.getHighPrice().doubleValue();
            double low = b.getLowPrice().doubleValue();
            double prevClose = (i == 0) ? b.getClosePrice().doubleValue() : series.getBar(i - 1).getClosePrice().doubleValue();
            double tr1 = high - low;
            double tr2 = Math.abs(high - prevClose);
            double tr3 = Math.abs(low - prevClose);
            tr[i] = Math.max(tr1, Math.max(tr2, tr3));
            if (i == 0) {
                atr[i] = tr[i];
            } else if (i < params.getAtrLength()) {
                atr[i] = ((atr[i - 1] * i) + tr[i]) / (i + 1);
            } else if (i == params.getAtrLength()) {
                double sum = 0.0;
                for (int k = i - params.getAtrLength() + 1; k <= i; k++) sum += tr[k];
                atr[i] = sum / params.getAtrLength();
            } else {
                atr[i] = ((atr[i - 1] * (params.getAtrLength() - 1)) + tr[i]) / params.getAtrLength();
            }
        }

        // Relative volatility (EMA of TR/Close)
        double[] rvol = computeRvolPct(series, tr, params.getRvolWindow());

        List<ZigZagPoint> pivots = new ArrayList<>();
        enum Dir { UP, DOWN, NONE }
        Dir dir = Dir.NONE;

        int lastExtIdx = 0;
        double lastExtPrice = series.getBar(0).getClosePrice().doubleValue();
        double currExtPriceHigh = series.getBar(0).getHighPrice().doubleValue();
        double currExtPriceLow = series.getBar(0).getLowPrice().doubleValue();
        int currExtIdx = 0;

        for (int i = 1; i < n; i++) {
            Bar b = series.getBar(i);
            double close = b.getClosePrice().doubleValue();
            double high = b.getHighPrice().doubleValue();
            double low = b.getLowPrice().doubleValue();

            if (dir == Dir.NONE) {
                // Determine initial direction when move exceeds min threshold
                double move = close - lastExtPrice;
                double effectivePct = params.isDynamicPctEnabled() ? params.getVolMult() * rvol[i] : params.getPctMin();
                double thr = Math.max(effectivePct * lastExtPrice, params.getAtrMult() * atr[i]);
                if (Math.abs(move) >= thr) {
                    dir = move > 0 ? Dir.UP : Dir.DOWN;
                    currExtPriceHigh = high;
                    currExtPriceLow = low;
                    currExtIdx = i;
                } else {
                    // Update extremes even before direction established
                    currExtPriceHigh = Math.max(currExtPriceHigh, high);
                    currExtPriceLow = Math.min(currExtPriceLow, low);
                    currExtIdx = (high >= currExtPriceHigh) ? i : (low <= currExtPriceLow ? i : currExtIdx);
                }
                continue;
            }

            // Extend current leg extremes
            if (dir == Dir.UP) {
                if (high >= currExtPriceHigh) {
                    currExtPriceHigh = high;
                    currExtIdx = i;
                }
                // Check for reversal
                double reversalMove = currExtPriceHigh - low;
                double effectivePct = params.isDynamicPctEnabled() ? params.getVolMult() * rvol[i] : params.getPctMin();
                double baseThr = Math.max(effectivePct * currExtPriceHigh, params.getAtrMult() * atr[i]);
                double revThr = baseThr * params.getHysteresis();
                boolean spaced = (i - lastExtIdx) >= params.getMinBarsBetweenPivots();
                if (reversalMove >= revThr && spaced) {
                    // Confirm HIGH pivot at currExtIdx
                    pivots.add(toPoint(series, currExtIdx, currExtPriceHigh, ZigZagPoint.Type.HIGH, atr[currExtIdx]));
                    lastExtIdx = currExtIdx;
                    lastExtPrice = currExtPriceHigh;
                    // Start DOWN leg
                    dir = Dir.DOWN;
                    currExtPriceLow = low;
                    currExtIdx = i;
                }
            } else { // DOWN
                if (low <= currExtPriceLow) {
                    currExtPriceLow = low;
                    currExtIdx = i;
                }
                double reversalMove = high - currExtPriceLow;
                double effectivePct = params.isDynamicPctEnabled() ? params.getVolMult() * rvol[i] : params.getPctMin();
                double baseThr = Math.max(effectivePct * Math.max(1e-9, currExtPriceLow), params.getAtrMult() * atr[i]);
                double revThr = baseThr * params.getHysteresis();
                boolean spaced = (i - lastExtIdx) >= params.getMinBarsBetweenPivots();
                if (reversalMove >= revThr && spaced) {
                    // Confirm LOW pivot at currExtIdx
                    pivots.add(toPoint(series, currExtIdx, currExtPriceLow, ZigZagPoint.Type.LOW, atr[currExtIdx]));
                    lastExtIdx = currExtIdx;
                    lastExtPrice = currExtPriceLow;
                    // Start UP leg
                    dir = Dir.UP;
                    currExtPriceHigh = high;
                    currExtIdx = i;
                }
            }
        }

        // In BACKTEST, you may optionally finalize the last extremum as pivot if desired
        if (params.getMode() == ZigZagParams.Mode.BACKTEST && pivots.size() >= 1) {
            ZigZagPoint last = pivots.get(pivots.size() - 1);
            if (last.getType() == ZigZagPoint.Type.HIGH) {
                pivots.add(toPoint(series, currExtIdx, currExtPriceLow, ZigZagPoint.Type.LOW, atr[currExtIdx]));
            } else {
                pivots.add(toPoint(series, currExtIdx, currExtPriceHigh, ZigZagPoint.Type.HIGH, atr[currExtIdx]));
            }
        }

        // Keep only pivots within the last N bars using sequence (epoch seconds) cutoff
        final int MAX_BARS = 1000;
        int cutoffIdx = Math.max(0, series.getBarCount() - MAX_BARS);
        long cutoffEpoch = series.getBar(cutoffIdx).getEndTime().getEpochSecond();

        java.util.List<ZigZagPoint> trimmed = new java.util.ArrayList<>();
        for (ZigZagPoint p : pivots) {
            if (p.getSequence() >= cutoffEpoch) {
                trimmed.add(p);
            }
        }

        // Compute retracement and extension percentages on each pivot for pattern analytics
        computePivotMetrics(trimmed);

        return trimmed;
    }

    /**
     * Compute retracement and extension percentages for each pivot in-place.
     *
     * Rules (assuming pivots alternate LOW/HIGH and are time-ordered):
     * - Retracement at pivot k (same type as k-2):
     *   * If Low(k-2)->High(k-1) is up amount U, then at Low(k): retr = (High(k-1)-Low(k))/U*100 (if U>0)
     *   * If High(k-2)->Low(k-1) is down amount D, then at High(k): retr = (High(k)-Low(k-1))/D*100 (if D>0)
     * - Extension at pivot k compares current swing (k-1->k) to previous same-direction swing (k-3->k-2):
     *   * Up:  (Low(k-1)->High(k)) / (Low(k-3)->High(k-2)) * 100
     *   * Down:(High(k-1)->Low(k))  / (High(k-3)->Low(k-2))  * 100
     */
    public void computePivotMetrics(java.util.List<ZigZagPoint> pivots) {
        if (pivots == null || pivots.isEmpty()) return;

        for (int k = 0; k < pivots.size(); k++) {
            ZigZagPoint curr = pivots.get(k);

            // Retracement requires k >= 2 (pattern X(k-2) -> OPP(k-1) -> X(k))
            if (k >= 2) {
                ZigZagPoint p2 = pivots.get(k - 2);
                ZigZagPoint p1 = pivots.get(k - 1);

                if (p2.isLow() && p1.isHigh() && curr.isLow()) {
                    double upPrev = p1.getValue() - p2.getValue();
                    if (upPrev > 0) {
                        double retr = (p1.getValue() - curr.getValue()) / upPrev * 100.0;
                        curr.setRetracementPct(Double.isFinite(retr) ? retr : null);
                    }
                } else if (p2.isHigh() && p1.isLow() && curr.isHigh()) {
                    double downPrev = p2.getValue() - p1.getValue();
                    if (downPrev > 0) {
                        double retr = (curr.getValue() - p1.getValue()) / downPrev * 100.0;
                        curr.setRetracementPct(Double.isFinite(retr) ? retr : null);
                    }
                }
            }

            // Extension requires k >= 3 (compare with previous same-direction swing)
            if (k >= 3) {
                ZigZagPoint p3 = pivots.get(k - 3);
                ZigZagPoint p2 = pivots.get(k - 2);
                ZigZagPoint p1 = pivots.get(k - 1);

                if (p3.isLow() && p2.isHigh() && p1.isLow() && curr.isHigh()) {
                    double upPrev = p2.getValue() - p3.getValue();
                    double upCurr = curr.getValue() - p1.getValue();
                    if (upPrev > 0 && upCurr >= 0) {
                        double ext = upCurr / upPrev * 100.0;
                        curr.setExtensionPct(Double.isFinite(ext) ? ext : null);
                    }
                } else if (p3.isHigh() && p2.isLow() && p1.isHigh() && curr.isLow()) {
                    double downPrev = p3.getValue() - p2.getValue();
                    double downCurr = p1.getValue() - curr.getValue();
                    if (downPrev > 0 && downCurr >= 0) {
                        double ext = downCurr / downPrev * 100.0;
                        curr.setExtensionPct(Double.isFinite(ext) ? ext : null);
                    }
                }
            }
        }
    }

    // Compute EMA of TR/close to get a volatility percentage per bar
    private double[] computeRvolPct(BarSeries series, double[] tr, int window) {
        int n = series.getBarCount();
        double[] rvol = new double[n];
        if (n == 0) return rvol;
        double alpha = 2.0 / (window + 1.0);
        for (int i = 0; i < n; i++) {
            double close = series.getBar(i).getClosePrice().doubleValue();
            double rv = close > 0 ? tr[i] / close : 0.0;
            if (i == 0) {
                rvol[i] = rv;
            } else {
                rvol[i] = alpha * rv + (1 - alpha) * rvol[i - 1];
            }
        }
        return rvol;
    }

    private ZigZagPoint toPoint(BarSeries series, int idx, double price, ZigZagPoint.Type type, double atrAtPivot) {
        Bar bar = series.getBar(idx);
        Instant zdt = bar.getEndTime();
        return ZigZagPoint.builder()
                .type(type)
                .timestamp(zdt)
                .sequence(zdt.getEpochSecond())
                .value(price)
                .atrAtPivot(atrAtPivot)
                .build();
    }

    private boolean isFresh(ZigZagSnapshot snap, Interval interval) {
        Duration ttl = PIVOT_TTL.getOrDefault(interval, Duration.ofMinutes(15));
        return snap.getUpdatedAt().isAfter(LocalDateTime.now().minus(ttl));
    }

    public List<ZigZagPoint> detectAndPersist(String tradingSymbol, Instrument instrument, Interval interval, boolean persist) {
        ZigZagParams params = resolveParams(tradingSymbol, interval);

        // Ensure latest candles are available for this symbol/timeframe before calculation
        try {
            dataFetchService.updateInstrumentToLatest(tradingSymbol, interval, new String[]{"NSE"});
        } catch (Exception e) {
            log.warn("Data refresh failed for {} {}: {}", tradingSymbol, interval, e.getMessage());
        }

        Instant from = candleLookbackFrom(interval);
        List<com.dtech.kitecon.data.Candle> candles = candleRepository
                .findAllByInstrumentAndTimeframeAndTimestampBetween(instrument, interval, from, Instant.now());
        candles.sort(Comparator.comparing(com.dtech.kitecon.data.Candle::getTimestamp));
        BarSeries series = new BaseBarSeriesBuilder().withName(tradingSymbol).build();
        candles.forEach(c ->
                series.addBar(BarsLoader.getBar(c.getOpen(), c.getHigh(), c.getLow(), c.getClose(), Optional.ofNullable(c.getVolume()).orElse(0L), c.getTimestamp())));
        List<ZigZagPoint> pivots = detect(series, params);

        if (persist) {
            persistSnapshot(tradingSymbol, interval, pivots);
        }
        return pivots;
    }

    /**
     * Build a BarSeries from persisted candles for this symbol/interval.
     * Candles are sorted oldest-first, matching the ZigZag pivot bar indices.
     */
    public BarSeries getBarSeries(String tradingSymbol, Instrument instrument, Interval interval) {
        Instant from = candleLookbackFrom(interval);
        List<com.dtech.kitecon.data.Candle> candles = candleRepository
                .findAllByInstrumentAndTimeframeAndTimestampBetween(instrument, interval, from, Instant.now());
        candles.sort(Comparator.comparing(com.dtech.kitecon.data.Candle::getTimestamp));
        BarSeries series = new BaseBarSeriesBuilder().withName(tradingSymbol).build();
        candles.forEach(c ->
                series.addBar(BarsLoader.getBar(c.getOpen(), c.getHigh(), c.getLow(), c.getClose(),
                        Optional.ofNullable(c.getVolume()).orElse(0L), c.getTimestamp())));
        return series;
    }

    /**
     * Try to load ZigZag pivots from snapshot; if none, compute and persist.
     */
    public List<ZigZagPoint> getOrComputePivots(String tradingSymbol, Instrument instrument, Interval interval) {
        return snapshotRepository.findByTradingSymbolAndInterval(tradingSymbol, interval)
                .map(snap -> {
                    if (!isFresh(snap, interval)) {
                        log.info("[ZigZag] Stale snapshot for {} {} (updated {}), recomputing",
                                tradingSymbol, interval, snap.getUpdatedAt());
                        return detectAndPersist(tradingSymbol, instrument, interval, true);
                    }
                    try {
                        return fromJson(snap.getPivotsJson());
                    } catch (Exception e) {
                        log.warn("Failed to parse pivots snapshot for {} {}, recomputing. Err: {}", tradingSymbol, interval, e.getMessage());
                        return detectAndPersist(tradingSymbol, instrument, interval, true);
                    }
                })
                .orElseGet(() -> detectAndPersist(tradingSymbol, instrument, interval, true));
    }

    /**
     * Invalidates the ZigZag snapshot cache for the given symbol+interval.
     * Called after a BOS or CHoCH event is detected, so the next scan uses fresh pivots.
     */
    public void invalidateCache(String tradingSymbol, Interval interval) {
        snapshotRepository.deleteByTradingSymbolAndInterval(tradingSymbol, interval);
        log.info("[ZigZag] Cache invalidated for {} {} (BOS/CHoCH triggered)", tradingSymbol, interval);
    }

    public void persistSnapshot(String tradingSymbol, Interval interval, List<ZigZagPoint> pivots) {
        try {
            String json = toJson(pivots);
            ZigZagSnapshot snapshot = snapshotRepository.findByTradingSymbolAndInterval(tradingSymbol, interval)
                    .orElseGet(() -> ZigZagSnapshot.builder()
                            .tradingSymbol(tradingSymbol)
                            .interval(interval)
                            .pivotsJson(json)
                            .updatedAt(LocalDateTime.now())
                            .build());
            snapshot.setPivotsJson(json);
            snapshot.setUpdatedAt(LocalDateTime.now());
            snapshotRepository.save(snapshot);
        } catch (Exception e) {
            log.error("Failed to persist ZigZag snapshot for {} {}", tradingSymbol, interval, e);
        }
    }

    /**
     * Returns the earliest candle timestamp to load for a given interval,
     * keeping context lean for the Elliott Wave engine and AI prompts.
     *
     * weekly  → 10 years  (~520 bars)
     * daily   → 3 years   (~750 bars)
     * 60min   → 1 year    (~1,600 bars)
     * 30min   → 6 months  (~1,600 bars)
     * 15min   → 3 months  (~1,500 bars)
     * 5min    → 1 month   (~1,500 bars)
     * 3min    → 3 weeks   (~1,500 bars)
     * 1min    → 1 week    (~1,500 bars)
     */
    private Instant candleLookbackFrom(Interval interval) {
        Instant now = Instant.now();
        return switch (interval) {
            case Week         -> now.minus(10 * 365, ChronoUnit.DAYS);
            case Day          -> now.minus(3 * 365, ChronoUnit.DAYS);
            case OneHour      -> now.minus(600,      ChronoUnit.DAYS);
            case FourHours    -> now.minus(2 * 365,  ChronoUnit.DAYS);
            case ThirtyMinute -> now.minus(180,      ChronoUnit.DAYS);
            case FifteenMinute -> now.minus(365,     ChronoUnit.DAYS);
            case FiveMinute   -> now.minus(300,      ChronoUnit.DAYS);
            case ThreeMinute  -> now.minus(21,       ChronoUnit.DAYS);
            case OneMinute    -> now.minus(7,        ChronoUnit.DAYS);
        };
    }

    /**
     * Appends (or extends) a synthetic LTP pivot to make the ZigZag analysis real-time aware.
     *
     * The last confirmed ZigZag pivot is always at least one reversal-threshold behind the
     * current market price. This method adds the current LTP as an in-progress leg endpoint:
     *
     *  - If last pivot is HIGH and LTP >= last.value  → replace last with new HIGH at LTP
     *    (up-leg is still extending)
     *  - If last pivot is HIGH and LTP < last.value   → append new LOW at LTP
     *    (down-leg has started but not yet confirmed as pivot)
     *  - If last pivot is LOW  and LTP <= last.value  → replace last with new LOW at LTP
     *    (down-leg is still extending)
     *  - If last pivot is LOW  and LTP > last.value   → append new HIGH at LTP
     *    (up-leg has started but not yet confirmed as pivot)
     *
     * Returns a new list; the original is NOT modified. The synthetic pivot is NOT persisted.
     * Pivot metrics (retracement/extension) are recomputed on the returned list.
     */
    public List<ZigZagPoint> withLtpPivot(List<ZigZagPoint> pivots, BarSeries series) {
        if (pivots == null || pivots.isEmpty() || series == null || series.isEmpty()) {
            return pivots == null ? new ArrayList<>() : new ArrayList<>(pivots);
        }

        Bar lastBar = series.getBar(series.getBarCount() - 1);
        double ltp = lastBar.getClosePrice().doubleValue();
        Instant ltpTime = lastBar.getEndTime();

        List<ZigZagPoint> result = new ArrayList<>(pivots);
        ZigZagPoint last = result.get(result.size() - 1);

        ZigZagPoint synthetic;
        if (last.isHigh()) {
            if (ltp >= last.getValue()) {
                // Up-leg still extending — replace last HIGH with new high at LTP
                synthetic = ZigZagPoint.builder()
                        .type(ZigZagPoint.Type.HIGH)
                        .timestamp(ltpTime)
                        .sequence(ltpTime.getEpochSecond())
                        .value(ltp)
                        .atrAtPivot(last.getAtrAtPivot())
                        .build();
                result.set(result.size() - 1, synthetic);
            } else {
                // Down-leg started — append new LOW at LTP
                synthetic = ZigZagPoint.builder()
                        .type(ZigZagPoint.Type.LOW)
                        .timestamp(ltpTime)
                        .sequence(ltpTime.getEpochSecond())
                        .value(ltp)
                        .atrAtPivot(last.getAtrAtPivot())
                        .build();
                result.add(synthetic);
            }
        } else { // last is LOW
            if (ltp <= last.getValue()) {
                // Down-leg still extending — replace last LOW with new low at LTP
                synthetic = ZigZagPoint.builder()
                        .type(ZigZagPoint.Type.LOW)
                        .timestamp(ltpTime)
                        .sequence(ltpTime.getEpochSecond())
                        .value(ltp)
                        .atrAtPivot(last.getAtrAtPivot())
                        .build();
                result.set(result.size() - 1, synthetic);
            } else {
                // Up-leg started — append new HIGH at LTP
                synthetic = ZigZagPoint.builder()
                        .type(ZigZagPoint.Type.HIGH)
                        .timestamp(ltpTime)
                        .sequence(ltpTime.getEpochSecond())
                        .value(ltp)
                        .atrAtPivot(last.getAtrAtPivot())
                        .build();
                result.add(synthetic);
            }
        }

        // Recompute retracement/extension metrics with the synthetic pivot included
        computePivotMetrics(result);
        log.debug("[ZigZag] withLtpPivot: ltp={} last={}@{} → result has {} pivots (last {}@{})",
                ltp, last.getType(), last.getValue(),
                result.size(), result.get(result.size() - 1).getType(), result.get(result.size() - 1).getValue());
        return result;
    }

    private String toJson(List<ZigZagPoint> pivots) {
        String items = pivots.stream().map(p -> {
            String retr = p.getRetracementPct() == null ? "null" : String.format(Locale.US, "%.6f", p.getRetracementPct());
            String ext  = p.getExtensionPct()   == null ? "null" : String.format(Locale.US, "%.6f", p.getExtensionPct());
            return String.format(Locale.US,
                    "{\"type\":\"%s\",\"ts\":\"%s\",\"seq\":%d,\"value\":%.8f,\"atr\":%.8f,\"retr\":%s,\"ext\":%s}",
                    p.getType().name(), p.getTimestamp(), p.getSequence(), p.getValue(), p.getAtrAtPivot(), retr, ext);
        }).collect(Collectors.joining(","));
        return "[" + items + "]";
    }

    private List<ZigZagPoint> fromJson(String json) {
        // Minimal parser to avoid extra deps
        List<ZigZagPoint> list = new ArrayList<>();
        if (json == null || json.isBlank()) return list;
        String trimmed = json.trim();
        if (!trimmed.startsWith("[") || !trimmed.endsWith("]")) return list;
        String body = trimmed.substring(1, trimmed.length() - 1);
        if (body.isBlank()) return list;
        String[] items = body.split("\\},\\s*\\{");
        for (int i = 0; i < items.length; i++) {
            String s = items[i].trim();
            if (!s.startsWith("{")) s = "{" + s;
            if (!s.endsWith("}")) s = s + "}";
            try {
                // Very simple parsing by tokens
                String[] fields = s.substring(1, s.length() - 1).split(",");
                ZigZagPoint.Type type = null;
                Instant ts = null;
                Long seq = null;
                Double value = null;
                Double atr = null;
                Double retr = null;
                Double ext = null;
                for (String f : fields) {
                    String[] kv = f.split(":", 2);
                    if (kv.length != 2) continue;
                    String key = kv[0].replaceAll("[\"\\s]", "");
                    String raw = kv[1].trim();
                    String val = raw.replaceAll("^\"|\"$", "");
                    switch (key) {
                        case "type" -> type = ZigZagPoint.Type.valueOf(val);
                        case "ts" -> ts = Instant.parse(val);
                        case "seq" -> seq = Long.parseLong(val);
                        case "value" -> value = Double.parseDouble(val);
                        case "atr" -> atr = Double.parseDouble(val);
                        case "retr" -> { if (!"null".equals(val)) retr = Double.parseDouble(val); }
                        case "ext" -> { if (!"null".equals(val)) ext = Double.parseDouble(val); }
                        default -> {}
                    }
                }
                if (type != null && ts != null && seq != null && value != null) {
                    list.add(ZigZagPoint.builder()
                            .type(type)
                            .timestamp(ts)
                            .sequence(seq)
                            .value(value)
                            .atrAtPivot(atr == null ? 0.0 : atr)
                            .retracementPct(retr)
                            .extensionPct(ext)
                            .build());
                }
            } catch (Exception ignore) {}
        }
        return list;
    }
}
