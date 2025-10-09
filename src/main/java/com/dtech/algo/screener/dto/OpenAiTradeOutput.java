package com.dtech.algo.screener.dto;

import com.dtech.algo.screener.enums.Verdict;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
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
@JsonIgnoreProperties(ignoreUnknown = true)
public class OpenAiTradeOutput {

    @JsonAlias({"Symbol", "SYMBOL"})
    private String symbol;     // Symbol under scan

    @JsonAlias({"Wave", "WAVE"})
    private String wave;       // Interval of wave

    @JsonAlias({"Direction", "DIRECTION"})
    private String direction;  // Trade Direction - Short/Long/Sideways

    @JsonAlias({"Confidence", "CONFIDENCE"})
    private String confidence; // Low, Medium, Moderate, High

    @JsonAlias({"Risks", "RISKS"})
    private Object risks;      // Indicators against trade

    @JsonAlias({"Advice", "ADVICE"})
    private String advice;     // Short advice (up to ~10 words)

    @JsonAlias({"Entry", "ENTRY"})
    private String entry;      // Entry zone

    @JsonAlias({"Target", "TARGET"})
    private String target;     // Target

    @JsonAlias({"Stoploss", "STOPLOSS", "stopLoss", "StopLoss"})
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
