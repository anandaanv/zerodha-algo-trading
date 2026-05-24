package com.dtech.aitrader.v2.narrative.donchian;

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
 * Donchian Channels config. REGIME_EPISODE tier per Narrative Core 533b3e85. Owner guidance
 * (b3ff4ca0): "Donchian — channel breakout events + channel-width episodes. Breakout = event;
 * equivalence EQUIV_CLASS_004-breakout-ish / EQUIV_CLASS_009 with volume."
 *
 * <p>Verbs:
 * <ul>
 *   <li>crossed — close beyond prior-bar upper (bullish breakout) or below prior-bar lower
 *       (bearish breakout). The structural event for Donchian.</li>
 *   <li>entered_zone / exited_zone — channel-width compression (relative to own history).</li>
 *   <li>currently — current channel state + position within channel.</li>
 *   <li>NO divergence, NO thrust (regime-episode tier).</li>
 * </ul>
 */
@RequiredArgsConstructor
public class DonchianIndicatorConfig implements IndicatorConfig {

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final DonchianNarrativeParams params;

    @Override
    public String getIndicatorName() {
        return "Donchian";
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
                .regimeChangePersistenceBars(params.getCompressionMinPersistenceBars())
                .historyPeakedCap(0)
                .historyTroughedCap(0)
                .historyRegimeCap(2)
                .recentPeakedCap(0)
                .recentTroughedCap(0)
                .recentThrustCap(0)
                .historyZoneCap(4)
                .failedAttemptMinBars(0)
                .build();
    }

    @Override
    public IndicatorSeries compute(List<OhlcBarDTO> bars, String symbol, String timeframe) {
        return DonchianComputer.compute(bars, params.getPeriod(), symbol, timeframe);
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
        DonchianSeries ds = (DonchianSeries) series;
        double[] upper = ds.getUpper();
        double[] lower = ds.getLower();
        double[] width = ds.getWidth();
        double[] closes = ds.getCloses();
        int n = upper.length;
        List<Beat> beats = new ArrayList<>();

        // 1. Breakout events: close[i] > upper[i-1] = bullish breakout; close[i] < lower[i-1] = bearish.
        for (int i = 1; i < n; i++) {
            double priorUp = upper[i - 1];
            double priorLo = lower[i - 1];
            boolean bullBreakout = closes[i] > priorUp;
            boolean bearBreakout = closes[i] < priorLo;
            if (bullBreakout || bearBreakout) {
                String dir = bullBreakout ? "bullish" : "bearish";
                beats.add(Beat.builder()
                        .what(BeatVerb.CROSSED)
                        .component(IndicatorComponent.DONCHIAN)
                        .whenBar(i)
                        .whenDate(instantToDateString(series.getBarTimestamps()[i]))
                        .value(closes[i])
                        .significance(0.85) // breakouts get high significance
                        .consequence(Consequence.CONFIRMED)
                        .priceContext(PriceContextBuilder.buildAt(i, bars, pricePivots, swingStates))
                        .direction(dir)
                        .type(bullBreakout ? "donchian_upper_breakout" : "donchian_lower_breakout")
                        .ref("dc_brk_" + (bullBreakout ? "up" : "lo") + "_" + i)
                        .note(String.format(
                                "Donchian %s breakout — close=%.2f vs prior %s=%.2f (EQUIV_CLASS_004; gate with volume per EQUIV_CLASS_009)",
                                dir, closes[i],
                                bullBreakout ? "upper" : "lower",
                                bullBreakout ? priorUp : priorLo))
                        .build());
            }
        }

        // 2. Channel-width compression episodes (similar to Bollinger/Keltner squeeze but channel-based).
        int win = params.getWidthPercentileWindow();
        double rank = params.getCompressionPctRank();
        boolean inCompression = false;
        int entry = -1;
        for (int i = 0; i < n; i++) {
            if (i < win) continue;
            double[] window = Arrays.copyOfRange(width, i - win + 1, i + 1);
            double[] sorted = window.clone();
            Arrays.sort(sorted);
            int idx = Arrays.binarySearch(sorted, width[i]);
            if (idx < 0) idx = -idx - 1;
            double pctile = (double) idx / sorted.length;
            boolean isCompr = pctile <= rank;
            if (isCompr && !inCompression) {
                inCompression = true;
                entry = i;
            } else if (!isCompr && inCompression) {
                int held = i - entry;
                if (held >= params.getCompressionMinPersistenceBars()) {
                    beats.add(buildZoneBeat(BeatVerb.ENTERED_ZONE, entry, width[entry], null,
                            "dc_compr_in_" + entry,
                            String.format("Donchian channel compressed — width=%.2f%% in bottom %.0f%% of last %d bars",
                                    width[entry], rank * 100, win),
                            series, bars, pricePivots, swingStates));
                    beats.add(buildZoneBeat(BeatVerb.EXITED_ZONE, i, width[i], held,
                            "dc_compr_out_" + i,
                            String.format("Donchian compression released after %d bars — width=%.2f%%", held, width[i]),
                            series, bars, pricePivots, swingStates));
                }
                inCompression = false;
            }
        }
        if (inCompression) {
            int held = n - entry;
            beats.add(Beat.builder()
                    .what(BeatVerb.ENTERED_ZONE)
                    .component(IndicatorComponent.DONCHIAN)
                    .whenBar(entry)
                    .whenDate(instantToDateString(series.getBarTimestamps()[entry]))
                    .value(width[entry])
                    .significance(1.0)
                    .persistedBars(held)
                    .consequence(Consequence.ONGOING)
                    .priceContext(PriceContextBuilder.buildAt(entry, bars, pricePivots, swingStates))
                    .ref("dc_compr_in_" + entry)
                    .note(String.format("Donchian compression ongoing (%d bars) — width=%.2f%%", held, width[entry]))
                    .build());
        }
        return beats;
    }

