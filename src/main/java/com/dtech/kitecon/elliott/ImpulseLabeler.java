package com.dtech.kitecon.elliott;

import com.dtech.chartpattern.zigzag.ZigZagPoint;
import com.dtech.kitecon.service.copilot.dto.MarketStructurePoint;
import com.dtech.kitecon.service.copilot.dto.MarketStructurePoint.StructureLabel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.ta4j.core.Bar;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.time.Instant;

/**
 * Labels ZigZag pivots as wave3_start, wave5_start, or no_impulse.
 *
 * Wave counting uses TREND SEGMENTS, not individual legs:
 *
 * Wave 1: Starts at CHoCH (trend flip). Measured as the trend segment
 *          from CHoCH until first significant pullback.
 *
 * Wave 2: Complex correction after wave 1. Can include nested patterns,
 *          deep retraces (61%+), and even internal CHoCH — as long as
 *          the wave 1 origin level is NOT broken. Wave 2 ends when price
 *          breaks beyond wave 1 territory with shallow retraces.
 *          Must retrace 50-99% of wave 1.
 *
 * Wave 3: Full trend segment from wave 2 end until the next trend reversal.
 *          Multi-leg — any pullback <50% of prior leg is still part of wave 3.
 *          Must be >= 1.618x wave 1 size. Max internal retrace 23.6%.
 */
@Slf4j
@Service
public class ImpulseLabeler implements ImpulseLabelStrategy {

    private double wave2RetraceMin = 50.0;
    private double wave2RetraceMax = 99.0;
    private double wave3MinRatio = 1.61;
    private double wave3MaxInternalRetrace = 50.0;
    private double wave4RetraceMin = 23.6;
    private double wave4RetraceMax = 50.0;
    private double wave5MinRatio = 0.618;

    public record LabeledPivot(
        ZigZagPoint pivot,
        String label,        // "wave3_start", "wave5_start", "no_impulse"
        int direction,       // 1 = bullish, -1 = bearish
        String detail
    ) {}

    /**
     * Enrich pivots with leg metrics.
     */
    public void enrichPivots(List<ZigZagPoint> pivots, Map<Instant, Integer> tsToIdx) {
        for (int i = 1; i < pivots.size(); i++) {
            ZigZagPoint prev = pivots.get(i - 1);
            ZigZagPoint curr = pivots.get(i);
            double sizePct = Math.abs(curr.getValue() - prev.getValue()) / prev.getValue() * 100.0;
            curr.setLegSizePct(sizePct);
            Integer prevIdx = tsToIdx.get(prev.getTimestamp());
            Integer currIdx = tsToIdx.get(curr.getTimestamp());
            if (prevIdx != null && currIdx != null) {
                double duration = Math.abs(currIdx - prevIdx);
                curr.setLegDurationBars(duration);
                curr.setLegSpeed(duration > 0 ? sizePct / duration : 0);
            }
        }
        if (!pivots.isEmpty()) {
            pivots.get(0).setLegSizePct(0.0);
            pivots.get(0).setLegDurationBars(0.0);
            pivots.get(0).setLegSpeed(0.0);
        }
    }

