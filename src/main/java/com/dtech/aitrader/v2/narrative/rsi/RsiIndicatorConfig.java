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
                // RSI emits TWO kinds of regime_change beats: Brown range shifts (load-bearing
                // regime context) and 50-centerline crosses (FAST-decay events per delta).
                // The default cap of 3 ranks them together by persistedBars — and the 50-cross
                // beats often outrank Brown beats on persistence, silently dropping the
                // regime context the LLM needs. Raising the cap so both kinds survive.
                // Per owner's "fix honesty, ignore precision" principle, more honest beats > tighter cap.
                .historyRegimeCap(10)
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
        // RSI zones are REGIME-RELATIVE per Brown — handled inside emitCustomBeats() where the
        // active regime is known per bar. Returning empty here keeps the engine's absolute-zone
        // emitter out of the way.
        return List.of();
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

        // Find persistent regime transitions, emit regime_change beats, and remember the
        // committed regime per bar so the zone emitter below can use the right thresholds.
        List<Beat> beats = new ArrayList<>();
        Regime current = Regime.UNDEFINED;
        Regime[] committedRegime = new Regime[n];
        for (int i = 0; i < n; i++) {
            committedRegime[i] = current;
            if (regimes[i] == Regime.UNDEFINED) continue;
            if (regimes[i] != current) {
                // Candidate flip — check it persists
                int holdCount = 1;
                for (int j = i + 1; j < n && j < i + persistence + 1; j++) {
                    if (regimes[j] == regimes[i]) holdCount++;
                    else break;
                }
                if (holdCount >= Math.min(persistence, n - i) && regimes[i] != Regime.NONE) {
                    // Emit on every persistent commit, including the FIRST one. Silently
                    // swallowing the UNDEFINED→BULL/BEAR transition (as the first cut did) is
                    // dishonest: it makes the LLM think there is no regime when there is one.
                    // SBIN was the symptom — bull_range at last bar with zero regime_change
                    // beats.
                    if (current != regimes[i]) {
                        int persistedBars = countForwardRegimeHold(regimes, i);
                        beats.add(buildBrownRegimeBeat(i, regimes[i], persistedBars, rsi, series, bars,
                                pricePivots, swingStates,
                                current == Regime.UNDEFINED));
                    }
                    current = regimes[i];
                    committedRegime[i] = current;
                }
            }
        }

        // Regime-relative zone emission (FIX 1 from owner validation memo d3020077).
        // Per Brown: zones are not absolute — they shift with the active regime. Bull-range
        // support (~40-50) is the bull-regime OS line; bear-range resistance (~55-65) is the
        // bear-regime OB line. UNDEFINED / NONE bars fall back to absolute 30/70.
        beats.addAll(emitRegimeRelativeZoneBeats(rsi, committedRegime, series, bars, pricePivots, swingStates));

        return beats;
    }

    /**
     * Walk the series with per-bar regime-aware zone thresholds. Emits {@code entered_zone} when
     * RSI crosses INTO a zone (from outside), {@code exited_zone} when it leaves (with
     * {@code persistedBars}). Each beat's note records the active regime so the LLM consumer
     * sees the regime context the zone meaning depends on.
     */
    private List<Beat> emitRegimeRelativeZoneBeats(double[] rsi, Regime[] committedRegime,
                                                    IndicatorSeries series, List<OhlcBarDTO> bars,
                                                    List<ZigZagPoint> pricePivots,
                                                    List<com.dtech.aitrader.v2.narrative.beat.SwingState> swingStates) {
        List<Beat> beats = new ArrayList<>();
        int n = rsi.length;

        // OS and OB are tracked independently so we can have both kinds of episode in a single run.
        boolean inOs = false;
        int osEntry = -1;
        Regime osEntryRegime = Regime.UNDEFINED;
        boolean inOb = false;
        int obEntry = -1;
        Regime obEntryRegime = Regime.UNDEFINED;

        for (int i = 0; i < n; i++) {
            Regime regime = committedRegime[i];
            double osUpper = regime == Regime.BULL ? params.getBullRegimeOsUpper()
                    : regime == Regime.BEAR ? params.getBearRegimeOsUpper()
                    : params.getOversoldThreshold();
            double obLower = regime == Regime.BULL ? params.getBullRegimeObLower()
                    : regime == Regime.BEAR ? params.getBearRegimeObLower()
                    : params.getOverboughtThreshold();

            boolean osNow = rsi[i] <= osUpper;
            boolean obNow = rsi[i] >= obLower;

            // OS transitions
            if (osNow && !inOs) {
                osEntry = i;
                osEntryRegime = regime;
                inOs = true;
            } else if (!osNow && inOs) {
                int persisted = i - osEntry;
                if (persisted >= Math.max(1, params.getZoneMinPersistenceBars())) {
                    beats.add(buildZoneBeat(BeatVerb.ENTERED_ZONE, osEntry, rsi[osEntry], null,
                            "rsi_os_in_" + osEntry,
                            zoneLabel("oversold", osEntryRegime, osUpperForRegime(osEntryRegime)),
                            series, bars, pricePivots, swingStates));
                    beats.add(buildZoneBeat(BeatVerb.EXITED_ZONE, i, rsi[i], persisted,
                            "rsi_os_out_" + i,
                            "Exited " + regimeOsName(osEntryRegime) + " after " + persisted + " bars",
                            series, bars, pricePivots, swingStates));
                }
                inOs = false;
            }

            // OB transitions
            if (obNow && !inOb) {
                obEntry = i;
                obEntryRegime = regime;
                inOb = true;
            } else if (!obNow && inOb) {
                int persisted = i - obEntry;
                if (persisted >= Math.max(1, params.getZoneMinPersistenceBars())) {
                    beats.add(buildZoneBeat(BeatVerb.ENTERED_ZONE, obEntry, rsi[obEntry], null,
                            "rsi_ob_in_" + obEntry,
                            zoneLabel("overbought", obEntryRegime, obLowerForRegime(obEntryRegime)),
                            series, bars, pricePivots, swingStates));
                    beats.add(buildZoneBeat(BeatVerb.EXITED_ZONE, i, rsi[i], persisted,
                            "rsi_ob_out_" + i,
                            "Exited " + regimeObName(obEntryRegime) + " after " + persisted + " bars",
                            series, bars, pricePivots, swingStates));
                }
                inOb = false;
            }
        }

        // Series ends in-zone — emit the entry as an ongoing episode
        if (inOs) {
            int persisted = n - osEntry;
            beats.add(Beat.builder()
                    .what(BeatVerb.ENTERED_ZONE)
                    .component(IndicatorComponent.RSI)
                    .whenBar(osEntry)
                    .whenDate(instantToDateString(series.getBarTimestamps()[osEntry]))
                    .value(rsi[osEntry])
                    .significance(1.0)
                    .persistedBars(persisted)
                    .consequence(Consequence.ONGOING)
                    .priceContext(PriceContextBuilder.buildAt(osEntry, bars, pricePivots, swingStates))
                    .ref("rsi_os_in_" + osEntry)
                    .note("Entered " + regimeOsName(osEntryRegime) + " (ongoing, held " + persisted + " bars)")
                    .build());
        }
        if (inOb) {
            int persisted = n - obEntry;
            beats.add(Beat.builder()
                    .what(BeatVerb.ENTERED_ZONE)
                    .component(IndicatorComponent.RSI)
                    .whenBar(obEntry)
                    .whenDate(instantToDateString(series.getBarTimestamps()[obEntry]))
                    .value(rsi[obEntry])
                    .significance(1.0)
                    .persistedBars(persisted)
                    .consequence(Consequence.ONGOING)
                    .priceContext(PriceContextBuilder.buildAt(obEntry, bars, pricePivots, swingStates))
                    .ref("rsi_ob_in_" + obEntry)
                    .note("Entered " + regimeObName(obEntryRegime) + " (ongoing, held " + persisted + " bars)")
                    .build());
        }
        return beats;
    }

    private double osUpperForRegime(Regime r) {
        return r == Regime.BULL ? params.getBullRegimeOsUpper()
                : r == Regime.BEAR ? params.getBearRegimeOsUpper()
                : params.getOversoldThreshold();
    }

    private double obLowerForRegime(Regime r) {
        return r == Regime.BULL ? params.getBullRegimeObLower()
                : r == Regime.BEAR ? params.getBearRegimeObLower()
                : params.getOverboughtThreshold();
    }

    private String regimeOsName(Regime r) {
        return r == Regime.BULL ? "bull-regime oversold"
                : r == Regime.BEAR ? "bear-regime oversold"
                : "oversold";
    }

    private String regimeObName(Regime r) {
        return r == Regime.BULL ? "bull-regime overbought"
                : r == Regime.BEAR ? "bear-regime overbought"
                : "overbought";
    }

    private String zoneLabel(String kind, Regime r, double threshold) {
        String regimeNote = r == Regime.BULL ? "bull-regime " : r == Regime.BEAR ? "bear-regime " : "";
        return "Entered " + regimeNote + kind + " (threshold=" + threshold + ", regime=" + r.label + ")";
    }

    private Beat buildZoneBeat(BeatVerb verb, int bar, double value, Integer persisted, String ref,
                                String note, IndicatorSeries series, List<OhlcBarDTO> bars,
                                List<ZigZagPoint> pricePivots,
                                List<com.dtech.aitrader.v2.narrative.beat.SwingState> swingStates) {
        return Beat.builder()
                .what(verb)
                .component(IndicatorComponent.RSI)
                .whenBar(bar)
                .whenDate(instantToDateString(series.getBarTimestamps()[bar]))
                .value(value)
                .significance(1.0)
                .persistedBars(persisted)
                .consequence(Consequence.CONFIRMED)
                .priceContext(PriceContextBuilder.buildAt(bar, bars, pricePivots, swingStates))
                .ref(ref)
                .note(note)
                .build();
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
                                       List<com.dtech.aitrader.v2.narrative.beat.SwingState> swingStates,
                                       boolean isFirstCommit) {
        String verb = isFirstCommit ? "entered" : "turned";
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
                .note("Brown range regime " + verb + " " + newRegime.label
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

        // Current Brown regime (recompute at last bar)
        int window = params.getBrownRegimeWindowBars();
        Regime regime = Regime.NONE;
        String regimeLabel = "transitioning";
        if (lastIdx >= window - 1) {
            double median = medianOfWindow(rsi, lastIdx - window + 1, lastIdx);
            if (median > params.getBrownBullMedianMin()) { regime = Regime.BULL; regimeLabel = "bull_range"; }
            else if (median < params.getBrownBearMedianMax()) { regime = Regime.BEAR; regimeLabel = "bear_range"; }
        }

        // Regime-relative zone classification
        double osUpper = osUpperForRegime(regime);
        double obLower = obLowerForRegime(regime);
        String zoneLabel;
        if (r <= osUpper) zoneLabel = regimeOsName(regime);
        else if (r >= obLower) zoneLabel = regimeObName(regime);
        else zoneLabel = "neutral";

        String note = String.format(
                "RSI posture at last bar: rsi=%.2f, brown_regime=%s, zone=%s (os_upper=%.1f, ob_lower=%.1f)",
                r, regimeLabel, zoneLabel, osUpper, obLower);

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
