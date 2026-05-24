package com.dtech.aitrader.v2.narrative.stochrsi;

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
 * StochRSI config (delta memsys 2fde845f). Shortest horizon; second-derivative oscillator; the
 * single biggest noise-filtering test in the indicator set.
 *
 * <p>Crucial spec constraints:
 * <ul>
 *   <li>NOT in the OB/OS equivalence class with RSI/Stoch — second-derivative, distinct info.
 *       Don't de-dup against them; emit StochRSI's own beats.</li>
 *   <li>EXPECTED OUTCOME (FAILURE_004): post-filter StochRSI beat count must be LESS than RSI
 *       on the same data. If StochRSI emits more beats than RSI, the filter is wrong.</li>
 * </ul>
 */
@RequiredArgsConstructor
public class StochRsiIndicatorConfig implements IndicatorConfig {

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final StochRsiNarrativeParams params;

    @Override
    public String getIndicatorName() {
        return "StochRSI";
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
                // Tightest caps in the set — second-derivative needs aggressive pruning per FAILURE_004.
                .historyPeakedCap(1)
                .historyTroughedCap(1)
                .historyRegimeCap(0) // no regime_change verb
                .recentPeakedCap(2)
                .recentTroughedCap(2)
                .recentThrustCap(0)  // no thrust
                .failedAttemptMinBars(5) // higher than Stoch's 4 — StochRSI midline crosses chop badly
                .build();
    }

    @Override
    public IndicatorSeries compute(List<OhlcBarDTO> bars, String symbol, String timeframe) {
        return StochRsiComputer.compute(bars, params.getPeriod(), params.getKSmoothing(),
                params.getDSmoothing(), symbol, timeframe);
    }

    @Override
    public List<PivotComponentSpec> getPivotComponents() {
        // Pivots only on Slow %D — %K duplicates.
        return List.of(PivotComponentSpec.builder()
                .component(IndicatorComponent.STOCHRSI_D)
                .verb(BeatVerb.PEAKED)
                .significanceParams(params.getPivotParams())
                .refPrefix(null)
                .labelPrefix("StochRSI %D")
                .build());
    }

    @Override
    public List<CrossoverSpec> getCrossovers() {
        // Midline 50-cross on %D. PATTERN_016 — higher quality than extremes in trends per delta.
        // regimeRelevant=false (no regime_change verb for StochRSI).
        return List.of(CrossoverSpec.builder()
                .primary(IndicatorComponent.STOCHRSI_D)
                .kind(CrossoverSpec.Kind.VS_LEVEL)
                .level(50.0)
                .regimeRelevant(false)
                .aboveLabel("above_50")
                .belowLabel("below_50")
                .build());
    }

    @Override
    public Optional<DivergenceSpec> getDivergence() {
        return Optional.of(DivergenceSpec.builder()
                .component(IndicatorComponent.STOCHRSI_D)
                .beatComponent(IndicatorComponent.STOCHRSI_ALL)
                .componentLabel("StochRSI %D")
                .refPrefix("stochrsi_div_")
                .build());
    }

    @Override
    public List<ZoneSpec> getZones() {
        return List.of(
                ZoneSpec.builder()
                        .component(IndicatorComponent.STOCHRSI_D)
                        .name("oversold")
                        .lower(0.0)
                        .upper(params.getOversoldThreshold())
                        .minPersistenceBars(params.getZoneMinPersistenceBars())
                        .refPrefix("stochrsi_os_")
                        .build(),
                ZoneSpec.builder()
                        .component(IndicatorComponent.STOCHRSI_D)
                        .name("overbought")
                        .lower(params.getOverboughtThreshold())
                        .upper(100.0)
                        .minPersistenceBars(params.getZoneMinPersistenceBars())
                        .refPrefix("stochrsi_ob_")
                        .build());
    }

