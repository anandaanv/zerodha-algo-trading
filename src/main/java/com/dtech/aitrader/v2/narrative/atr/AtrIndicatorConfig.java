package com.dtech.aitrader.v2.narrative.atr;

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
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * ATR(14) config. SNAPSHOT tier per Narrative Core 533b3e85 — pure volatility conditioner. Owner
 * guidance (b3ff4ca0): "Emit as a volatility LEVEL / percentile (e.g. 'ATR at 30th percentile of
 * own range, low-vol'), used to gate squeeze/breakout patterns downstream. NOT a narrative."
 *
 * <p>The {@code currently} beat carries everything: absolute ATR value, percentile within its own
 * recent range, low/normal/high-vol classification. No pivots, no crossovers, no divergence, no
 * zones, no custom beats — just the state line. REGIMEWT_014 (volatility-conditioner).
 */
@RequiredArgsConstructor
public class AtrIndicatorConfig implements IndicatorConfig {

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final AtrNarrativeParams params;

    @Override
    public String getIndicatorName() {
        return "ATR";
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
        return AtrComputer.compute(bars, params.getPeriod(), symbol, timeframe);
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
        double[] atr = series.getComponent(IndicatorComponent.ATR);
        double cur = atr[lastIdx];
        double close = bars.get(lastIdx).getClose();

        // Percentile of cur within the trailing percentileWindow.
        int win = Math.min(params.getPercentileWindow(), lastIdx + 1);
        double[] window = Arrays.copyOfRange(atr, lastIdx - win + 1, lastIdx + 1);
        double[] sorted = window.clone();
        Arrays.sort(sorted);
        int rank = Arrays.binarySearch(sorted, cur);
        if (rank < 0) rank = -rank - 1;
        double pctile = sorted.length > 0 ? (double) rank / sorted.length : 0.5;
        String volRegime = pctile <= params.getLowVolPercentile() ? "low_vol"
                : pctile >= params.getHighVolPercentile() ? "high_vol" : "normal_vol";

        double atrPctOfClose = close != 0 ? (cur / close) * 100.0 : 0.0;

        String note = String.format(
                "ATR=%.2f (%.1f%% of close, %.0fth pctile of last %d bars, %s). " +
                        "Volatility conditioner — gates squeeze/breakout reliability downstream.",
                cur, atrPctOfClose, pctile * 100, win, volRegime);

        return Beat.builder()
                .what(BeatVerb.CURRENTLY)
                .component(IndicatorComponent.ATR)
                .whenBar(lastIdx)
                .whenDate(instantToDateString(series.getBarTimestamps()[lastIdx]))
                .value(cur)
                .significance(1.0)
                .consequence(Consequence.ONGOING)
                .tier(Tier.PRESENT)
                .ref("atr_now_" + lastIdx)
                .note(note)
                .build();
    }

    @Override
    public List<Checkpoint> buildVerificationCheckpoints(IndicatorSeries series,
                                                         List<SeriesPivot> primaryPivots,
                                                         int lastIdx) {
        // Snapshot tier — only the last bar matters.
        List<Checkpoint> cps = new ArrayList<>();
        cps.add(Checkpoint.builder().bar(lastIdx).build());
        return cps;
    }

    private static String instantToDateString(Instant instant) {
        LocalDateTime ldt = LocalDateTime.ofInstant(instant, ZoneId.of("Asia/Kolkata"));
        return ldt.format(DATE_FORMATTER);
    }
}
