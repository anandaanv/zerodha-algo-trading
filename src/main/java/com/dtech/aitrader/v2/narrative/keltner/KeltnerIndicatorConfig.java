package com.dtech.aitrader.v2.narrative.keltner;

import com.dtech.aitrader.v2.narrative.beat.*;
import com.dtech.aitrader.v2.narrative.engine.*;
import com.dtech.aitrader.v2.narrative.pivot.SeriesPivot;
import com.dtech.aitrader.v2.narrative.support.PriceContextBuilder;
import com.dtech.chartdata.model.OhlcBarDTO;
import com.dtech.chartpattern.zigzag.ZigZagPoint;
import lombok.RequiredArgsConstructor;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Keltner Channels config. REGIME_EPISODE tier per Narrative Core 533b3e85. Mirrors Bollinger
 * (delta 0c1c601e) in shape but ATR-based instead of stdev-based — same squeeze-then-walk story.
 * EQUIV_CLASS_005 (Volatility-Compression-Squeeze) — downstream de-dups vs Bollinger.
 *
 * <p>Owner guidance (b3ff4ca0): "Keltner — volatility-channel episodes (like Bollinger but
 * ATR-based). Squeeze + channel-walk. In squeeze equivalence class EQUIV_CLASS_005 (de-dup vs
 * Bollinger downstream — they observe the same compression; Bollinger=stdev, Keltner=ATR,
 * NOT interchangeable but same class)."
 */
@RequiredArgsConstructor
public class KeltnerIndicatorConfig implements IndicatorConfig {

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final KeltnerNarrativeParams params;

    @Override
    public String getIndicatorName() {
        return "Keltner";
    }

    @Override
    public NarrativeTier getNarrativeTier() {
        return NarrativeTier.REGIME_EPISODE;
    }

    @Override
    public EngineParams getEngineParams() {
        return EngineParams.builder()
                .defaultPivotParams(params.getPivotParams())
                .presentWindowBars(params.getPresentWindowBars())
                .recentWindowBars(params.getRecentWindowBars())
                .regimeChangePersistenceBars(params.getBandWalkMinPersistenceBars())
                .historyPeakedCap(0)
                .historyTroughedCap(0)
                .historyRegimeCap(3)
                .recentPeakedCap(0)
                .recentTroughedCap(0)
                .recentThrustCap(0)
                .historyZoneCap(4)
                .failedAttemptMinBars(0)
                .build();
    }

    @Override
    public IndicatorSeries compute(List<OhlcBarDTO> bars, String symbol, String timeframe) {
        return KeltnerComputer.compute(bars, params.getPeriod(), params.getAtrPeriod(),
                params.getAtrMult(), params.getAdxPeriod(), symbol, timeframe);
    }

    @Override
    public List<PivotComponentSpec> getPivotComponents() {
        return Collections.emptyList();
    }

    @Override
    public List<CrossoverSpec> getCrossovers() {
        return Collections.emptyList();
    }

    @Override
    public Optional<DivergenceSpec> getDivergence() {
        return Optional.empty();
    }

    @Override
    public List<ZoneSpec> getZones() {
        return Collections.emptyList();
    }

