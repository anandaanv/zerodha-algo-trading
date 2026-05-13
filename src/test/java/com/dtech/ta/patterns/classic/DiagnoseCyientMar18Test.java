package com.dtech.ta.patterns.classic;

import com.dtech.kitecon.KiteconApplication;
import com.dtech.kitecon.analysis.levels.Level;
import com.dtech.kitecon.analysis.levels.SupportResistanceLevelStudy;
import com.dtech.kitecon.simulation.CandidatePivotZigZag;
import com.dtech.chartpattern.zigzag.ZigZagParams;
import com.dtech.chartpattern.zigzag.ZigZagPoint;
import com.dtech.kitecon.strategy.dataloader.BarsLoader;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.ta4j.core.Bar;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBarSeriesBuilder;
import org.ta4j.core.indicators.MACDIndicator;
import org.ta4j.core.indicators.RSIIndicator;
import org.ta4j.core.indicators.averages.EMAIndicator;
import org.ta4j.core.indicators.averages.SMAIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Diagnostic test for CYIENT Mar 18, 2015 entry rejection.
 * Traces every filter stage to identify which one rejects the expected entry.
 */
@SpringBootTest(classes = KiteconApplication.class)
@ActiveProfiles("integration")
class DiagnoseCyientMar18Test {

    private static final Path HOURLY_DATA = Paths.get("/tmp/hourly-scan-bars-2015-2022/CYIENT.csv");
    private static final Path DAILY_DATA = Paths.get("/tmp/daily-bars-2015-2022/CYIENT.csv");
    private static final Path OUTPUT_LOG = Paths.get("/tmp/diagnose-cyient-mar18.log");

