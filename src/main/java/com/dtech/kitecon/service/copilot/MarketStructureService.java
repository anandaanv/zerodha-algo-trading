package com.dtech.kitecon.service.copilot;

import com.dtech.chartpattern.zigzag.ZigZagPoint;
import com.dtech.kitecon.service.copilot.dto.MarketStructureData;
import com.dtech.kitecon.service.copilot.dto.MarketStructurePoint;
import com.dtech.kitecon.service.copilot.dto.MarketStructurePoint.PivotType;
import com.dtech.kitecon.service.copilot.dto.MarketStructurePoint.StructureLabel;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Derives maximum market structure detail from ZigZag pivot data.
 *
 * Extracts:
 * - HH / HL / LH / LL classification per pivot (Dow Theory)
 * - Current trend direction and trend strength
 * - BOS (Break of Structure) events
 * - CHoCH (Change of Character) events
 *
 * This data replaces raw OHLCV in AI prompts. AI receives rich structural
 * context and can reason about Elliott Wave patterns without processing candles.
 */
@Service
public class MarketStructureService {

    /**
     * Analyse ZigZag pivots for a timeframe and return fully labelled market structure.
     *
     * @param zigzagPoints ordered list of ZigZag pivots (oldest first)
     * @param timeframe    human-readable label, e.g. "daily", "1h"
     */
    public MarketStructureData analyse(List<ZigZagPoint> zigzagPoints, String timeframe) {
        if (zigzagPoints == null || zigzagPoints.isEmpty()) {
            return MarketStructureData.builder()
                    .timeframe(timeframe)
                    .swingPoints(List.of())
                    .trendDirection(MarketStructureData.TrendDirection.UNDEFINED)
                    .trendStrength(0)
                    .build();
        }

        List<MarketStructurePoint> highs = new ArrayList<>();
        List<MarketStructurePoint> lows = new ArrayList<>();
        List<MarketStructurePoint> allLabelled = new ArrayList<>();

        // Separate highs and lows to compare like-with-like
        for (ZigZagPoint zp : zigzagPoints) {
            MarketStructurePoint mp = MarketStructurePoint.builder()
                    .pivotType(zp.isHigh() ? PivotType.HIGH : PivotType.LOW)
                    .structureLabel(StructureLabel.FIRST)
                    .timestamp(zp.getTimestamp())
                    .price(zp.getValue())
                    .atrAtPivot(zp.getAtrAtPivot())
                    .retracementPct(zp.getRetracementPct())
                    .extensionPct(zp.getExtensionPct())
                    .isStructureBreak(false)
                    .build();

            if (zp.isHigh()) highs.add(mp);
            else lows.add(mp);
            allLabelled.add(mp);
        }

        // Label highs: HH / LH
        labelHighs(highs);
        // Label lows: HL / LL
        labelLows(lows);

        // Detect BOS and CHoCH events (modifies labels on the relevant points)
        detectStructureBreaks(allLabelled);

        // Determine overall trend
        MarketStructureData.TrendDirection trend = deriveTrend(highs, lows);
        int strength = computeTrendStrength(highs, lows, trend);

        // Key levels
        Double lastHigh = highs.isEmpty() ? null : highs.get(highs.size() - 1).getPrice();
        Double lastLow = lows.isEmpty() ? null : lows.get(lows.size() - 1).getPrice();
        Double prevHigh = highs.size() > 1 ? highs.get(highs.size() - 2).getPrice() : null;
        Double prevLow = lows.size() > 1 ? lows.get(lows.size() - 2).getPrice() : null;

        boolean lastBOS = !allLabelled.isEmpty() &&
                (allLabelled.get(allLabelled.size() - 1).getStructureLabel() == StructureLabel.BOS_HIGH ||
                 allLabelled.get(allLabelled.size() - 1).getStructureLabel() == StructureLabel.BOS_LOW);
        boolean lastCHoCH = !allLabelled.isEmpty() &&
                (allLabelled.get(allLabelled.size() - 1).getStructureLabel() == StructureLabel.CHOCH_HIGH ||
                 allLabelled.get(allLabelled.size() - 1).getStructureLabel() == StructureLabel.CHOCH_LOW);

        return MarketStructureData.builder()
                .timeframe(timeframe)
                .swingPoints(allLabelled)
                .trendDirection(trend)
                .trendStrength(strength)
                .lastSwingHigh(lastHigh)
                .lastSwingLow(lastLow)
                .prevSwingHigh(prevHigh)
                .prevSwingLow(prevLow)
                .lastEventWasBOS(lastBOS)
                .lastEventWasCHoCH(lastCHoCH)
                .build();
    }

    // ─── Internal helpers ────────────────────────────────────────────────────

    private void labelHighs(List<MarketStructurePoint> highs) {
        for (int i = 1; i < highs.size(); i++) {
            MarketStructurePoint prev = highs.get(i - 1);
            MarketStructurePoint curr = highs.get(i);
            curr.setStructureLabel(curr.getPrice() > prev.getPrice() ? StructureLabel.HH : StructureLabel.LH);
        }
    }

