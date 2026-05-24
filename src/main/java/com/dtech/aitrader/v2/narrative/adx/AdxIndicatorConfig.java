package com.dtech.aitrader.v2.narrative.adx;

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
 * ADX/DMI config (delta memsys e94519ea). REGIME-EPISODE tier — reduced verb set, no divergence,
 * no thrust. ADX is the **Tier-1 regime conditioner** (PATTERN_041) — its current regime gates
 * how every other oscillator's OB/OS readings are interpreted downstream.
 *
 * <p>Two orthogonal regime axes:
 * <ol>
 *   <li>STRENGTH: trending (ADX&gt;25) vs range (ADX&lt;20). Handled via zone beats with the
 *       persistence-dominant noise filter (delta Section 10).</li>
 *   <li>DIRECTION: bullish (+DI &gt; −DI) vs bearish (−DI &gt; +DI). Handled via custom-beats
 *       hook — emit +DI/−DI crossover only when ADX &gt;= diCrossMinAdx (suppress noise per
 *       the Wilder caveat).</li>
 * </ol>
 *
 * <p>Trend-initiation composite beat (PATTERN_043): when ADX crosses up through 25 AND a DI
 * cross occurs within ±3 bars, emit ONE composite beat (direction + strength) instead of two
 * separate zone-entry + DI-cross beats.
 */
@RequiredArgsConstructor
public class AdxIndicatorConfig implements IndicatorConfig {

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final AdxNarrativeParams params;

    @Override
    public String getIndicatorName() {
        return "ADX_DMI";
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
                .historyPeakedCap(2)
                .historyTroughedCap(2)
                .historyRegimeCap(3)
                .recentPeakedCap(3)
                .recentTroughedCap(3)
                .recentThrustCap(0) // no thrust verb
                // ADX zone episodes are SLOW-MEDIUM decay per delta — owner: "the regime
                // episode is ADX's whole point; keep it". Allow them in history.
                .historyZoneCap(6)
                .failedAttemptMinBars(0) // ADX has no regime_change failed_attempt concept
                .build();
    }

    @Override
    public IndicatorSeries compute(List<OhlcBarDTO> bars, String symbol, String timeframe) {
        return AdxComputer.compute(bars, params.getPeriod(), symbol, timeframe);
    }

    @Override
    public List<PivotComponentSpec> getPivotComponents() {
        // Pivots on ADX line — strength-topping (peak) and strength-bottoming (trough) markers,
        // per delta Section 3 (episode/structural; NOT price-divergence semantics).
        return List.of(PivotComponentSpec.builder()
                .component(IndicatorComponent.ADX)
                .verb(BeatVerb.PEAKED)
                .significanceParams(params.getPivotParams())
                .refPrefix(null)
                .labelPrefix("ADX")
                .build());
    }

    @Override
    public List<CrossoverSpec> getCrossovers() {
        // No CrossoverSpec — DI crossover is handled in emitCustomBeats() so we can suppress it
        // when ADX < diCrossMinAdx (Wilder caveat).
        return List.of();
    }

    @Override
    public Optional<DivergenceSpec> getDivergence() {
        // ADX has no price-divergence semantics per delta.
        return Optional.empty();
    }

    @Override
    public List<ZoneSpec> getZones() {
        // Trending zone (ADX > strongTrendThreshold) — the primary ADX regime episode.
        // Range zone (ADX < rangeThreshold) — the other side.
        // 20-25 transitional is captured by absence (neither zone active).
        return List.of(
                ZoneSpec.builder()
                        .component(IndicatorComponent.ADX)
                        .name("strong_trend")
                        .lower(params.getStrongTrendThreshold())
                        .upper(100.0)
                        .minPersistenceBars(params.getRegimeMinPersistenceBars())
                        .refPrefix("adx_strong_")
                        .build(),
                ZoneSpec.builder()
                        .component(IndicatorComponent.ADX)
                        .name("range")
                        .lower(0.0)
                        .upper(params.getRangeThreshold())
                        .minPersistenceBars(params.getRegimeMinPersistenceBars())
                        .refPrefix("adx_range_")
                        .build());
    }