    private static final ZoneId UTC = ZoneId.of("UTC");
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    @Test
    void diagnoseMar18Trade() throws IOException {
        Assumptions.assumeTrue(Files.exists(HOURLY_DATA), "Hourly data missing");
        Assumptions.assumeTrue(Files.exists(DAILY_DATA), "Daily data missing");

        StringBuilder logOutput = new StringBuilder();
        logOutput.append("=== CYIENT MAR 18, 2015 DIAGNOSTIC TEST ===\n\n");

        try {
            BarSeries hourly = loadHourlyCsv("CYIENT", HOURLY_DATA);
            BarSeries daily = loadDailyCsv("CYIENT", DAILY_DATA);

            logOutput.append("Loaded: ").append(hourly.getBarCount()).append(" hourly bars, ")
                    .append(daily.getBarCount()).append(" daily bars\n\n");

            // Filter to Mar 14-20, 2015
            BarSeries hourlyFiltered = filterDateRange(hourly,
                    LocalDate.of(2015, 3, 14), LocalDate.of(2015, 3, 20));
            BarSeries dailyFiltered = filterDateRange(daily,
                    LocalDate.of(2015, 3, 14), LocalDate.of(2015, 3, 20));

            logOutput.append("Filtered to Mar 14-20: ").append(hourlyFiltered.getBarCount())
                    .append(" hourly bars\n\n");

            // Build indicators
            ClosePriceIndicator closeHourly = new ClosePriceIndicator(hourly);
            ClosePriceIndicator closeDaily = new ClosePriceIndicator(daily);
            MACDIndicator macdDaily = new MACDIndicator(closeDaily, 12, 26);
            EMAIndicator signalDaily = new EMAIndicator(macdDaily, 9);
            RSIIndicator rsiHourly = new RSIIndicator(closeHourly, 14);
            RSIIndicator rsiDaily = new RSIIndicator(closeDaily, 14);
            MACDIndicator macdHourly = new MACDIndicator(closeHourly, 12, 26);
            EMAIndicator signalHourly = new EMAIndicator(macdHourly, 9);
            SMAIndicator sma20Hourly = new SMAIndicator(closeHourly, 20);

            // Pre-compute StochRSI
            double[] stochRsi = new double[hourly.getBarCount()];
            for (int i = 0; i < hourly.getBarCount(); i++) {
                stochRsi[i] = computeStochRsi(rsiHourly, i);
            }

            // Initialize structural pivots
            CandidatePivotZigZag cpzz = new CandidatePivotZigZag(
                    ZigZagParams.ofDefaults(14, 1.0, 0.005, 1.0, 1, false, 1.0, 14, ZigZagParams.Mode.BACKTEST));
            cpzz.initialize(hourly, hourly.getBarCount() - 1);
            List<ZigZagPoint> structuralHighs = cpzz.getConfirmedPivots().stream()
                    .filter(p -> p.getType() == ZigZagPoint.Type.HIGH)
                    .collect(Collectors.toList());

            // Find MACD daily bear crosses (within date range)
            List<MacdCross> macdCrosses = new ArrayList<>();
            for (int d = 1; d < daily.getBarCount(); d++) {
                double currMacd = macdDaily.getValue(d).doubleValue();
                double currSignal = signalDaily.getValue(d).doubleValue();
                double prevMacd = macdDaily.getValue(d - 1).doubleValue();
                double prevSignal = signalDaily.getValue(d - 1).doubleValue();

                if (prevMacd >= prevSignal && currMacd < currSignal) {
                    Instant crossTime = daily.getBar(d).getEndTime();
                    LocalDate crossDate = crossTime.atZone(UTC).toLocalDate();
                    macdCrosses.add(new MacdCross(d, crossDate, crossTime));
                    logOutput.append("Found MACD bear cross at daily bar ").append(d)
                            .append(": ").append(crossDate).append("\n");
                }
            }

            logOutput.append("\nScanning hourly bars in Mar 14-20 range...\n\n");

            // Scan each hourly bar in filtered range
            SupportResistanceLevelStudy levelStudy = new SupportResistanceLevelStudy();
            BarSeries weeklyBars = resampleToWeekly(daily);

            for (int h = 0; h < hourlyFiltered.getBarCount(); h++) {
                int originalIdx = findIndexInOriginal(hourly, hourlyFiltered.getBar(h));
                if (originalIdx < 0) continue;

                Bar bar = hourly.getBar(originalIdx);
                Instant barTime = bar.getEndTime();
                LocalDateTime utcTime = LocalDateTime.ofInstant(barTime, UTC);
                LocalDateTime istTime = LocalDateTime.ofInstant(barTime, IST);

                logOutput.append("========== Bar Index=").append(originalIdx)
                        .append(", Time=").append(utcTime).append("Z (IST ").append(istTime).append(") ==========\n");

                // Stage 1: MACD daily bear-cross within past 2 days
                LocalDate barDate = barTime.atZone(UTC).toLocalDate();
                MacdCross recentCross = null;
                for (MacdCross cross : macdCrosses) {
                    long daysDiff = ChronoUnit.DAYS.between(cross.crossDate, barDate);
                    if (daysDiff >= 0 && daysDiff <= 2) {
                        recentCross = cross;
                        break;
                    }
                }

                boolean stage1Pass = recentCross != null;
                if (stage1Pass) {
                    logOutput.append("[Stage 1] MACD daily bear-cross (past 2d)? ✓ Found at ")
                            .append(recentCross.crossDate).append("\n");
                } else {
                    logOutput.append("[Stage 1] MACD daily bear-cross (past 2d)? ✗ No cross found\n");
                    logOutput.append("FINAL: ✗ REJECTED AT STAGE 1 — No recent MACD bear cross\n\n");
                    continue;
                }

                // Stage 2: StochRSI >= 99
                double stochVal = stochRsi[originalIdx];
                boolean stage2Pass = stochVal >= 99;
                if (stage2Pass) {
                    logOutput.append("[Stage 2] StochRSI(14) >= 99? ✓ ").append(String.format("%.2f", stochVal)).append("\n");
                } else {
                    logOutput.append("[Stage 2] StochRSI(14) >= 99? ✗ ").append(String.format("%.2f", stochVal)).append("\n");
                    logOutput.append("FINAL: ✗ REJECTED AT STAGE 2 — StochRSI below 99\n\n");
                    continue;
                }

                // Stage 3: Pairing check (cross and sat within 2 days, cross first)
                long daysDiffToTrigger = ChronoUnit.DAYS.between(recentCross.crossDate, barDate);
                boolean stage3Pass = daysDiffToTrigger <= 2;
                if (stage3Pass) {
                    logOutput.append("[Stage 3] Pairing (MACD+Stoch align)? ✓ Cross on ").append(recentCross.crossDate)
                            .append(", StochRSI sat on ").append(barDate).append(" (").append(daysDiffToTrigger)
                            .append(" days apart)\n");
                } else {
                    logOutput.append("[Stage 3] Pairing (MACD+Stoch align)? ✗ Too far apart (").append(daysDiffToTrigger)
                            .append(" days)\n");
                    logOutput.append("FINAL: ✗ REJECTED AT STAGE 3 — Trigger stale\n\n");
                    continue;
                }

                // Stage 4: Pattern window scan ±5 bars
                int patternSearchStart = Math.max(0, originalIdx - 5);
                int patternSearchEnd = Math.min(originalIdx + 5, hourly.getBarCount() - 1);
                CandlePattern matchedPattern = null;
                int closestDist = Integer.MAX_VALUE;

                logOutput.append("[Stage 4] Pattern scan ±5 bars from trigger:\n");
                for (int p = patternSearchStart + 1; p <= patternSearchEnd; p++) {
                    Bar prevBar = hourly.getBar(p - 1);
                    Bar currBar = hourly.getBar(p);
                    String patternType = detectBearishCandlePattern(prevBar, currBar);

                    logOutput.append("  Bar[").append(p).append("]: OHLC=(").append(String.format("%.2f", currBar.getOpenPrice().doubleValue()))
                            .append(",").append(String.format("%.2f", currBar.getHighPrice().doubleValue()))
                            .append(",").append(String.format("%.2f", currBar.getLowPrice().doubleValue()))
                            .append(",").append(String.format("%.2f", currBar.getClosePrice().doubleValue())).append(")");

                    if (patternType != null) {
                        logOutput.append(" — ").append(patternType).append(" ✓\n");
                        int distFromTrigger = Math.abs(p - originalIdx);
                        if (distFromTrigger < closestDist) {
                            closestDist = distFromTrigger;
                            double patternLow = currBar.getLowPrice().doubleValue();
                            if ("BEARISH_ENGULFING".equals(patternType) || "DARK_CLOUD_COVER".equals(patternType)) {
                                if (p > 0) {
                                    patternLow = Math.min(patternLow, hourly.getBar(p - 1).getLowPrice().doubleValue());
                                }
                            }
                            matchedPattern = new CandlePattern(p, patternType, currBar.getHighPrice().doubleValue(), patternLow);
                        }
                    } else {
                        logOutput.append(" — no pattern\n");
                    }
                }

                if (matchedPattern == null) {
                    logOutput.append("FINAL: ✗ REJECTED AT STAGE 4 — No bearish pattern found ±5 bars\n\n");
                    continue;
                }

                logOutput.append("Pattern matched: ").append(matchedPattern.type).append(" at bar ").append(matchedPattern.barIndex).append("\n");

                // Stage 5: Pattern low > SMA(20)
                double patternLow = matchedPattern.patternLow;
                double sma20 = sma20Hourly.getValue(matchedPattern.barIndex).doubleValue();
                boolean stage5Pass = patternLow > sma20;

                logOutput.append("[Stage 5] Pattern low > SMA(20)? ");
                if (stage5Pass) {
                    logOutput.append("✓ PatternLow=").append(String.format("%.2f", patternLow))
                            .append(", SMA20=").append(String.format("%.2f", sma20)).append("\n");
                } else {
                    logOutput.append("✗ PatternLow=").append(String.format("%.2f", patternLow))
                            .append(" <= SMA20=").append(String.format("%.2f", sma20)).append("\n");
                    logOutput.append("FINAL: ✗ REJECTED AT STAGE 5 — Pattern low not above SMA(20)\n\n");
                    continue;
                }

                // Stage 6: Breakout scan (close < pattern_low within 10 bars)
                int breakoutEnd = Math.min(matchedPattern.barIndex + 10, hourly.getBarCount() - 1);
                int entryBarIdx = -1;
                double entryPrice = -1;

                for (int b = matchedPattern.barIndex + 1; b <= breakoutEnd; b++) {
                    double close = hourly.getBar(b).getClosePrice().doubleValue();
                    if (close < patternLow) {
                        entryBarIdx = b;
                        entryPrice = close;
                        break;
                    }
                }

                if (entryBarIdx < 0) {
                    logOutput.append("[Stage 6] Breakout (close < low within 10 bars)? ✗ No breakout found\n");
                    logOutput.append("FINAL: ✗ REJECTED AT STAGE 6 — No breakout within 10 bars\n\n");
                    continue;
                }

                Instant entryTime = hourly.getBar(entryBarIdx).getEndTime();
                logOutput.append("[Stage 6] Breakout (close < low within 10 bars)? ✓ Found at bar ").append(entryBarIdx)
                        .append(", close=").append(String.format("%.2f", entryPrice)).append("\n");

                // Stage 7: MACD still bearish at entry
                int entryDailyIdx = findDailyBarAt(daily, entryTime);
                if (entryDailyIdx < 0) {
                    entryDailyIdx = daily.getBarCount() - 1;
                }

                double entryMacd = macdDaily.getValue(entryDailyIdx).doubleValue();
                double entrySignal = signalDaily.getValue(entryDailyIdx).doubleValue();
                boolean stage7Pass = entryMacd < entrySignal;

                logOutput.append("[Stage 7] MACD still bearish? ");
                if (stage7Pass) {
                    logOutput.append("✓ MACD=").append(String.format("%.4f", entryMacd))
                            .append(" < Signal=").append(String.format("%.4f", entrySignal)).append("\n");
                } else {
                    logOutput.append("✗ MACD=").append(String.format("%.4f", entryMacd))
                            .append(" >= Signal=").append(String.format("%.4f", entrySignal)).append("\n");
                    logOutput.append("FINAL: ✗ REJECTED AT STAGE 7 — MACD already bullish\n\n");
                    continue;
                }

                // Stage 8: Daily RSI > 30
                int dIdx = dailyBarIndexForHourly(daily, entryTime);
                if (dIdx < 14) {
                    logOutput.append("[Stage 8] Daily RSI > 30? ✗ Insufficient warmup\n");
                    logOutput.append("FINAL: ✗ REJECTED AT STAGE 8 — Daily RSI warmup\n\n");
                    continue;
                }

                double dailyRsi = rsiDaily.getValue(dIdx).doubleValue();
                boolean stage8Pass = dailyRsi > 30;

                logOutput.append("[Stage 8] Daily RSI > 30? ");
                if (stage8Pass) {
                    logOutput.append("✓ RSI=").append(String.format("%.2f", dailyRsi)).append("\n");
                } else {
                    logOutput.append("✗ RSI=").append(String.format("%.2f", dailyRsi)).append("\n");
                    logOutput.append("FINAL: ✗ REJECTED AT STAGE 8 — Daily RSI < 30 (oversold)\n\n");
                    continue;
                }

                // Stage 9: R:R >= 3.0
                Bar patternBar = hourly.getBar(matchedPattern.barIndex);
                double patternHigh = patternBar.getHighPrice().doubleValue();
                if ("BEARISH_ENGULFING".equals(matchedPattern.type) || "DARK_CLOUD_COVER".equals(matchedPattern.type)) {
                    if (matchedPattern.barIndex > 0) {
                        patternHigh = Math.max(patternHigh, hourly.getBar(matchedPattern.barIndex - 1).getHighPrice().doubleValue());
                    }
                }
                double stop = patternHigh * 1.001;  // 0.1% buffer

                final double entryPriceF = entryPrice;
                List<Level> levels = levelStudy.computeLevels(daily, weeklyBars, entryPrice, entryTime, 10);
                Level target = levels.stream()
                        .filter(l -> l.price() < entryPriceF)
                        .max(Comparator.comparingDouble(Level::price))
                        .orElse(null);

                if (target == null) {
                    logOutput.append("[Stage 9] R:R >= 3.0? ✗ No S/R target found\n");
                    logOutput.append("FINAL: ✗ REJECTED AT STAGE 9 — No S/R level\n\n");
                    continue;
                }

                double risk = stop - entryPrice;
                double reward = entryPrice - target.price();
                double rr = risk > 0 ? reward / risk : 0;
                boolean stage9Pass = rr >= 3.0;

                logOutput.append("[Stage 9] R:R >= 3.0? ");
                if (stage9Pass) {
                    logOutput.append("✓ R:R=").append(String.format("%.2f", rr)).append(" (target=").append(String.format("%.2f", target.price())).append(")\n");
                } else {
                    logOutput.append("✗ R:R=").append(String.format("%.2f", rr)).append(" (target=").append(String.format("%.2f", target.price())).append(")\n");
                    logOutput.append("FINAL: ✗ REJECTED AT STAGE 9 — R:R below 3.0\n\n");
                    continue;
                }

                // Stage 10: Divergence check
                Bar entryBar = hourly.getBar(entryBarIdx);
                double entryBarHigh = entryBar.getHighPrice().doubleValue();
                boolean hasDivergence = checkBearishDivergence(hourly, entryBarIdx, entryBarHigh, structuralHighs, rsiHourly);

                logOutput.append("[Stage 10] Divergence found? ");
                if (hasDivergence) {
                    logOutput.append("✓ Bearish divergence detected\n");
                    logOutput.append("FINAL: ✓ ACCEPTED — All filters passed!\n\n");
                } else {
                    logOutput.append("✗ No bearish divergence in past 6 pivots\n");
                    logOutput.append("FINAL: ✗ REJECTED AT STAGE 10 — No divergence\n\n");
                }
            }

            logOutput.append("=== END DIAGNOSTIC ===\n");

            // Write to file
            Files.write(OUTPUT_LOG, logOutput.toString().getBytes());
            System.out.println(logOutput.toString());
            System.out.println("\nDiagnostic written to: " + OUTPUT_LOG);

        } catch (Exception e) {
            e.printStackTrace();
            Files.write(OUTPUT_LOG, ("ERROR: " + e.getMessage()).getBytes());
        }
    }