    /**
     * %K/%D crossover detection with zone context. Same shape as Stoch's, but:
     * <ul>
     *   <li>Higher post-cross persistence requirement (kdCrossMinPersistenceBars=5 default)</li>
     *   <li>Stricter zone significance: only OS-bull and OB-bear get sig=1.0; mid-range gets
     *       0.3 (vs Stoch's 0.4) so the tier filter drops more aggressively</li>
     * </ul>
     */
    @Override
    public List<Beat> emitCustomBeats(IndicatorSeries series, List<OhlcBarDTO> bars,
                                       List<ZigZagPoint> pricePivots,
                                       List<SwingState> swingStates,
                                       Map<IndicatorComponent, List<SeriesPivot>> pivotsByComponent) {
        List<Beat> beats = new ArrayList<>();
        double[] k = series.getComponent(IndicatorComponent.STOCHRSI_K);
        double[] d = series.getComponent(IndicatorComponent.STOCHRSI_D);
        int minPersist = Math.max(1, params.getKdCrossMinPersistenceBars());
        int n = k.length;

        for (int i = 1; i < n; i++) {
            double prevDiff = k[i - 1] - d[i - 1];
            double currDiff = k[i] - d[i];
            if (prevDiff * currDiff > 0 || prevDiff == 0) continue;
            boolean bullishCross = currDiff > 0;

            int hold = 1;
            for (int j = i + 1; j < n && j < i + minPersist; j++) {
                double diff = k[j] - d[j];
                if ((bullishCross && diff > 0) || (!bullishCross && diff < 0)) hold++;
                else break;
            }
            if (hold < minPersist) continue;

            double dValue = d[i];
            boolean inOversold = dValue <= params.getOversoldThreshold();
            boolean inOverbought = dValue >= params.getOverboughtThreshold();

            double significance;
            String noteSuffix;
            String refKind;
            if (bullishCross && inOversold) {
                significance = 1.0;
                noteSuffix = " — PATTERN_014 OS bullish cross (high-conviction; relative-to-RSI-range extreme)";
                refKind = "bull_in_os";
            } else if (!bullishCross && inOverbought) {
                significance = 1.0;
                noteSuffix = " — PATTERN_015 OB bearish cross (high-conviction; relative-to-RSI-range extreme)";
                refKind = "bear_in_ob";
            } else {
                // Mid-range — second-derivative noise; drop via tier filter (sig < 0.7).
                significance = 0.3;
                noteSuffix = " (mid-range, no zone context — second-derivative noise)";
                refKind = bullishCross ? "bull_mid" : "bear_mid";
            }

            beats.add(Beat.builder()
                    .what(BeatVerb.CROSSED)
                    .component(IndicatorComponent.STOCHRSI_ALL)
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
                    .ref("stochrsi_kd_" + refKind + "_" + i)
                    .note(String.format("StochRSI %%K/%%D %s cross at %%K=%.1f / %%D=%.1f%s",
                            bullishCross ? "bullish" : "bearish", k[i], d[i], noteSuffix))
                    .build());
        }
        return beats;
    }

    @Override
    public Beat buildCurrentlyBeat(int lastIdx, IndicatorSeries series, List<OhlcBarDTO> bars) {
        double[] k = series.getComponent(IndicatorComponent.STOCHRSI_K);
        double[] d = series.getComponent(IndicatorComponent.STOCHRSI_D);
        double kv = k[lastIdx], dv = d[lastIdx];
        String zone = dv <= params.getOversoldThreshold() ? "oversold"
                : dv >= params.getOverboughtThreshold() ? "overbought" : "neutral";
        String posture = kv > dv ? "%K above %D (bullish)" : "%K below %D (bearish)";
        String note = String.format(
                "StochRSI posture at last bar: %%K=%.2f, %%D=%.2f, zone=%s, %s (relative position of RSI within its own 14-bar range)",
                kv, dv, zone, posture);

        return Beat.builder()
                .what(BeatVerb.CURRENTLY)
                .component(IndicatorComponent.STOCHRSI_ALL)
                .whenBar(lastIdx)
                .whenDate(instantToDateString(series.getBarTimestamps()[lastIdx]))
                .value(dv)
                .significance(1.0)
                .consequence(Consequence.ONGOING)
                .tier(Tier.PRESENT)
                .ref("stochrsi_now_" + lastIdx)
                .note(note)
                .build();
    }

    @Override
    public List<Checkpoint> buildVerificationCheckpoints(IndicatorSeries series,
                                                         List<SeriesPivot> primaryPivots,
                                                         int lastIdx) {
        // Same pattern as Stochastic — record bar only; avoid field-reuse hack.
        List<Checkpoint> checkpoints = new ArrayList<>();
        for (SeriesPivot pivot : primaryPivots) {
            if (pivot.idx() <= lastIdx - 100) {
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
