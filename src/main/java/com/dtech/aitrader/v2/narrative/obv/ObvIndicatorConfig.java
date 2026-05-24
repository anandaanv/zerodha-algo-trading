package com.dtech.aitrader.v2.narrative.obv;

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
 * OBV config. FULL_NARRATIVE tier per Narrative Core 533b3e85, but with a constrained verb set:
 * DIVERGENCE is the primary signal; no thrust (cumulative scale isn't comparable), no zero-cross
 * (OBV value is arbitrary). The {@code currently} cell carries the recent OBV trend (rising /
 * falling / flat vs N bars ago) as the volume-confirmation gate state — per owner guidance: "the
 * narrative carries the divergence + the confirmation state." EQUIV_CLASS_009.
 */
@RequiredArgsConstructor
public class ObvIndicatorConfig implements IndicatorConfig {

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final ObvNarrativeParams params;

    @Override
    public String getIndicatorName() {
        return "OBV";
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
                .regimeChangePersistenceBars(0)
                .historyPeakedCap(2)
                .historyTroughedCap(2)
                .historyRegimeCap(0)
                .recentPeakedCap(3)
                .recentTroughedCap(3)
                .recentThrustCap(0)
                .historyZoneCap(0)
                .failedAttemptMinBars(0)
                .build();
    }

    @Override
    public IndicatorSeries compute(List<OhlcBarDTO> bars, String symbol, String timeframe) {
        return ObvComputer.compute(bars, symbol, timeframe);
    }

    @Override
    public List<PivotComponentSpec> getPivotComponents() {
        return List.of(
                PivotComponentSpec.builder()
                        .component(IndicatorComponent.OBV)
                        .verb(BeatVerb.PEAKED)
                        .significanceParams(params.getPivotParams())
                        .refPrefix(null)
                        .labelPrefix("OBV")
                        .build());
    }

    @Override
    public List<CrossoverSpec> getCrossovers() {
        return Collections.emptyList();
    }

    @Override
    public Optional<DivergenceSpec> getDivergence() {
        return Optional.of(DivergenceSpec.builder()
                .component(IndicatorComponent.OBV)
                .beatComponent(IndicatorComponent.OBV)
                .componentLabel("OBV")
                .refPrefix("obv_div_")
                .build());
    }

    @Override
    public Beat buildCurrentlyBeat(int lastIdx, IndicatorSeries series, List<OhlcBarDTO> bars) {
        double[] obv = series.getComponent(IndicatorComponent.OBV);
        double cur = obv[lastIdx];

        // Volume-confirmation gate: trend of OBV over the present window vs price.
        int lookback = Math.min(params.getPresentWindowBars(), lastIdx);
        double startObv = obv[lastIdx - lookback];
        double startClose = bars.get(lastIdx - lookback).getClose();
        double curClose = bars.get(lastIdx).getClose();
        double obvDelta = cur - startObv;
        double priceDeltaPct = startClose != 0 ? (curClose - startClose) / startClose * 100.0 : 0.0;

        String obvDir = Math.abs(obvDelta) < 1e-9 ? "flat"
                : obvDelta > 0 ? "rising" : "falling";
        String priceDir = Math.abs(priceDeltaPct) < 0.1 ? "flat"
                : priceDeltaPct > 0 ? "rising" : "falling";
        String confirmation;
        if (obvDir.equals(priceDir) && !obvDir.equals("flat")) {
            confirmation = "confirming"; // volume agrees with price
        } else if (!obvDir.equals(priceDir) && !obvDir.equals("flat") && !priceDir.equals("flat")) {
            confirmation = "diverging";  // volume disagrees with price
        } else {
            confirmation = "ambiguous";  // one side flat
        }

        String note = String.format(
                "OBV cumulative=%s. Over last %d bars: OBV %s, price %s %.2f%%; volume %s price. Gate: %s.",
                humanize(cur), lookback, obvDir, priceDir, priceDeltaPct, confirmation, confirmation);

        return Beat.builder()
                .what(BeatVerb.CURRENTLY)
                .component(IndicatorComponent.OBV)
                .whenBar(lastIdx)
                .whenDate(instantToDateString(series.getBarTimestamps()[lastIdx]))
                .value(cur)
                .significance(1.0)
                .consequence(Consequence.ONGOING)
                .tier(Tier.PRESENT)
                .ref("obv_now_" + lastIdx)
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

    /** Humanize large/small cumulative OBV: 4098790136 → 4.1B; -1.2e9 → -1.2B; ±1234 → 1234. */
    private static String humanize(double v) {
        double abs = Math.abs(v);
        String sign = v < 0 ? "-" : "";
        if (abs >= 1e9) return String.format("%s%.1fB", sign, abs / 1e9);
        if (abs >= 1e6) return String.format("%s%.1fM", sign, abs / 1e6);
        if (abs >= 1e3) return String.format("%s%.1fK", sign, abs / 1e3);
        return String.format("%s%.0f", sign, abs);
    }
}