    private Beat buildZoneBeat(BeatVerb verb, int bar, double value, Integer persisted, String ref,
                                String note, IndicatorSeries series, List<OhlcBarDTO> bars,
                                List<ZigZagPoint> pricePivots, List<SwingState> swingStates) {
        return Beat.builder()
                .what(verb)
                .component(IndicatorComponent.DONCHIAN)
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
        DonchianSeries ds = (DonchianSeries) series;
        double u = ds.getUpper()[lastIdx];
        double l = ds.getLower()[lastIdx];
        double m = ds.getMiddle()[lastIdx];
        double w = ds.getWidth()[lastIdx];
        double c = ds.getCloses()[lastIdx];
        double posPct = (u - l) > 0 ? ((c - l) / (u - l)) * 100.0 : 50.0;
        String pos = posPct >= 95 ? "at_upper" : posPct <= 5 ? "at_lower"
                : posPct >= 50 ? "upper_half" : "lower_half";
        String comprState = "normal";
        if (lastIdx >= params.getWidthPercentileWindow()) {
            double[] window = Arrays.copyOfRange(ds.getWidth(),
                    lastIdx - params.getWidthPercentileWindow() + 1, lastIdx + 1);
            double[] sorted = window.clone();
            Arrays.sort(sorted);
            int idx = Arrays.binarySearch(sorted, w);
            if (idx < 0) idx = -idx - 1;
            double pctile = (double) idx / sorted.length;
            if (pctile <= params.getCompressionPctRank()) comprState = "compressed";
            else if (pctile >= 0.85) comprState = "expanded";
        }
        String note = String.format(
                "Donchian(%d) posture at last bar: upper=%.2f, mid=%.2f, lower=%.2f, width=%.2f%% (%s); " +
                        "close=%.2f at %.0f%% of channel (%s).",
                params.getPeriod(), u, m, l, w, comprState, c, posPct, pos);
        return Beat.builder()
                .what(BeatVerb.CURRENTLY)
                .component(IndicatorComponent.DONCHIAN)
                .whenBar(lastIdx)
                .whenDate(instantToDateString(series.getBarTimestamps()[lastIdx]))
                .value(c)
                .significance(1.0)
                .consequence(Consequence.ONGOING)
                .tier(Tier.PRESENT)
                .ref("dc_now_" + lastIdx)
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
