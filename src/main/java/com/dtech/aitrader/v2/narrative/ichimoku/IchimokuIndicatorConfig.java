package com.dtech.aitrader.v2.narrative.ichimoku;

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
 * Ichimoku config. SNAPSHOT tier per Narrative Core 533b3e85. Owner guidance (b3ff4ca0):
 * "positional/boolean: price vs cloud, TK cross, cloud color, future twist. Emit as a STATE line
 * in the bundle (current posture), NOT a narrative with history/divergence."
 *
 * <p>The {@code currently} beat carries everything at the last bar:
 * <ul>
 *   <li>Price vs cloud (above / in / below)</li>
 *   <li>TK relation (tenkan above / below kijun) + recent cross direction if any</li>
 *   <li>Cloud color (Senkou A vs B → bullish / bearish)</li>
 *   <li>Future twist (does the cloud color invert within the displacement window ahead?) —
 *       only knowable from the unshifted cloud, so reported as a posture only when the bar's
 *       Senkou A/B values flip relative to a few bars ago.</li>
 * </ul>
 * REGIMEWT_012 (cloud as integrated regime).
 */
@RequiredArgsConstructor
public class IchimokuIndicatorConfig implements IndicatorConfig {

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final IchimokuNarrativeParams params;

    @Override
    public String getIndicatorName() {
        return "Ichimoku";
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
        return IchimokuComputer.compute(bars, params.getTenkanPeriod(), params.getKijunPeriod(),
                params.getSenkouBPeriod(), params.getDisplacement(), symbol, timeframe);
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
        IchimokuSeries is = (IchimokuSeries) series;
        double tk = is.getTenkan()[lastIdx];
        double kj = is.getKijun()[lastIdx];
        double sa = is.getSenkouA()[lastIdx];
        double sb = is.getSenkouB()[lastIdx];
        double cl = is.getCloses()[lastIdx];

        double cloudTop = Math.max(sa, sb);
        double cloudBot = Math.min(sa, sb);
        String pricePos = cl > cloudTop ? "above_cloud"
                : cl < cloudBot ? "below_cloud" : "in_cloud";
        String cloudColor = sa > sb ? "bullish" : sa < sb ? "bearish" : "flat";
        String tkRel = tk > kj ? "tenkan_above_kijun" : tk < kj ? "tenkan_below_kijun" : "tk_aligned";

        // Detect a recent TK cross (within the present window).
        int win = Math.min(params.getPresentWindowBars(), lastIdx);
        String tkCrossNote = "no_recent_TK_cross";
        for (int j = lastIdx; j >= lastIdx - win + 1 && j > 0; j--) {
            double tkj = is.getTenkan()[j], kjj = is.getKijun()[j];
            double tkp = is.getTenkan()[j - 1], kjp = is.getKijun()[j - 1];
            if ((tkp - kjp) * (tkj - kjj) < 0) {
                boolean bull = tkj > kjj;
                tkCrossNote = String.format("TK_cross_%s@%d_bars_ago", bull ? "bullish" : "bearish",
                        lastIdx - j);
                break;
            }
        }

        // Future twist: is the (unshifted) cloud about to invert?  Approximated by the
        // sign of (sa - sb) {disp} bars back vs now — if it flipped within the cloud window,
        // a twist is approaching.
        int disp = params.getDisplacement();
        String twistNote = "no_future_twist";
        if (lastIdx >= disp) {
            double saPast = is.getSenkouA()[lastIdx - disp];
            double sbPast = is.getSenkouB()[lastIdx - disp];
            double now = sa - sb;
            double past = saPast - sbPast;
            if (now * past < 0) twistNote = "future_twist_pending";
        }

        String note = String.format(
                "Ichimoku posture at last bar: price=%.2f %s (cloud[%.2f .. %.2f], color=%s); " +
                        "Tenkan=%.2f, Kijun=%.2f (%s; %s); %s. REGIMEWT_012 — integrated regime.",
                cl, pricePos, cloudBot, cloudTop, cloudColor, tk, kj, tkRel, tkCrossNote, twistNote);

        return Beat.builder()
                .what(BeatVerb.CURRENTLY)
                .component(IndicatorComponent.ICHIMOKU)
                .whenBar(lastIdx)
                .whenDate(instantToDateString(series.getBarTimestamps()[lastIdx]))
                .value(cl)
                .significance(1.0)
                .consequence(Consequence.ONGOING)
                .tier(Tier.PRESENT)
                .ref("ichimoku_now_" + lastIdx)
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
