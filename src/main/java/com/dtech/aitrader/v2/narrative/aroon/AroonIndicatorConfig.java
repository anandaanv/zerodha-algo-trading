package com.dtech.aitrader.v2.narrative.aroon;

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
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Aroon config. REGIME_EPISODE tier per Narrative Core 533b3e85. Owner guidance (b3ff4ca0):
 * "Aroon — trend/consolidation regime episodes. Aroon-up/down crossovers + Aroon-Oscillator zero.
 * Mirror of ADX (regime classifier)." EQUIV_CLASS_006 (Trend-Strength), REGIMEWT_015.
 *
 * <p>Verbs:
 * <ul>
 *   <li>entered_zone / exited_zone — uptrend (Aroon-up ≥ 70 sustained), downtrend (Aroon-down ≥ 70
 *       sustained), consolidation (both ≤ 50).</li>
 *   <li>crossed — Aroon-up vs Aroon-down (the primary regime-flip signal).</li>
 *   <li>regime_change — sustained Aroon-Oscillator above/below zero (parallels ADX trend regime).</li>
 *   <li>currently — Aroon-up/down/osc + active regime.</li>
 *   <li>NO divergence, NO thrust (regime-episode tier).</li>
 * </ul>
 */
@RequiredArgsConstructor
public class AroonIndicatorConfig implements IndicatorConfig {

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final AroonNarrativeParams params;

    @Override
    public String getIndicatorName() {
        return "Aroon";
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
                .regimeChangePersistenceBars(params.getRegimeMinPersistenceBars())
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
        return AroonComputer.compute(bars, params.getPeriod(), symbol, timeframe);
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
        // Uptrend zone — Aroon-up high (≥ trendThreshold).
        // Downtrend zone — Aroon-down high (≥ trendThreshold).
        return List.of(
                ZoneSpec.builder()
                        .component(IndicatorComponent.AROON_UP)
                        .name("aroon_uptrend")
                        .lower(params.getTrendThreshold())
                        .upper(100.0)
                        .minPersistenceBars(params.getRegimeMinPersistenceBars())
                        .refPrefix("aroon_up_")
                        .build(),
                ZoneSpec.builder()
                        .component(IndicatorComponent.AROON_DOWN)
                        .name("aroon_downtrend")
                        .lower(params.getTrendThreshold())
                        .upper(100.0)
                        .minPersistenceBars(params.getRegimeMinPersistenceBars())
                        .refPrefix("aroon_down_")
                        .build());
    }

    @Override
    public List<Beat> emitCustomBeats(IndicatorSeries series, List<OhlcBarDTO> bars,
                                       List<ZigZagPoint> pricePivots,
                                       List<SwingState> swingStates,
                                       Map<IndicatorComponent, List<SeriesPivot>> pivotsByComponent) {
        AroonSeries as = (AroonSeries) series;
        double[] up = as.getAroonUp();
        double[] dn = as.getAroonDown();
        double[] osc = as.getAroonOsc();
        int n = up.length;
        List<Beat> beats = new ArrayList<>();

        // 1. Aroon-up vs Aroon-down crossover (the primary regime-flip event).
        for (int i = 1; i < n; i++) {
            double prevDiff = up[i - 1] - dn[i - 1];
            double currDiff = up[i] - dn[i];
            if (prevDiff * currDiff > 0 || prevDiff == 0) continue;
            boolean bullishCross = currDiff > 0;
            beats.add(Beat.builder()
                    .what(BeatVerb.CROSSED)
                    .component(IndicatorComponent.AROON)
                    .whenBar(i)
                    .whenDate(instantToDateString(series.getBarTimestamps()[i]))
                    .value(osc[i])
                    .significance(0.8)
                    .consequence(Consequence.CONFIRMED)
                    .priceContext(PriceContextBuilder.buildAt(i, bars, pricePivots, swingStates))
                    .from(bullishCross ? "down_above_up" : "up_above_down")
                    .to(bullishCross ? "up_above_down" : "down_above_up")
                    .type(bullishCross ? "aroon_bullish" : "aroon_bearish")
                    .direction(bullishCross ? "bullish" : "bearish")
                    .ref("aroon_cross_" + (bullishCross ? "bull" : "bear") + "_" + i)
                    .note(String.format(
                            "Aroon crossover — %s (Up=%.1f, Down=%.1f, Osc=%.1f)",
                            bullishCross ? "bullish" : "bearish", up[i], dn[i], osc[i]))
                    .build());
        }

        // 2. Sustained-oscillator regime: Aroon-Osc > 0 or < 0 for at least regimeMinPersistenceBars
        //    that ALSO crossed (entering) at the start.
        int runStart = -1;
        boolean runBullish = false;
        for (int i = 0; i < n; i++) {
            boolean nowBull = osc[i] > 0;
            if (i == 0 || (nowBull != (osc[i - 1] > 0))) {
                // Run boundary — close previous if it was long enough.
                if (runStart >= 0) {
                    int len = i - runStart;
                    if (len >= params.getRegimeMinPersistenceBars()) {
                        beats.add(buildOscRegimeBeat(runBullish, runStart, len, as,
                                series, bars, pricePivots, swingStates));
                    }
                }
                runStart = i;
                runBullish = nowBull;
            }
        }
        if (runStart >= 0) {
            int len = n - runStart;
            if (len >= params.getRegimeMinPersistenceBars()) {
                beats.add(buildOscRegimeBeat(runBullish, runStart, len, as,
                        series, bars, pricePivots, swingStates));
            }
        }
        return beats;
    }