    @Override
    public List<Beat> emitCustomBeats(IndicatorSeries series, List<OhlcBarDTO> bars,
                                       List<ZigZagPoint> pricePivots,
                                       List<SwingState> swingStates,
                                       Map<IndicatorComponent, List<SeriesPivot>> pivotsByComponent) {
        KeltnerSeries ks = (KeltnerSeries) series;
        double[] width = ks.getWidth();
        double[] closes = ks.getCloses();
        double[] upper = ks.getUpper();
        double[] lower = ks.getLower();
        double[] adx = ks.getAdx();
        int n = width.length;
        List<Beat> beats = new ArrayList<>();

        // 1. Squeeze episodes — width in bottom percentile of recent history.
        int win = params.getWidthPercentileWindow();
        double rank = params.getSqueezePctRank();
        boolean inSqueeze = false;
        int squeezeEntry = -1;
        for (int i = 0; i < n; i++) {
            if (i < win) continue;
            double[] window = Arrays.copyOfRange(width, i - win + 1, i + 1);
            double[] sorted = window.clone();
            Arrays.sort(sorted);
            int idx = Arrays.binarySearch(sorted, width[i]);
            if (idx < 0) idx = -idx - 1;
            double pctile = (double) idx / sorted.length;
            boolean isSqueeze = pctile <= rank;
            if (isSqueeze && !inSqueeze) {
                inSqueeze = true;
                squeezeEntry = i;
            } else if (!isSqueeze && inSqueeze) {
                int held = i - squeezeEntry;
                if (held >= params.getSqueezeMinPersistenceBars()) {
                    beats.add(buildZoneBeat(BeatVerb.ENTERED_ZONE, squeezeEntry, width[squeezeEntry], null,
                            "kc_squeeze_in_" + squeezeEntry,
                            String.format("Keltner squeeze entered — width=%.2f%% in bottom %.0f%% of last %d bars (EQUIV_CLASS_005)",
                                    width[squeezeEntry], rank * 100, win),
                            series, bars, pricePivots, swingStates));
                    beats.add(buildZoneBeat(BeatVerb.EXITED_ZONE, i, width[i], held,
                            "kc_squeeze_out_" + i,
                            String.format("Keltner squeeze released after %d bars — width expanded to %.2f%%",
                                    held, width[i]),
                            series, bars, pricePivots, swingStates));
                }
                inSqueeze = false;
            }
        }
        if (inSqueeze) {
            int held = n - squeezeEntry;
            beats.add(Beat.builder()
                    .what(BeatVerb.ENTERED_ZONE)
                    .component(IndicatorComponent.KELTNER)
                    .whenBar(squeezeEntry)
                    .whenDate(instantToDateString(series.getBarTimestamps()[squeezeEntry]))
                    .value(width[squeezeEntry])
                    .significance(1.0)
                    .persistedBars(held)
                    .consequence(Consequence.ONGOING)
                    .priceContext(PriceContextBuilder.buildAt(squeezeEntry, bars, pricePivots, swingStates))
                    .ref("kc_squeeze_in_" + squeezeEntry)
                    .note(String.format("Keltner squeeze ongoing (%d bars) — width=%.2f%% still compressed",
                            held, width[squeezeEntry]))
                    .build());
        }

        // 2. Band-tag events with ADX context.
        // 3. Band-walk runs — consecutive band-tags ≥ persistence.
        int upperRunStart = -1;
        int lowerRunStart = -1;
        for (int i = 0; i < n; i++) {
            boolean tagUpper = closes[i] >= upper[i];
            boolean tagLower = closes[i] <= lower[i];
            if (tagUpper || tagLower) {
                String dir = tagUpper ? "bullish" : "bearish";
                double a = adx[i];
                String regimeContext = a >= params.getBandWalkAdxThreshold()
                        ? String.format("ADX=%.1f → walk-favorable (continuation)", a)
                        : a <= params.getReversionAdxThreshold()
                        ? String.format("ADX=%.1f → reversion-favorable (countertrend)", a)
                        : String.format("ADX=%.1f transitional", a);
                beats.add(Beat.builder()
                        .what(BeatVerb.CROSSED)
                        .component(IndicatorComponent.KELTNER)
                        .whenBar(i)
                        .whenDate(instantToDateString(series.getBarTimestamps()[i]))
                        .value(closes[i])
                        .significance(0.7)
                        .consequence(Consequence.CONFIRMED)
                        .priceContext(PriceContextBuilder.buildAt(i, bars, pricePivots, swingStates))
                        .direction(dir)
                        .type(tagUpper ? "kc_upper_tag" : "kc_lower_tag")
                        .ref("kc_tag_" + (tagUpper ? "up" : "lo") + "_" + i)
                        .note(String.format(
                                "Keltner %s-band tag — close=%.2f. Context: %s. LLM picks walk vs reversion.",
                                tagUpper ? "upper" : "lower", closes[i], regimeContext))
                        .build());
            }
            if (tagUpper) {
                if (upperRunStart < 0) upperRunStart = i;
                if (lowerRunStart >= 0) lowerRunStart = -1;
            } else if (tagLower) {
                if (lowerRunStart < 0) lowerRunStart = i;
                if (upperRunStart >= 0) upperRunStart = -1;
            } else {
                if (upperRunStart >= 0) {
                    int runLen = i - upperRunStart;
                    if (runLen >= params.getBandWalkMinPersistenceBars()) {
                        beats.add(buildBandWalkBeat(true, upperRunStart, runLen, ks,
                                series, bars, pricePivots, swingStates));
                    }
                    upperRunStart = -1;
                }
                if (lowerRunStart >= 0) {
                    int runLen = i - lowerRunStart;
                    if (runLen >= params.getBandWalkMinPersistenceBars()) {
                        beats.add(buildBandWalkBeat(false, lowerRunStart, runLen, ks,
                                series, bars, pricePivots, swingStates));
                    }
                    lowerRunStart = -1;
                }
            }
        }
        if (upperRunStart >= 0) {
            int runLen = n - upperRunStart;
            if (runLen >= params.getBandWalkMinPersistenceBars())
                beats.add(buildBandWalkBeat(true, upperRunStart, runLen, ks,
                        series, bars, pricePivots, swingStates));
        }
        if (lowerRunStart >= 0) {
            int runLen = n - lowerRunStart;
            if (runLen >= params.getBandWalkMinPersistenceBars())
                beats.add(buildBandWalkBeat(false, lowerRunStart, runLen, ks,
                        series, bars, pricePivots, swingStates));
        }
        return beats;
    }

