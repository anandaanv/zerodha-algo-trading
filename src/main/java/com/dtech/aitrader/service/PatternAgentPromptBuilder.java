package com.dtech.aitrader.service;

import com.dtech.aitrader.data.AiLevels;
import com.dtech.aitrader.model.PatternSignal;
import com.dtech.aitrader.repository.AiLevelsRepository;
import com.dtech.chartdata.model.OhlcBarDTO;
import com.dtech.chartdata.service.ChartDataService;
import com.dtech.kitecon.data.UserChartState;
import com.dtech.kitecon.repository.UserChartStateRepository;
import com.dtech.kitecon.service.DrawingExtractorService;
import com.dtech.kitecon.service.ai.tools.ValidationInput;
import com.dtech.kitecon.strategy.dataloader.BarsLoader;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBarSeriesBuilder;
import org.ta4j.core.Bar;
import org.ta4j.core.indicators.MACDIndicator;
import org.ta4j.core.indicators.RSIIndicator;
import org.ta4j.core.indicators.ATRIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Builds a focused prompt for PatternAgent to make a binary TRADE/NO_TRADE decision.
 * Includes pattern signal, recent bars, indicators, AI levels, and user drawings.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PatternAgentPromptBuilder {

    private final ChartDataService chartDataService;
    private final AiLevelsRepository aiLevelsRepository;
    private final UserChartStateRepository userChartStateRepository;
    private final DrawingExtractorService drawingExtractorService;
    private final ObjectMapper objectMapper;

    /**
     * Builds a comprehensive pattern decision prompt (target: 5-10K tokens).
     *
     * @param signal The pattern signal with all context
     * @return User prompt for Claude with complete decision context
     */
    public String buildPatternPrompt(PatternSignal signal) {
        return buildPatternPrompt(signal, Optional.empty());
    }

    /**
     * Builds a pattern prompt with optional asOf slicing for walk-forward testing.
     *
     * @param signal The pattern signal
     * @param asOf If present, slice bars to bar.endTime <= asOf; use signal.signalTime if empty
     * @return User prompt with no look-ahead
     */
    public String buildPatternPrompt(PatternSignal signal, Optional<Instant> asOf) {
        // If asOf not provided, use signal's signalTime
        Instant effectiveAsOf = asOf.orElse(signal.getSignalTime());
        StringBuilder prompt = new StringBuilder();

        // 1. Pattern Signal Block
        prompt.append("## PATTERN SIGNAL\n");
        prompt.append(String.format("Symbol: %s | Type: %s | Source: %s\n",
            signal.getSymbol(), signal.getPatternType(), signal.getPatternSource()));
        prompt.append(String.format("Direction: %s | Timeframe: %s\n",
            signal.getDirection(), signal.getTimeframe()));
        prompt.append(String.format("Suggested Entry: %.2f | SL: %.2f | Target: %.2f\n",
            signal.getSuggestedEntry(), signal.getSuggestedSl(), signal.getSuggestedTarget()));
        prompt.append(String.format("Signal Time: %s\n", signal.getSignalTime()));
        prompt.append(String.format("Signal Ref: %s\n\n", signal.getSignalRef()));

        // 2. Extra context (engine-specific)
        if (signal.getExtraContext() != null && !signal.getExtraContext().isEmpty()) {
            prompt.append("## PATTERN CONTEXT\n");
            signal.getExtraContext().forEach((key, value) ->
                prompt.append(String.format("%s: %s\n", key, value))
            );
            prompt.append("\n");
        }

        // 3. Recent hourly bars (last 100, sliced to asOf)
        appendHourlyBars(prompt, signal.getSymbol(), effectiveAsOf);

        // 4. Current hourly indicators (sliced to asOf)
        appendHourlyIndicators(prompt, signal.getSymbol(), effectiveAsOf);

        // 5. Daily indicators snapshot (sliced to asOf)
        appendDailyIndicators(prompt, signal.getSymbol(), effectiveAsOf);

        // 6. AI Structural Levels (use signal's signalTime for look-up)
        appendAiStructuralLevels(prompt, signal.getSymbol(), signal.getSignalTime());

        // 7. User-drawn lines (always use latest)
        appendUserDrawings(prompt, signal.getSymbol());

        // 8. Decision Instructions with JSON Schema (strengthened)
        prompt.append("## DECISION INSTRUCTIONS\n");
        prompt.append("You are a quant trader making a SINGLE binary decision: TRADE or NO_TRADE.\n");
        prompt.append("A pattern engine has fired. You have context: bars, indicators, structural levels, user drawings.\n\n");

        prompt.append("Filter AGGRESSIVELY for:\n");
        prompt.append("- Fight-the-trend setups (e.g. SHORT into a major rising trendline)\n");
        prompt.append("- Poor R:R relative to nearby support/resistance\n");
        prompt.append("- Conflicting daily-vs-hourly bias\n");
        prompt.append("- Already at extreme indicator (overbought for LONG, oversold for SHORT)\n");
        prompt.append("- Weak recent price action or momentum reversal\n\n");

        prompt.append("When you take a trade, you MAY override entry/SL/target using structural levels — justify it.\n");
        prompt.append("When you reject a trade, state the primary filter that triggered (trend, R:R, bias, indicator, price action).\n\n");

        // Strengthened system prompt about structural levels
        prompt.append("You have access to structural levels generated by a dedicated levels agent. Treat these as high-quality prior context:\n");
        prompt.append("- If the pattern direction conflicts with a strong structural level very near entry (e.g. SHORT triggered just below major rising support with high confidence), favor NO_TRADE.\n");
        prompt.append("- You MAY override the strategy's suggested target with a structural line in the same direction if R:R improves.\n");
        prompt.append("- If a user-drawn line and an AI-detected line agree on a level, give that level extra weight.\n\n");

        prompt.append("Output STRICTLY this JSON (no markdown, no commentary):\n");
        prompt.append("{\n");
        prompt.append("  \"verdict\": \"TRADE\" | \"NO_TRADE\",\n");
        prompt.append("  \"direction\": \"LONG\" | \"SHORT\" | null,\n");
        prompt.append("  \"entry\": <number> | null,\n");
        prompt.append("  \"stop\": <number> | null,\n");
        prompt.append("  \"target\": <number> | null,\n");
        prompt.append("  \"confidence\": <0.0-1.0>,\n");
        prompt.append("  \"reasoning\": \"<300 chars max, filter name if rejecting>\"\n");
        prompt.append("}\n");

        log.debug("Built pattern prompt for symbol={}, patternType={}, length={} chars (~{} tokens)",
            signal.getSymbol(), signal.getPatternType(), prompt.length(), prompt.length() / 4);

        return prompt.toString();
    }

    private void appendHourlyBars(StringBuilder prompt, String symbol, Instant asOf) {
        try {
            List<OhlcBarDTO> hourlyBars = chartDataService.getBars(symbol, "OneHour", null, null, false);
            if (hourlyBars.isEmpty()) {
                prompt.append("## Recent Hourly Bars\n(no bars available)\n\n");
                return;
            }

            // Slice to asOf
            long asOfMillis = asOf.toEpochMilli();
            hourlyBars = hourlyBars.stream()
                    .filter(bar -> bar.getTime() * 1000L <= asOfMillis)
                    .collect(Collectors.toList());
            if (hourlyBars.isEmpty()) {
                prompt.append("## Recent Hourly Bars\n(no bars available as of ").append(asOf).append(")\n\n");
                return;
            }

            // Take last 100 bars
            List<OhlcBarDTO> recentBars = hourlyBars.stream()
                .skip(Math.max(0, hourlyBars.size() - 100))
                .collect(Collectors.toList());

            prompt.append("## Recent Hourly Bars (last ").append(recentBars.size()).append(")\n");
            prompt.append("```\n");
            prompt.append("idx, datetime_utc, open, high, low, close\n");

            int baseIdx = -recentBars.size();
            for (int i = 0; i < recentBars.size(); i++) {
                OhlcBarDTO bar = recentBars.get(i);
                int idx = baseIdx + i;
                String datetime = formatIsoDateTime(bar.getTime());
                prompt.append(String.format("%3d, %s, %8.2f, %8.2f, %8.2f, %8.2f\n",
                    idx, datetime, bar.getOpen(), bar.getHigh(), bar.getLow(), bar.getClose()));
            }
            prompt.append("```\n\n");
        } catch (Exception e) {
            log.warn("Error fetching hourly bars for {}", symbol, e);
            prompt.append("## Recent Hourly Bars\n(error fetching bars)\n\n");
        }
    }

    private void appendHourlyIndicators(StringBuilder prompt, String symbol, Instant asOf) {
        try {
            List<OhlcBarDTO> hourlyBars = chartDataService.getBars(symbol, "OneHour", null, null, false);
            if (hourlyBars.isEmpty()) {
                prompt.append("## Current Hourly Indicators\n(no bars available)\n\n");
                return;
            }

            // Slice to asOf
            long asOfMillis = asOf.toEpochMilli();
            hourlyBars = hourlyBars.stream()
                    .filter(bar -> bar.getTime() * 1000L <= asOfMillis)
                    .collect(Collectors.toList());
            if (hourlyBars.isEmpty()) {
                prompt.append("## Current Hourly Indicators\n(no bars available as of ").append(asOf).append(")\n\n");
                return;
            }

            // Build ta4j series
            BarSeries series = buildBarSeries(hourlyBars);

            // Compute indicators
            ClosePriceIndicator closePrice = new ClosePriceIndicator(series);
            RSIIndicator rsi = new RSIIndicator(closePrice, 14);
            MACDIndicator macd = new MACDIndicator(closePrice, 12, 26);
            ATRIndicator atr = new ATRIndicator(series, 14);

            // Manually compute EMA
            double[] ema10 = computeEma(series, 10);
            double[] ema20 = computeEma(series, 20);
            double[] ema50 = computeEma(series, 50);
            double[] ema100 = computeEma(series, 100);

            int lastIdx = series.getBarCount() - 1;
            prompt.append("## Current Hourly Indicators\n");
            prompt.append(String.format("- RSI(14): %.1f\n", rsi.getValue(lastIdx).doubleValue()));
            prompt.append(String.format("- MACD(12,26,9): line=%.4f\n",
                macd.getValue(lastIdx).doubleValue()));
            prompt.append(String.format("- EMA(10/20/50/100): %.2f / %.2f / %.2f / %.2f\n",
                ema10[lastIdx], ema20[lastIdx], ema50[lastIdx], ema100[lastIdx]));
            prompt.append(String.format("- ATR(14): %.2f\n", atr.getValue(lastIdx).doubleValue()));
            prompt.append("\n");
        } catch (Exception e) {
            log.warn("Error computing hourly indicators for {}", symbol, e);
            prompt.append("## Current Hourly Indicators\n(error computing indicators)\n\n");
        }
    }

    private void appendDailyIndicators(StringBuilder prompt, String symbol, Instant asOf) {
        try {
            List<OhlcBarDTO> dailyBars = chartDataService.getBars(symbol, "Day", null, null, false);
            if (dailyBars.isEmpty()) {
                prompt.append("## Daily Indicators Snapshot\n(no daily bars available)\n\n");
                return;
            }

            // Slice to asOf
            long asOfMillis = asOf.toEpochMilli();
            dailyBars = dailyBars.stream()
                    .filter(bar -> bar.getTime() * 1000L <= asOfMillis)
                    .collect(Collectors.toList());
            if (dailyBars.isEmpty()) {
                prompt.append("## Daily Indicators Snapshot\n(no daily bars available as of ").append(asOf).append(")\n\n");
                return;
            }

            // Keep last 100 for warmup
            List<OhlcBarDTO> context = dailyBars.stream()
                .skip(Math.max(0, dailyBars.size() - 100))
                .collect(Collectors.toList());

            BarSeries series = buildBarSeries(context);
            ClosePriceIndicator closePrice = new ClosePriceIndicator(series);
            RSIIndicator rsi = new RSIIndicator(closePrice, 14);
            MACDIndicator macd = new MACDIndicator(closePrice, 12, 26);

            int lastIdx = series.getBarCount() - 1;
            OhlcBarDTO lastBar = context.get(context.size() - 1);
            String date = formatIsoDate(lastBar.getTime());

            prompt.append("## Daily Indicators Snapshot\n");
            prompt.append(String.format("Date: %s | RSI(14): %.1f | MACD(12,26,9): line=%.4f\n\n",
                date, rsi.getValue(lastIdx).doubleValue(), macd.getValue(lastIdx).doubleValue()));
        } catch (Exception e) {
            log.warn("Error computing daily indicators for {}", symbol, e);
            prompt.append("## Daily Indicators Snapshot\n(error computing indicators)\n\n");
        }
    }

    private void appendAiStructuralLevels(StringBuilder prompt, String symbol, Instant signalTime) {
        try {
            // Look up levels generated for this signal's date
            LocalDate signalDate = signalTime.atZone(ZoneId.of("Asia/Kolkata")).toLocalDate();
            Optional<AiLevels> levels = aiLevelsRepository.findBySymbolAndTimeframeAndGeneratedForDate(
                symbol, "OneHour", signalDate);

            if (levels.isEmpty()) {
                // Fallback: try to get the most recent levels
                levels = aiLevelsRepository.findFirstBySymbolOrderByGeneratedAtDesc(symbol);
                if (levels.isEmpty()) {
                    prompt.append("## AI Structural Levels\n(no AI structural levels available)\n\n");
                    return;
                }
            }

            AiLevels levelData = levels.get();

            // Parse levelsJson
            try {
                JsonNode root = objectMapper.readTree(levelData.getLevelsJson());

                prompt.append("## AI Structural Levels (from levels agent)\n\n");

                // Format trendlines
                if (root.has("trendlines") && root.get("trendlines").isArray()) {
                    prompt.append("Trendlines:\n");
                    for (JsonNode tl : root.get("trendlines")) {
                        String id = tl.has("id") ? tl.get("id").asText() : "?";
                        String role = tl.has("role") ? tl.get("role").asText() : "?";
                        String tf = tl.has("primary_timeframe") ? tl.get("primary_timeframe").asText() : "?";
                        double conf = tl.has("confidence") ? tl.get("confidence").asDouble() : 0.0;
                        String rationale = tl.has("rationale") ? tl.get("rationale").asText() : "";
                        double p0 = tl.has("anchor_p0") ? tl.get("anchor_p0").asDouble() : 0.0;
                        double p1 = tl.has("anchor_p1") ? tl.get("anchor_p1").asDouble() : 0.0;
                        String t0 = tl.has("anchor_t0") ? tl.get("anchor_t0").asText() : "";
                        String t1 = tl.has("anchor_t1") ? tl.get("anchor_t1").asText() : "";

                        prompt.append(String.format("  - %s | %s | %s | %s → %s | conf %.2f | \"%s\"\n",
                            id, role, tf, t0.substring(0, 10), t1.substring(0, 10), conf, rationale));
                    }
                    prompt.append("\n");
                }

                // Format horizontal levels
                if (root.has("horizontal_levels") && root.get("horizontal_levels").isArray()) {
                    prompt.append("Horizontals:\n");
                    for (JsonNode h : root.get("horizontal_levels")) {
                        String id = h.has("id") ? h.get("id").asText() : "?";
                        double price = h.has("price") ? h.get("price").asDouble() : 0.0;
                        String role = h.has("role") ? h.get("role").asText() : "?";
                        String tf = h.has("primary_timeframe") ? h.get("primary_timeframe").asText() : "?";
                        double conf = h.has("confidence") ? h.get("confidence").asDouble() : 0.0;
                        String rationale = h.has("rationale") ? h.get("rationale").asText() : "";

                        prompt.append(String.format("  - %s | %.2f | %s | %s | conf %.2f | \"%s\"\n",
                            id, price, role, tf, conf, rationale));
                    }
                    prompt.append("\n");
                }

                // Format fib zones
                if (root.has("fib_zones") && root.get("fib_zones").isArray()) {
                    prompt.append("Fib zones:\n");
                    for (JsonNode f : root.get("fib_zones")) {
                        String id = f.has("id") ? f.get("id").asText() : "?";
                        double fromP = f.has("from_p") ? f.get("from_p").asDouble() : 0.0;
                        double toP = f.has("to_p") ? f.get("to_p").asDouble() : 0.0;
                        String rationale = f.has("rationale") ? f.get("rationale").asText() : "";

                        prompt.append(String.format("  - %s | %.2f → %.2f | \"%s\"\n",
                            id, fromP, toP, rationale));
                    }
                    prompt.append("\n");
                }

                prompt.append("\n");
            } catch (Exception e) {
                log.warn("Error parsing levelsJson for {}", symbol, e);
                prompt.append("## AI Structural Levels\n(error parsing levels)\n\n");
            }
        } catch (Exception e) {
            log.warn("Error fetching AI levels for {}", symbol, e);
            prompt.append("## AI Structural Levels\n(error fetching levels)\n\n");
        }
    }

    private void appendUserDrawings(StringBuilder prompt, String symbol) {
        try {
            List<UserChartState> states = userChartStateRepository.findAll().stream()
                .filter(s -> s.getSymbol().equals(symbol))
                .sorted(Comparator.comparing(UserChartState::getCreatedAt).reversed())
                .limit(1)
                .collect(Collectors.toList());

            if (states.isEmpty()) {
                prompt.append("## User-Drawn Lines\n(user drawings not associated — only AI levels available)\n\n");
                return;
            }

            UserChartState state = states.get(0);
            if (state.getOverlaysJson() == null || state.getOverlaysJson().isEmpty()) {
                prompt.append("## User-Drawn Lines\n(user drawings not associated — only AI levels available)\n\n");
                return;
            }

            List<ValidationInput.Drawing> drawings = drawingExtractorService.extractDrawings(state.getOverlaysJson());
            if (drawings.isEmpty()) {
                prompt.append("## User-Drawn Lines\n(user drawings not associated — only AI levels available)\n\n");
                return;
            }

            prompt.append("## User-Drawn Lines\n");
            for (ValidationInput.Drawing drawing : drawings) {
                prompt.append(String.format("  - Type: %s\n", drawing.getType() != null ? drawing.getType() : "?"));
            }
            prompt.append("\n");
        } catch (Exception e) {
            log.warn("Error fetching user drawings for {}", symbol, e);
            prompt.append("## User-Drawn Lines\n(user drawings not associated — only AI levels available)\n\n");
        }
    }

    private BarSeries buildBarSeries(List<OhlcBarDTO> bars) {
        BarSeries series = new BaseBarSeriesBuilder().withName("data").build();
        for (OhlcBarDTO bar : bars) {
            Instant timestamp = Instant.ofEpochSecond(bar.getTime());
            Bar taBar = BarsLoader.getBar(bar.getOpen(), bar.getHigh(), bar.getLow(),
                bar.getClose(), bar.getVolume(), timestamp);
            series.addBar(taBar);
        }
        return series;
    }

    private double[] computeEma(BarSeries series, int period) {
        int n = series.getBarCount();
        double[] ema = new double[n];
        double[] closes = new double[n];

        for (int i = 0; i < n; i++) {
            closes[i] = series.getBar(i).getClosePrice().doubleValue();
        }

        double alpha = 2.0 / (period + 1);
        ema[0] = closes[0];

        for (int i = 1; i < n; i++) {
            ema[i] = alpha * closes[i] + (1 - alpha) * ema[i - 1];
        }

        return ema;
    }

    private String formatIsoDateTime(long epochSeconds) {
        return Instant.ofEpochSecond(epochSeconds).atZone(ZoneId.of("UTC"))
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'"));
    }

    private String formatIsoDate(long epochSeconds) {
        return Instant.ofEpochSecond(epochSeconds).atZone(ZoneId.of("UTC"))
                .format(DateTimeFormatter.ISO_DATE);
    }
}