    /**
     * Label pivots using trend-segment based wave counting.
     */
    public List<LabeledPivot> label(List<ZigZagPoint> pivots, List<Bar> bars,
                                     Map<Instant, Integer> tsToIdx,
                                     List<MarketStructurePoint> structurePoints) {
        List<LabeledPivot> results = new ArrayList<>(pivots.size());
        for (ZigZagPoint p : pivots) {
            results.add(new LabeledPivot(p, "no_impulse", 0, ""));
        }

        // Find CHoCH events — these are potential wave 1 starts
        List<Integer> chochIndices = findChochPivotIndices(pivots, structurePoints);
        log.info("[Labeler] Found {} CHoCH events in {} pivots", chochIndices.size(), pivots.size());

        int wave3Found = 0, wave5Found = 0;
        int[] failCounts = new int[5]; // 0=wave2retrace, 1=wave3ratio, 2=wave3dir, 3=internalRetrace, 4=wave4

        // ── Extreme-based wave measurement ──
        // Start from each CHoCH (wave 1 start), find the price extreme (wave 1 end),
        // then scan forward for the deepest correction within 50-99% retrace
        // (skipping any intermediate CHoCH events — they're noise within the correction),
        // then measure wave 3 as the full move from correction low until trend truly reverses.
        for (int ci = 0; ci < chochIndices.size(); ci++) {
            int chochIdx = chochIndices.get(ci);
            boolean bullish = isBullishChoch(pivots.get(chochIdx), structurePoints);

            // ── Wave 1: from CHoCH, find the extreme in the trend direction ──
            // Scan ALL pivots forward until we hit a >50% retrace of the move from CHoCH
            double w1Start = pivots.get(chochIdx).getValue();
            double w1Extreme = w1Start;
            int w1ExtremeIdx = chochIdx;

            for (int i = chochIdx + 1; i < pivots.size(); i++) {
                double p = pivots.get(i).getValue();
                if (bullish && p > w1Extreme) { w1Extreme = p; w1ExtremeIdx = i; }
                if (!bullish && p < w1Extreme) { w1Extreme = p; w1ExtremeIdx = i; }

                // Check if current price retraces >50% of the move so far
                double totalMove = Math.abs(w1Extreme - w1Start);
                double retrace = bullish ? (w1Extreme - p) : (p - w1Extreme);
                if (totalMove > 0 && retrace / totalMove > 0.50) break;
            }
            double wave1Size = Math.abs(w1Extreme - w1Start);
            if (wave1Size == 0) continue;

            // ── Wave 2: from wave1 extreme, find the deepest correction point ──
            // Scan forward until price breaks beyond wave1 extreme (wave 3 starts)
            // or retrace exceeds 100% (invalidation). Can span multiple CHoCH events.
            double w2Deepest = w1Extreme;
            int w2DeepestIdx = w1ExtremeIdx;
            boolean w2Invalid = false;

            for (int i = w1ExtremeIdx + 1; i < pivots.size(); i++) {
                double p = pivots.get(i).getValue();

                // Track deepest correction point
                if (bullish && p < w2Deepest) { w2Deepest = p; w2DeepestIdx = i; }
                if (!bullish && p > w2Deepest) { w2Deepest = p; w2DeepestIdx = i; }

                // Check invalidation (retrace > 100% of wave 1)
                double retrace = Math.abs(w2Deepest - w1Extreme);
                if (retrace > wave1Size) { w2Invalid = true; break; }

                // Wave 2 ends when price breaks beyond wave 1 extreme
                if (bullish && p > w1Extreme) break;
                if (!bullish && p < w1Extreme) break;
            }
            if (w2Invalid) continue;
            if (w2DeepestIdx == w1ExtremeIdx) continue;

            double wave2Size = Math.abs(w2Deepest - w1Extreme);
            double wave2Retrace = wave2Size / wave1Size * 100.0;

            if (wave2Retrace < wave2RetraceMin || wave2Retrace > wave2RetraceMax) {
                failCounts[0]++;
                log.info("[W2] CHoCH@{}: W1={}→{} ({}%) W2ret={}% — outside {}-{}%",
                        pivots.get(chochIdx).getTimestamp(), w1Start, w1Extreme,
                        String.format("%.1f", wave1Size/w1Start*100),
                        String.format("%.1f", wave2Retrace), wave2RetraceMin, wave2RetraceMax);
                continue;
            }

            // ── Wave 3: from wave2 deepest point, measure the full move until trend reverses ──
            // Wave 3 ends when price retraces >50% of the wave 3 move from the correction point
            double w3Start = w2Deepest;
            double w3Extreme = w3Start;
            int w3ExtremeIdx = w2DeepestIdx;

            for (int i = w2DeepestIdx + 1; i < pivots.size(); i++) {
                double p = pivots.get(i).getValue();
                if (bullish && p > w3Extreme) { w3Extreme = p; w3ExtremeIdx = i; }
                if (!bullish && p < w3Extreme) { w3Extreme = p; w3ExtremeIdx = i; }

                double w3Move = Math.abs(w3Extreme - w3Start);
                double w3Retrace = bullish ? (w3Extreme - p) : (p - w3Extreme);
                if (w3Move > 0 && w3Retrace / w3Move > 0.50) break;
            }
            double wave3Size = Math.abs(w3Extreme - w3Start);

            // Wave 3 direction check
            if (bullish && w3Extreme <= w3Start) { failCounts[2]++; continue; }
            if (!bullish && w3Extreme >= w3Start) { failCounts[2]++; continue; }

            // Wave 3 ratio check
            double wave3Ratio = wave3Size / wave1Size;
            if (wave3Ratio < wave3MinRatio) {
                log.info("[W3 FAIL] CHoCH@{}: W1={} ({}→{}) W2ret={}% W3={} ratio={} (need {}x)",
                        pivots.get(chochIdx).getTimestamp(),
                        String.format("%.1f", wave1Size), w1Start, w1Extreme,
                        String.format("%.1f", wave2Retrace),
                        String.format("%.1f", wave3Size),
                        String.format("%.3f", wave3Ratio), wave3MinRatio);
                failCounts[1]++;
                continue;
            }

            // Wave 3 internal retracement check
            if (!checkInternalRetracement(pivots.get(w2DeepestIdx), pivots.get(w3ExtremeIdx),
                    bars, tsToIdx, bullish, wave3MaxInternalRetrace)) {
                failCounts[3]++;
                log.info("[W3 INTERN] CHoCH@{}: W3 ratio={} PASSED but internal retrace > {}%",
                        pivots.get(chochIdx).getTimestamp(), String.format("%.3f", wave3Ratio), wave3MaxInternalRetrace);
                continue;
            }

            // ── WAVE 3 START CONFIRMED ──
            int wave3StartPivotIdx = w2DeepestIdx;
            int direction = bullish ? 1 : -1;
            String detail = String.format(
                    "W1: %.2f→%.2f (%.1f%%) | W2: %.2f (ret %.1f%%) | W3: %.2f→%.2f (%.2fx W1)",
                    w1Start, w1Extreme, wave1Size / w1Start * 100 * direction,
                    w2Deepest, wave2Retrace,
                    w3Start, w3Extreme, wave3Ratio);

            results.set(wave3StartPivotIdx,
                    new LabeledPivot(pivots.get(wave3StartPivotIdx), "wave3_start", direction, detail));
            wave3Found++;

            // ── Check for Wave 5 ──
            Wave wave1W = new Wave(chochIdx, w1ExtremeIdx, w1Start, w1Extreme, wave1Size);
            Wave wave3W = new Wave(w2DeepestIdx, w3ExtremeIdx, w3Start, w3Extreme, wave3Size);
            if (w3ExtremeIdx + 1 < pivots.size()) {
                LabeledPivot w5 = checkWave5(pivots, wave3W, wave1W, bullish, bars, tsToIdx);
                if (w5 != null) {
                    // Find the pivot index for wave 5 start
                    for (int p = 0; p < pivots.size(); p++) {
                        if (pivots.get(p).getTimestamp().equals(w5.pivot().getTimestamp())) {
                            results.set(p, w5);
                            wave5Found++;
                            break;
                        }
                    }
                }
            }
        }

        log.info("[Labeler] Results: wave3={}, wave5={}, no_impulse={} | Failures: w2Retrace={}, w3Ratio={}, w3Dir={}, internalRet={}, w4={}",
                wave3Found, wave5Found, pivots.size() - wave3Found - wave5Found,
                failCounts[0], failCounts[1], failCounts[2], failCounts[3], failCounts[4]);

        return results;
    }

