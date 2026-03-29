package com.dtech.kitecon.analysis;

import com.dtech.ta.elliott.EnrichedPivot;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
public class IndicatorSnapshotFormatter {

    private static final DateTimeFormatter DATE_FMT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.of("Asia/Kolkata"));
    private static final double ADX_TRENDING = 25.0;
    private static final double RELVOL_HIGH  = 1.5;

    /**
     * Formats the last 2 enriched pivots per timeframe as a human-readable
     * indicator snapshot for the AI prompt.
     *
     * @param enrichedPivotsByTf  Map<timeframe, list of enriched pivots> from ElliottWaveAnalysis
     * @return formatted text block, or empty string if no data
     */
    public String format(Map<String, List<EnrichedPivot>> enrichedPivotsByTf) {
        if (enrichedPivotsByTf == null || enrichedPivotsByTf.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("=== INDICATOR SNAPSHOT ===\n");

        for (Map.Entry<String, List<EnrichedPivot>> entry : enrichedPivotsByTf.entrySet()) {
            String tf = entry.getKey();
            List<EnrichedPivot> pivots = entry.getValue();
            if (pivots == null || pivots.isEmpty()) continue;

            // Take last 2 pivots
            int from = Math.max(0, pivots.size() - 2);
            List<EnrichedPivot> last2 = pivots.subList(from, pivots.size());

            // Detect divergences across last 4 pivots
            String rsiDivHigh  = detectRsiDivergence(pivots, true);
            String rsiDivLow   = detectRsiDivergence(pivots, false);
            String macdDivHigh = detectMacdDivergence(pivots, true);
            String macdDivLow  = detectMacdDivergence(pivots, false);

            for (int i = last2.size() - 1; i >= 0; i--) {
                EnrichedPivot p = last2.get(i);
                sb.append(String.format("[%s] %s @ %s | Price: %.2f | Structure: %s%n",
                    tf,
                    p.isHigh() ? "HIGH" : "LOW",
                    p.getTimestamp() != null ? DATE_FMT.format(p.getTimestamp()) : "?",
                    p.getPrice(),
                    p.getStructureLabel() != null ? p.getStructureLabel() : "?"));

                // RSI
                double rsi = p.getRsi();
                sb.append(String.format("  RSI(14): %.1f (%s)%n", rsi, rsiZone(rsi)));

                // MACD
                double hist = p.getMacdHistogram();
                sb.append(String.format("  MACD: hist=%+.2f (%s)  EWO: %+.1f (%s)%n",
                    hist,
                    hist > 0 ? "bullish" : (hist < 0 ? "bearish" : "flat"),
                    p.getEwo(),
                    p.getEwo() > 0 ? "positive momentum" : (p.getEwo() < 0 ? "negative momentum" : "flat")));

                // Bollinger
                sb.append(String.format("  Bollinger: pct_b=%.2f (%s) squeeze=%s width=%.2f%%%n",
                    p.getBollingerPctB(),
                    bollingerZone(p.getBollingerPctB(), p.isBollingerWalk()),
                    p.isBollingerSqueeze() ? "YES" : "no",
                    p.getBollingerWidth() * 100));

                // ADX
                String adxLabel = p.getAdx() >= ADX_TRENDING
                    ? (p.getDiPlus() > p.getDiMinus() ? "TRENDING UP" : "TRENDING DOWN")
                    : "RANGING";
                sb.append(String.format("  ADX: %.1f  DI+: %.1f  DI-: %.1f → %s%n",
                    p.getAdx(), p.getDiPlus(), p.getDiMinus(), adxLabel));

                // EMAs
                sb.append(String.format("  EMAs: 20=%.0f 50=%.0f 200=%.0f → %s%n",
                    p.getEma20(), p.getEma50(), p.getEma200(),
                    p.getEmaStackOrder() != null ? p.getEmaStackOrder() : "?"));

                // Volume
                String volLabel = p.getRelativeVolume() >= RELVOL_HIGH ? "HIGH" : "normal";
                sb.append(String.format("  Volume: MFI=%.1f  relVol=%.1fx (%s)%n",
                    p.getMfi(), p.getRelativeVolume(), volLabel));

                // Divergences (annotate on the most recent pivot only)
                if (i == last2.size() - 1) {
                    List<String> divs = new ArrayList<>();
                    if (p.isHigh() && rsiDivHigh  != null) divs.add(rsiDivHigh);
                    if (p.isLow()  && rsiDivLow   != null) divs.add(rsiDivLow);
                    if (p.isHigh() && macdDivHigh != null) divs.add(macdDivHigh);
                    if (p.isLow()  && macdDivLow  != null) divs.add(macdDivLow);
                    sb.append(String.format("  Divergence: %s%n",
                        divs.isEmpty() ? "NONE" : String.join(", ", divs)));
                }
            }
        }

        return sb.toString();
    }

    // ── RSI zone label ────────────────────────────────────────────────────────

    private String rsiZone(double rsi) {
        if (rsi >= 70) return "OVERBOUGHT";
        if (rsi <= 30) return "OVERSOLD";
        if (rsi >= 50) return "BULLISH_ZONE";
        return "BEARISH_ZONE";
    }

    // ── Bollinger zone label ──────────────────────────────────────────────────

    private String bollingerZone(double pctB, boolean bollingerWalk) {
        if (bollingerWalk) return "WALKING_UPPER_BAND";
        if (pctB >= 0.8)   return "near upper band";
        if (pctB <= 0.2)   return "near lower band";
        return "mid band";
    }

    // ── Divergence detection ──────────────────────────────────────────────────

    /**
     * Detects RSI divergence by comparing the last two same-direction pivots.
     * @param pivots  ordered list of pivots (oldest → newest)
     * @param highs   true = compare HIGH pivots, false = compare LOW pivots
     * @return divergence label or null if none
     */
    private String detectRsiDivergence(List<EnrichedPivot> pivots, boolean highs) {
        // Collect last 4 pivots of the target type
        List<EnrichedPivot> typed = new ArrayList<>();
        for (int i = pivots.size() - 1; i >= 0 && typed.size() < 4; i--) {
            EnrichedPivot p = pivots.get(i);
            if (highs ? p.isHigh() : p.isLow()) typed.add(p);
        }
        if (typed.size() < 2) return null;

        EnrichedPivot latest = typed.get(0);
        EnrichedPivot prior  = typed.get(1);

        if (highs) {
            // Bearish: price higher but RSI lower
            if (latest.getPrice() > prior.getPrice() && latest.getRsi() < prior.getRsi())
                return "BEARISH_RSI_DIV";
            // Hidden bullish: price lower but RSI higher (less common, skip for brevity)
        } else {
            // Bullish: price lower but RSI higher
            if (latest.getPrice() < prior.getPrice() && latest.getRsi() > prior.getRsi())
                return "BULLISH_RSI_DIV";
        }
        return null;
    }

    /**
     * Detects MACD histogram divergence between the last two same-direction pivots.
     */
    private String detectMacdDivergence(List<EnrichedPivot> pivots, boolean highs) {
        List<EnrichedPivot> typed = new ArrayList<>();
        for (int i = pivots.size() - 1; i >= 0 && typed.size() < 4; i--) {
            EnrichedPivot p = pivots.get(i);
            if (highs ? p.isHigh() : p.isLow()) typed.add(p);
        }
        if (typed.size() < 2) return null;

        EnrichedPivot latest = typed.get(0);
        EnrichedPivot prior  = typed.get(1);

        if (highs) {
            if (latest.getPrice() > prior.getPrice()
                    && latest.getMacdHistogram() < prior.getMacdHistogram())
                return "BEARISH_MACD_DIV";
        } else {
            if (latest.getPrice() < prior.getPrice()
                    && latest.getMacdHistogram() > prior.getMacdHistogram())
                return "BULLISH_MACD_DIV";
        }
        return null;
    }
}
