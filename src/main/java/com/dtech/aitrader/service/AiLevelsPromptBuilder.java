package com.dtech.aitrader.service;

import com.dtech.chartdata.model.OhlcBarDTO;
import com.dtech.chartdata.service.ChartDataService;
import com.dtech.kitecon.strategy.dataloader.BarsLoader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.ta4j.core.Bar;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBarSeriesBuilder;
import org.ta4j.core.indicators.MACDIndicator;
import org.ta4j.core.indicators.RSIIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class AiLevelsPromptBuilder {
    private final ChartDataService chartDataService;

    /**
     * Builds levels prompt with optional asOf timestamp for walk-forward testing.
     * @param symbol The symbol to analyze
     * @return Levels prompt using latest data
     */
    public String buildLevelsPrompt(String symbol) {
        return buildLevelsPrompt(symbol, Optional.empty(), "");
    }

    /**
     * Builds levels prompt sliced to a specific asOf instant (no look-ahead).
     * Delegates to the annotation-aware overload with no annotations.
     */
    public String buildLevelsPrompt(String symbol, Optional<Instant> asOf) {
        return buildLevelsPrompt(symbol, asOf, "");
    }

    /**
     * Builds levels prompt sliced to a specific asOf instant, with an optional pre-rendered annotations
     * section. The annotations fragment is injected immediately after the as-of timestamp block so the
     * agent sees the trader's intent before the indicator/bar dump.
     *
     * @param symbol The symbol to analyze
     * @param asOf If present, slice all bars to bar.endTime &lt;= asOf
     * @param annotationsPromptFragment Pre-rendered Markdown annotations section, or empty/null for none
     * @return Levels prompt with data as-of the specified instant
     */
    public String buildLevelsPrompt(String symbol, Optional<Instant> asOf, String annotationsPromptFragment) {
        log.info("Building levels prompt for symbol: {} asOf: {} hasAnnotations: {}",
                symbol, asOf, annotationsPromptFragment != null && !annotationsPromptFragment.isBlank());

        try {
            // Fetch hourly, daily, and weekly bars
            List<OhlcBarDTO> hourlyBars = chartDataService.getBars(symbol, "OneHour", null, null, false);
            List<OhlcBarDTO> dailyBars = chartDataService.getBars(symbol, "Day", null, null, false);

            if (hourlyBars.isEmpty() || dailyBars.isEmpty()) {
                throw new IllegalStateException("No bars available for " + symbol);
            }

            // Slice bars if asOf is provided
            if (asOf.isPresent()) {
                long asOfMillis = asOf.get().toEpochMilli();
                hourlyBars = hourlyBars.stream()
                        .filter(bar -> bar.getTime() * 1000L <= asOfMillis)
                        .collect(Collectors.toList());
                dailyBars = dailyBars.stream()
                        .filter(bar -> bar.getTime() * 1000L <= asOfMillis)
                        .collect(Collectors.toList());

                if (hourlyBars.isEmpty() || dailyBars.isEmpty()) {
                    throw new IllegalStateException("No bars available for " + symbol + " as of " + asOf.get());
                }
            }

            // Keep last 300 hourly bars for context
            List<OhlcBarDTO> hourlyContext = hourlyBars.stream()
                    .skip(Math.max(0, hourlyBars.size() - 300))
                    .collect(Collectors.toList());

            // Keep last 250 daily bars for context (extend fetch for indicator warmup)
            List<OhlcBarDTO> dailyContext = dailyBars.stream()
                    .skip(Math.max(0, dailyBars.size() - 250))
                    .collect(Collectors.toList());

            // Get timestamp to show in prompt
            String latestIso;
            if (asOf.isPresent()) {
                latestIso = asOf.get().toString();
            } else {
                long latestTs = hourlyContext.isEmpty() ? System.currentTimeMillis() / 1000 :
                               hourlyContext.get(hourlyContext.size() - 1).getTime();
                latestIso = formatIsoDateTime(latestTs);
            }

            // Resample daily bars to weekly
            List<WeeklyBar> weeklyBars = resampleToWeekly(dailyContext);

            // Build ta4j series for indicator computation
            BarSeries hSeries = buildBarSeries(hourlyContext);
            BarSeries dSeries = buildBarSeries(dailyContext);
            BarSeries wSeries = buildBarSeriesFromWeekly(weeklyBars);

            // Compute MACD, RSI, StochRSI for all timeframes
            IndicatorData hIndicators = computeIndicators(hSeries);
            IndicatorData dIndicators = computeIndicators(dSeries);
            IndicatorData wIndicators = computeIndicators(wSeries);

            // Build prompt
            StringBuilder prompt = new StringBuilder();

            prompt.append("# ").append(symbol).append(" Levels & Structure Analysis\n\n");
            prompt.append("You are a senior chart analyst. Your job is to identify the IMPORTANT support / resistance / trendlines on ")
                    .append(symbol).append(" across hourly, daily, and weekly timeframes, plus label the most plausible Elliott Wave count on the daily timeframe.\n\n");
            prompt.append("These outputs will be used by an intraday trading agent as context for trade decisions. So focus on LEVELS THAT MATTER — not every minor pivot. Quality over quantity.\n\n");

            // As-of timestamp
            prompt.append("## As-of timestamp\n");
            prompt.append(latestIso).append("\n\n");

            // Trader's annotations — placed up front so the agent reads intent before the data dump
            if (annotationsPromptFragment != null && !annotationsPromptFragment.isBlank()) {
                prompt.append(annotationsPromptFragment);
                if (!annotationsPromptFragment.endsWith("\n\n")) prompt.append("\n");
            }

            // Weekly bars
            prompt.append("## Weekly bars (last ").append(weeklyBars.size()).append(" weeks)\n");
            prompt.append("```\n");
            prompt.append("week_start_iso, open, high, low, close\n");
            for (WeeklyBar wb : weeklyBars) {
                prompt.append(String.format("%s, %8.2f, %8.2f, %8.2f, %8.2f\n",
                        wb.weekStartIso, wb.open, wb.high, wb.low, wb.close));
            }
            prompt.append("```\n\n");

            // Weekly indicators (last 30)
            prompt.append("## Weekly indicators (last 30 bars)\n");
            prompt.append("```\n");
            prompt.append("week_start, close, rsi14, macd_hist, stochrsi_k\n");
            formatIndicatorsCsvTrimmed(prompt, weeklyBars, wIndicators, 30);
            prompt.append("```\n\n");

            // Daily bars
            prompt.append("## Daily bars (last ").append(dailyContext.size()).append(")\n");
            prompt.append("```\n");
            prompt.append("date, open, high, low, close\n");
            for (OhlcBarDTO bar : dailyContext) {
                prompt.append(String.format("%s, %8.2f, %8.2f, %8.2f, %8.2f\n",
                        formatIsoDate(bar.getTime()), bar.getOpen(), bar.getHigh(), bar.getLow(), bar.getClose()));
            }
            prompt.append("```\n\n");

            // Daily indicators (last 30)
            prompt.append("## Daily indicators (last 30 bars)\n");
            prompt.append("```\n");
            prompt.append("date, close, rsi14, macd_hist, stochrsi_k\n");
            formatIndicatorsCsvFromBarsTrimmed(prompt, dailyContext, dIndicators, 30);
            prompt.append("```\n\n");

            // Hourly bars
            prompt.append("## Hourly bars (last ").append(hourlyContext.size()).append(")\n");
            prompt.append("```\n");
            prompt.append("datetime_utc, open, high, low, close\n");
            for (OhlcBarDTO bar : hourlyContext) {
                prompt.append(String.format("%s, %8.2f, %8.2f, %8.2f, %8.2f\n",
                        formatIsoDateTime(bar.getTime()), bar.getOpen(), bar.getHigh(), bar.getLow(), bar.getClose()));
            }
            prompt.append("```\n\n");

            // Current indicator snapshot
            prompt.append("## Current indicator snapshot (most recent bar of each TF)\n\n");
            prompt.append(String.format("- Weekly: RSI=%s, MACD=%s/%s/%s, StochRSI %%K=%s/%%D=%s\n",
                    formatIndValue(wIndicators.rsiLast), formatIndValue(wIndicators.macdLine[wSeries.getBarCount()-1]),
                    formatIndValue(wIndicators.macdSig[wSeries.getBarCount()-1]), formatIndValue(wIndicators.macdHist[wSeries.getBarCount()-1]),
                    formatIndValue(wIndicators.stochK[wSeries.getBarCount()-1]), formatIndValue(wIndicators.stochD[wSeries.getBarCount()-1])));
            prompt.append(String.format("- Daily:  RSI=%s, MACD=%s/%s/%s, StochRSI %%K=%s/%%D=%s\n",
                    formatIndValue(dIndicators.rsiLast), formatIndValue(dIndicators.macdLine[dSeries.getBarCount()-1]),
                    formatIndValue(dIndicators.macdSig[dSeries.getBarCount()-1]), formatIndValue(dIndicators.macdHist[dSeries.getBarCount()-1]),
                    formatIndValue(dIndicators.stochK[dSeries.getBarCount()-1]), formatIndValue(dIndicators.stochD[dSeries.getBarCount()-1])));
            prompt.append(String.format("- Hourly: RSI=%s, MACD=%s/%s/%s, StochRSI %%K=%s/%%D=%s\n\n",
                    formatIndValue(hIndicators.rsiLast), formatIndValue(hIndicators.macdLine[hSeries.getBarCount()-1]),
                    formatIndValue(hIndicators.macdSig[hSeries.getBarCount()-1]), formatIndValue(hIndicators.macdHist[hSeries.getBarCount()-1]),
                    formatIndValue(hIndicators.stochK[hSeries.getBarCount()-1]), formatIndValue(hIndicators.stochD[hSeries.getBarCount()-1])));

            // Output schema with canonical anchor_t0/anchor_p0 keys
            prompt.append("## OUTPUT — STRICT JSON, NO MARKDOWN\n\n");
            prompt.append("Emit a single JSON object. Use anchor times in ISO-8601 UTC (e.g. \"2025-04-21T03:45:00Z\"). All prices are absolute INR levels.\n\n");
            prompt.append("{\n");
            prompt.append("  \"trendlines\": [\n");
            prompt.append("    {\n");
            prompt.append("      \"id\": \"tl1\",\n");
            prompt.append("      \"anchor_t0\": \"<iso>\",\n");
            prompt.append("      \"anchor_p0\": <number>,\n");
            prompt.append("      \"anchor_t1\": \"<iso>\",\n");
            prompt.append("      \"anchor_p1\": <number>,\n");
            prompt.append("      \"role\": \"support\" | \"resistance\",\n");
            prompt.append("      \"primary_timeframe\": \"weekly\" | \"daily\" | \"hourly\",\n");
            prompt.append("      \"confidence\": <0.0-1.0>,\n");
            prompt.append("      \"rationale\": \"<150 chars max>\"\n");
            prompt.append("    }\n");
            prompt.append("  ],\n");
            prompt.append("  \"horizontal_levels\": [\n");
            prompt.append("    {\n");
            prompt.append("      \"id\": \"h1\",\n");
            prompt.append("      \"price\": <number>,\n");
            prompt.append("      \"role\": \"support\" | \"resistance\",\n");
            prompt.append("      \"primary_timeframe\": \"weekly\" | \"daily\" | \"hourly\",\n");
            prompt.append("      \"confidence\": <0.0-1.0>,\n");
            prompt.append("      \"rationale\": \"<150 chars>\"\n");
            prompt.append("    }\n");
            prompt.append("  ],\n");
            prompt.append("  \"elliott_waves\": [\n");
            prompt.append("    {\n");
            prompt.append("      \"wave\": \"1\" | \"2\" | \"3\" | \"4\" | \"5\" | \"A\" | \"B\" | \"C\",\n");
            prompt.append("      \"anchor_t\": \"<iso>\",\n");
            prompt.append("      \"anchor_p\": <number>,\n");
            prompt.append("      \"rationale\": \"<150 chars>\"\n");
            prompt.append("    }\n");
            prompt.append("  ],\n");
            prompt.append("  \"fib_zones\": [\n");
            prompt.append("    {\n");
            prompt.append("      \"id\": \"f1\",\n");
            prompt.append("      \"from_t\": \"<iso>\", \"from_p\": <number>,\n");
            prompt.append("      \"to_t\": \"<iso>\", \"to_p\": <number>,\n");
            prompt.append("      \"rationale\": \"<150 chars>\"\n");
            prompt.append("    }\n");
            prompt.append("  ],\n");
            prompt.append("  \"summary\": \"<300 chars overall structural read>\"\n");
            prompt.append("}\n\n");
            prompt.append("## Quality bar\n");
            prompt.append("- Aim for 5-8 trendlines (multi-touch confirmed, not single-pivot guesses)\n");
            prompt.append("- 4-6 horizontal levels (round numbers + multi-touch swing highs/lows)\n");
            prompt.append("- ONE coherent Elliott labeling on daily (don't over-commit, mark only what's confident)\n");
            prompt.append("- 1-2 fib zones from the major recent swing\n");
            prompt.append("- If you cannot label Elliott confidently, return empty array — don't force it\n");
            prompt.append("- For each line, confidence must reflect: number of historical touches, age, alignment with MACD/RSI structure at the touches\n\n");
            prompt.append("Now produce the JSON.\n");

            String result = prompt.toString();
            log.debug("Prompt built successfully for {}. Size: {} chars (~{} tokens)",
                    symbol, result.length(), result.length() / 4);
            return result;

        } catch (Exception e) {
            log.error("Error building prompt for {}", symbol, e);
            throw new RuntimeException("Failed to build prompt: " + e.getMessage(), e);
        }
    }

    private BarSeries buildBarSeries(List<OhlcBarDTO> bars) {
        BarSeries series = new BaseBarSeriesBuilder().withName("data").build();
        for (OhlcBarDTO bar : bars) {
            Instant timestamp = Instant.ofEpochSecond(bar.getTime());
            Bar taBar = BarsLoader.getBar(bar.getOpen(), bar.getHigh(), bar.getLow(), bar.getClose(), bar.getVolume(), timestamp);
            series.addBar(taBar);
        }
        return series;
    }

    private BarSeries buildBarSeriesFromWeekly(List<WeeklyBar> weeks) {
        BarSeries series = new BaseBarSeriesBuilder().withName("weekly").build();
        for (WeeklyBar wb : weeks) {
            Instant timestamp = Instant.ofEpochSecond(wb.timestamp);
            Bar taBar = BarsLoader.getBar(wb.open, wb.high, wb.low, wb.close, wb.volume, timestamp);
            series.addBar(taBar);
        }
        return series;
    }

    private List<WeeklyBar> resampleToWeekly(List<OhlcBarDTO> dailyBars) {
        Map<String, WeeklyBar> weeklyMap = new LinkedHashMap<>();

        for (OhlcBarDTO bar : dailyBars) {
            Instant instant = Instant.ofEpochSecond(bar.getTime());
            LocalDate date = instant.atZone(ZoneId.of("UTC")).toLocalDate();

            // Get ISO week start (Monday) for this date
            LocalDate weekStart = getIsoWeekStart(date);
            String weekKey = weekStart.format(DateTimeFormatter.ISO_DATE);

            WeeklyBar wb = weeklyMap.computeIfAbsent(weekKey, k -> {
                WeeklyBar newWeek = new WeeklyBar();
                newWeek.timestamp = weekStart.atStartOfDay(ZoneId.of("UTC")).toEpochSecond();
                newWeek.weekStartIso = weekStart.format(DateTimeFormatter.ISO_DATE) + "T00:00:00Z";
                newWeek.open = bar.getOpen();
                newWeek.high = bar.getHigh();
                newWeek.low = bar.getLow();
                newWeek.close = bar.getClose();
                newWeek.volume = bar.getVolume();
                return newWeek;
            });

            wb.high = Math.max(wb.high, bar.getHigh());
            wb.low = Math.min(wb.low, bar.getLow());
            wb.close = bar.getClose();
            wb.volume += bar.getVolume();
        }

        // Keep last 500 weeks
        List<WeeklyBar> result = new ArrayList<>(weeklyMap.values());
        if (result.size() > 500) {
            result = result.subList(result.size() - 500, result.size());
        }
        return result;
    }

    private LocalDate getIsoWeekStart(LocalDate date) {
        // Java ISO week: Monday is 1, Sunday is 7
        int dayOfWeek = date.getDayOfWeek().getValue();
        return date.minusDays(dayOfWeek - 1);
    }

    private IndicatorData computeIndicators(BarSeries series) {
        IndicatorData data = new IndicatorData();
        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);
        RSIIndicator rsi = new RSIIndicator(closePrice, 14);
        MACDIndicator macdInd = new MACDIndicator(closePrice, 12, 26);

        int n = series.getBarCount();
        data.rsi = new double[n];
        data.macdLine = new double[n];
        data.macdSig = new double[n];
        data.macdHist = new double[n];
        data.stochK = new double[n];
        data.stochD = new double[n];

        // Compute RSI and MACD for all bars
        for (int i = 0; i < n; i++) {
            data.rsi[i] = rsi.getValue(i).doubleValue();
            data.macdLine[i] = macdInd.getValue(i).doubleValue();
        }

        // Compute MACD signal (EMA of MACD line) and histogram
        double[] signal = computeEma(data.macdLine, 9);
        for (int i = 0; i < n; i++) {
            data.macdSig[i] = signal[i];
            data.macdHist[i] = data.macdLine[i] - signal[i];
        }

        // Compute StochRSI(%K and %D)
        computeStochRsi(data.rsi, data.stochK, data.stochD);

        data.rsiLast = data.rsi[n - 1];
        return data;
    }

    private double[] computeEma(double[] values, int period) {
        double[] ema = new double[values.length];
        if (values.length == 0) return ema;

        // Simple moving average for first period
        double sum = 0;
        for (int i = 0; i < Math.min(period, values.length); i++) {
            sum += values[i];
        }
        ema[Math.min(period - 1, values.length - 1)] = sum / Math.min(period, values.length);

        double alpha = 2.0 / (period + 1);
        for (int i = Math.min(period, values.length); i < values.length; i++) {
            ema[i] = alpha * values[i] + (1 - alpha) * ema[i - 1];
        }

        // Fill backward for early bars
        for (int i = Math.min(period - 1, values.length - 1) - 1; i >= 0; i--) {
            sum = 0;
            for (int j = 0; j <= i && j < period; j++) {
                sum += values[j];
            }
            ema[i] = sum / Math.min(i + 1, period);
        }

        return ema;
    }

    private void computeStochRsi(double[] rsiVals, double[] stochK, double[] stochD) {
        int period = 14;
        int kPeriod = 14;
        int dPeriod = 3;

        for (int i = 0; i < rsiVals.length; i++) {
            if (i < period - 1) {
                stochK[i] = Double.NaN;
                stochD[i] = Double.NaN;
                continue;
            }

            // Get RSI range over last 14 bars
            double minRsi = rsiVals[i];
            double maxRsi = rsiVals[i];
            for (int j = Math.max(0, i - period + 1); j <= i; j++) {
                minRsi = Math.min(minRsi, rsiVals[j]);
                maxRsi = Math.max(maxRsi, rsiVals[j]);
            }

            // Calculate %K
            if (maxRsi == minRsi) {
                stochK[i] = 50.0;
            } else {
                stochK[i] = 100.0 * (rsiVals[i] - minRsi) / (maxRsi - minRsi);
            }
        }

        // Calculate %D as SMA of %K
        for (int i = 0; i < stochK.length; i++) {
            if (i < dPeriod - 1 + period - 1) {
                stochD[i] = Double.NaN;
                continue;
            }

            double sum = 0;
            int count = 0;
            for (int j = i - dPeriod + 1; j <= i; j++) {
                if (!Double.isNaN(stochK[j])) {
                    sum += stochK[j];
                    count++;
                }
            }
            stochD[i] = count > 0 ? sum / count : Double.NaN;
        }
    }

    private void formatIndicatorsCsvTrimmed(StringBuilder sb, List<WeeklyBar> weeks, IndicatorData ind, int limit) {
        int start = Math.max(0, weeks.size() - limit);
        for (int i = start; i < weeks.size(); i++) {
            WeeklyBar wb = weeks.get(i);
            sb.append(String.format("%s, %8.2f, %s, %s, %s\n",
                    wb.weekStartIso.substring(0, 10) + "T00:00:00Z",
                    wb.close,
                    formatIndValue(i < ind.rsi.length ? ind.rsi[i] : Double.NaN),
                    formatIndValue(i < ind.macdHist.length ? ind.macdHist[i] : Double.NaN),
                    formatIndValue(i < ind.stochK.length ? ind.stochK[i] : Double.NaN)));
        }
    }

    private void formatIndicatorsCsv(StringBuilder sb, List<WeeklyBar> weeks, IndicatorData ind, int limit) {
        int start = Math.max(0, weeks.size() - limit);
        for (int i = start; i < weeks.size(); i++) {
            WeeklyBar wb = weeks.get(i);
            sb.append(String.format("%s, %8.2f, %s, %s, %s, %s, %s, %s\n",
                    wb.weekStartIso.substring(0, 10) + "T00:00:00Z",
                    wb.close,
                    formatIndValue(i < ind.rsi.length ? ind.rsi[i] : Double.NaN),
                    formatIndValue(i < ind.macdLine.length ? ind.macdLine[i] : Double.NaN),
                    formatIndValue(i < ind.macdSig.length ? ind.macdSig[i] : Double.NaN),
                    formatIndValue(i < ind.macdHist.length ? ind.macdHist[i] : Double.NaN),
                    formatIndValue(i < ind.stochK.length ? ind.stochK[i] : Double.NaN),
                    formatIndValue(i < ind.stochD.length ? ind.stochD[i] : Double.NaN)));
        }
    }

    private void formatIndicatorsCsvFromBarsTrimmed(StringBuilder sb, List<OhlcBarDTO> bars, IndicatorData ind, int limit) {
        int start = Math.max(0, bars.size() - limit);
        for (int i = start; i < bars.size(); i++) {
            OhlcBarDTO bar = bars.get(i);
            String timeStr = bar.getTime() > 86400 * 30000 ?
                    formatIsoDateTime(bar.getTime()) : formatIsoDate(bar.getTime());
            sb.append(String.format("%s, %8.2f, %s, %s, %s\n",
                    timeStr,
                    bar.getClose(),
                    formatIndValue(i < ind.rsi.length ? ind.rsi[i] : Double.NaN),
                    formatIndValue(i < ind.macdHist.length ? ind.macdHist[i] : Double.NaN),
                    formatIndValue(i < ind.stochK.length ? ind.stochK[i] : Double.NaN)));
        }
    }

    private void formatIndicatorsCsvFromBars(StringBuilder sb, List<OhlcBarDTO> bars, IndicatorData ind, int limit) {
        int start = Math.max(0, bars.size() - limit);
        for (int i = start; i < bars.size(); i++) {
            OhlcBarDTO bar = bars.get(i);
            String timeStr = bar.getTime() > 86400 * 30000 ?
                    formatIsoDateTime(bar.getTime()) : formatIsoDate(bar.getTime());
            sb.append(String.format("%s, %8.2f, %s, %s, %s, %s, %s, %s\n",
                    timeStr,
                    bar.getClose(),
                    formatIndValue(i < ind.rsi.length ? ind.rsi[i] : Double.NaN),
                    formatIndValue(i < ind.macdLine.length ? ind.macdLine[i] : Double.NaN),
                    formatIndValue(i < ind.macdSig.length ? ind.macdSig[i] : Double.NaN),
                    formatIndValue(i < ind.macdHist.length ? ind.macdHist[i] : Double.NaN),
                    formatIndValue(i < ind.stochK.length ? ind.stochK[i] : Double.NaN),
                    formatIndValue(i < ind.stochD.length ? ind.stochD[i] : Double.NaN)));
        }
    }

    private String formatIsoDateTime(long epochSeconds) {
        return Instant.ofEpochSecond(epochSeconds).atZone(ZoneId.of("UTC"))
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'"));
    }

    private String formatIsoDate(long epochSeconds) {
        return Instant.ofEpochSecond(epochSeconds).atZone(ZoneId.of("UTC"))
                .format(DateTimeFormatter.ISO_DATE);
    }

    private String formatIndValue(double val) {
        if (Double.isNaN(val)) return "N/A";
        if (val < 100 && val > -100) return String.format("%.1f", val);
        return String.format("%.4f", val);
    }

    // Helper classes
    static class WeeklyBar {
        long timestamp;
        String weekStartIso;
        double open, high, low, close;
        double volume;
    }

    static class IndicatorData {
        double[] rsi, macdLine, macdSig, macdHist, stochK, stochD;
        double rsiLast;
    }
}
