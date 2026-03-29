package com.dtech.ta.elliott;

import org.springframework.stereotype.Service;
import org.ta4j.core.BarSeries;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Detects unfilled price gaps (gap-up and gap-down) from a BarSeries.
 *
 * Gaps act as support/resistance zones:
 *   Gap-up zone:   prior bar high → current bar low  (support on pullback)
 *   Gap-down zone: current bar high → prior bar low  (resistance on bounce)
 *
 * Only UNFILLED and PARTIALLY_FILLED gaps are returned — filled gaps are discarded.
 * Significance scoring: size + volume + timeframe + fill status + Fib proximity.
 *
 * DESIGN RULE: Indicators only increase probability — gaps are structural S/R,
 * never pattern eliminators.
 */
@Service
public class GapDetectorService {

    private static final double MIN_GAP_SIZE_PCT  = 0.003;  // 0.3% minimum to count as gap
    private static final double NEAR_PRICE_RANGE  = 0.05;   // within 5% of current price
    private static final int    VOLUME_AVG_PERIOD = 20;

    /**
     * Detect all significant unfilled gaps in the series.
     *
     * @param series    full BarSeries for this timeframe
     * @param timeframe label e.g. "1w", "1d", "4h"
     * @return list of UNFILLED/PARTIALLY_FILLED gaps, most significant first
     */
    public List<GapLevel> detectGaps(BarSeries series, String timeframe) {
        int n = series.getBarCount();
        if (n < 2) return List.of();

        double avgVolume = computeAvgVolume(series, n);
        double currentPrice = series.getBar(n - 1).getClosePrice().doubleValue();

        List<GapLevel> gaps = new ArrayList<>();

        for (int i = 1; i < n; i++) {
            double prevHigh = series.getBar(i - 1).getHighPrice().doubleValue();
            double prevLow  = series.getBar(i - 1).getLowPrice().doubleValue();
            double currLow  = series.getBar(i).getLowPrice().doubleValue();
            double currHigh = series.getBar(i).getHighPrice().doubleValue();
            double currVol  = series.getBar(i).getVolume().doubleValue();

            LocalDate date = series.getBar(i).getEndTime()
                    .atZone(ZoneId.systemDefault()).toLocalDate();

            GapLevel gap = null;

            if (currLow > prevHigh) {
                // Gap UP: price jumped above prior high
                double gapSizePct = (currLow - prevHigh) / prevHigh * 100;
                if (gapSizePct >= MIN_GAP_SIZE_PCT * 100) {
                    gap = GapLevel.builder()
                            .timeframe(timeframe)
                            .gapBottom(prevHigh)
                            .gapTop(currLow)
                            .gapMidpoint((prevHigh + currLow) / 2)
                            .direction(GapLevel.GapDirection.GAP_UP)
                            .fillStatus(GapLevel.GapFillStatus.UNFILLED)
                            .barIndex(i)
                            .date(date)
                            .gapSizePct(gapSizePct)
                            .volumeRatio(avgVolume > 0 ? currVol / avgVolume : 1.0)
                            .nearCurrentPrice(nearPrice(currentPrice, (prevHigh + currLow) / 2))
                            .build();
                }
            } else if (currHigh < prevLow) {
                // Gap DOWN: price fell below prior low
                double gapSizePct = (prevLow - currHigh) / prevLow * 100;
                if (gapSizePct >= MIN_GAP_SIZE_PCT * 100) {
                    gap = GapLevel.builder()
                            .timeframe(timeframe)
                            .gapBottom(currHigh)
                            .gapTop(prevLow)
                            .gapMidpoint((currHigh + prevLow) / 2)
                            .direction(GapLevel.GapDirection.GAP_DOWN)
                            .fillStatus(GapLevel.GapFillStatus.UNFILLED)
                            .barIndex(i)
                            .date(date)
                            .gapSizePct(gapSizePct)
                            .volumeRatio(avgVolume > 0 ? currVol / avgVolume : 1.0)
                            .nearCurrentPrice(nearPrice(currentPrice, (currHigh + prevLow) / 2))
                            .build();
                }
            }

            if (gap != null) gaps.add(gap);
        }

        // Check fill status using subsequent bars
        checkFillStatus(gaps, series);

        // Assign significance
        gaps.forEach(g -> g.setSignificance(computeSignificance(g, timeframe)));

        // Return only unfilled / partial — sorted by significance then recency
        return gaps.stream()
                .filter(g -> g.getFillStatus() != GapLevel.GapFillStatus.FILLED)
                .sorted((a, b) -> {
                    int sig = b.getSignificance().ordinal() - a.getSignificance().ordinal();
                    return sig != 0 ? sig : b.getBarIndex() - a.getBarIndex();
                })
                .collect(Collectors.toList());
    }

    // ── Fill status check ─────────────────────────────────────────────────────

    private void checkFillStatus(List<GapLevel> gaps, BarSeries series) {
        int n = series.getBarCount();
        for (GapLevel gap : gaps) {
            int start = gap.getBarIndex() + 1;
            boolean partial = false;

            for (int i = start; i < n; i++) {
                double lo = series.getBar(i).getLowPrice().doubleValue();
                double hi = series.getBar(i).getHighPrice().doubleValue();

                if (gap.getDirection() == GapLevel.GapDirection.GAP_UP) {
                    if (lo <= gap.getGapTop())    partial = true;
                    if (lo <= gap.getGapBottom()) { gap.setFillStatus(GapLevel.GapFillStatus.FILLED); break; }
                } else {
                    if (hi >= gap.getGapBottom()) partial = true;
                    if (hi >= gap.getGapTop())    { gap.setFillStatus(GapLevel.GapFillStatus.FILLED); break; }
                }
            }

            if (gap.getFillStatus() == GapLevel.GapFillStatus.UNFILLED && partial) {
                gap.setFillStatus(GapLevel.GapFillStatus.PARTIALLY_FILLED);
            }
        }
    }

    // ── Significance scoring ──────────────────────────────────────────────────

    private GapLevel.GapSignificance computeSignificance(GapLevel gap, String tf) {
        int score = 0;
        if (gap.getGapSizePct() >= 1.5)  score += 2;   // large gap
        if (gap.getVolumeRatio() >= 2.0)  score += 3;   // institutional volume
        if (tf.equals("1w") || tf.equals("1d")) score += 2;  // higher TF = more significant
        if (gap.getFillStatus() == GapLevel.GapFillStatus.UNFILLED) score += 3; // still open
        if (gap.isNearCurrentPrice())      score += 1;   // actionable now

        if (score >= 8) return GapLevel.GapSignificance.MAJOR;
        if (score >= 5) return GapLevel.GapSignificance.SIGNIFICANT;
        return GapLevel.GapSignificance.MINOR;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private double computeAvgVolume(BarSeries series, int n) {
        double total = 0;
        int from = Math.max(0, n - VOLUME_AVG_PERIOD);
        for (int i = from; i < n; i++) total += series.getBar(i).getVolume().doubleValue();
        return total / (n - from);
    }

    private boolean nearPrice(double currentPrice, double level) {
        return currentPrice > 0 && Math.abs(currentPrice - level) / currentPrice <= NEAR_PRICE_RANGE;
    }
}