    // ── Wave measurement helpers ──

    private record Wave(int startIdx, int endIdx, double startPrice, double endPrice, double size) {}

    /**
     * Measure wave 1: from CHoCH pivot, extend forward through pivots
     * that continue the trend. Wave 1 ends at the first significant correction
     * (>50% retrace of the move so far).
     */
    private Wave measureWave1(List<ZigZagPoint> pivots, int chochIdx, boolean bullish) {
        double startPrice = pivots.get(chochIdx).getValue();
        double extreme = startPrice;
        int extremeIdx = chochIdx;

        for (int i = chochIdx + 1; i < pivots.size(); i++) {
            double price = pivots.get(i).getValue();

            if (bullish) {
                if (price > extreme) {
                    extreme = price;
                    extremeIdx = i;
                } else {
                    // Pullback — check if it's significant (>50% retrace)
                    double moveSize = extreme - startPrice;
                    if (moveSize > 0) {
                        double pullback = extreme - price;
                        double retracePct = pullback / moveSize * 100.0;
                        if (retracePct > 50.0) {
                            // Wave 1 ends at the extreme before this pullback
                            break;
                        }
                    }
                }
            } else {
                if (price < extreme) {
                    extreme = price;
                    extremeIdx = i;
                } else {
                    double moveSize = startPrice - extreme;
                    if (moveSize > 0) {
                        double pullback = price - extreme;
                        double retracePct = pullback / moveSize * 100.0;
                        if (retracePct > 50.0) {
                            break;
                        }
                    }
                }
            }
        }

        if (extremeIdx == chochIdx) {
            log.info("[W1] CHoCH@idx={} price={}: no move after CHoCH", chochIdx, startPrice);
            return null;
        }
        double size = Math.abs(extreme - startPrice);
        if (size == 0) return null;

        log.info("[W1] CHoCH@idx={}: {} {}→{} size={} ({}%) extremeIdx={}",
                chochIdx, bullish?"BULL":"BEAR", startPrice, extreme, size,
                String.format("%.1f", size/startPrice*100), extremeIdx);
        return new Wave(chochIdx, extremeIdx, startPrice, extreme, size);
    }

