package com.dtech.aitrader.v2.narrative.rsi;

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
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * RSI-specific configuration ("delta") for the {@link DescriptiveNarrativeEngine}, per memsys
 * spec 39d06c2e.
 *
 * <p>RSI's signature is the SPLIT HORIZON profile:
 * <ul>
 *   <li>DEEP regime memory — Brown bull/bear range shifts persist into history tier.</li>
 *   <li>SHALLOW episode memory — oversold/overbought episodes decay fast.</li>
 * </ul>
 *
 * <p>What's wired now:
 * <ul>
 *   <li>RSI(14) via ta4j {@link org.ta4j.core.indicators.RSIIndicator}.</li>
 *   <li>Pivot detection on the RSI line (peaked/troughed; for divergence).</li>
 *   <li>50 centerline cross (regime-relevant, fallback to Brown).</li>
 *   <li>Divergence (RSI line vs close price) — regular bull/bear, with Fix 2 invalidation update.</li>
 *   <li>Zones: oversold {@code [0,30]}, overbought {@code [70,100]} (absolute defaults).</li>
 *   <li>Brown range classifier emitting {@code regime_change} beats on bull↔bear flips.</li>
 * </ul>
 *
 * <p>Deferred (TODO, owner-acknowledged in spec): Wilder failure-swing (PATTERN_006/007),
 * Cardwell positive/negative reversal (PATTERN_004/005), lifecycle phase linking.
 */
@RequiredArgsConstructor
public class RsiIndicatorConfig implements IndicatorConfig {

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final RsiNarrativeParams params;

    @Override
    public String getIndicatorName() {
        return "RSI";
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
                .recentThrustCap(0) // RSI has no thrust verb (bounded)
                .build();
    }

    @Override
    public IndicatorSeries compute(List<OhlcBarDTO> bars, String symbol, String timeframe) {
        return RsiComputer.compute(bars, params.getPeriod(), symbol, timeframe);
    }

    @Override
    public List<PivotComponentSpec> getPivotComponents() {
        return List.of(PivotComponentSpec.builder()
                .component(IndicatorComponent.RSI)
                .verb(BeatVerb.PEAKED)
                .significanceParams(params.getPivotParams())
                .refPrefix(null) // engine derives "rsi_pk_"/"rsi_tr_"
                .labelPrefix("RSI")
                .build());
    }

    @Override
    public List<CrossoverSpec> getCrossovers() {
        return List.of(CrossoverSpec.builder()
                .primary(IndicatorComponent.RSI)
                .kind(CrossoverSpec.Kind.VS_LEVEL)
                .level(50.0)
                .regimeRelevant(true)
                .aboveLabel("above_50")
                .belowLabel("below_50")
                .build());
    }

    @Override
    public Optional<DivergenceSpec> getDivergence() {
        return Optional.of(DivergenceSpec.builder()
                .component(IndicatorComponent.RSI)
                .beatComponent(IndicatorComponent.RSI_ALL)
                .componentLabel("RSI")
                .refPrefix("rsi_div_")
                .build());
    }

    @Override
    public List<ZoneSpec> getZones() {
        return List.of(
                ZoneSpec.builder()
                        .component(IndicatorComponent.RSI)
                        .name("oversold")
                        .lower(0.0)
                        .upper(params.getOversoldThreshold())
                        .minPersistenceBars(params.getZoneMinPersistenceBars())
                        .refPrefix("rsi_os_")
                        .build(),
                ZoneSpec.builder()
                        .component(IndicatorComponent.RSI)
                        .name("overbought")
                        .lower(params.getOverboughtThreshold())
                        .upper(100.0)
                        .minPersistenceBars(params.getZoneMinPersistenceBars())
                        .refPrefix("rsi_ob_")
                        .build());
    }

    /**
     * Brown bull/bear range classifier emitted as {@link BeatVerb#REGIME_CHANGE} beats.
     *
     * <p>Permissive heuristic per the governing principle: median(RSI, window) decides regime.
     * Median &gt; {@code brownBullMedianMin} → bull; &lt; {@code brownBearMedianMax} → bear;
     * else NONE/transitioning. A new regime is emitted only after {@code brownRegimePersistenceBars}
     * of holding (kills whipsaw).
     */
    @Override
    public List<Beat> emitCustomBeats(IndicatorSeries series, List<OhlcBarDTO> bars,
                                       List<ZigZagPoint> pricePivots, List<com.dtech.aitrader.v2.narrative.beat.SwingState> swingStates) {
        double[] rsi = series.getComponent(IndicatorComponent.RSI);
        int n = rsi.length;
        int window = params.getBrownRegimeWindowBars();
        int persistence = params.getBrownRegimePersistenceBars();
        if (n < window) return List.of();

        // Per-bar regime label using a rolling median.
        Regime[] regimes = new Regime[n];
        for (int i = 0; i < n; i++) {
            if (i < window - 1) {
                regimes[i] = Regime.UNDEFINED;
                continue;
            }
            double median = medianOfWindow(rsi, i - window + 1, i);
            if (median > params.getBrownBullMedianMin()) regimes[i] = Regime.BULL;
            else if (median < params.getBrownBearMedianMax()) regimes[i] = Regime.BEAR;
            else regimes[i] = Regime.NONE;
        }

        // Find persistent regime transitions
        List<Beat> beats = new ArrayList<>();
        Regime current = Regime.UNDEFINED;
        int currentStart = -1;
        for (int i = 0; i < n; i++) {
            if (regimes[i] == Regime.UNDEFINED) continue;
            if (regimes[i] != current) {
                // Candidate flip — check it persists
                int holdCount = 1;
                for (int j = i + 1; j < n && j < i + persistence + 1; j++) {
                    if (regimes[j] == regimes[i]) holdCount++;
                    else break;
                }
                if (holdCount >= Math.min(persistence, n - i) && regimes[i] != Regime.NONE) {
                    // Confirmed regime; emit if it differs from prior confirmed regime
                    if (current != regimes[i] && current != Regime.UNDEFINED) {
                        int persistedBars = countForwardRegimeHold(regimes, i);
                        beats.add(buildBrownRegimeBeat(i, regimes[i], persistedBars, rsi, series, bars,
                                pricePivots, swingStates));
                    } else if (current == Regime.UNDEFINED) {
                        // First confirmed regime; record the start but do not emit a "change" beat
                        // (there was no prior regime to change from).
                    }
                    current = regimes[i];
                    currentStart = i;
                }
            }
        }
        return beats;
    }

