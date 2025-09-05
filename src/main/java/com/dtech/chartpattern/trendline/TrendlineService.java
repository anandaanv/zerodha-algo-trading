package com.dtech.chartpattern.trendline;

import com.dtech.algo.series.Interval;
import com.dtech.chartpattern.config.ChartPatternProperties;
import com.dtech.chartpattern.persistence.TrendlineRecord;
import com.dtech.chartpattern.persistence.TrendlineRepository;
import com.dtech.chartpattern.zigzag.ZigZagPoint;
import com.dtech.chartpattern.zigzag.ZigZagService;
import com.dtech.kitecon.data.Instrument;
import com.dtech.kitecon.repository.CandleRepository;
import com.dtech.kitecon.repository.InstrumentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBarSeries;
import org.ta4j.core.indicators.EMAIndicator;
import org.ta4j.core.indicators.SMAIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.indicators.statistics.StandardDeviationIndicator;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class TrendlineService {

    private final TrendlineRepository repository;
    private final ZigZagService zigZagService;
    private final InstrumentRepository instrumentRepository;
    private final CandleRepository candleRepository;
    private final ChartPatternProperties props;

    @Transactional
    public List<TrendlineRecord> recalc(String tradingSymbol, Interval timeframe) {
        Instrument instrument = instrumentRepository.findByTradingsymbolAndExchangeIn(tradingSymbol, new String[]{"NSE"});
        if (instrument == null) {
            throw new IllegalArgumentException("Instrument not found for " + tradingSymbol);
        }

        // Ensure ZigZag pivots
        List<ZigZagPoint> pivots = zigZagService.getOrComputePivots(tradingSymbol, instrument, timeframe);
        if (pivots.isEmpty()) {
            repository.deleteByTradingSymbolAndTimeframe(tradingSymbol, timeframe);
            return Collections.emptyList();
        }

        // Build BarSeries for indicators (EMA/BB)
        BarSeries series = buildSeries(instrument, timeframe);

        // Compute trendlines from EMA/BB-qualified anchors
        List<TrendlineRecord> lines = new ArrayList<>();
        lines.addAll(computeSide(tradingSymbol, timeframe, pivots, TrendlineSide.SUPPORT, series));
        lines.addAll(computeSide(tradingSymbol, timeframe, pivots, TrendlineSide.RESISTANCE, series));

        // Keep top-k per side by score
        int k = Math.max(1, props.getTrendline().getMaxLinesPerSide());
        Map<TrendlineSide, List<TrendlineRecord>> topBySide = lines.stream()
                .collect(Collectors.groupingBy(TrendlineRecord::getSide));
        List<TrendlineRecord> finalLines = new ArrayList<>();
        for (Map.Entry<TrendlineSide, List<TrendlineRecord>> e : topBySide.entrySet()) {
            finalLines.addAll(e.getValue().stream()
                    .sorted(Comparator.comparingDouble(TrendlineRecord::getScore).reversed())
                    .limit(k)
                    .toList());
        }

        // Recalculate isFlat and buckets (ensure filled)
        finalLines.forEach(this::fillBucketsAndFlags);

        // Replace existing
        repository.deleteByTradingSymbolAndTimeframe(tradingSymbol, timeframe);
        return repository.saveAll(finalLines);
    }

    @Transactional(readOnly = true)
    public List<TrendlineRecord> getExisting(String tradingSymbol, Interval timeframe) {
        List<TrendlineRecord> rows = repository.findByTradingSymbolAndTimeframe(tradingSymbol, timeframe);
        if (rows == null) return Collections.emptyList();
        return rows.stream()
                .sorted(Comparator.comparingDouble(TrendlineRecord::getScore).reversed())
                .toList();
    }

    @Transactional
    public List<TrendlineRecord> getOrRecalc(String tradingSymbol, Interval timeframe) {
        List<TrendlineRecord> rows = getExisting(tradingSymbol, timeframe);
        if (rows.isEmpty()) {
            return recalc(tradingSymbol, timeframe);
        }
        return rows;
    }

    private List<TrendlineRecord> computeSide(String symbol, Interval tf, List<ZigZagPoint> allPivots, TrendlineSide side, BarSeries series) {
        var cfg = props.getTrendline();

        // Build arrays for indicators
        ClosePriceIndicator close = new ClosePriceIndicator(series);
        EMAIndicator ema20 = new EMAIndicator(close, 20);
        EMAIndicator ema50 = new EMAIndicator(close, 50);
        EMAIndicator ema100 = new EMAIndicator(close, 100);
        EMAIndicator ema200 = new EMAIndicator(close, 200);

        SMAIndicator sma100 = new SMAIndicator(close, 100);
        SMAIndicator sma200 = new SMAIndicator(close, 200);
        StandardDeviationIndicator sd100 = new StandardDeviationIndicator(close, 100);
        StandardDeviationIndicator sd200 = new StandardDeviationIndicator(close, 200);
        double k = cfg.getBbStdDev();

        // Filter pivots for this side
        List<ZigZagPoint> pivots = allPivots.stream()
                .filter(p -> side == TrendlineSide.SUPPORT ? p.isLow() : p.isHigh())
                .sorted(Comparator.comparingLong(ZigZagPoint::getSequence))
                .toList();

        if (pivots.size() < 2) return Collections.emptyList();

        int recent = Math.min(cfg.getRecentPivotCount(), pivots.size());
        List<ZigZagPoint> recentPivots = pivots.subList(pivots.size() - recent, pivots.size());
        long maxSeq = allPivots.stream().mapToLong(ZigZagPoint::getSequence).max().orElse(recentPivots.getLast().getSequence());

        // Build qualified anchors (EMA/BB conformity). Also include last two pivots if enabled.
        double emaPct = cfg.getEmaProximityPct();

        record Anchor(ZigZagPoint p, int tier, String tag) {}
        List<Anchor> anchors = new ArrayList<>();

        for (ZigZagPoint p : recentPivots) {
            int i = (int) p.getSequence();
            double price = p.getValue();
            double tolEma = Math.max(emaPct * price, cfg.getAtrBandMult() * p.getAtrAtPivot());
            double ubb100 = sma100.getValue(i).doubleValue() + k * sd100.getValue(i).doubleValue();
            double lbb100 = sma100.getValue(i).doubleValue() - k * sd100.getValue(i).doubleValue();
            double ubb200 = sma200.getValue(i).doubleValue() + k * sd200.getValue(i).doubleValue();
            double lbb200 = sma200.getValue(i).doubleValue() - k * sd200.getValue(i).doubleValue();

            if (side == TrendlineSide.SUPPORT) {
                boolean near200 = Math.abs(price - ema200.getValue(i).doubleValue()) <= tolEma || price <= lbb200;
                boolean near100 = Math.abs(price - ema100.getValue(i).doubleValue()) <= tolEma || price <= lbb100;
                boolean near50  = Math.abs(price - ema50.getValue(i).doubleValue())  <= tolEma;
                if (near200) anchors.add(new Anchor(p, 200, "EMA200/BB200"));
                else if (near100) anchors.add(new Anchor(p, 100, "EMA100/BB100"));
                else if (near50) anchors.add(new Anchor(p, 50, "EMA50"));
            } else {
                // RESISTANCE: require breach of upper BB100/200; BB200 = stronger anchor
                boolean breach200 = price >= ubb200;
                boolean breach100 = price >= ubb100;
                if (breach200) anchors.add(new Anchor(p, 200, "BB200"));
                else if (breach100) anchors.add(new Anchor(p, 100, "BB100"));
            }
        }

        if (cfg.isAllowTwoLatestPivots() && recentPivots.size() >= 2) {
            // Add the two latest same-side pivots with the lowest tier (50) to guarantee recency
            anchors.add(new Anchor(recentPivots.get(recentPivots.size() - 2), 50, "LATEST"));
            anchors.add(new Anchor(recentPivots.get(recentPivots.size() - 1), 50, "LATEST"));
        }

        if (anchors.size() < 2) return Collections.emptyList();

        // Form candidate pairs honoring tiers preference (200->200, 200->100/50, 100->100/50, 50->50)
        anchors.sort(Comparator.comparingLong(a -> a.p.getSequence()));
        List<TrendlineRecord> candidates = new ArrayList<>();

        for (int a = 0; a < anchors.size() - 1; a++) {
            for (int b = a + 1; b < anchors.size(); b++) {
                Anchor A = anchors.get(a);
                Anchor B = anchors.get(b);

                long dx = B.p.getSequence() - A.p.getSequence();
                if (dx < cfg.getMinGapBars()) continue;

                // Require at least one 20EMA crossover between the two pivots
                if (!hasEma20Crossover(ema20, close, (int) A.p.getSequence(), (int) B.p.getSequence())) {
                    continue;
                }

                double m = (B.p.getValue() - A.p.getValue()) / dx;
                double intercept = A.p.getValue() - m * A.p.getSequence();

                // For resistance falling lines, prefer starting anchor outside BB100/200 (already enforced by anchors)
                // Evaluate
                EvalResult ev = evaluateLine(allPivots, side, m, intercept, A.p.getSequence(), maxSeq, cfg.getPctBand(), cfg.getAtrBandMult(), true);

                // Filters
                //if (ev.proximityNowPct > cfg.getMaxProximityNowPct()) continue;
//                if (ev.containmentWindow < cfg.getCoverageMin()) continue;
//                if (ev.breachRatioWindow > cfg.getBreachMax()) continue;

                double angleDeg = Math.toDegrees(Math.atan(m));
                boolean flat = Math.abs(angleDeg) <= cfg.getFlatAngleDeg();
                double score = score(ev, maxSeq - A.p.getSequence(), A.p.getSequence(), maxSeq);

                TrendlineRecord rec = TrendlineRecord.builder()
                        .tradingSymbol(symbol)
                        .timeframe(tf)
                        .side(side)
                        .slopePerBar(m)
                        .intercept(intercept)
                        .startSeq(A.p.getSequence())
                        .endSeq(B.p.getSequence())
                        .touches(ev.touches)
                        .spanBars((int) (maxSeq - A.p.getSequence()))
                        .residual(ev.residual)
                        .breachRatio(ev.breachRatioTotal)
                        .containmentWindow(ev.containmentWindow)
                        .containmentTotal(ev.containmentTotal)
                        .lastTouchTs(ev.lastTouchTs)
                        .score(score)
                        .state(ev.state)
                        .brokenAtTs(ev.brokenAtTs)
                        .retestTs(ev.retestTs)
                        .proximityNowPct(ev.proximityNowPct)
                        .angleDeg(angleDeg)
                        .isFlat(flat)
                        .updatedAt(LocalDateTime.now())
                        .build();

                candidates.add(rec);
            }
        }

        // Ranking and suppression as before
        candidates.sort(Comparator.comparingDouble(TrendlineRecord::getScore).reversed());
        List<TrendlineRecord> kept = new ArrayList<>();
        for (TrendlineRecord c : candidates) {
            boolean similar = kept.stream().anyMatch(kv ->
                    Math.abs(kv.getSlopePerBar() - c.getSlopePerBar()) <= cfg.getSlopeSimilarityEps()
                            && Math.abs(kv.getStartSeq() - c.getStartSeq()) <= 50);
            if (!similar) kept.add(c);
            if (kept.size() >= cfg.getMaxLinesPerSide()) break;
        }
        // Keep closest active if missing
        candidates.stream()
                .filter(r -> r.getState() != TrendlineState.BROKEN)
                .min(Comparator.comparingDouble(TrendlineRecord::getProximityNowPct))
                .ifPresent(ca -> {
                    boolean present = kept.stream().anyMatch(kv ->
                            Math.abs(kv.getSlopePerBar() - ca.getSlopePerBar()) <= cfg.getSlopeSimilarityEps()
                                    && Math.abs(kv.getStartSeq() - ca.getStartSeq()) <= 50);
                    if (!present) {
                        kept.add(0, ca);
                        if (kept.size() > cfg.getMaxLinesPerSide()) kept.remove(kept.size() - 1);
                    }
                });

        return kept;
    }

    // Build lower (support) or upper (resistance) hull over recent pivots using monotone chain.
    private List<Integer> buildHullIndices(List<ZigZagPoint> pts, TrendlineSide side) {
        if (pts.size() < 2) return Collections.emptyList();
        List<Integer> idx = new ArrayList<>();
        for (int i = 0; i < pts.size(); i++) idx.add(i);
        // Sort by x (sequence)
        idx.sort(Comparator.comparingLong(i -> pts.get(i).getSequence()));
        List<Integer> hull = new ArrayList<>();
        for (int i : idx) {
            while (hull.size() >= 2) {
                int j = hull.get(hull.size() - 1);
                int k = hull.get(hull.size() - 2);
                double x1 = pts.get(k).getSequence(), y1 = pts.get(k).getValue();
                double x2 = pts.get(j).getSequence(), y2 = pts.get(j).getValue();
                double x3 = pts.get(i).getSequence(), y3 = pts.get(i).getValue();
                double cross = (x2 - x1) * (y3 - y1) - (y2 - y1) * (x3 - x1);
                boolean pop = (side == TrendlineSide.SUPPORT) ? (cross > 0) : (cross < 0);
                if (pop) hull.remove(hull.size() - 1); else break;
            }
            hull.add(i);
        }
        return hull;
    }

    private BarSeries buildSeries(Instrument instrument, Interval tf) {
        List<com.dtech.kitecon.data.Candle> candles = candleRepository.findAllByInstrumentAndTimeframe(instrument, tf);
        candles.sort(Comparator.comparing(com.dtech.kitecon.data.Candle::getTimestamp));
        BarSeries series = new BaseBarSeries(instrument.getTradingsymbol());
        for (var c : candles) {
            series.addBar(ZonedDateTime.of(c.getTimestamp(), ZoneId.systemDefault()),
                    c.getOpen(), c.getHigh(), c.getLow(), c.getClose(), c.getVolume() == null ? 0L : c.getVolume());
        }
        return series;
    }

    private boolean hasEma20Crossover(EMAIndicator ema20, ClosePriceIndicator close, int from, int to) {
        if (to - from < 1) return false;
        double prevDiff = close.getValue(from).doubleValue() - ema20.getValue(from).doubleValue();
        for (int i = from + 1; i <= to; i++) {
            double diff = close.getValue(i).doubleValue() - ema20.getValue(i).doubleValue();
            if ((prevDiff <= 0 && diff > 0) || (prevDiff >= 0 && diff < 0)) {
                return true;
            }
            prevDiff = diff;
        }
        return false;
    }

    private record EvalResult(
            int touches,
            double residual,
            double breachRatioTotal,
            double containmentWindow,
            double containmentTotal,
            double breachRatioWindow,
            LocalDateTime lastTouchTs,
            TrendlineState state,
            LocalDateTime brokenAtTs,
            LocalDateTime retestTs,
            double proximityNowPct
    ) {}

    private EvalResult evaluateLine(List<ZigZagPoint> allPivots, TrendlineSide side,
                                    double m, double b, long startSeq, long maxSeq,
                                    double pctBand, double atrBandMult, boolean allowAtrBreaches) {
        int windowBars = Math.max(50, props.getTrendline().getContainmentWindowBars());
        long windowStart = Math.max(startSeq, maxSeq - windowBars);

        // Metrics accumulators
        int touches = 0;
        double sumResidual = 0.0;
        LocalDateTime lastTouchTs = null;

        int totalCount = 0;
        int totalContained = 0;
        int totalWrong = 0;

        int windowCount = 0;
        int windowContained = 0;
        int windowWrong = 0;

        boolean broken = false;
        boolean retesting = false;
        LocalDateTime brokenAt = null;
        LocalDateTime retestAt = null;

        for (ZigZagPoint p : allPivots) {
            if (p.getSequence() < startSeq) continue;

            double y = m * p.getSequence() + b;
            // Base tolerance band
            double baseTol = Math.max(pctBand * p.getValue(), atrBandMult * p.getAtrAtPivot());
            // Allow breaches within 1x ATR explicitly (as requested)
            double tol = allowAtrBreaches ? Math.max(baseTol, p.getAtrAtPivot()) : baseTol;

            double dist = Math.abs(y - p.getValue());
            boolean onCorrectSide =
                    (side == TrendlineSide.SUPPORT) ? (p.getValue() >= y - tol) : (p.getValue() <= y + tol);
            boolean wrongSideBeyond = 
                    (side == TrendlineSide.SUPPORT) ? (p.getValue() < y - tol) : (p.getValue() > y + tol);
            boolean isTouch = dist <= tol && ((side == TrendlineSide.SUPPORT && p.isLow()) || (side == TrendlineSide.RESISTANCE && p.isHigh()));

            totalCount++;
            if (onCorrectSide) totalContained++;
            if (wrongSideBeyond) totalWrong++;
            if (p.getSequence() >= windowStart) {
                windowCount++;
                if (onCorrectSide) windowContained++;
                if (wrongSideBeyond) windowWrong++;
            }
            if (isTouch) {
                touches++;
                sumResidual += dist / Math.max(1e-9, p.getValue());
                lastTouchTs = p.getTimestamp();
            }

            if (!broken && wrongSideBeyond) {
                broken = true;
                brokenAt = p.getTimestamp();
            } else if (broken) {
                boolean backToBand = dist <= tol;
                if (backToBand) {
                    retesting = true;
                    retestAt = p.getTimestamp();
                }
            }
        }

        double residual = touches > 0 ? (sumResidual / touches) : 1.0;
        double containmentTotal = totalCount > 0 ? (double) totalContained / totalCount : 0.0;
        double breachRatioTotal = totalCount > 0 ? (double) totalWrong / totalCount : 0.0;
        double containmentWindow = windowCount > 0 ? (double) windowContained / windowCount : 0.0;
        double breachRatioWindow = windowCount > 0 ? (double) windowWrong / windowCount : 0.0;

        TrendlineState state = broken ? (retesting ? TrendlineState.RETESTING : TrendlineState.BROKEN) : TrendlineState.ACTIVE;

        // Proximity using last pivot
        ZigZagPoint last = allPivots.get(allPivots.size() - 1);
        double yNow = m * last.getSequence() + b;
        double proximityNowPct = Math.abs(yNow - last.getValue()) / Math.max(1e-9, last.getValue());

        return new EvalResult(touches, residual, breachRatioTotal, containmentWindow, containmentTotal,
                breachRatioWindow, lastTouchTs, state, brokenAt, retestAt, proximityNowPct);
    }

    private double score(EvalResult ev, long spanBars, long startSeq, long maxSeq) {
        // Strongly weight containment in recent window; modest weight to overall containment.
        double cw = ev.containmentWindow();
        double ct = ev.containmentTotal();
        double touchesNorm = Math.min(ev.touches(), 5) / 5.0;
        double lastTouchBonus = ev.lastTouchTs() != null ? 0.1 : 0.0;

        // Recency emphasis: 1.0 if start within recentEmphasisBars; decays to 0 beyond.
        int recentBars = Math.max(1, props.getTrendline().getRecentEmphasisBars());
        double age = Math.max(0, (double) (maxSeq - startSeq));
        double recencyFactor = Math.max(0.0, 1.0 - Math.min(1.0, age / recentBars));
        double recencyBonus = props.getTrendline().getRecencyWeight() * recencyFactor;

        // Penalize recent breaches more than historical ones; reduce penalty if it just broke after strong containment
        double recentBreachPenalty = 0.6 * ev.breachRatioWindow();
        double totalBreachPenalty = 0.2 * ev.breachRatioTotal();
        double residualPenalty = 0.2 * ev.residual();

        double statePenalty = 0.0;
        if (ev.state() == TrendlineState.BROKEN) {
            statePenalty = (cw >= 0.7) ? 0.1 : 0.3; // lenient if it was well-contained before breaking
        } else if (ev.state() == TrendlineState.RETESTING) {
            statePenalty = 0.05; // slight penalty; still actionable
        }

        // Slight preference for span, but do not dominate
        double spanBonus = 0.1 * Math.tanh(spanBars / 200.0);

        double base = 0.6 * cw + 0.2 * ct + 0.1 * touchesNorm + lastTouchBonus + spanBonus + recencyBonus;
        double penalty = recentBreachPenalty + totalBreachPenalty + residualPenalty + statePenalty;

        return Math.max(0.0, base - penalty);
    }

    private void fillBucketsAndFlags(TrendlineRecord r) {
        r.setScoreBucket((short) Math.max(0, Math.min(10, (int) Math.floor(r.getScore() * 10))));
        double prox = r.getProximityNowPct();
        short proxBucket = (short) (prox <= 0.001 ? 0 : prox <= 0.0025 ? 1 : prox <= 0.005 ? 2 : prox <= 0.01 ? 3 : 4);
        r.setProximityBucket(proxBucket);
        r.setAngleBucket((short) Math.floor(Math.abs(r.getAngleDeg()) / 5.0));
        r.setUpdatedAt(LocalDateTime.now());
    }
}
