package com.dtech.kitecon.analysis.levels;

import org.ta4j.core.Bar;
import org.ta4j.core.BarSeries;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

public class SupportResistanceLevelStudy {
    private static final double PROXIMITY_PCT = 10.0;
    private static final double CLUSTER_PCT = 0.5;
    private static final int DEFAULT_MAX_LEVELS = 3;

    public List<Level> computeLevels(
        BarSeries dailyBars,
        BarSeries weeklyBars,
        double currentPrice,
        Instant asOfTime,
        int maxLevels
    ) {
        List<Level> candidates = new ArrayList<>();

        if (dailyBars == null || dailyBars.getBarCount() == 0) {
            return candidates;
        }

        // 1. Daily pivots
        addDailyPivots(candidates, dailyBars, asOfTime);

        // 2. Weekly pivots (if available)
        if (weeklyBars != null && weeklyBars.getBarCount() > 0) {
            addWeeklyPivots(candidates, weeklyBars, asOfTime);
        }

        // 3. Gaps
        addGaps(candidates, dailyBars, asOfTime);

        // 4. Big-volume trend pivots
        addBigVolumePivots(candidates, dailyBars, asOfTime);

        // 5. Big-move trend pivots
        addBigMovePivots(candidates, dailyBars, asOfTime);

        // Proximity filter
        candidates = candidates.stream()
            .filter(l -> {
                double pctDiff = Math.abs(l.price() - currentPrice) / currentPrice * 100;
                return pctDiff <= PROXIMITY_PCT;
            })
            .collect(Collectors.toList());

        // Clustering
        candidates = cluster(candidates);

        // Sort by score descending and take top maxLevels
        return candidates.stream()
            .sorted(Comparator.comparingDouble(Level::score).reversed())
            .limit(maxLevels)
            .collect(Collectors.toList());
    }

    // Daily pivots: high[i] >= high[i-5..i+5], low[i] <= low[i-5..i+5]
    private void addDailyPivots(List<Level> candidates, BarSeries bars, Instant asOfTime) {
        int endIdx = getBarIndexAsOfTime(bars, asOfTime);
        if (endIdx < 5) return;

        for (int i = 5; i < endIdx; i++) {
            Bar bar = bars.getBar(i);
            double high = bar.getHighPrice().doubleValue();
            double low = bar.getLowPrice().doubleValue();
            Instant barTime = bar.getEndTime();

            // High pivot
            boolean highPivot = true;
            for (int j = i - 5; j <= i + 5; j++) {
                if (j != i && bars.getBar(j).getHighPrice().doubleValue() > high) {
                    highPivot = false;
                    break;
                }
            }
            if (highPivot) {
                double score = scorePivot(bars, i, high, endIdx, 2.0);
                candidates.add(new Level(high, LevelType.PIVOT_DAILY, score, barTime, "Daily pivot high"));
            }

            // Low pivot
            boolean lowPivot = true;
            for (int j = i - 5; j <= i + 5; j++) {
                if (j != i && bars.getBar(j).getLowPrice().doubleValue() < low) {
                    lowPivot = false;
                    break;
                }
            }
            if (lowPivot) {
                double score = scorePivot(bars, i, low, endIdx, 2.0);
                candidates.add(new Level(low, LevelType.PIVOT_DAILY, score, barTime, "Daily pivot low"));
            }
        }
    }

    // Weekly pivots using L3/R3 windows (±3 weeks)
    private void addWeeklyPivots(List<Level> candidates, BarSeries bars, Instant asOfTime) {
        int endIdx = getBarIndexAsOfTime(bars, asOfTime);
        if (endIdx < 3) return;

        for (int i = 3; i < endIdx; i++) {
            Bar bar = bars.getBar(i);
            double high = bar.getHighPrice().doubleValue();
            double low = bar.getLowPrice().doubleValue();
            Instant barTime = bar.getEndTime();

            // High pivot
            boolean highPivot = true;
            for (int j = i - 3; j <= i + 3; j++) {
                if (j != i && j >= 0 && j < bars.getBarCount() &&
                    bars.getBar(j).getHighPrice().doubleValue() > high) {
                    highPivot = false;
                    break;
                }
            }
            if (highPivot) {
                double score = scorePivot(bars, i, high, endIdx, 2.5);
                candidates.add(new Level(high, LevelType.PIVOT_WEEKLY, score, barTime, "Weekly pivot high"));
            }

            // Low pivot
            boolean lowPivot = true;
            for (int j = i - 3; j <= i + 3; j++) {
                if (j != i && j >= 0 && j < bars.getBarCount() &&
                    bars.getBar(j).getLowPrice().doubleValue() < low) {
                    lowPivot = false;
                    break;
                }
            }
            if (lowPivot) {
                double score = scorePivot(bars, i, low, endIdx, 2.5);
                candidates.add(new Level(low, LevelType.PIVOT_WEEKLY, score, barTime, "Weekly pivot low"));
            }
        }
    }

