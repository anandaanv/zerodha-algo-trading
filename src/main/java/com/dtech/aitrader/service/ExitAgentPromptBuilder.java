package com.dtech.aitrader.service;

import com.dtech.aitrader.data.AiLevels;
import com.dtech.aitrader.data.PaperTrade;
import com.dtech.aitrader.repository.AiLevelsRepository;
import com.dtech.chartdata.model.OhlcBarDTO;
import com.dtech.chartdata.service.ChartDataService;
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

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Builds a focused prompt for ExitAgent to make exit decisions on open paper trades.
 * Includes trade state, recent bars, indicators, and structural levels.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ExitAgentPromptBuilder {

    private final ChartDataService chartDataService;
    private final AiLevelsRepository aiLevelsRepository;
    private final ObjectMapper objectMapper;

    /**
     * Builds a compact exit decision prompt (~1.5-2K tokens).
     *
     * @param trade The open paper trade
     * @param symbol The symbol to fetch bars and indicators for
     * @return User prompt for Claude with exit decision context
     */
    public String build(PaperTrade trade, String symbol) {
        StringBuilder prompt = new StringBuilder();

        // 1. Position State Block
        prompt.append("## OPEN POSITION STATE\n");
        prompt.append(String.format("Symbol: %s\n", symbol));
        prompt.append(String.format("Direction: %s\n", trade.getDirection()));
        prompt.append(String.format("Entry Price: %.2f\n", trade.getEntryPrice()));
        prompt.append(String.format("Current SL: %.2f\n", trade.getSl()));
        prompt.append(String.format("Current Target: %.2f\n", trade.getTarget()));
        prompt.append(String.format("Opened At: %s\n", trade.getOpenedAt()));

        // Calculate bars held (approximate)
        long minutesHeld = java.time.temporal.ChronoUnit.MINUTES.between(
            trade.getOpenedAt(), java.time.LocalDateTime.now());
        long barsHeld = minutesHeld / 60;
        prompt.append(String.format("Bars Held (approx): %d\n\n", barsHeld));

        // 2. Fetch and append hourly bars (last 20 + current)
        appendHourlyBars(prompt, symbol);

        // 3. Current hourly indicators
        appendHourlyIndicators(prompt, symbol);

        // 4. AI Structural Levels (2-3 nearest to current price)
        appendAiStructuralLevels(prompt, symbol);

        // 5. Decision Instructions with JSON Schema
        prompt.append("## DECISION INSTRUCTIONS\n");
        prompt.append("You are an exit manager for a single open trade.\n");
        prompt.append("Your job: every check, decide if we should hold, exit, or move the stop/target.\n\n");

        prompt.append("Heuristics:\n");
        prompt.append("- If price wicked through SL but bounced back >0.3% AND structural support holds → consider HOLD (avoid stop hunt)\n");
        prompt.append("- If price ran beyond target but momentum still strong → EXTEND_TARGET_TO_Y (Y = next AI line in direction)\n");
        prompt.append("- If we have >1R profit AND price stalled → MOVE_TO_BREAKEVEN\n");
        prompt.append("- If pattern context invalidated (trend flipped vs entry premise) → CLOSE_NOW\n");
        prompt.append("- Default: HOLD\n\n");

        prompt.append("Output STRICT JSON only, no markdown:\n");
        prompt.append("{\n");
        prompt.append("  \"action\": \"HOLD\" | \"CLOSE_NOW\" | \"MOVE_TO_BREAKEVEN\" | \"TRAIL_TO_X\" | \"EXTEND_TARGET_TO_Y\",\n");
        prompt.append("  \"new_stop\": <number> | null,\n");
        prompt.append("  \"new_target\": <number> | null,\n");
        prompt.append("  \"confidence\": <0.0-1.0>,\n");
        prompt.append("  \"reasoning\": \"<150 chars>\"\n");
        prompt.append("}\n");

        return prompt.toString();
    }

    private void appendHourlyBars(StringBuilder prompt, String symbol) {
        prompt.append("## RECENT HOURLY BARS (Last 20)\n");
        try {
            // Get last 20 bars (from start of day to now)
            List<OhlcBarDTO> bars = chartDataService.getBars(symbol, "OneHour", null, null, true);
            if (bars == null || bars.isEmpty()) {
                prompt.append("No bars found\n\n");
                return;
            }

            // Take last 20 bars
            int start = Math.max(0, bars.size() - 20);
            List<OhlcBarDTO> recentBars = bars.subList(start, bars.size());

            for (OhlcBarDTO bar : recentBars) {
                prompt.append(String.format("epoch=%d: close=%.2f volume=%.0f\n",
                    bar.getTime(), bar.getClose(), bar.getVolume()));
            }
            prompt.append("\n");
        } catch (Exception e) {
            log.warn("Failed to load hourly bars for {}: {}", symbol, e.getMessage());
            prompt.append("Error loading bars\n\n");
        }
    }

    private void appendHourlyIndicators(StringBuilder prompt, String symbol) {
        prompt.append("## HOURLY INDICATORS (Current)\n");
        try {
            List<OhlcBarDTO> bars = chartDataService.getBars(symbol, "OneHour", null, null, true);
            if (bars == null || bars.isEmpty()) {
                prompt.append("No bars for indicators\n\n");
                return;
            }

            // Build ta4j series
            BarSeries series = buildBarSeries(bars);

            // RSI(14)
            RSIIndicator rsi = new RSIIndicator(new ClosePriceIndicator(series), 14);
            double rsiValue = rsi.getValue(series.getEndIndex()).doubleValue();
            prompt.append(String.format("RSI(14): %.2f\n", rsiValue));

            // MACD(12,26,9)
            MACDIndicator macd = new MACDIndicator(new ClosePriceIndicator(series), 12, 26);
            double macdValue = macd.getValue(series.getEndIndex()).doubleValue();
            prompt.append(String.format("MACD(12,26): %.4f\n", macdValue));

            // ATR(14)
            ATRIndicator atr = new ATRIndicator(series, 14);
            double atrValue = atr.getValue(series.getEndIndex()).doubleValue();
            prompt.append(String.format("ATR(14): %.2f\n", atrValue));

            prompt.append("\n");
        } catch (Exception e) {
            log.warn("Failed to calculate indicators for {}: {}", symbol, e.getMessage());
            prompt.append("Error calculating indicators\n\n");
        }
    }

    private void appendAiStructuralLevels(StringBuilder prompt, String symbol) {
        prompt.append("## AI STRUCTURAL LEVELS (2-3 Nearest)\n");
        try {
            // Get current price
            List<OhlcBarDTO> bars = chartDataService.getBars(symbol, "OneHour", null, null, true);
            if (bars == null || bars.isEmpty()) {
                prompt.append("No current price found\n\n");
                return;
            }

            double currentPrice = bars.get(bars.size() - 1).getClose();

            // Get AI levels for the symbol (using custom query)
            List<AiLevels> aiLevelsList = aiLevelsRepository.findBySymbolOrderByGeneratedAtDesc(symbol);
            if (aiLevelsList.isEmpty()) {
                prompt.append("No AI levels found\n\n");
                return;
            }

            AiLevels aiLevels = aiLevelsList.get(0);
            if (aiLevels.getLevelsJson() == null || aiLevels.getLevelsJson().isEmpty()) {
                prompt.append("No levels in JSON\n\n");
                return;
            }

            // Parse levels
            JsonNode levelsNode = objectMapper.readTree(aiLevels.getLevelsJson());
            List<LevelWithDistance> levelsList = new ArrayList<>();

            if (levelsNode.isArray()) {
                for (JsonNode node : levelsNode) {
                    double levelPrice = node.asDouble();
                    double distance = Math.abs(levelPrice - currentPrice);
                    levelsList.add(new LevelWithDistance(levelPrice, distance));
                }
            }

            // Sort by distance and take 3 nearest
            levelsList.sort(Comparator.comparingDouble(l -> l.distance));
            levelsList.stream().limit(3).forEach(l ->
                prompt.append(String.format("  %.2f (%.2f away)\n", l.price, l.distance))
            );
            prompt.append("\n");
        } catch (Exception e) {
            log.warn("Failed to load AI levels for {}: {}", symbol, e.getMessage());
            prompt.append("Error loading levels\n\n");
        }
    }

    private BarSeries buildBarSeries(List<OhlcBarDTO> bars) {
        BarSeries series = new BaseBarSeriesBuilder().withName("temp").build();
        for (OhlcBarDTO bar : bars) {
            Instant timestamp = Instant.ofEpochSecond(bar.getTime());
            Bar taBar = BarsLoader.getBar(
                bar.getOpen(),
                bar.getHigh(),
                bar.getLow(),
                bar.getClose(),
                bar.getVolume(),
                timestamp,
                Duration.ofHours(1)
            );
            series.addBar(taBar);
        }
        return series;
    }

    private static class LevelWithDistance {
        double price;
        double distance;

        LevelWithDistance(double price, double distance) {
            this.price = price;
            this.distance = distance;
        }
    }
}
