package com.dtech.aitrader.v2.narrative.stoch;

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
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Stochastic config (delta memsys 5e359982). Slow (14,3,3); Slow %D canonical for divergence;
 * %K/%D crossover is the primary EVENT; absolute 20/80/50 zones; SHORT horizon; no regime verb;
 * no thrust verb.
 *
 * <p>Williams %R is NOT separately narrated (hard-identity with Fast %K per delta Section 8) —
 * this config simply omits it; no Williams class exists.
 */
@RequiredArgsConstructor
public class StochIndicatorConfig implements IndicatorConfig {

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final StochNarrativeParams params;

    @Override
    public String getIndicatorName() {
        return "Stochastic";
    }

    @Override
    public NarrativeTier getNarrativeTier() {
        return NarrativeTier.FULL_NARRATIVE;
    }

    @Override
    public EngineParams getEngineParams() {
        return EngineParams.builder()
                .defaultPivotParams(params.getPivotParams())
                .presentWindowBars(params.getPresentWindowBars())
                .recentWindowBars(params.getRecentWindowBars())
                .regimeChangePersistenceBars(params.getRegimeChangePersistenceBars())
                .historyPeakedCap(2)
                .historyTroughedCap(2)
                // No regime_change verb for Stochastic (delta Section 3); cap to keep history clean.
                .historyRegimeCap(0)
                .recentPeakedCap(3)
                .recentTroughedCap(3)
                .recentThrustCap(0) // bounded → no thrust
                // Stoch 50-cross is FAST decay per delta — only emit failed_attempt for crosses
                // persisting ≥4 weekly bars; below that is noise.
                .failedAttemptMinBars(4)
                .build();
    }

    @Override
    public IndicatorSeries compute(List<OhlcBarDTO> bars, String symbol, String timeframe) {
        return StochComputer.compute(bars, params.getKPeriod(), params.getKSmoothing(),
                params.getDSmoothing(), symbol, timeframe);
    }

    @Override
    public List<PivotComponentSpec> getPivotComponents() {
        // Pivots on Slow %D — Lane's canonical signal line. %K is more jagged; emitting pivots
        // on both would duplicate.
        return List.of(PivotComponentSpec.builder()
                .component(IndicatorComponent.STOCH_D)
                .verb(BeatVerb.PEAKED)
                .significanceParams(params.getPivotParams())
                .refPrefix(null)
                .labelPrefix("Stoch %D")
                .build());
    }

    @Override
    public List<CrossoverSpec> getCrossovers() {
        // Centerline 50-cross on Slow %D (PATTERN_026). Regime-relevant=false — Stochastic has
        // no long-lived regime per delta; this emits at sig=0.5 and gets filtered from output.
        return List.of(CrossoverSpec.builder()
                .primary(IndicatorComponent.STOCH_D)
                .kind(CrossoverSpec.Kind.VS_LEVEL)
                .level(50.0)
                .regimeRelevant(false)
                .aboveLabel("above_50")
                .belowLabel("below_50")
                .build());
    }

    @Override
    public Optional<DivergenceSpec> getDivergence() {
        // Lane's PRIMARY signal — on Slow %D.
        return Optional.of(DivergenceSpec.builder()
                .component(IndicatorComponent.STOCH_D)
                .beatComponent(IndicatorComponent.STOCH_ALL)
                .componentLabel("Stoch %D")
                .refPrefix("stoch_div_")
                .build());
    }

    @Override
    public List<ZoneSpec> getZones() {
        return List.of(
                ZoneSpec.builder()
                        .component(IndicatorComponent.STOCH_D)
                        .name("oversold")
                        .lower(0.0)
                        .upper(params.getOversoldThreshold())
                        .minPersistenceBars(params.getZoneMinPersistenceBars())
                        .refPrefix("stoch_os_")
                        .build(),
                ZoneSpec.builder()
                        .component(IndicatorComponent.STOCH_D)
                        .name("overbought")
                        .lower(params.getOverboughtThreshold())
                        .upper(100.0)
                        .minPersistenceBars(params.getZoneMinPersistenceBars())
                        .refPrefix("stoch_ob_")
                        .build());
    }