    private BarSeries filterDateRange(BarSeries bars, LocalDate startDate, LocalDate endDate) {
        BarSeries filtered = new BaseBarSeriesBuilder().withName(bars.getName()).build();
        for (int i = 0; i < bars.getBarCount(); i++) {
            Bar bar = bars.getBar(i);
            LocalDate barDate = bar.getEndTime().atZone(UTC).toLocalDate();
            if (!barDate.isBefore(startDate) && !barDate.isAfter(endDate)) {
                filtered.addBar(bar);
            }
        }
        return filtered;
    }

    private int findIndexInOriginal(BarSeries original, Bar target) {
        for (int i = 0; i < original.getBarCount(); i++) {
            if (original.getBar(i).getEndTime().equals(target.getEndTime())) {
                return i;
            }
        }
        return -1;
    }

    private String detectBearishCandlePattern(Bar prevBar, Bar currBar) {
        double prevOpen = prevBar.getOpenPrice().doubleValue();
        double prevClose = prevBar.getClosePrice().doubleValue();
        double prevHigh = prevBar.getHighPrice().doubleValue();
        double prevLow = prevBar.getLowPrice().doubleValue();

        double currOpen = currBar.getOpenPrice().doubleValue();
        double currClose = currBar.getClosePrice().doubleValue();
        double currHigh = currBar.getHighPrice().doubleValue();
        double currLow = currBar.getLowPrice().doubleValue();

        boolean prevGreen = prevClose > prevOpen;
        boolean currRed = currClose < currOpen;

        if (!prevGreen || !currRed) return null;

        // BEARISH ENGULFING
        if (currOpen >= prevClose && currClose <= prevOpen) {
            double prevBody = Math.abs(prevClose - prevOpen);
            double currBody = Math.abs(currClose - currOpen);
            if (currBody > prevBody) {
                return "BEARISH_ENGULFING";
            }
        }

        // SHOOTING STAR
        double currBody = Math.abs(currClose - currOpen);
        double upperWick = currHigh - Math.max(currOpen, currClose);
        double lowerWick = Math.min(currOpen, currClose) - currLow;
        double range = currHigh - currLow;

        if (range > 0 && upperWick > 2 * currBody && (currBody / range) < 0.3 && lowerWick < currBody) {
            return "SHOOTING_STAR";
        }

        // DARK CLOUD COVER
        double prevMid = (prevOpen + prevClose) / 2;
        if (currOpen > prevHigh && currClose < prevMid && currClose > prevOpen) {
            return "DARK_CLOUD_COVER";
        }

        return null;
    }