    /**
     * Find wave 2: complex correction after wave 1.
     * Wave 2 continues as long as:
     *   - Price doesn't break the wave 1 origin level in wave 1's direction
     *   - Deep retraces or messy structure
     * Wave 2 ends when price breaks beyond wave 1 end (extreme) OR
     * when we find a pivot that breaks the wave 1 territory with shallow retraces.
     */
    private Wave findWave2(List<ZigZagPoint> pivots, Wave wave1, boolean bullish) {
        double wave1Origin = wave1.startPrice;
        double wave1Extreme = wave1.endPrice;
        double deepest = wave1Extreme; // tracks deepest correction point
        int deepestIdx = wave1.endIdx;

        for (int i = wave1.endIdx + 1; i < pivots.size(); i++) {
            double price = pivots.get(i).getValue();

            if (bullish) {
                // Wave 2 is a pullback from wave 1 high
                if (price < deepest) {
                    deepest = price;
                    deepestIdx = i;
                }
                // If wave 2 retraces > 100% of wave 1 — invalidation, not a correction
                double currentRetrace = (wave1Extreme - price) / (wave1Extreme - wave1Origin);
                if (currentRetrace > 1.0) {
                    log.info("[W2] INVALID: retrace {}% > 100% at idx={}", String.format("%.1f", currentRetrace*100), i);
                    return null;
                }
                // If price breaks above wave 1 extreme — wave 2 is over
                if (price > wave1Extreme && i > wave1.endIdx + 1) {
                    // Wave 2 ended at the deepest point before this breakout
                    break;
                }
            } else {
                if (price > deepest) {
                    deepest = price;
                    deepestIdx = i;
                }
                // If wave 2 retraces > 100% of wave 1 — invalidation
                double currentRetrace = (price - wave1Extreme) / (wave1Origin - wave1Extreme);
                if (currentRetrace > 1.0) {
                    log.info("[W2] INVALID: retrace {}% > 100% at idx={}", String.format("%.1f", currentRetrace*100), i);
                    return null;
                }
                if (price < wave1Extreme && i > wave1.endIdx + 1) {
                    break;
                }
            }
        }

        if (deepestIdx == wave1.endIdx) {
            log.info("[W2] FAIL: no correction found after W1 end idx={}", wave1.endIdx);
            return null;
        }
        double size = Math.abs(deepest - wave1Extreme);
        double retPct = wave1.size > 0 ? size / wave1.size * 100 : 0;
        log.info("[W2] Found: {}→{} size={} retrace={}% deepestIdx={}", wave1Extreme, deepest, size,
                String.format("%.1f", retPct), deepestIdx);
        return new Wave(wave1.endIdx, deepestIdx, wave1Extreme, deepest, size);
    }