    /**
     * Custom beats for Stochastic: %K/%D crossover detection with zone context. Per delta:
     * <ul>
     *   <li>PATTERN_020: bullish %K/%D cross in OS zone — high-conviction continuation-buy</li>
     *   <li>PATTERN_021: bearish %K/%D cross in OB zone — high-conviction continuation-sell</li>
     *   <li>%K/%D cross mid-range — lower conviction (drops via sig &lt; 0.7 filter)</li>
     * </ul>
     *
     * <p>Significance assignment makes the engine's CROSSED-keep-gate self-tuning: in-zone
     * crosses get 1.0 (kept), mid-range crosses get 0.4 (filtered).
     */
    @Override
    public List<Beat> emitCustomBeats(IndicatorSeries series, List<OhlcBarDTO> bars,
                                       List<ZigZagPoint> pricePivots,
                                       List<com.dtech.aitrader.v2.narrative.beat.SwingState> swingStates,
                                       Map<IndicatorComponent, List<SeriesPivot>> pivotsByComponent) {
        List<Beat> beats = new ArrayList<>();
        double[] k = series.getComponent(IndicatorComponent.STOCH_K);
        double[] d = series.getComponent(IndicatorComponent.STOCH_D);
        int minPersist = Math.max(1, params.getKdCrossMinPersistenceBars());
        int n = k.length;

        for (int i = 1; i < n; i++) {
            double prevDiff = k[i - 1] - d[i - 1];
            double currDiff = k[i] - d[i];
            // %K crossed %D this bar
            if (prevDiff * currDiff > 0 || prevDiff == 0) continue;
            boolean bullishCross = currDiff > 0; // %K crossed above %D

            // Filter whipsaw: require the post-cross side to persist ≥ minPersist bars
            int hold = 1;
            for (int j = i + 1; j < n && j < i + minPersist; j++) {
                double diff = k[j] - d[j];
                if ((bullishCross && diff > 0) || (!bullishCross && diff < 0)) hold++;
                else break;
            }
            if (hold < minPersist) continue;

            // Zone context — was %D inside OS/OB at the cross bar?
            double dValue = d[i];
            boolean inOversold = dValue <= params.getOversoldThreshold();
            boolean inOverbought = dValue >= params.getOverboughtThreshold();

            double significance;
            String noteSuffix;
            String refKind;
            if (bullishCross && inOversold) {
                // PATTERN_020 — high conviction continuation-buy
                significance = 1.0;
                noteSuffix = " — PATTERN_020 high-conviction continuation-buy signal";
                refKind = "bull_in_os";
            } else if (!bullishCross && inOverbought) {
                // PATTERN_021 — high conviction continuation-sell
                significance = 1.0;
                noteSuffix = " — PATTERN_021 high-conviction continuation-sell signal";
                refKind = "bear_in_ob";
            } else {
                // Mid-range cross — lower conviction; emitted but tier-filter likely drops it.
                significance = 0.4;
                noteSuffix = " (mid-range, no Lane zone context)";
                refKind = bullishCross ? "bull_mid" : "bear_mid";
            }

            beats.add(Beat.builder()
                    .what(BeatVerb.CROSSED)
                    .component(IndicatorComponent.STOCH_ALL)
                    .whenBar(i)
                    .whenDate(instantToDateString(series.getBarTimestamps()[i]))
                    .value(dValue)
                    .significance(significance)
                    .consequence(Consequence.CONFIRMED)
                    .priceContext(PriceContextBuilder.buildAt(i, bars, pricePivots, swingStates))
                    .from(bullishCross ? "k_below_d" : "k_above_d")
                    .to(bullishCross ? "k_above_d" : "k_below_d")
                    .type(refKind)
                    .direction(bullishCross ? "bullish" : "bearish")
                    .ref("stoch_kd_" + refKind + "_" + i)
                    .note(String.format("Stoch %%K/%%D %s cross at %%K=%.1f / %%D=%.1f%s",
                            bullishCross ? "bullish" : "bearish", k[i], d[i], noteSuffix))
                    .build());
        }
        return beats;
    }

    @Override
    public Beat buildCurrentlyBeat(int lastIdx, IndicatorSeries series, List<OhlcBarDTO> bars) {
        double[] k = series.getComponent(IndicatorComponent.STOCH_K);
        double[] d = series.getComponent(IndicatorComponent.STOCH_D);
        double kv = k[lastIdx], dv = d[lastIdx];
        String zone = dv <= params.getOversoldThreshold() ? "oversold"
                : dv >= params.getOverboughtThreshold() ? "overbought" : "neutral";
        String posture = kv > dv ? "%K above %D (bullish)" : "%K below %D (bearish)";
        String note = String.format("Stoch posture at last bar: %%K=%.2f, %%D=%.2f, zone=%s, %s",
                kv, dv, zone, posture);

        return Beat.builder()
                .what(BeatVerb.CURRENTLY)
                .component(IndicatorComponent.STOCH_ALL)
                .whenBar(lastIdx)
                .whenDate(instantToDateString(series.getBarTimestamps()[lastIdx]))
                .value(dv)
                .significance(1.0)
                .consequence(Consequence.ONGOING)
                .tier(Tier.PRESENT)
                .ref("stoch_now_" + lastIdx)
                .note(note)
                .build();
    }

    @Override
    public List<Checkpoint> buildVerificationCheckpoints(IndicatorSeries series,
                                                         List<SeriesPivot> primaryPivots,
                                                         int lastIdx) {
        // Reuse a generic-ish shape: write the %D value into the macd_line field is FALSE
        // labeling. To avoid recurring the RSI-style Fix 2 issue, leave only the bar number for
        // history checkpoints; emit %K and %D explicitly on the last-bar checkpoint via the note
        // field. (Future: add stoch_k/stoch_d fields to Checkpoint when we do EMA/Bollinger.)
        double[] k = series.getComponent(IndicatorComponent.STOCH_K);
        double[] d = series.getComponent(IndicatorComponent.STOCH_D);
        List<Checkpoint> checkpoints = new ArrayList<>();
        for (SeriesPivot pivot : primaryPivots) {
            if (pivot.idx() <= lastIdx - 100) {
                // %D pivots — record bar only (no field-reuse hack).
                checkpoints.add(Checkpoint.builder().bar(pivot.idx()).build());
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
