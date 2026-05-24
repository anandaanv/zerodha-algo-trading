package com.dtech.aitrader.v2.narrative.vwap;

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
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * VWAP config. SNAPSHOT tier per Narrative Core 533b3e85. Owner guidance (b3ff4ca0): above/below
 * + distance; anchored, resets. STATE line only. REGIMEWT_013 (anchor decay).
 *
 * <p>This v0.1 uses rolling VWAP (period-based). On daily/weekly TFs the rolling VWAP is close to
 * the close price; the state line still reports the relation so consumers can disregard. On
 * hourly/15-min the rolling VWAP is meaningful as a short-term mean-reference.
 */
@RequiredArgsConstructor
public class VwapIndicatorConfig implements IndicatorConfig {

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final VwapNarrativeParams params;

    @Override
    public String getIndicatorName() {
        return "VWAP";
    }

    @Override
    public NarrativeTier getNarrativeTier() {
        return NarrativeTier.SNAPSHOT;
    }

    @Override
    public EngineParams getEngineParams() {
        return EngineParams.builder()
                .defaultPivotParams(params.getPivotParams())
                .presentWindowBars(params.getPresentWindowBars())
                .recentWindowBars(params.getRecentWindowBars())
                .regimeChangePersistenceBars(0)
                .historyPeakedCap(0)
                .historyTroughedCap(0)
                .historyRegimeCap(0)
                .recentPeakedCap(0)
                .recentTroughedCap(0)
                .recentThrustCap(0)
                .historyZoneCap(0)
                .failedAttemptMinBars(0)
                .build();
    }

    @Override
    public IndicatorSeries compute(List<OhlcBarDTO> bars, String symbol, String timeframe) {
        return VwapComputer.compute(bars, params.getPeriod(), symbol, timeframe);
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
    public Beat buildCurrentlyBeat(int lastIdx, IndicatorSeries series, List<OhlcBarDTO> bars) {
        VwapSeries vs = (VwapSeries) series;
        double v = vs.getVwap()[lastIdx];
        double c = vs.getCloses()[lastIdx];
        double distPct = v != 0 ? ((c - v) / v) * 100.0 : 0.0;
        String side = c > v ? "above" : c < v ? "below" : "at";
        String materiality = Math.abs(distPct) >= params.getMaterialDistancePct() ? "material" : "near";
        String note = String.format(
                "VWAP(%d)=%.2f, close=%.2f, %s VWAP by %.2f%% (%s). Anchor: rolling %d-bar window.",
                params.getPeriod(), v, c, side, distPct, materiality, params.getPeriod());

        return Beat.builder()
                .what(BeatVerb.CURRENTLY)
                .component(IndicatorComponent.VWAP)
                .whenBar(lastIdx)
                .whenDate(instantToDateString(series.getBarTimestamps()[lastIdx]))
                .value(v)
                .significance(1.0)
                .consequence(Consequence.ONGOING)
                .tier(Tier.PRESENT)
                .ref("vwap_now_" + lastIdx)
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