    private Beat buildOscRegimeBeat(boolean bullish, int startBar, int len, AroonSeries as,
                                     IndicatorSeries series, List<OhlcBarDTO> bars,
                                     List<ZigZagPoint> pricePivots,
                                     List<SwingState> swingStates) {
        return Beat.builder()
                .what(BeatVerb.REGIME_CHANGE)
                .component(IndicatorComponent.AROON_OSC)
                .whenBar(startBar)
                .whenDate(instantToDateString(series.getBarTimestamps()[startBar]))
                .value(as.getAroonOsc()[startBar])
                .significance(0.9)
                .persistedBars(len)
                .consequence(Consequence.CONFIRMED)
                .priceContext(PriceContextBuilder.buildAt(startBar, bars, pricePivots, swingStates))
                .direction(bullish ? "bullish" : "bearish")
                .type(bullish ? "aroon_osc_positive" : "aroon_osc_negative")
                .ref("aroon_osc_" + (bullish ? "pos" : "neg") + "_" + startBar)
                .note(String.format(
                        "Aroon-Osc sustained %s for %d bars — %s regime per oscillator",
                        bullish ? "positive (Up>Down)" : "negative (Down>Up)", len,
                        bullish ? "bullish trend" : "bearish trend"))
                .build();
    }

    @Override
    public Beat buildCurrentlyBeat(int lastIdx, IndicatorSeries series, List<OhlcBarDTO> bars) {
        AroonSeries as = (AroonSeries) series;
        double u = as.getAroonUp()[lastIdx];
        double d = as.getAroonDown()[lastIdx];
        double o = as.getAroonOsc()[lastIdx];
        String regime;
        double tt = params.getTrendThreshold();
        double ct = params.getConsolidationThreshold();
        if (u >= tt && d <= 100 - tt) regime = "uptrend";
        else if (d >= tt && u <= 100 - tt) regime = "downtrend";
        else if (u <= ct && d <= ct) regime = "consolidation";
        else regime = "transitional";
        String note = String.format(
                "Aroon posture at last bar: Up=%.1f, Down=%.1f, Osc=%.1f. Regime: %s.",
                u, d, o, regime);
        return Beat.builder()
                .what(BeatVerb.CURRENTLY)
                .component(IndicatorComponent.AROON)
                .whenBar(lastIdx)
                .whenDate(instantToDateString(series.getBarTimestamps()[lastIdx]))
                .value(o)
                .significance(1.0)
                .consequence(Consequence.ONGOING)
                .tier(Tier.PRESENT)
                .ref("aroon_now_" + lastIdx)
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