    /**
     * Custom beats for ADX:
     * <ul>
     *   <li>+DI/−DI directional crossover, but SUPPRESSED when ADX &lt; {@code diCrossMinAdx}
     *       (Wilder noise caveat — DI crosses in weak ADX are noise).</li>
     *   <li>Trend-initiation composite (PATTERN_043): if the engine emitted an
     *       {@code entered_zone} for {@code strong_trend} AND a DI cross occurred within ±N bars,
     *       emit ONE additional composite beat. (We do not suppress the underlying parts; we
     *       add a higher-significance link beat so the LLM sees the lifecycle.)</li>
     * </ul>
     */
    @Override
    public List<Beat> emitCustomBeats(IndicatorSeries series, List<OhlcBarDTO> bars,
                                       List<ZigZagPoint> pricePivots,
                                       List<com.dtech.aitrader.v2.narrative.beat.SwingState> swingStates,
                                       Map<IndicatorComponent, List<SeriesPivot>> pivotsByComponent) {
        double[] adx = series.getComponent(IndicatorComponent.ADX);
        double[] plusDi = series.getComponent(IndicatorComponent.PLUS_DI);
        double[] minusDi = series.getComponent(IndicatorComponent.MINUS_DI);
        int n = adx.length;
        List<Beat> beats = new ArrayList<>();
        double diMinAdx = params.getDiCrossMinAdx();

        // Track DI crossovers with ADX suppression.
        List<Integer> diCrossBars = new ArrayList<>();
        for (int i = 1; i < n; i++) {
            double prevDiff = plusDi[i - 1] - minusDi[i - 1];
            double currDiff = plusDi[i] - minusDi[i];
            if (prevDiff * currDiff > 0 || prevDiff == 0) continue;
            // Suppress when ADX too weak (no real trend present)
            if (adx[i] < diMinAdx) continue;

            boolean bullishCross = currDiff > 0;
            diCrossBars.add(i);
            beats.add(Beat.builder()
                    .what(BeatVerb.CROSSED)
                    .component(IndicatorComponent.ADX_DMI)
                    .whenBar(i)
                    .whenDate(instantToDateString(series.getBarTimestamps()[i]))
                    .value(adx[i])
                    .significance(0.8) // above the engine's 0.7 keep-gate
                    .consequence(Consequence.CONFIRMED)
                    .priceContext(PriceContextBuilder.buildAt(i, bars, pricePivots, swingStates))
                    .from(bullishCross ? "minus_di_above" : "plus_di_above")
                    .to(bullishCross ? "plus_di_above" : "minus_di_above")
                    .type(bullishCross ? "di_bullish" : "di_bearish")
                    .direction(bullishCross ? "bullish" : "bearish")
                    .ref("adx_di_" + (bullishCross ? "bull" : "bear") + "_" + i)
                    .note(String.format(
                            "DI directional cross — %s (+DI=%.1f / -DI=%.1f) at ADX=%.1f%s",
                            bullishCross ? "bullish" : "bearish",
                            plusDi[i], minusDi[i], adx[i],
                            adx[i] >= params.getStrongTrendThreshold() ? " [in strong trend]" : " [transitional]"))
                    .build());
        }

        // Trend-initiation composite (PATTERN_043) — when ADX crosses up through the strong-
        // trend threshold AND a DI cross sits within ±window bars, emit a composite trend-
        // initiation beat. Direction comes from the DI cross.
        int window = params.getTrendInitiationWindowBars();
        double strongThresh = params.getStrongTrendThreshold();
        for (int i = 1; i < n; i++) {
            if (adx[i - 1] < strongThresh && adx[i] >= strongThresh) {
                // ADX upcross of 25 at bar i — look for nearby DI cross
                int nearest = -1;
                for (int diBar : diCrossBars) {
                    if (Math.abs(diBar - i) <= window) {
                        if (nearest < 0 || Math.abs(diBar - i) < Math.abs(nearest - i)) nearest = diBar;
                    }
                }
                if (nearest >= 0) {
                    boolean bullish = plusDi[nearest] > minusDi[nearest];
                    beats.add(Beat.builder()
                            .what(BeatVerb.REGIME_CHANGE) // composite is a structural regime shift
                            .component(IndicatorComponent.ADX_DMI)
                            .whenBar(i)
                            .whenDate(instantToDateString(series.getBarTimestamps()[i]))
                            .value(adx[i])
                            .significance(1.0) // highest — Tier-1 regime initiation
                            .persistedBars(0)  // filled in below if we can measure the run
                            .consequence(Consequence.CONFIRMED)
                            .priceContext(PriceContextBuilder.buildAt(i, bars, pricePivots, swingStates))
                            .direction(bullish ? "bullish" : "bearish")
                            .ref("adx_trend_init_" + (bullish ? "bull" : "bear") + "_" + i)
                            .note(String.format(
                                    "Trend initiation (PATTERN_043) — ADX crossed up through %.0f at bar %d (now %.1f); "
                                            + "DI %s cross at bar %d (+DI=%.1f / -DI=%.1f). Strong-trend regime engaged %s.",
                                    strongThresh, i, adx[i],
                                    bullish ? "bullish" : "bearish",
                                    nearest, plusDi[nearest], minusDi[nearest],
                                    bullish ? "bullish" : "bearish"))
                            .build());
                }
            }
        }
        return beats;
    }

    @Override
    public Beat buildCurrentlyBeat(int lastIdx, IndicatorSeries series, List<OhlcBarDTO> bars) {
        double[] adx = series.getComponent(IndicatorComponent.ADX);
        double[] plusDi = series.getComponent(IndicatorComponent.PLUS_DI);
        double[] minusDi = series.getComponent(IndicatorComponent.MINUS_DI);
        double a = adx[lastIdx];
        double p = plusDi[lastIdx];
        double m = minusDi[lastIdx];
        String regime = a >= params.getStrongTrendThreshold() ? "strong_trend"
                : a <= params.getRangeThreshold() ? "range" : "transitional";
        String direction = p > m ? "bullish" : "bearish";

        String note = String.format(
                "ADX/DMI posture at last bar: ADX=%.2f (%s), +DI=%.2f, -DI=%.2f, direction=%s. "
                        + "Tier-1 regime conditioner: %s regime gates downstream OB/OS interpretation.",
                a, regime, p, m, direction,
                regime.equals("strong_trend") ? "STRONG TREND — activates FAILURE_001 (oscillator OB/OS persists, don't fade)" :
                regime.equals("range") ? "RANGE — oscillator OB/OS readings are mean-revert-actionable" :
                "TRANSITIONAL — interpret oscillator readings cautiously");

        return Beat.builder()
                .what(BeatVerb.CURRENTLY)
                .component(IndicatorComponent.ADX_DMI)
                .whenBar(lastIdx)
                .whenDate(instantToDateString(series.getBarTimestamps()[lastIdx]))
                .value(a)
                .significance(1.0)
                .consequence(Consequence.ONGOING)
                .tier(Tier.PRESENT)
                .ref("adx_now_" + lastIdx)
                .note(note)
                .build();
    }

    @Override
    public List<Checkpoint> buildVerificationCheckpoints(IndicatorSeries series,
                                                         List<SeriesPivot> primaryPivots,
                                                         int lastIdx) {
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
