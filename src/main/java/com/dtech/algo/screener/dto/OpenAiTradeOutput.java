package com.dtech.algo.screener.dto;

import com.dtech.algo.screener.enums.Verdict;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Locale;

/**
 * POJO representing the expected OpenAI JSON output:
 * {
 *   Symbol, Wave, Direction, Confidence, Risks, Advice, entry, target, stoploss
 * }
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OpenAiTradeOutput {
    private String symbol;     // Symbol under scan
    private String wave;       // Interval of wave
    private String direction;  // Trade Direction - Short/Long/Sideways
    private String confidence; // Low, Medium, Moderate, High
    private Object risks;      // Indicators against trade
    private String advice;     // Short advice (up to ~10 words)
    private String entry;      // Entry zone
    private String target;     // Target
    private String stoploss;   // Stoploss

    public boolean isHighConviction() {
        String c = confidence == null ? "" : confidence.toLowerCase(Locale.ROOT);
        return c.contains("moderate") || c.contains("high");
    }

    public Verdict getFinalVerdict() {
        String d = direction == null ? "" : direction.toLowerCase(Locale.ROOT);
        if (d.contains("long") || d.contains("buy") || d.contains("bull")) return Verdict.BUY;
        if (d.contains("short") || d.contains("sell") || d.contains("bear")) return Verdict.SELL;
        if (d.contains("side") || d.contains("neutral") || d.contains("wait") || d.contains("range")) return Verdict.WAIT;
        return null;
    }

    public String getTranslatedSummary() {
        return String.format(
                "Symbol=%s; Wave=%s; Direction=%s; Confidence=%s; Advice=%s; Entry=%s; Target=%s; StopLoss=%s",
                symbol, wave, direction, confidence, advice, entry, target, stoploss
        );
    }
}