    private Beat buildZoneBeat(BeatVerb verb, int bar, double value, Integer persisted, String ref,
                                String note, IndicatorSeries series, List<OhlcBarDTO> bars,
                                List<ZigZagPoint> pricePivots, List<SwingState> swingStates) {
        return Beat.builder()
                .what(verb)
                .component(IndicatorComponent.KELTNER)
                .whenBar(bar)
                .whenDate(instantToDateString(series.getBarTimestamps()[bar]))
                .value(value)
                .significance(1.0)
                .persistedBars(persisted)
                .consequence(Consequence.CONFIRMED)
                .priceContext(PriceContextBuilder.buildAt(bar, bars, pricePivots, swingStates))
                .ref(ref)
                .note(note)
                .build();
    }

    private Beat buildBandWalkBeat(boolean bullish, int startBar, int runLen,
                                    KeltnerSeries ks, IndicatorSeries series, List<OhlcBarDTO> bars,
                                    List<ZigZagPoint> pricePivots, List<SwingState> swingStates) {
        double avgAdx = 0;
        int n = ks.length();
        for (int j = startBar; j < Math.min(n, startBar + runLen); j++) avgAdx += ks.getAdx()[j];
        avgAdx /= Math.max(1, runLen);
        return Beat.builder()
                .what(BeatVerb.REGIME_CHANGE)
                .component(IndicatorComponent.KELTNER)
                .whenBar(startBar)
                .whenDate(instantToDateString(series.getBarTimestamps()[startBar]))
                .value(ks.getCloses()[startBar])
                .significance(1.0)
                .persistedBars(runLen)
                .consequence(Consequence.CONFIRMED)
                .priceContext(PriceContextBuilder.buildAt(startBar, bars, pricePivots, swingStates))
                .direction(bullish ? "bullish" : "bearish")
                .type(bullish ? "kc_upper_walk" : "kc_lower_walk")
                .ref("kc_walk_" + (bullish ? "up" : "lo") + "_" + startBar)
                .note(String.format(
                        "Keltner sustained %s band-walk — price rode the %s band for %d bars; avg ADX=%.1f. %s",
                        bullish ? "upper" : "lower", bullish ? "upper" : "lower", runLen, avgAdx,
                        avgAdx >= params.getBandWalkAdxThreshold()
                                ? "Confirmed walk regime (continuation)."
                                : "Walk persisted but ADX below threshold — interpretation cautious."))
                .build();
    }

    @Override
    public Beat buildCurrentlyBeat(int lastIdx, IndicatorSeries series, List<OhlcBarDTO> bars) {
        KeltnerSeries ks = (KeltnerSeries) series;
        double w = ks.getWidth()[lastIdx];
        double c = ks.getCloses()[lastIdx];
        double u = ks.getUpper()[lastIdx];
        double l = ks.getLower()[lastIdx];
        double m = ks.getMiddle()[lastIdx];
        double a = ks.getAdx()[lastIdx];

        String squeezeState = "normal";
        if (lastIdx >= params.getWidthPercentileWindow()) {
            double[] window = Arrays.copyOfRange(ks.getWidth(),
                    lastIdx - params.getWidthPercentileWindow() + 1, lastIdx + 1);
            double[] sorted = window.clone();
            Arrays.sort(sorted);
            int idx = Arrays.binarySearch(sorted, w);
            if (idx < 0) idx = -idx - 1;
            double pctile = (double) idx / sorted.length;
            if (pctile <= params.getSqueezePctRank()) squeezeState = "compressed (squeeze)";
            else if (pctile >= 0.85) squeezeState = "expanded";
        }
        String bandPos = c >= u ? "above upper" : c <= l ? "below lower"
                : c >= m ? "upper half" : "lower half";
        String regime = a >= params.getBandWalkAdxThreshold()
                ? String.format("ADX=%.1f → walk-favorable", a)
                : a <= params.getReversionAdxThreshold()
                ? String.format("ADX=%.1f → reversion-favorable", a)
                : String.format("ADX=%.1f transitional", a);
        String note = String.format(
                "Keltner posture at last bar: width=%.2f%% (%s), close=%.2f vs upper=%.2f / mid=%.2f / lower=%.2f, position=%s. %s.",
                w, squeezeState, c, u, m, l, bandPos, regime);
        return Beat.builder()
                .what(BeatVerb.CURRENTLY)
                .component(IndicatorComponent.KELTNER)
                .whenBar(lastIdx)
                .whenDate(instantToDateString(series.getBarTimestamps()[lastIdx]))
                .value(c)
                .significance(1.0)
                .consequence(Consequence.ONGOING)
                .tier(Tier.PRESENT)
                .ref("kc_now_" + lastIdx)
                .note(note)
                .build();
    }

    @Override
    public List<Checkpoint> buildVerificationCheckpoints(IndicatorSeries series,
                                                         List<SeriesPivot> primaryPivots,
                                                         int lastIdx) {
        List<Checkpoint> cps = new ArrayList<>();
        cps.add(Checkpoint.builder().bar(lastIdx).build());
        return cps;
    }

    private static String instantToDateString(Instant instant) {
        LocalDateTime ldt = LocalDateTime.ofInstant(instant, ZoneId.of("Asia/Kolkata"));
        return ldt.format(DATE_FORMATTER);
    }
}
