package com.dtech.aitrader.v2.narrative.roc;

import com.dtech.aitrader.v2.narrative.beat.*;
import com.dtech.aitrader.v2.narrative.engine.*;
import com.dtech.aitrader.v2.narrative.pivot.SeriesPivot;
import com.dtech.chartdata.model.OhlcBarDTO;
import lombok.RequiredArgsConstructor;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * ROC config. FULL_NARRATIVE tier per Narrative Core 533b3e85. Owner guidance (b3ff4ca0):
 * "unbounded momentum oscillator. Divergence + zero-line regime + thrust. Like MACD but
 * single-line. Horizon: medium (between MACD-long and Stoch-short). Equivalence: divergence
 * classes EQUIV_CLASS_003/004."
 *
 * <p>Pivot component is ROC itself (peaked/troughed + thrust on the same line; no separate
 * histogram). Zero-cross is regime-relevant.
 */
@RequiredArgsConstructor
public class RocIndicatorConfig implements IndicatorConfig {

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final RocNarrativeParams params;

    @Override
    public String getIndicatorName() {
        return "ROC";
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
                .historyRegimeCap(3)
                .recentPeakedCap(3)
                .recentTroughedCap(3)
                .recentThrustCap(2)
                .failedAttemptMinBars(0)
                .build();
    }

    @Override
    public IndicatorSeries compute(List<OhlcBarDTO> bars, String symbol, String timeframe) {
        return RocComputer.compute(bars, params.getPeriod(), symbol, timeframe);
    }

    @Override
    public List<PivotComponentSpec> getPivotComponents() {
        return List.of(
                PivotComponentSpec.builder()
                        .component(IndicatorComponent.ROC)
                        .verb(BeatVerb.PEAKED)
                        .significanceParams(params.getPivotParams())
                        .refPrefix(null)
                        .labelPrefix("ROC")
                        .build(),
                PivotComponentSpec.builder()
                        .component(IndicatorComponent.ROC)
                        .verb(BeatVerb.THRUST)
                        .significanceParams(params.getPivotParams())
                        .refPrefix("roc_thrust_")
                        .labelPrefix("ROC")
                        .build());
    }

    @Override
    public List<CrossoverSpec> getCrossovers() {
        return List.of(
                CrossoverSpec.builder()
                        .primary(IndicatorComponent.ROC)
                        .kind(CrossoverSpec.Kind.VS_LEVEL)
                        .level(0.0)
                        .regimeRelevant(true)
                        .aboveLabel("above_zero")
                        .belowLabel("below_zero")
                        .build());
    }

    @Override
    public Optional<DivergenceSpec> getDivergence() {
        return Optional.of(DivergenceSpec.builder()
                .component(IndicatorComponent.ROC)
                .beatComponent(IndicatorComponent.ROC)
                .componentLabel("ROC")
                .refPrefix("roc_div_")
                .build());
    }

    @Override
    public Beat buildCurrentlyBeat(int lastIdx, IndicatorSeries series, List<OhlcBarDTO> bars) {
        double[] roc = series.getComponent(IndicatorComponent.ROC);
        double r = roc[lastIdx];
        String dir = r > 0 ? "positive_momentum" : r < 0 ? "negative_momentum" : "flat";
        String magnitude = Math.abs(r) >= 10 ? "strong" : Math.abs(r) >= 3 ? "moderate" : "weak";
        String note = String.format(
                "ROC(%d) posture at last bar: %.2f%% — %s %s.",
                params.getPeriod(), r, magnitude, dir);
        return Beat.builder()
                .what(BeatVerb.CURRENTLY)
                .component(IndicatorComponent.ROC)
                .whenBar(lastIdx)
                .whenDate(instantToDateString(series.getBarTimestamps()[lastIdx]))
                .value(r)
                .significance(1.0)
                .consequence(Consequence.ONGOING)
                .tier(Tier.PRESENT)
                .ref("roc_now_" + lastIdx)
                .note(note)
                .build();
    }

    @Override
    public List<Checkpoint> buildVerificationCheckpoints(IndicatorSeries series,
                                                         List<SeriesPivot> primaryPivots,
                                                         int lastIdx) {
        List<Checkpoint> cps = new ArrayList<>();
        for (SeriesPivot p : primaryPivots) {
            if (p.idx() <= lastIdx - 100) cps.add(Checkpoint.builder().bar(p.idx()).build());
        }
        cps.add(Checkpoint.builder().bar(lastIdx).build());
        return cps;
    }

    private static String instantToDateString(Instant instant) {
        LocalDateTime ldt = LocalDateTime.ofInstant(instant, ZoneId.of("Asia/Kolkata"));
        return ldt.format(DATE_FORMATTER);
    }
}