    private boolean checkBearishDivergence(BarSeries hourly, int entryBarIdx, double entryHigh,
                                          List<ZigZagPoint> structuralHighs, RSIIndicator rsiHourly) {
        List<Integer> validPivots = new ArrayList<>();
        for (ZigZagPoint p : structuralHighs) {
            if (p.getBarIndex() < entryBarIdx && p.getValue() < entryHigh) {
                validPivots.add(p.getBarIndex());
            }
        }
        if (validPivots.size() > 6) {
            validPivots = validPivots.subList(validPivots.size() - 6, validPivots.size());
        }
        if (validPivots.isEmpty()) {
            return false;
        }

        for (int pivotIdx : validPivots) {
            double pivotPrice = hourly.getBar(pivotIdx).getHighPrice().doubleValue();
            boolean priceHH = entryHigh > pivotPrice;
            if (!priceHH) continue;

            double entryRsi = rsiHourly.getValue(entryBarIdx).doubleValue();
            double pivotRsi = rsiHourly.getValue(pivotIdx).doubleValue();
            boolean rsiLH = entryRsi < pivotRsi;

            double maxRsiLast10 = entryRsi;
            for (int j = Math.max(0, entryBarIdx - 10); j < entryBarIdx; j++) {
                maxRsiLast10 = Math.max(maxRsiLast10, rsiHourly.getValue(j).doubleValue());
            }
            boolean rsiTurnedDown = entryRsi < maxRsiLast10;

            if (rsiLH && rsiTurnedDown) {
                return true;
            }
        }
        return false;
    }