    private int countForwardRegimeHold(Regime[] regimes, int from) {
        Regime r = regimes[from];
        int count = 1;
        for (int j = from + 1; j < regimes.length; j++) {
            if (regimes[j] == r) count++;
            else break;
        }
        return count;
    }

    private Beat buildBrownRegimeBeat(int bar, Regime newRegime, int persistedBars, double[] rsi,
                                       IndicatorSeries series, List<OhlcBarDTO> bars,
                                       List<ZigZagPoint> pricePivots,
                                       List<com.dtech.aitrader.v2.narrative.beat.SwingState> swingStates) {
        return Beat.builder()
                .what(BeatVerb.REGIME_CHANGE)
                .component(IndicatorComponent.RSI)
                .whenBar(bar)
                .whenDate(instantToDateString(series.getBarTimestamps()[bar]))
                .value(rsi[bar])
                .significance(0.9)
                .persistedBars(persistedBars)
                .consequence(Consequence.CONFIRMED)
                .priceContext(PriceContextBuilder.buildAt(bar, bars, pricePivots, swingStates))
                .ref("rsi_brown_" + newRegime.name().toLowerCase() + "_" + bar)
                .note("Brown range regime turned " + newRegime.label
                        + " (held " + persistedBars + " bars, median RSI over " + params.getBrownRegimeWindowBars()
                        + "-bar window crossed " + (newRegime == Regime.BULL ?
                            "above " + params.getBrownBullMedianMin() :
                            "below " + params.getBrownBearMedianMax()) + ")")
                .build();
    }

    private double medianOfWindow(double[] rsi, int from, int to) {
        int len = to - from + 1;
        double[] copy = Arrays.copyOfRange(rsi, from, to + 1);
        Arrays.sort(copy);
        if (len % 2 == 1) return copy[len / 2];
        return (copy[len / 2 - 1] + copy[len / 2]) / 2.0;
    }

    @Override
    public Beat buildCurrentlyBeat(int lastIdx, IndicatorSeries series, List<OhlcBarDTO> bars) {
        double[] rsi = series.getComponent(IndicatorComponent.RSI);
        double r = rsi[lastIdx];

        String zoneLabel;
        if (r <= params.getOversoldThreshold()) zoneLabel = "oversold";
        else if (r >= params.getOverboughtThreshold()) zoneLabel = "overbought";
        else zoneLabel = "neutral";

        // Current Brown regime (recompute at last bar)
        int window = params.getBrownRegimeWindowBars();
        String regimeLabel = "transitioning";
        if (lastIdx >= window - 1) {
            double median = medianOfWindow(rsi, lastIdx - window + 1, lastIdx);
            if (median > params.getBrownBullMedianMin()) regimeLabel = "bull_range";
            else if (median < params.getBrownBearMedianMax()) regimeLabel = "bear_range";
            else regimeLabel = "transitioning";
        }

        String note = String.format("RSI posture at last bar: rsi=%.2f, zone=%s, brown_regime=%s",
                r, zoneLabel, regimeLabel);

        return Beat.builder()
                .what(BeatVerb.CURRENTLY)
                .component(IndicatorComponent.RSI_ALL)
                .whenBar(lastIdx)
                .whenDate(instantToDateString(series.getBarTimestamps()[lastIdx]))
                .value(r)
                .significance(1.0)
                .consequence(Consequence.ONGOING)
                .tier(Tier.PRESENT)
                .ref("rsi_now_" + lastIdx)
                .note(note)
                .build();
    }

    @Override
    public List<Checkpoint> buildVerificationCheckpoints(IndicatorSeries series,
                                                         List<SeriesPivot> primaryPivots,
                                                         int lastIdx) {
        double[] rsi = series.getComponent(IndicatorComponent.RSI);
        List<Checkpoint> checkpoints = new ArrayList<>();
        for (SeriesPivot pivot : primaryPivots) {
            if (pivot.idx() <= lastIdx - 100) {
                checkpoints.add(Checkpoint.builder()
                        .bar(pivot.idx())
                        .macdLine(rsi[pivot.idx()]) // reusing the field for RSI value — schema is shared
                        .build());
            }
        }
        checkpoints.add(Checkpoint.builder()
                .bar(lastIdx)
                .macdLine(rsi[lastIdx])
                .build());
        return checkpoints;
    }

    private static String instantToDateString(Instant instant) {
        LocalDateTime ldt = LocalDateTime.ofInstant(instant, ZoneId.of("Asia/Kolkata"));
        return ldt.format(DATE_FORMATTER);
    }

    private enum Regime {
        UNDEFINED("undefined"),
        BULL("bull"),
        BEAR("bear"),
        NONE("transitioning");

        final String label;

        Regime(String label) {
            this.label = label;
        }
    }
}