    /**
     * Measure a trend segment from a given pivot index.
     * The segment includes all consecutive legs in the trend direction.
     * A pullback < 50% of the prior leg is still part of the segment.
     * The segment ends at a reversal (pullback > 50% or opposite direction confirmed).
     */
    private Wave measureTrendSegment(List<ZigZagPoint> pivots, int startIdx, boolean bullish) {
        if (startIdx >= pivots.size()) return null;

        double startPrice = pivots.get(startIdx).getValue();
        double extreme = startPrice;
        int extremeIdx = startIdx;
        double lastLegSize = 0;

        for (int i = startIdx + 1; i < pivots.size(); i++) {
            double price = pivots.get(i).getValue();

            if (bullish) {
                if (price > extreme) {
                    extreme = price;
                    extremeIdx = i;
                } else {
                    // Pullback — only end segment if retrace > 50% of TOTAL move from start
                    double totalMove = extreme - startPrice;
                    double pullback = extreme - price;
                    if (totalMove > 0 && pullback / totalMove > 0.50) {
                        break;
                    }
                }
            } else {
                if (price < extreme) {
                    extreme = price;
                    extremeIdx = i;
                } else {
                    double totalMove = startPrice - extreme;
                    double pullback = price - extreme;
                    if (totalMove > 0 && pullback / totalMove > 0.50) {
                        break;
                    }
                }
            }
        }

        if (extremeIdx == startIdx) return null;
        double size = Math.abs(extreme - startPrice);
        return new Wave(startIdx, extremeIdx, startPrice, extreme, size);
    }

    /**
     * Check wave 5 after a confirmed wave 3.
     */
    private LabeledPivot checkWave5(List<ZigZagPoint> pivots, Wave wave3, Wave wave1,
                                     boolean bullish, List<Bar> bars, Map<Instant, Integer> tsToIdx) {
        // Find wave 4: correction after wave 3
        int w4SearchStart = wave3.endIdx + 1;
        if (w4SearchStart >= pivots.size()) return null;

        double w3Extreme = wave3.endPrice;
        double deepest = w3Extreme;
        int deepestIdx = wave3.endIdx;

        for (int i = w4SearchStart; i < pivots.size(); i++) {
            double price = pivots.get(i).getValue();
            if (bullish) {
                if (price < deepest) { deepest = price; deepestIdx = i; }
                if (price > w3Extreme) break; // wave 5 starting
            } else {
                if (price > deepest) { deepest = price; deepestIdx = i; }
                if (price < w3Extreme) break;
            }
        }

        if (deepestIdx == wave3.endIdx) return null;

        // Wave 4 retracement check
        double wave4Size = Math.abs(deepest - w3Extreme);
        double wave4Retrace = wave4Size / wave3.size * 100.0;
        if (wave4Retrace < wave4RetraceMin || wave4Retrace > wave4RetraceMax) return null;

        // Wave 4 must not overlap wave 1 territory
        double wave1End = wave1.endPrice;
        if (bullish && deepest < wave1End) return null;
        if (!bullish && deepest > wave1End) return null;

        // Measure wave 5
        Wave wave5 = measureTrendSegment(pivots, deepestIdx, bullish);
        if (wave5 == null) return null;

        double wave5Ratio = wave5.size / wave3.size;
        if (wave5Ratio < wave5MinRatio) return null;

        // Direction check
        if (bullish && wave5.endPrice <= wave5.startPrice) return null;
        if (!bullish && wave5.endPrice >= wave5.startPrice) return null;

        int direction = bullish ? 1 : -1;
        String detail = String.format("W3 size: %.1f%% | W4 ret: %.1f%% | W5: %.2fx W3",
                wave3.size / wave3.startPrice * 100 * direction, wave4Retrace, wave5Ratio);

        return new LabeledPivot(pivots.get(deepestIdx), "wave5_start", direction, detail);
    }