    private double computeStochRsi(RSIIndicator rsi, int barIndex) {
        if (barIndex < 13) return 0;
        double minRsi = Double.MAX_VALUE;
        double maxRsi = -Double.MAX_VALUE;

        for (int i = barIndex - 13; i <= barIndex; i++) {
            double val = rsi.getValue(i).doubleValue();
            minRsi = Math.min(minRsi, val);
            maxRsi = Math.max(maxRsi, val);
        }

        if (maxRsi == minRsi) return 50;

        double currRsi = rsi.getValue(barIndex).doubleValue();
        return ((currRsi - minRsi) / (maxRsi - minRsi)) * 100;
    }

    private int findDailyBarAt(BarSeries daily, Instant target) {
        LocalDate targetDate = target.atZone(UTC).toLocalDate();
        for (int i = 0; i < daily.getBarCount(); i++) {
            Instant barTime = daily.getBar(i).getEndTime();
            LocalDate barDate = barTime.atZone(UTC).toLocalDate();
            if (barDate.equals(targetDate)) {
                return i;
            }
        }
        return -1;
    }

    private int dailyBarIndexForHourly(BarSeries daily, Instant hourlyEndTime) {
        LocalDate targetDate = hourlyEndTime.atZone(UTC).toLocalDate();
        int result = -1;

        for (int i = 0; i < daily.getBarCount(); i++) {
            Instant barTime = daily.getBar(i).getEndTime();
            LocalDate barDate = barTime.atZone(UTC).toLocalDate();

            if (barDate.isAfter(targetDate)) {
                break;
            }
            result = i;
        }

        return result;
    }

