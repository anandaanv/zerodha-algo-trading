package com.dtech.aitrader.v2.narrative.macd;

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
 * MACD-specific configuration ("delta") for the {@link DescriptiveNarrativeEngine}, per memsys
 * spec a2b5e3f3.
 *
 * <p>Provides:
 * <ul>
 *   <li>MACD(12, 26, 9) computation via {@link MacdComputer}</li>
 *   <li>Pivot detection on {@code macd_line} (PEAKED/TROUGHED) and {@code histogram} (THRUST)</li>
 *   <li>Zero-cross (regime-relevant) + signal-cross (not regime-relevant) crossovers</li>
 *   <li>Divergence on {@code macd_line} peaks/troughs against close price</li>
 *   <li>Engine params matching the pre-refactor MACD defaults so output is byte-identical</li>
 * </ul>
 */
@RequiredArgsConstructor
public class MacdIndicatorConfig implements IndicatorConfig {

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final MacdNarrativeParams params;

    @Override
    public String getIndicatorName() {
        return "MACD";
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
                .recentPeakedCap(4)
                .recentTroughedCap(4)
                .recentThrustCap(2)
                // MACD: emit every failed zero-cross. Preserves pre-Fix-3 behavior — MACD's
                // zero-cross is meaningful at any persistence because the line is unbounded.
                .failedAttemptMinBars(0)
                .build();
    }

    @Override
    public IndicatorSeries compute(List<OhlcBarDTO> bars, String symbol, String timeframe) {
        return MacdComputer.compute(bars,
                params.getFastPeriod(), params.getSlowPeriod(), params.getSignalPeriod(),
                symbol, timeframe);
    }

    @Override
    public List<PivotComponentSpec> getPivotComponents() {
        return List.of(
                // Primary: macd_line — PEAKED/TROUGHED + divergence source
                PivotComponentSpec.builder()
                        .component(IndicatorComponent.MACD_LINE)
                        .verb(BeatVerb.PEAKED) // engine emits both PEAKED and TROUGHED based on pivot kind
                        .significanceParams(params.getPivotParams())
                        .refPrefix(null) // engine derives "macd_pk_"/"macd_tr_" automatically
                        .labelPrefix("MACD")
                        .build(),
                // Secondary: histogram — THRUST
                PivotComponentSpec.builder()
                        .component(IndicatorComponent.HISTOGRAM)
                        .verb(BeatVerb.THRUST)
                        .significanceParams(params.getPivotParams())
                        .refPrefix("macd_thrust_")
                        .labelPrefix("MACD")
                        .build());
    }

    @Override
    public List<CrossoverSpec> getCrossovers() {
        return List.of(
                // Zero-cross: regime-relevant (regime_change | failed_attempt | crossed-ongoing per Fix 3)
                CrossoverSpec.builder()
                        .primary(IndicatorComponent.MACD_LINE)
                        .kind(CrossoverSpec.Kind.VS_LEVEL)
                        .level(0.0)
                        .regimeRelevant(true)
                        .aboveLabel("above_zero")
                        .belowLabel("below_zero")
                        .build(),
                // Signal-cross: not regime-relevant; emits bare CROSSED (tier filter drops it)
                CrossoverSpec.builder()
                        .primary(IndicatorComponent.MACD_LINE)
                        .reference(IndicatorComponent.SIGNAL_LINE)
                        .kind(CrossoverSpec.Kind.VS_LINE)
                        .regimeRelevant(false)
                        .aboveLabel("above_signal")
                        .belowLabel("below_signal")
                        .build());
    }

    @Override
    public Optional<DivergenceSpec> getDivergence() {
        return Optional.of(DivergenceSpec.builder()
                .component(IndicatorComponent.MACD_LINE)
                .beatComponent(IndicatorComponent.MACD_ALL)
                .componentLabel("MACD")
                .refPrefix("macd_div_")
                .build());
    }

    @Override
    public Beat buildCurrentlyBeat(int lastIdx, IndicatorSeries series, List<OhlcBarDTO> bars) {
        double[] macdLine = series.getComponent(IndicatorComponent.MACD_LINE);
        double[] signalLine = series.getComponent(IndicatorComponent.SIGNAL_LINE);
        double[] histogram = series.getComponent(IndicatorComponent.HISTOGRAM);

        String note = "MACD posture at last bar: line=" + String.format("%.2f", macdLine[lastIdx]) +
                ", signal=" + String.format("%.2f", signalLine[lastIdx]) +
                ", histogram=" + String.format("%.2f", histogram[lastIdx]);

        return Beat.builder()
                .what(BeatVerb.CURRENTLY)
                .component(IndicatorComponent.MACD_ALL)
                .whenBar(lastIdx)
                .whenDate(instantToDateString(series.getBarTimestamps()[lastIdx]))
                .value(macdLine[lastIdx])
                .significance(1.0)
                .consequence(Consequence.ONGOING)
                .macdLine(macdLine[lastIdx])
                .signalLine(signalLine[lastIdx])
                .histogram(histogram[lastIdx])
                .tier(Tier.PRESENT)
                .ref("macd_now_" + lastIdx)
                .note(note)
                .build();
    }

    @Override
    public List<Checkpoint> buildVerificationCheckpoints(IndicatorSeries series,
                                                        List<SeriesPivot> primaryPivots,
                                                        int lastIdx) {
        double[] macdLine = series.getComponent(IndicatorComponent.MACD_LINE);
        double[] signalLine = series.getComponent(IndicatorComponent.SIGNAL_LINE);
        double[] histogram = series.getComponent(IndicatorComponent.HISTOGRAM);

        List<Checkpoint> checkpoints = new ArrayList<>();
        for (SeriesPivot pivot : primaryPivots) {
            if (pivot.idx() <= lastIdx - 100) { // history tier cutoff (matches pre-refactor)
                checkpoints.add(Checkpoint.builder()
                        .bar(pivot.idx())
                        .macdLine(macdLine[pivot.idx()])
                        .signalLine(signalLine[pivot.idx()])
                        .histogram(histogram[pivot.idx()])
                        .build());
            }
        }
        checkpoints.add(Checkpoint.builder()
                .bar(lastIdx)
                .macdLine(macdLine[lastIdx])
                .signalLine(signalLine[lastIdx])
                .histogram(histogram[lastIdx])
                .build());
        return checkpoints;
    }

    private static String instantToDateString(Instant instant) {
        LocalDateTime ldt = LocalDateTime.ofInstant(instant, ZoneId.of("Asia/Kolkata"));
        return ldt.format(DATE_FORMATTER);
    }
}
