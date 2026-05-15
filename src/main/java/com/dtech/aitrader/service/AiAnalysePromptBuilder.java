package com.dtech.aitrader.service;

import com.dtech.aitrader.data.AiLevels;
import com.dtech.chartdata.model.OhlcBarDTO;
import com.dtech.chartdata.service.ChartDataService;
import com.dtech.aitrader.repository.AiLevelsRepository;
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
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Builds a focused prompt for AiAnalyseService to generate watch trades.
 * Pulls recent hourly bars, current indicators, user drawings, and AI levels.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AiAnalysePromptBuilder {

    private final ChartDataService chartDataService;
    private final AiLevelsRepository aiLevelsRepository;
    private final UserChartStateRepository userChartStateRepository;
    private final DrawingExtractorService drawingExtractorService;
    private final ObjectMapper objectMapper;

    /**
     * Builds a comprehensive prompt for generating watch trades.
     *
     * @param symbol The stock symbol
     * @param tabId User's tab ID for scoped drawing lookup
     * @param layoutId Chart layout ID
     * @param timeframe Timeframe (e.g. "OneHour")
     * @return User prompt with bars, indicators, drawings, and AI levels
     */
    public String buildAnalysePrompt(String symbol, String tabId, Long layoutId, String timeframe) {
        StringBuilder prompt = new StringBuilder();

        // 1. Header with context
        prompt.append("## CONTEXT\n");
        prompt.append(String.format("Symbol: %s | Timeframe: %s\n", symbol, timeframe));
        prompt.append(String.format("Generated At: %s UTC\n\n", formatIsoDateTime(Instant.now().getEpochSecond())));

        // 2. Recent hourly bars (last 100)
        appendHourlyBars(prompt, symbol, timeframe);

        // 3. Current hourly indicators
        appendHourlyIndicators(prompt, symbol, timeframe);

        // 4. Today's AI horizontal levels
        appendAiHorizontalLevels(prompt, symbol, timeframe);

        // 5. User's drawn lines for this symbol
        appendUserDrawings(prompt, symbol, tabId, layoutId, timeframe);

        // 6. Decision instructions with strict JSON output
        appendDecisionInstructions(prompt);

        log.debug("Built analyse prompt for symbol={}, timeframe={}, length={} chars (~{} tokens)",
            symbol, timeframe, prompt.length(), prompt.length() / 4);

        return prompt.toString();
    }

    private void appendHourlyBars(StringBuilder prompt, String symbol, String timeframe) {
        try {
            List<OhlcBarDTO> hourlyBars = chartDataService.getBars(symbol, timeframe, null, null, false);
            if (hourlyBars.isEmpty()) {
                prompt.append("## Recent Hourly Bars\n(no bars available)\n\n");
                return;
            }

            // Take last 100 bars
            List<OhlcBarDTO> recentBars = hourlyBars.stream()
                .skip(Math.max(0, hourlyBars.size() - 100))
                .collect(Collectors.toList());

            prompt.append("## Recent Hourly Bars (last ").append(recentBars.size()).append(")\n");
            prompt.append("```\n");
            prompt.append("idx, datetime_utc, open, high, low, close, volume\n");

            int baseIdx = -recentBars.size();
            for (int i = 0; i < recentBars.size(); i++) {
                OhlcBarDTO bar = recentBars.get(i);
                int idx = baseIdx + i;
                String datetime = formatIsoDateTime(bar.getTime());
                prompt.append(String.format("%3d, %s, %8.2f, %8.2f, %8.2f, %8.2f, %12.0f\n",
                    idx, datetime, bar.getOpen(), bar.getHigh(), bar.getLow(), bar.getClose(), bar.getVolume()));
            }
            prompt.append("```\n\n");
        } catch (Exception e) {
            log.warn("Error fetching hourly bars for {}", symbol, e);
            prompt.append("## Recent Hourly Bars\n(error fetching bars)\n\n");
        }
    }

    private void appendHourlyIndicators(StringBuilder prompt, String symbol, String timeframe) {
        try {
            List<OhlcBarDTO> hourlyBars = chartDataService.getBars(symbol, timeframe, null, null, false);
            if (hourlyBars.isEmpty()) {
                prompt.append("## Current Hourly Indicators\n(no bars available)\n\n");
                return;
            }

            // Build ta4j series
            BarSeries series = buildBarSeries(hourlyBars);

            // Compute indicators
            ClosePriceIndicator closePrice = new ClosePriceIndicator(series);
            RSIIndicator rsi = new RSIIndicator(closePrice, 14);
            MACDIndicator macd = new MACDIndicator(closePrice, 12, 26);

            // Manually compute EMA
            double[] ema10 = computeEma(series, 10);
            double[] ema20 = computeEma(series, 20);
            double[] ema50 = computeEma(series, 50);
            double[] ema100 = computeEma(series, 100);

            int lastIdx = series.getBarCount() - 1;
            prompt.append("## Current Hourly Indicators\n");
            prompt.append(String.format("- RSI(14): %.1f\n", rsi.getValue(lastIdx).doubleValue()));
            prompt.append(String.format("- MACD(12,26,9): %.4f\n", macd.getValue(lastIdx).doubleValue()));
            prompt.append(String.format("- EMA(10/20/50/100): %.2f / %.2f / %.2f / %.2f\n",
                ema10[lastIdx], ema20[lastIdx], ema50[lastIdx], ema100[lastIdx]));
            prompt.append("\n");
        } catch (Exception e) {
            log.warn("Error computing hourly indicators for {}", symbol, e);
            prompt.append("## Current Hourly Indicators\n(error computing indicators)\n\n");
        }
    }

    private void appendAiHorizontalLevels(StringBuilder prompt, String symbol, String timeframe) {
        try {
            Optional<AiLevels> levelsOpt = aiLevelsRepository.findBySymbolAndTimeframeAndGeneratedForDate(
                symbol, timeframe, LocalDate.now());

            if (levelsOpt.isEmpty()) {
                prompt.append("## AI Horizontal Levels\n(no levels generated for today)\n\n");
                return;
            }

            AiLevels levels = levelsOpt.get();
            if (levels.getLevelsJson() == null || levels.getLevelsJson().isEmpty()) {
                prompt.append("## AI Horizontal Levels\n(levels json empty)\n\n");
                return;
            }

            try {
                JsonNode root = objectMapper.readTree(levels.getLevelsJson());
                JsonNode levelsArray = root.get("horizontal_levels");
                if (levelsArray == null || !levelsArray.isArray()) {
                    prompt.append("## AI Horizontal Levels\n(no horizontal_levels array)\n\n");
                    return;
                }

                prompt.append("## AI Horizontal Levels (Today)\n");
                for (JsonNode level : levelsArray) {
                    String id = level.get("id") != null ? level.get("id").asText() : "?";
                    double price = level.get("price") != null ? level.get("price").asDouble() : 0.0;
                    String role = level.get("role") != null ? level.get("role").asText() : "?";
                    double confidence = level.get("confidence") != null ? level.get("confidence").asDouble() : 0.0;
                    String rationale = level.get("rationale") != null ? level.get("rationale").asText() : "";

                    prompt.append(String.format("  - %s (%.2f) [%s, confidence=%.0f%%] %s\n",
                        id, price, role, confidence * 100, rationale));
                }
                prompt.append("\n");
            } catch (Exception e) {
                log.warn("Error parsing levelsJson for {}", symbol, e);
                prompt.append("## AI Horizontal Levels\n(error parsing levels)\n\n");
            }
        } catch (Exception e) {
            log.warn("Error fetching AI levels for {}", symbol, e);
            prompt.append("## AI Horizontal Levels\n(error fetching levels)\n\n");
        }
    }

    private void appendUserDrawings(StringBuilder prompt, String symbol, String tabId, Long layoutId, String timeframe) {
        try {
            // The frontend save_load_adapter stores user_chart_state.symbol as a
            // composite that's evolved over time:
            //   "<tabId>:<symbol>:<timeframe>"  ← current (per-TF storage)
            //   "<tabId>:<symbol>"              ← previous (tab-scoped only)
            //   "<symbol>"                       ← oldest legacy
            // Try all three keys in priority order. The first hit with content wins.
            String perTfKey   = tabId + ":" + symbol + (timeframe != null ? ":" + timeframe : "");
            String tabKey     = tabId + ":" + symbol;
            String legacyKey  = symbol;
            String[] candidates = new String[] { perTfKey, tabKey, legacyKey };

            UserChartState found = null;
            for (String key : candidates) {
                Optional<UserChartState> withTf = (timeframe != null && !timeframe.isEmpty())
                        ? userChartStateRepository.findBySymbolAndLayoutIdAndTimeframe(key, layoutId, timeframe)
                        : Optional.empty();
                if (withTf.isPresent() && withTf.get().getOverlaysJson() != null) {
                    found = withTf.get();
                    break;
                }
                Optional<UserChartState> nullTf = userChartStateRepository
                        .findBySymbolAndLayoutIdAndTimeframeIsNull(key, layoutId);
                if (nullTf.isPresent() && nullTf.get().getOverlaysJson() != null) {
                    found = nullTf.get();
                    break;
                }
            }

            if (found == null || found.getOverlaysJson() == null || found.getOverlaysJson().isEmpty()) {
                prompt.append("## User-Drawn Lines\n(no drawings found)\n\n");
                return;
            }

            List<ValidationInput.Drawing> drawings = drawingExtractorService.extractDrawings(found.getOverlaysJson());
            if (drawings.isEmpty()) {
                prompt.append("## User-Drawn Lines\n(no drawings found)\n\n");
                return;
            }

            prompt.append("## User-Drawn Lines\n");
            prompt.append("Drawn by the trader on this symbol's chart. Treat as high-prior signals — propose trades that respect them, and reference the IDs below in your watch_trade rationale.\n");
            for (int i = 0; i < drawings.size(); i++) {
                ValidationInput.Drawing drawing = drawings.get(i);
                String type = drawing.getType() != null ? drawing.getType() : "?";
                String id = "user_line_" + i;
                String label = (drawing.getProperties() != null && drawing.getProperties().getLabel() != null)
                        ? drawing.getProperties().getLabel() : "";
                StringBuilder coords = new StringBuilder();
                if (drawing.getPoints() != null) {
                    for (ValidationInput.Point p : drawing.getPoints()) {
                        if (coords.length() > 0) coords.append(" → ");
                        coords.append(String.format("(%s @ %.2f)",
                                java.time.Instant.ofEpochSecond(p.getTimestamp())
                                        .atZone(java.time.ZoneId.of("UTC"))
                                        .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")),
                                p.getPrice()));
                    }
                }
                prompt.append(String.format("  - %s: type=%s, %s%s\n",
                        id, type,
                        coords.length() > 0 ? coords.toString() : "(no coords)",
                        label.isEmpty() ? "" : "  [\"" + label + "\"]"));
            }
            prompt.append("\n");
        } catch (Exception e) {
            log.warn("Error fetching user drawings for {} (tabId={}, layoutId={})", symbol, tabId, layoutId, e);
            prompt.append("## User-Drawn Lines\n(error fetching drawings)\n\n");
        }
    }

    private void appendDecisionInstructions(StringBuilder prompt) {
        prompt.append("## DECISION INSTRUCTIONS\n");
        prompt.append("You are a trade-plan analyst. The user has drawn lines on their chart and the system has detected horizontal levels.\n");
        prompt.append("Your job: propose 0-3 CONCRETE, HIGH-CONVICTION watch trades referencing those lines by ID.\n\n");

        prompt.append("Rules:\n");
        prompt.append("- Each watch trade MUST reference a real line_id from the input (user or AI).\n");
        prompt.append("- Only propose trades with high confidence (>0.70).\n");
        prompt.append("- Return 0 trades if no high-conviction setup exists. Don't force trades.\n");
        prompt.append("- Validity can be up to 7 days from now; default to current time + 24 hours if unclear.\n");
        prompt.append("- Trigger types: TL_BREAK, TL_RETEST, PATTERN_FORM, LEVEL_BREAK, LEVEL_BOUNCE.\n\n");

        prompt.append("Output STRICTLY this JSON (no markdown, no commentary):\n");
        prompt.append("{\n");
        prompt.append("  \"watch_trades\": [\n");
        prompt.append("    {\n");
        prompt.append("      \"id\": \"wt1\",\n");
        prompt.append("      \"direction\": \"LONG\" | \"SHORT\",\n");
        prompt.append("      \"trigger_type\": \"TL_BREAK\" | \"TL_RETEST\" | \"PATTERN_FORM\" | \"LEVEL_BREAK\" | \"LEVEL_BOUNCE\",\n");
        prompt.append("      \"trigger_spec\": {\n");
        prompt.append("        \"line_id\": \"<id-from-input>\",\n");
        prompt.append("        \"price\": <number>,\n");
        prompt.append("        \"direction\": \"above\" | \"below\",\n");
        prompt.append("        \"min_close\": true\n");
        prompt.append("      },\n");
        prompt.append("      \"entry\": <number>,\n");
        prompt.append("      \"stop\": <number>,\n");
        prompt.append("      \"target\": <number>,\n");
        prompt.append("      \"rr\": <number>,\n");
        prompt.append("      \"confidence\": <0.0-1.0>,\n");
        prompt.append("      \"validity_until\": \"<ISO-8601 UTC, e.g. 2026-05-20T10:30:00Z>\",\n");
        prompt.append("      \"rationale\": \"<200 chars>\"\n");
        prompt.append("    }\n");
        prompt.append("  ]\n");
        prompt.append("}\n");
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
}
