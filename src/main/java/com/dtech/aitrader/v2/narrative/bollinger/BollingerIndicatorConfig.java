package com.dtech.aitrader.v2.narrative.bollinger;

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
 * Bollinger Bands config (delta memsys 0c1c601e). REGIME_EPISODE tier. The squeeze IS a genuine
 * episode (duration + how-tight matter — why Bollinger gets a narrative and Ichimoku doesn't).
 *
 * <p>CRITICAL per delta Section 5 (the governing principle made local): band-tags have OPPOSITE
 * meaning by regime — band-walk continuation in trending ADX, mean-reversion in low ADX. The
 * engine MUST emit the band-tag event WITH the current ADX value as context. The LLM judges.
 * The engine never hardcodes walk-vs-reversion.
 */
@RequiredArgsConstructor
public class BollingerIndicatorConfig implements IndicatorConfig {

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final BollingerNarrativeParams params;

    @Override
    public String getIndicatorName() {
        return "Bollinger";
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
                .historyPeakedCap(2)
                .historyTroughedCap(2)
                .historyRegimeCap(4)
                .recentPeakedCap(3)
                .recentTroughedCap(3)
                .recentThrustCap(0)
                .historyZoneCap(4)  // squeeze episodes are MEDIUM decay — keep top-4 in history
                .failedAttemptMinBars(0)
                .build();
    }

    @Override
    public IndicatorSeries compute(List<OhlcBarDTO> bars, String symbol, String timeframe) {
        return BollingerComputer.compute(bars, params.getPeriod(), params.getStdevMult(),
                params.getAdxPeriod(), symbol, timeframe);
    }

    @Override
    public List<PivotComponentSpec> getPivotComponents() {
        // BBW peak/trough — vol-cycle phase markers.
        return List.of(PivotComponentSpec.builder()
                .component(IndicatorComponent.BB_WIDTH)
                .verb(BeatVerb.PEAKED)
                .significanceParams(params.getPivotParams())
                .refPrefix(null)
                .labelPrefix("BBW")
                .build());
    }

    @Override
    public List<CrossoverSpec> getCrossovers() {
        // No simple-cross spec; band-tag events emitted in custom-beats with ADX context.
        return Collections.emptyList();
    }

    @Override
    public Optional<DivergenceSpec> getDivergence() {
        return Optional.empty(); // no price-divergence on the bands themselves
    }

    @Override
    public List<ZoneSpec> getZones() {
        // Squeeze episode is RELATIVE to BBW history, not an absolute threshold — handled in
        // custom-beats. Empty here.
        return Collections.emptyList();
    }

    /**
     * Custom beats:
     * <ol>
     *   <li>Squeeze episodes (entered_zone/exited_zone on BBW relative percentile).</li>
     *   <li>Band-tag events with ADX-context note (engine emits, LLM judges walk-vs-reversion).</li>
     *   <li>Sustained band-walk regime_change when consecutive band-tags persist.</li>
     * </ol>
     */
    @Override
    public List<Beat> emitCustomBeats(IndicatorSeries series, List<OhlcBarDTO> bars,
                                       List<ZigZagPoint> pricePivots,
                                       List<com.dtech.aitrader.v2.narrative.beat.SwingState> swingStates,
                                       Map<IndicatorComponent, List<SeriesPivot>> pivotsByComponent) {
        BollingerSeries bb = (BollingerSeries) series;
        double[] width = bb.getWidth();
        double[] closes = bb.getCloses();
        double[] upper = bb.getUpper();
        double[] lower = bb.getLower();
        double[] adx = bb.getAdx();
        int n = width.length;
        List<Beat> beats = new ArrayList<>();

        // Step 1: squeeze episodes — BBW in bottom percentile of its own recent history.
        int win = params.getBbwPercentileWindow();
        double rank = params.getSqueezePctRank();
        boolean inSqueeze = false;
        int squeezeEntry = -1;
        for (int i = 0; i < n; i++) {
            if (i < win) continue;
            // Percentile of width[i] within last `win` bars.
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
                    String enterNote = String.format(
                            "Squeeze entered — BBW=%.2f%% in bottom %.0f%% of its %d-bar history (PATTERN_064 volatility compression)",
                            width[squeezeEntry], rank * 100, win);
                    String exitNote = String.format(
                            "Squeeze released after %d bars — BBW expanded to %.2f%% (volatility cycle phase 2: release)",
                            held, width[i]);
                    beats.add(buildZoneBeat(BeatVerb.ENTERED_ZONE, squeezeEntry, width[squeezeEntry], null,
                            "bb_squeeze_in_" + squeezeEntry, enterNote, series, bars, pricePivots, swingStates));
                    beats.add(buildZoneBeat(BeatVerb.EXITED_ZONE, i, width[i], held,
                            "bb_squeeze_out_" + i, exitNote, series, bars, pricePivots, swingStates));
                }
                inSqueeze = false;
            }
        }
        if (inSqueeze) {
            int held = n - squeezeEntry;
            beats.add(Beat.builder()
                    .what(BeatVerb.ENTERED_ZONE)
                    .component(IndicatorComponent.BOLLINGER)
                    .whenBar(squeezeEntry)
                    .whenDate(instantToDateString(series.getBarTimestamps()[squeezeEntry]))
                    .value(width[squeezeEntry])
                    .significance(1.0)
                    .persistedBars(held)
                    .consequence(Consequence.ONGOING)
                    .priceContext(PriceContextBuilder.buildAt(squeezeEntry, bars, pricePivots, swingStates))
                    .ref("bb_squeeze_in_" + squeezeEntry)
                    .note(String.format("Squeeze ongoing (%d bars) — BBW=%.2f%% still compressed", held, width[squeezeEntry]))
                    .build());
        }

        // Step 2: band-tag events with ADX context (engine emits + context; LLM judges).
        // Also accumulate consecutive-tag runs for the band-walk regime detector.
        int upperRunStart = -1;
        int lowerRunStart = -1;
        for (int i = 0; i < n; i++) {
            boolean tagUpper = closes[i] >= upper[i];
            boolean tagLower = closes[i] <= lower[i];

            // Single band-tag event
            if (tagUpper || tagLower) {
                String dir = tagUpper ? "bullish" : "bearish";
                String regimeContext;
                double a = adx[i];
                if (a >= params.getBandWalkAdxThreshold()) {
                    regimeContext = String.format(
                            "ADX=%.1f ≥ %.0f → band-walk-favorable regime (continuation interpretation per PATTERN_066, NOT mean-reversion)",
                            a, params.getBandWalkAdxThreshold());
                } else if (a <= params.getReversionAdxThreshold()) {
                    regimeContext = String.format(
                            "ADX=%.1f ≤ %.0f → mean-reversion-favorable regime (countertrend interpretation per PATTERN_067)",
                            a, params.getReversionAdxThreshold());
                } else {
                    regimeContext = String.format("ADX=%.1f transitional — interpretation ambiguous; defer to LLM judgment", a);
                }
                beats.add(Beat.builder()
                        .what(BeatVerb.CROSSED)
                        .component(IndicatorComponent.BOLLINGER)
                        .whenBar(i)
                        .whenDate(instantToDateString(series.getBarTimestamps()[i]))
                        .value(closes[i])
                        .significance(0.7) // survives the engine's CROSSED keep-gate
                        .consequence(Consequence.CONFIRMED)
                        .priceContext(PriceContextBuilder.buildAt(i, bars, pricePivots, swingStates))
                        .direction(dir)
                        .type(tagUpper ? "upper_band_tag" : "lower_band_tag")
                        .ref("bb_tag_" + (tagUpper ? "up" : "lo") + "_" + i)
                        .note(String.format(
                                "Price tagged %s band — close=%.2f vs %s=%.2f. Regime context: %s. "
                                        + "LLM should choose walk-vs-reversion based on this context.",
                                tagUpper ? "upper" : "lower", closes[i],
                                tagUpper ? "upper" : "lower",
                                tagUpper ? upper[i] : lower[i], regimeContext))
                        .build());
            }

            // Band-walk run tracking
            if (tagUpper) {
                if (upperRunStart < 0) upperRunStart = i;
                if (lowerRunStart >= 0) lowerRunStart = -1;
            } else if (tagLower) {
                if (lowerRunStart < 0) lowerRunStart = i;
                if (upperRunStart >= 0) upperRunStart = -1;
            } else {
                // Close run on next non-tag bar
                if (upperRunStart >= 0) {
                    int runLen = i - upperRunStart;
                    if (runLen >= params.getBandWalkMinPersistenceBars()) {
                        beats.add(buildBandWalkBeat(true, upperRunStart, runLen, bb, series, bars, pricePivots, swingStates));
                    }
                    upperRunStart = -1;
                }
                if (lowerRunStart >= 0) {
                    int runLen = i - lowerRunStart;
                    if (runLen >= params.getBandWalkMinPersistenceBars()) {
                        beats.add(buildBandWalkBeat(false, lowerRunStart, runLen, bb, series, bars, pricePivots, swingStates));
                    }
                    lowerRunStart = -1;
                }
            }
        }
        // Close any open run at series end
        if (upperRunStart >= 0) {
            int runLen = n - upperRunStart;
            if (runLen >= params.getBandWalkMinPersistenceBars()) {
                beats.add(buildBandWalkBeat(true, upperRunStart, runLen, bb, series, bars, pricePivots, swingStates));
            }
        }
        if (lowerRunStart >= 0) {
            int runLen = n - lowerRunStart;
            if (runLen >= params.getBandWalkMinPersistenceBars()) {
                beats.add(buildBandWalkBeat(false, lowerRunStart, runLen, bb, series, bars, pricePivots, swingStates));
            }
        }
        return beats;
    }

    private Beat buildBandWalkBeat(boolean bullish, int startBar, int runLen,
                                    BollingerSeries bb, IndicatorSeries series, List<OhlcBarDTO> bars,
                                    List<ZigZagPoint> pricePivots,
                                    List<com.dtech.aitrader.v2.narrative.beat.SwingState> swingStates) {
        double avgAdx = 0;
        int n = bb.length();
        for (int j = startBar; j < Math.min(n, startBar + runLen); j++) avgAdx += bb.getAdx()[j];
        avgAdx /= Math.max(1, runLen);
        return Beat.builder()
                .what(BeatVerb.REGIME_CHANGE)
                .component(IndicatorComponent.BOLLINGER)
                .whenBar(startBar)
                .whenDate(instantToDateString(series.getBarTimestamps()[startBar]))
                .value(bb.getCloses()[startBar])
                .significance(1.0)
                .persistedBars(runLen)
                .consequence(Consequence.CONFIRMED)
                .priceContext(PriceContextBuilder.buildAt(startBar, bars, pricePivots, swingStates))
                .direction(bullish ? "bullish" : "bearish")
                .type(bullish ? "upper_band_walk" : "lower_band_walk")
                .ref("bb_walk_" + (bullish ? "up" : "lo") + "_" + startBar)
                .note(String.format(
                        "Sustained %s band-walk (PATTERN_066) — price rode the %s band for %d consecutive bars. "
                                + "Average ADX during walk=%.1f. %s",
                        bullish ? "upper" : "lower", bullish ? "upper" : "lower",
                        runLen, avgAdx,
                        avgAdx >= params.getBandWalkAdxThreshold()
                                ? "Confirmed band-walk regime — continuation context, NOT countertrend."
                                : "Band-walk persisted but ADX was below band-walk threshold — interpretation cautious."))
                .build();
    }

    private Beat buildZoneBeat(BeatVerb verb, int bar, double value, Integer persisted, String ref,
                                String note, IndicatorSeries series, List<OhlcBarDTO> bars,
                                List<ZigZagPoint> pricePivots,
                                List<com.dtech.aitrader.v2.narrative.beat.SwingState> swingStates) {
        return Beat.builder()
                .what(verb)
                .component(IndicatorComponent.BOLLINGER)
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

    @Override
    public Beat buildCurrentlyBeat(int lastIdx, IndicatorSeries series, List<OhlcBarDTO> bars) {
        BollingerSeries bb = (BollingerSeries) series;
        double w = bb.getWidth()[lastIdx];
        double close = bb.getCloses()[lastIdx];
        double up = bb.getUpper()[lastIdx];
        double lo = bb.getLower()[lastIdx];
        double mid = bb.getMiddle()[lastIdx];
        double pb = bb.getPercentB()[lastIdx];
        double a = bb.getAdx()[lastIdx];

        // Squeeze state at last bar — percentile of width within rolling window
        String squeezeState = "normal";
        if (lastIdx >= params.getBbwPercentileWindow()) {
            double[] window = Arrays.copyOfRange(bb.getWidth(),
                    lastIdx - params.getBbwPercentileWindow() + 1, lastIdx + 1);
            double[] sorted = window.clone();
            Arrays.sort(sorted);
            int idx = Arrays.binarySearch(sorted, w);
            if (idx < 0) idx = -idx - 1;
            double pctile = (double) idx / sorted.length;
            if (pctile <= params.getSqueezePctRank()) squeezeState = "compressed (squeeze)";
            else if (pctile >= 0.85) squeezeState = "expanded";
        }
        String bandPosition = close >= up ? "above upper band"
                : close <= lo ? "below lower band"
                : close >= mid ? "upper half"
                : "lower half";
        String regimeNote = a >= params.getBandWalkAdxThreshold()
                ? String.format("ADX=%.1f ≥ %.0f — band-walk-favorable; band-tags read as continuation", a, params.getBandWalkAdxThreshold())
                : a <= params.getReversionAdxThreshold()
                ? String.format("ADX=%.1f ≤ %.0f — mean-reversion-favorable; band-tags read as countertrend", a, params.getReversionAdxThreshold())
                : String.format("ADX=%.1f transitional — band-tag interpretation ambiguous", a);

        String note = String.format(
                "Bollinger posture at last bar: BBW=%.2f%% (%s), close=%.2f vs upper=%.2f / mid=%.2f / lower=%.2f, "
                        + "%%B=%.2f, position=%s. Conditioner: %s.",
                w, squeezeState, close, up, mid, lo, pb, bandPosition, regimeNote);

        return Beat.builder()
                .what(BeatVerb.CURRENTLY)
                .component(IndicatorComponent.BOLLINGER)
                .whenBar(lastIdx)
                .whenDate(instantToDateString(series.getBarTimestamps()[lastIdx]))
                .value(close)
                .significance(1.0)
                .consequence(Consequence.ONGOING)
                .tier(Tier.PRESENT)
                .ref("bb_now_" + lastIdx)
                .note(note)
                .build();
    }

    @Override
    public List<Checkpoint> buildVerificationCheckpoints(IndicatorSeries series,
                                                         List<SeriesPivot> primaryPivots,
                                                         int lastIdx) {
        List<Checkpoint> checkpoints = new ArrayList<>();
        if (primaryPivots != null) {
            for (SeriesPivot pivot : primaryPivots) {
                if (pivot.idx() <= lastIdx - 100) {
                    checkpoints.add(Checkpoint.builder().bar(pivot.idx()).build());
                }
            }
        }
        checkpoints.add(Checkpoint.builder().bar(lastIdx).build());
        return checkpoints;
    }

    private static String instantToDateString(Instant instant) {
        LocalDateTime ldt = LocalDateTime.ofInstant(instant, ZoneId.of("Asia/Kolkata"));
        return ldt.format(DATE_FORMATTER);
    }
}