    private void labelLows(List<MarketStructurePoint> lows) {
        for (int i = 1; i < lows.size(); i++) {
            MarketStructurePoint prev = lows.get(i - 1);
            MarketStructurePoint curr = lows.get(i);
            curr.setStructureLabel(curr.getPrice() > prev.getPrice() ? StructureLabel.HL : StructureLabel.LL);
        }
    }

    /**
     * Detect BOS and CHoCH events by scanning the merged chronological sequence.
     *
     * BOS_HIGH: price (a HIGH pivot) exceeds the previous swing high while trend was DOWN
     *           → clean break with likely momentum continuation
     * BOS_LOW:  price (a LOW pivot) breaks below the previous swing low while trend was UP
     *
     * CHoCH_HIGH: first HH in a downtrend — first sign of potential reversal
     * CHoCH_LOW:  first LL in an uptrend  — first sign of potential reversal
     */
    private void detectStructureBreaks(List<MarketStructurePoint> all) {
        // Track rolling trend from left to right
        String rollingTrend = "UNDEFINED";
        boolean firstHHInDowntrend = false;
        boolean firstLLInUptrend = false;

        for (MarketStructurePoint mp : all) {
            StructureLabel lbl = mp.getStructureLabel();

            if (lbl == StructureLabel.HH) {
                if ("DOWNTREND".equals(rollingTrend)) {
                    // First HH after a downtrend = CHoCH
                    if (!firstHHInDowntrend) {
                        mp.setStructureLabel(StructureLabel.CHOCH_HIGH);
                        mp.setStructureBreak(true);
                        firstHHInDowntrend = true;
                    } else {
                        // Second HH in former downtrend = BOS
                        mp.setStructureLabel(StructureLabel.BOS_HIGH);
                        mp.setStructureBreak(true);
                        rollingTrend = "UPTREND";
                        firstHHInDowntrend = false;
                    }
                } else {
                    rollingTrend = "UPTREND";
                    firstLLInUptrend = false;
                }
            } else if (lbl == StructureLabel.LL) {
                if ("UPTREND".equals(rollingTrend)) {
                    if (!firstLLInUptrend) {
                        mp.setStructureLabel(StructureLabel.CHOCH_LOW);
                        mp.setStructureBreak(true);
                        firstLLInUptrend = true;
                    } else {
                        mp.setStructureLabel(StructureLabel.BOS_LOW);
                        mp.setStructureBreak(true);
                        rollingTrend = "DOWNTREND";
                        firstLLInUptrend = false;
                    }
                } else {
                    rollingTrend = "DOWNTREND";
                    firstHHInDowntrend = false;
                }
            } else if (lbl == StructureLabel.HL) {
                rollingTrend = "UPTREND";
                firstLLInUptrend = false;
            } else if (lbl == StructureLabel.LH) {
                rollingTrend = "DOWNTREND";
                firstHHInDowntrend = false;
            }
        }
    }

    private MarketStructureData.TrendDirection deriveTrend(
            List<MarketStructurePoint> highs, List<MarketStructurePoint> lows) {

        if (highs.size() < 2 || lows.size() < 2) {
            return MarketStructureData.TrendDirection.UNDEFINED;
        }

        MarketStructurePoint lastHigh = highs.get(highs.size() - 1);
        MarketStructurePoint lastLow = lows.get(lows.size() - 1);
        MarketStructurePoint prevHigh = highs.get(highs.size() - 2);
        MarketStructurePoint prevLow = lows.get(lows.size() - 2);

        boolean higherHigh = lastHigh.getPrice() > prevHigh.getPrice();
        boolean higherLow = lastLow.getPrice() > prevLow.getPrice();
        boolean lowerHigh = lastHigh.getPrice() < prevHigh.getPrice();
        boolean lowerLow = lastLow.getPrice() < prevLow.getPrice();

        if (higherHigh && higherLow) return MarketStructureData.TrendDirection.UPTREND;
        if (lowerHigh && lowerLow) return MarketStructureData.TrendDirection.DOWNTREND;
        return MarketStructureData.TrendDirection.RANGING;
    }

    private int computeTrendStrength(
            List<MarketStructurePoint> highs, List<MarketStructurePoint> lows,
            MarketStructureData.TrendDirection trend) {

        if (trend == MarketStructureData.TrendDirection.UNDEFINED ||
            trend == MarketStructureData.TrendDirection.RANGING) return 0;

        int count = 0;
        if (trend == MarketStructureData.TrendDirection.UPTREND) {
            for (int i = highs.size() - 1; i >= 1; i--) {
                if (highs.get(i).getPrice() > highs.get(i - 1).getPrice()) count++;
                else break;
            }
        } else {
            for (int i = highs.size() - 1; i >= 1; i--) {
                if (highs.get(i).getPrice() < highs.get(i - 1).getPrice()) count++;
                else break;
            }
        }
        return count;
    }
}