    // ── Structure helpers ──

    /**
     * Find pivot indices that correspond to CHoCH events.
     */
    private List<Integer> findChochPivotIndices(List<ZigZagPoint> pivots,
                                                 List<MarketStructurePoint> structurePoints) {
        List<Integer> result = new ArrayList<>();
        for (MarketStructurePoint sp : structurePoints) {
            if (sp.getStructureLabel() == StructureLabel.CHOCH_HIGH ||
                sp.getStructureLabel() == StructureLabel.CHOCH_LOW) {
                // Find matching pivot by timestamp
                for (int i = 0; i < pivots.size(); i++) {
                    if (pivots.get(i).getTimestamp().equals(sp.getTimestamp())) {
                        result.add(i);
                        break;
                    }
                }
            }
        }
        return result;
    }

    /**
     * Determine if a CHoCH is bullish (CHOCH_HIGH = first HH in downtrend = bullish flip).
     */
    private boolean isBullishChoch(ZigZagPoint pivot, List<MarketStructurePoint> structurePoints) {
        for (MarketStructurePoint sp : structurePoints) {
            if (sp.getTimestamp().equals(pivot.getTimestamp())) {
                return sp.getStructureLabel() == StructureLabel.CHOCH_HIGH;
            }
        }
        return false;
    }

    /**
     * Check internal retracement within a wave using bar-level data.
     */
    private boolean checkInternalRetracement(ZigZagPoint start, ZigZagPoint end,
                                              List<Bar> bars, Map<Instant, Integer> tsToIdx,
                                              boolean bullish, double maxRetracePct) {
        Integer startIdx = tsToIdx.get(start.getTimestamp());
        Integer endIdx = tsToIdx.get(end.getTimestamp());
        if (startIdx == null || endIdx == null) return true;

        double extreme = start.getValue();
        for (int k = startIdx + 1; k <= Math.min(endIdx, bars.size() - 1); k++) {
            Bar bar = bars.get(k);
            if (bullish) {
                double high = bar.getHighPrice().doubleValue();
                if (high > extreme) extreme = high;
                double low = bar.getLowPrice().doubleValue();
                if (extreme > start.getValue()) {
                    double retrace = (extreme - low) / (extreme - start.getValue()) * 100.0;
                    if (retrace > maxRetracePct) return false;
                }
            } else {
                double low = bar.getLowPrice().doubleValue();
                if (low < extreme) extreme = low;
                double high = bar.getHighPrice().doubleValue();
                if (start.getValue() > extreme) {
                    double retrace = (high - extreme) / (start.getValue() - extreme) * 100.0;
                    if (retrace > maxRetracePct) return false;
                }
            }
        }
        return true;
    }

    @Override
    public String name() {
        return "pivot-scan";
    }
}