    private double scorePivot(BarSeries bars, int pivotIdx, double pivotPrice, int endIdx, double weight) {
        // Count touches: subsequent bars with low or high within 0.5% of pivotPrice
        int touches = 0;
        for (int j = pivotIdx + 1; j <= endIdx; j++) {
            Bar bar = bars.getBar(j);
            double low = bar.getLowPrice().doubleValue();
            double high = bar.getHighPrice().doubleValue();
            if (Math.abs(low - pivotPrice) / pivotPrice * 100 <= 0.5 ||
                Math.abs(high - pivotPrice) / pivotPrice * 100 <= 0.5) {
                touches++;
            }
        }

        // Age decay
        long ageMs = getAgeMs(bars, pivotIdx, endIdx);
        double ageInDays = ageMs / (24.0 * 3600 * 1000);
        double decay = Math.max(0.3, 1.0 - ageInDays / 365.0);

        return weight * (1.0 + touches * 0.5) * decay;
    }

    // Gaps: gap-up = low[i] > high[i-1]; gap-down = high[i] < low[i-1]
    private void addGaps(List<Level> candidates, BarSeries bars, Instant asOfTime) {
        int endIdx = getBarIndexAsOfTime(bars, asOfTime);
        if (endIdx < 1) return;

        for (int i = 1; i <= endIdx; i++) {
            Bar bar = bars.getBar(i);
            Bar prevBar = bars.getBar(i - 1);
            double low = bar.getLowPrice().doubleValue();
            double high = bar.getHighPrice().doubleValue();
            double prevHigh = prevBar.getHighPrice().doubleValue();
            double prevLow = prevBar.getLowPrice().doubleValue();
            Instant barTime = bar.getEndTime();

            // Gap-up: use prevHigh as resistance
            if (low > prevHigh) {
                double gapSizePct = (low - prevHigh) / prevHigh * 100;
                if (!isGapClosed(bars, i, prevHigh, endIdx, true)) {
                    long ageMs = getAgeMs(bars, i - 1, endIdx);
                    double ageInDays = ageMs / (24.0 * 3600 * 1000);
                    double decay = Math.max(0.3, 1.0 - ageInDays / 365.0);
                    double score = 3.0 * gapSizePct * decay;
                    Instant prevBarTime = prevBar.getEndTime();
                    candidates.add(new Level(prevHigh, LevelType.GAP, score, prevBarTime,
                        String.format("Gap-up resistance (%.2f%%)", gapSizePct)));
                }
            }

            // Gap-down: use prevLow as support
            if (high < prevLow) {
                double gapSizePct = (prevLow - high) / prevLow * 100;
                if (!isGapClosed(bars, i, prevLow, endIdx, false)) {
                    long ageMs = getAgeMs(bars, i - 1, endIdx);
                    double ageInDays = ageMs / (24.0 * 3600 * 1000);
                    double decay = Math.max(0.3, 1.0 - ageInDays / 365.0);
                    double score = 3.0 * gapSizePct * decay;
                    Instant prevBarTime = prevBar.getEndTime();
                    candidates.add(new Level(prevLow, LevelType.GAP, score, prevBarTime,
                        String.format("Gap-down support (%.2f%%)", gapSizePct)));
                }
            }
        }
    }

    private boolean isGapClosed(BarSeries bars, int gapBarIdx, double gapPrice, int endIdx, boolean isGapUp) {
        for (int j = gapBarIdx + 1; j <= endIdx; j++) {
            Bar bar = bars.getBar(j);
            double low = bar.getLowPrice().doubleValue();
            double high = bar.getHighPrice().doubleValue();
            if (isGapUp && low <= gapPrice) return true;  // gap-up closed if bar low <= prevHigh
            if (!isGapUp && high >= gapPrice) return true;  // gap-down closed if bar high >= prevLow
        }
        return false;
    }

    // Big-volume trend-pivot candle
    private void addBigVolumePivots(List<Level> candidates, BarSeries bars, Instant asOfTime) {
        int endIdx = getBarIndexAsOfTime(bars, asOfTime);
        if (endIdx < 25) return;  // need 20-bar SMA + pivot check

        // Compute 20-bar SMA of volume
        double[] avgVol = new double[endIdx + 1];
        for (int i = 0; i <= endIdx; i++) {
            double sum = 0;
            int count = Math.min(20, i + 1);
            for (int j = Math.max(0, i - 19); j <= i; j++) {
                sum += bars.getBar(j).getVolume().doubleValue();
            }
            avgVol[i] = sum / count;
        }

        for (int i = 5; i < endIdx - 5; i++) {
            Bar bar = bars.getBar(i);
            double volume = bar.getVolume().doubleValue();

            if (volume >= 3 * avgVol[i]) {
                // Check trend pivot
                double preTrend = Math.signum(
                    bars.getBar(i - 1).getClosePrice().doubleValue() -
                    bars.getBar(i - 5).getClosePrice().doubleValue()
                );
                double postTrend = Math.signum(
                    bars.getBar(i + 5).getClosePrice().doubleValue() -
                    bars.getBar(i + 1).getClosePrice().doubleValue()
                );

                Instant barTime = bar.getEndTime();
                double levelPrice;
                String desc;

                if (preTrend < 0 && postTrend > 0) {
                    // Bull pivot: use bar low
                    levelPrice = bar.getLowPrice().doubleValue();
                    desc = "Big-volume bull pivot (low)";
                } else if (preTrend > 0 && postTrend < 0) {
                    // Bear pivot: use bar high
                    levelPrice = bar.getHighPrice().doubleValue();
                    desc = "Big-volume bear pivot (high)";
                } else {
                    continue;  // not a pivot
                }

                long ageMs = getAgeMs(bars, i, endIdx);
                double ageInDays = ageMs / (24.0 * 3600 * 1000);
                double decay = Math.max(0.3, 1.0 - ageInDays / 365.0);
                double volumeMultiplier = volume / avgVol[i];
                double score = 3.0 * volumeMultiplier * decay;

                candidates.add(new Level(levelPrice, LevelType.BIG_VOLUME_PIVOT, score, barTime, desc));
            }
        }
    }