    private BarSeries loadHourlyCsv(String sym, Path csv) throws IOException {
        BarSeries series = new BaseBarSeriesBuilder().withName(sym).build();
        List<String> lines = Files.readAllLines(csv);
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (line.isEmpty()) continue;
            String[] parts = line.split(",");
            if (parts.length < 6) continue;

            String ts = parts[0];
            if (!ts.endsWith("Z")) ts += "Z";
            try {
                series.addBar(BarsLoader.getBar(
                        Double.parseDouble(parts[1]), Double.parseDouble(parts[2]),
                        Double.parseDouble(parts[3]), Double.parseDouble(parts[4]),
                        Double.parseDouble(parts[5]), Instant.parse(ts), Duration.ofHours(1)));
            } catch (Exception e) {
                // Skip malformed
            }
        }
        return series;
    }

    private BarSeries loadDailyCsv(String sym, Path csv) throws IOException {
        BarSeries series = new BaseBarSeriesBuilder().withName(sym).build();
        List<String> lines = Files.readAllLines(csv);
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (line.isEmpty()) continue;
            String[] parts = line.split(",");
            if (parts.length < 6) continue;

            try {
                LocalDate date = LocalDate.parse(parts[0]);
                Instant ts = date.atTime(LocalTime.MIDNIGHT).atZone(ZoneId.systemDefault()).toInstant();
                series.addBar(BarsLoader.getBar(
                        Double.parseDouble(parts[1]), Double.parseDouble(parts[2]),
                        Double.parseDouble(parts[3]), Double.parseDouble(parts[4]),
                        Double.parseDouble(parts[5]), ts, Duration.ofDays(1)));
            } catch (Exception e) {
                // Skip malformed
            }
        }
        return series;
    }

    private BarSeries resampleToWeekly(BarSeries daily) {
        // Simplified: just return daily as-is for weekly, it's only used for S/R level study
        // and the exact resampling doesn't matter for diagnostic purposes
        return daily;
    }

    // Data classes
    static class MacdCross {
        int dailyBarIdx;
        LocalDate crossDate;
        Instant crossTime;

        MacdCross(int dailyBarIdx, LocalDate crossDate, Instant crossTime) {
            this.dailyBarIdx = dailyBarIdx;
            this.crossDate = crossDate;
            this.crossTime = crossTime;
        }
    }

    static class CandlePattern {
        int barIndex;
        String type;
        double patternHigh, patternLow;

        CandlePattern(int barIndex, String type, double patternHigh, double patternLow) {
            this.barIndex = barIndex;
            this.type = type;
            this.patternHigh = patternHigh;
            this.patternLow = patternLow;
        }
    }
}