    // Big-move trend-pivot candle
    private void addBigMovePivots(List<Level> candidates, BarSeries bars, Instant asOfTime) {
        int endIdx = getBarIndexAsOfTime(bars, asOfTime);
        if (endIdx < 6) return;

        for (int i = 5; i < endIdx - 5; i++) {
            Bar bar = bars.getBar(i);
            double move = Math.abs(
                bar.getClosePrice().doubleValue() - bars.getBar(i - 1).getClosePrice().doubleValue()
            ) / bars.getBar(i - 1).getClosePrice().doubleValue() * 100;

            if (move >= 4.0) {
                double preTrend = Math.signum(
                    bars.getBar(i - 1).getClosePrice().doubleValue() -
                    bars.getBar(i - 5).getClosePrice().doubleValue()
                );
                double postTrend = Math.signum(
                    bars.getBar(i + 5).getClosePrice().doubleValue() -
                    bars.getBar(i + 1).getClosePrice().doubleValue()
                );

                Instant barTime = bar.getEndTime();
                double levelPrice;
                String desc;

                if (preTrend < 0 && postTrend > 0) {
                    levelPrice = bar.getLowPrice().doubleValue();
                    desc = "Big-move bull pivot (low)";
                } else if (preTrend > 0 && postTrend < 0) {
                    levelPrice = bar.getHighPrice().doubleValue();
                    desc = "Big-move bear pivot (high)";
                } else {
                    continue;
                }

                long ageMs = getAgeMs(bars, i, endIdx);
                double ageInDays = ageMs / (24.0 * 3600 * 1000);
                double decay = Math.max(0.3, 1.0 - ageInDays / 365.0);
                double score = 3.0 * move * decay;

                candidates.add(new Level(levelPrice, LevelType.BIG_MOVE_PIVOT, score, barTime, desc));
            }
        }
    }

    private int getBarIndexAsOfTime(BarSeries bars, Instant asOfTime) {
        int idx = bars.getBarCount() - 1;
        for (int i = 0; i < bars.getBarCount(); i++) {
            if (bars.getBar(i).getEndTime().isAfter(asOfTime)) {
                return i - 1;
            }
        }
        return idx;
    }

    private long getAgeMs(BarSeries bars, int barIdx, int endIdx) {
        long barTime = bars.getBar(barIdx).getEndTime().toEpochMilli();
        long endTime = bars.getBar(endIdx).getEndTime().toEpochMilli();
        return endTime - barTime;
    }

    // Clustering: merge consecutive levels within CLUSTER_PCT (0.5%)
    private List<Level> cluster(List<Level> levels) {
        if (levels.isEmpty()) return levels;

        List<Level> sorted = levels.stream()
            .sorted(Comparator.comparingDouble(Level::price))
            .collect(Collectors.toList());

        List<Level> clusters = new ArrayList<>();
        double clusterPrice = sorted.get(0).price();
        double clusterScore = sorted.get(0).score();
        LevelType clusterType = sorted.get(0).type();
        Instant clusterTime = sorted.get(0).createdAt();

        for (int i = 1; i < sorted.size(); i++) {
            Level current = sorted.get(i);
            double pctDiff = Math.abs(current.price() - clusterPrice) / clusterPrice * 100;

            if (pctDiff <= CLUSTER_PCT) {
                // Merge: keep highest score type, sum scores
                if (current.score() > clusterScore) {
                    clusterType = current.type();
                    clusterScore = current.score();
                }
                clusterScore += current.score();
                // Keep the earlier time
                if (current.createdAt().isBefore(clusterTime)) {
                    clusterTime = current.createdAt();
                }
            } else {
                // Flush cluster
                clusters.add(new Level(clusterPrice, clusterType, clusterScore, clusterTime, "Clustered"));
                clusterPrice = current.price();
                clusterScore = current.score();
                clusterType = current.type();
                clusterTime = current.createdAt();
            }
        }
        clusters.add(new Level(clusterPrice, clusterType, clusterScore, clusterTime, "Clustered"));

        return clusters;
    }
}
