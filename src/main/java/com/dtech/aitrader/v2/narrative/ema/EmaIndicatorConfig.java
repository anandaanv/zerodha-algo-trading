package com.dtech.aitrader.v2.narrative.ema;

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
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * EMA-stack config (delta memsys 417f755d). REGIME_EPISODE tier. Tier-1 regime classifier like
 * ADX. SPLIT horizon — deep stack-regime / golden-death memory; shallow pullback / price-MA
 * memory.
 *
 * <p>Three regime states: bullish-stacked (EMA20&gt;50&gt;100&gt;200), tangled, bearish-stacked.
 * State flips with persistence emit {@link BeatVerb#REGIME_CHANGE} (the primary verb per delta).
 *
 * <p>Lifecycle collapse (delta Section 6, KB INTERACTION_018): fast cross + golden cross +
 * full-stack alignment within ~30 bars = ONE regime-birth observation at three linked phases.
 * Implemented as a composite note on the stack regime_change beat that explicitly lists the
 * fast-cross and golden-cross bars.
 */
@RequiredArgsConstructor
public class EmaIndicatorConfig implements IndicatorConfig {

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final EmaNarrativeParams params;

    @Override
    public String getIndicatorName() {
        return "EMA_Stack";
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
                .regimeChangePersistenceBars(params.getStackRegimeMinPersistenceBars())
                .historyPeakedCap(0)    // EMAs don't peak meaningfully per delta Section 3
                .historyTroughedCap(0)
                .historyRegimeCap(6)    // stack regime is SLOW decay — long memory
                .recentPeakedCap(0)
                .recentTroughedCap(0)
                .recentThrustCap(0)     // no thrust
                .historyZoneCap(4)      // pullback zone visits can survive medium-term
                .failedAttemptMinBars(3)
                .build();
    }

    @Override
    public IndicatorSeries compute(List<OhlcBarDTO> bars, String symbol, String timeframe) {
        return EmaComputer.compute(bars, params.getP20(), params.getP50(), params.getP100(),
                params.getP200(), symbol, timeframe);
    }

    @Override
    public List<PivotComponentSpec> getPivotComponents() {
        // EMAs don't peak meaningfully per delta — use slope instead (carried in currently beat).
        return Collections.emptyList();
    }

    @Override
    public List<CrossoverSpec> getCrossovers() {
        // Golden/death cross (50/200) — high significance (durable structural event, SLOW decay).
        // Fast cross (20/50) — medium significance (faster, less durable).
        // Both regimeRelevant=false: the stack-alignment regime_change is emitted separately in
        // emitCustomBeats(). These two are individual events.
        return List.of(
                CrossoverSpec.builder()
                        .primary(IndicatorComponent.EMA50)
                        .reference(IndicatorComponent.EMA200)
                        .kind(CrossoverSpec.Kind.VS_LINE)
                        .regimeRelevant(false)
                        .aboveLabel("ema50_above_ema200")
                        .belowLabel("ema50_below_ema200")
                        .build(),
                CrossoverSpec.builder()
                        .primary(IndicatorComponent.EMA20)
                        .reference(IndicatorComponent.EMA50)
                        .kind(CrossoverSpec.Kind.VS_LINE)
                        .regimeRelevant(false)
                        .aboveLabel("ema20_above_ema50")
                        .belowLabel("ema20_below_ema50")
                        .build());
    }

    @Override
    public Optional<DivergenceSpec> getDivergence() {
        return Optional.empty(); // no price-divergence semantics for EMA stack
    }

    @Override
    public List<ZoneSpec> getZones() {
        return Collections.emptyList(); // pullback zones handled in custom-beats
    }

    /**
     * Custom beats for EMA-stack:
     * <ol>
     *   <li>Stack alignment regime_change (the primary verb per delta) — emit when bull/bear/
     *       tangled state flips with persistence ≥ {@code stackRegimeMinPersistenceBars}.
     *       For bullish-stacked entries, look back within {@code lifecycleCollapseWindowBars}
     *       for fast-cross + golden-cross — if both found, the note carries the composite
     *       lifecycle-birth framing per delta Section 6.</li>
     *   <li>Pullback detection — when stack is bullish AND close drops within
     *       {@code pullbackProximityPct} of EMA50, mark a pullback episode (entered_zone /
     *       exited_zone pair). Mirror for bearish: rallies into EMA50.</li>
     *   <li>Promote the two CrossoverSpec-emitted golden/fast CROSSED beats from sig=0.5 by
     *       NOT re-emitting them here — instead the engine's CROSSED keep-gate (≥0.7) filters
     *       them at sig=0.5. To keep them visible, we'd need to either bump the spec sig or
     *       emit custom replacements. Choosing the latter so we can add the "LAGGING" framing
     *       per delta Section 7.</li>
     * </ol>
     */
    @Override
    public List<Beat> emitCustomBeats(IndicatorSeries series, List<OhlcBarDTO> bars,
                                       List<ZigZagPoint> pricePivots,
                                       List<com.dtech.aitrader.v2.narrative.beat.SwingState> swingStates,
                                       Map<IndicatorComponent, List<SeriesPivot>> pivotsByComponent) {
        List<Beat> beats = new ArrayList<>();
        double[] e20 = series.getComponent(IndicatorComponent.EMA20);
        double[] e50 = series.getComponent(IndicatorComponent.EMA50);
        double[] e100 = series.getComponent(IndicatorComponent.EMA100);
        double[] e200 = series.getComponent(IndicatorComponent.EMA200);
        double[] closes = ((EmaSeries) series).getCloses();
        int n = e20.length;

        // Step 1: per-bar stack alignment classification
        StackState[] perBar = new StackState[n];
        for (int i = 0; i < n; i++) {
            perBar[i] = classify(e20[i], e50[i], e100[i], e200[i]);
        }

        // Step 2: persistent commits + transition emissions. Also track fast-cross and
        // golden-cross bars for lifecycle collapse on bullish stack entries.
        int persistence = params.getStackRegimeMinPersistenceBars();
        int lifecycleWindow = params.getLifecycleCollapseWindowBars();

        // Pre-compute fast-cross and golden-cross bars (and direction).
        List<int[]> fastCrosses = new ArrayList<>();   // {bar, direction(+1/-1)}
        List<int[]> goldenCrosses = new ArrayList<>(); // {bar, direction(+1/-1)}
        for (int i = 1; i < n; i++) {
            double prevFastDiff = e20[i - 1] - e50[i - 1];
            double currFastDiff = e20[i] - e50[i];
            if (prevFastDiff * currFastDiff <= 0 && prevFastDiff != 0) {
                fastCrosses.add(new int[]{i, currFastDiff > 0 ? 1 : -1});
            }
            double prevGoldenDiff = e50[i - 1] - e200[i - 1];
            double currGoldenDiff = e50[i] - e200[i];
            if (prevGoldenDiff * currGoldenDiff <= 0 && prevGoldenDiff != 0) {
                goldenCrosses.add(new int[]{i, currGoldenDiff > 0 ? 1 : -1});
            }
        }

        // Emit standalone golden/death cross beats (LAGGING-CONFIRMATION framing per delta Section 7).
        for (int[] gc : goldenCrosses) {
            int bar = gc[0];
            boolean bull = gc[1] > 0;
            beats.add(Beat.builder()
                    .what(BeatVerb.CROSSED)
                    .component(IndicatorComponent.EMA_STACK)
                    .whenBar(bar)
                    .whenDate(instantToDateString(series.getBarTimestamps()[bar]))
                    .value(e50[bar])
                    .significance(0.9)
                    .consequence(Consequence.CONFIRMED)
                    .priceContext(PriceContextBuilder.buildAt(bar, bars, pricePivots, swingStates))
                    .direction(bull ? "bullish" : "bearish")
                    .type(bull ? "golden_cross" : "death_cross")
                    .ref("ema_" + (bull ? "golden" : "death") + "_" + bar)
                    .note(String.format(
                            "%s cross (PATTERN_052): EMA50=%.2f crossed %s EMA200=%.2f. NOTE: this is a LAGGING confirmation "
                                    + "(fires after substantial movement) — frames the regime, NOT an entry signal.",
                            bull ? "Golden" : "Death", e50[bar], bull ? "above" : "below", e200[bar]))
                    .build());
        }

        // Emit standalone fast-cross beats (PATTERN_053).
        for (int[] fc : fastCrosses) {
            int bar = fc[0];
            boolean bull = fc[1] > 0;
            beats.add(Beat.builder()
                    .what(BeatVerb.CROSSED)
                    .component(IndicatorComponent.EMA_STACK)
                    .whenBar(bar)
                    .whenDate(instantToDateString(series.getBarTimestamps()[bar]))
                    .value(e20[bar])
                    .significance(0.7)
                    .consequence(Consequence.CONFIRMED)
                    .priceContext(PriceContextBuilder.buildAt(bar, bars, pricePivots, swingStates))
                    .direction(bull ? "bullish" : "bearish")
                    .type(bull ? "fast_bullish" : "fast_bearish")
                    .ref("ema_fast_" + (bull ? "bull" : "bear") + "_" + bar)
                    .note(String.format("Fast cross (PATTERN_053): EMA20=%.2f crossed %s EMA50=%.2f.",
                            e20[bar], bull ? "above" : "below", e50[bar]))
                    .build());
        }

        // Stack regime transitions
        StackState current = StackState.UNDEFINED;
        for (int i = 0; i < n; i++) {
            StackState s = perBar[i];
            if (s == StackState.UNDEFINED || s == current) continue;
            // Check persistence: does s hold for `persistence` bars starting at i?
            int hold = 1;
            for (int j = i + 1; j < n && j < i + persistence; j++) {
                if (perBar[j] == s) hold++;
                else break;
            }
            if (hold < Math.min(persistence, n - i)) continue;

            // Confirmed transition. Look for lifecycle composite if entering BULLISH/BEARISH.
            int lifecycleStart = Math.max(0, i - lifecycleWindow);
            Integer fastCrossBar = null, goldenCrossBar = null;
            if (s == StackState.BULLISH) {
                for (int[] fc : fastCrosses) {
                    if (fc[1] > 0 && fc[0] >= lifecycleStart && fc[0] <= i) fastCrossBar = fc[0];
                }
                for (int[] gc : goldenCrosses) {
                    if (gc[1] > 0 && gc[0] >= lifecycleStart && gc[0] <= i) goldenCrossBar = gc[0];
                }
            } else if (s == StackState.BEARISH) {
                for (int[] fc : fastCrosses) {
                    if (fc[1] < 0 && fc[0] >= lifecycleStart && fc[0] <= i) fastCrossBar = fc[0];
                }
                for (int[] gc : goldenCrosses) {
                    if (gc[1] < 0 && gc[0] >= lifecycleStart && gc[0] <= i) goldenCrossBar = gc[0];
                }
            }

            String verbWord = current == StackState.UNDEFINED ? "entered" : "turned";
            String noteBase = String.format("EMA stack %s %s (EMA20=%.2f, EMA50=%.2f, EMA100=%.2f, EMA200=%.2f)",
                    verbWord, s.label, e20[i], e50[i], e100[i], e200[i]);
            String noteLifecycle = "";
            if (fastCrossBar != null && goldenCrossBar != null) {
                noteLifecycle = String.format(" — LIFECYCLE BIRTH composite: fast cross at bar %d + golden cross at bar %d + full stack at bar %d (PATTERN_053→052→051 within window).",
                        fastCrossBar, goldenCrossBar, i);
            } else if (s == StackState.TANGLED && current != StackState.UNDEFINED) {
                noteLifecycle = " — stack has tangled; prior regime conditioning lapses, downstream OB/OS readings get less weight.";
            }

            beats.add(Beat.builder()
                    .what(BeatVerb.REGIME_CHANGE)
                    .component(IndicatorComponent.EMA_STACK)
                    .whenBar(i)
                    .whenDate(instantToDateString(series.getBarTimestamps()[i]))
                    .value(closes[i])
                    .significance(s == StackState.TANGLED ? 0.7 : 1.0)
                    .persistedBars(countForwardHold(perBar, i))
                    .consequence(Consequence.CONFIRMED)
                    .priceContext(PriceContextBuilder.buildAt(i, bars, pricePivots, swingStates))
                    .direction(s == StackState.BULLISH ? "bullish"
                            : s == StackState.BEARISH ? "bearish" : "tangled")
                    .type("stack_" + s.name().toLowerCase())
                    .ref("ema_stack_" + s.name().toLowerCase() + "_" + i)
                    .note(noteBase + noteLifecycle)
                    .build());
            current = s;
        }

        // Step 3: Pullback detection (PATTERN_056). Track price-to-EMA50 proximity inside an
        // active bullish stack (mirror for bearish).
        double prox = params.getPullbackProximityPct();
        boolean inPullback = false;
        int pullbackEntry = -1;
        String pullbackDir = null;
        for (int i = 0; i < n; i++) {
            if (perBar[i] != StackState.BULLISH && perBar[i] != StackState.BEARISH) {
                if (inPullback) inPullback = false; // exit on stack tangle
                continue;
            }
            double dist = Math.abs(closes[i] - e50[i]) / e50[i];
            boolean nearMa = dist <= prox;
            if (nearMa && !inPullback) {
                inPullback = true;
                pullbackEntry = i;
                pullbackDir = perBar[i] == StackState.BULLISH ? "bullish_pullback" : "bearish_rally";
            } else if (!nearMa && inPullback) {
                int held = i - pullbackEntry;
                if (held >= 2) {
                    String pdir = pullbackDir;
                    // The exit direction matters: did price bounce AWAY from MA in the regime's
                    // favor (bullish stack: closed > EMA50 = bounce up) or break THROUGH it?
                    boolean bounce = (perBar[i] == StackState.BULLISH && closes[i] > e50[i])
                            || (perBar[i] == StackState.BEARISH && closes[i] < e50[i]);
                    beats.add(Beat.builder()
                            .what(BeatVerb.ENTERED_ZONE)
                            .component(IndicatorComponent.EMA_STACK)
                            .whenBar(pullbackEntry)
                            .whenDate(instantToDateString(series.getBarTimestamps()[pullbackEntry]))
                            .value(closes[pullbackEntry])
                            .significance(bounce ? 1.0 : 0.6)
                            .persistedBars(held)
                            .consequence(bounce ? Consequence.CONFIRMED : Consequence.FAILED)
                            .priceContext(PriceContextBuilder.buildAt(pullbackEntry, bars, pricePivots, swingStates))
                            .direction(perBar[pullbackEntry] == StackState.BULLISH ? "bullish" : "bearish")
                            .type(pdir)
                            .ref("ema_pullback_" + pdir + "_" + pullbackEntry)
                            .note(String.format(
                                    "%s — close=%.2f within %.0f%% of EMA50=%.2f inside active %s stack. %s",
                                    pdir, closes[pullbackEntry], prox * 100, e50[pullbackEntry],
                                    perBar[pullbackEntry] == StackState.BULLISH ? "bullish" : "bearish",
                                    bounce ? "Held " + held + " bars then bounced (PATTERN_056 continuation trigger)." :
                                            "Failed: price broke through EMA50 against the regime."))
                            .build());
                }
                inPullback = false;
            }
        }
        return beats;
    }

    private int countForwardHold(StackState[] perBar, int from) {
        StackState s = perBar[from];
        int count = 1;
        for (int j = from + 1; j < perBar.length; j++) {
            if (perBar[j] == s) count++;
            else break;
        }
        return count;
    }

    private StackState classify(double e20, double e50, double e100, double e200) {
        if (Double.isNaN(e20) || Double.isNaN(e50) || Double.isNaN(e100) || Double.isNaN(e200)) return StackState.UNDEFINED;
        if (e20 > e50 && e50 > e100 && e100 > e200) return StackState.BULLISH;
        if (e20 < e50 && e50 < e100 && e100 < e200) return StackState.BEARISH;
        return StackState.TANGLED;
    }

    @Override
    public Beat buildCurrentlyBeat(int lastIdx, IndicatorSeries series, List<OhlcBarDTO> bars) {
        double[] e20 = series.getComponent(IndicatorComponent.EMA20);
        double[] e50 = series.getComponent(IndicatorComponent.EMA50);
        double[] e100 = series.getComponent(IndicatorComponent.EMA100);
        double[] e200 = series.getComponent(IndicatorComponent.EMA200);
        double[] closes = ((EmaSeries) series).getCloses();
        StackState s = classify(e20[lastIdx], e50[lastIdx], e100[lastIdx], e200[lastIdx]);
        double close = closes[lastIdx];

        // 200-EMA slope over the last 10 bars (% change)
        double slope = lastIdx >= 10
                ? (e200[lastIdx] - e200[lastIdx - 10]) / e200[lastIdx - 10]
                : 0.0;
        String slopeLabel = slope > 0.005 ? "rising" : slope < -0.005 ? "falling" : "flat";

        // Pullback check
        double dist50 = Math.abs(close - e50[lastIdx]) / e50[lastIdx];
        boolean atPullback = (s == StackState.BULLISH || s == StackState.BEARISH)
                && dist50 <= params.getPullbackProximityPct();

        String conditioner = s == StackState.BULLISH ? "BULLISH STACK — favors momentum continuation; oscillator OS readings near EMA50 are pullback-buy candidates (Cardwell)" :
                s == StackState.BEARISH ? "BEARISH STACK — favors continuation lower; oscillator OB readings near EMA50 are pullback-sell candidates" :
                "TANGLED — no trend-following weight; oscillator OB/OS readings mean-revert-actionable";

        String note = String.format(
                "EMA-stack posture at last bar: state=%s, close=%.2f, EMA20=%.2f, EMA50=%.2f, EMA100=%.2f, EMA200=%.2f, "
                        + "200-EMA slope=%s (%.2f%% over 10 bars)%s. Conditioner: %s.",
                s.label, close, e20[lastIdx], e50[lastIdx], e100[lastIdx], e200[lastIdx],
                slopeLabel, slope * 100,
                atPullback ? ", AT PULLBACK to EMA50 (within " + (params.getPullbackProximityPct() * 100) + "%)" : "",
                conditioner);

        return Beat.builder()
                .what(BeatVerb.CURRENTLY)
                .component(IndicatorComponent.EMA_STACK)
                .whenBar(lastIdx)
                .whenDate(instantToDateString(series.getBarTimestamps()[lastIdx]))
                .value(close)
                .significance(1.0)
                .consequence(Consequence.ONGOING)
                .tier(Tier.PRESENT)
                .ref("ema_now_" + lastIdx)
                .note(note)
                .build();
    }

    @Override
    public List<Checkpoint> buildVerificationCheckpoints(IndicatorSeries series,
                                                         List<SeriesPivot> primaryPivots,
                                                         int lastIdx) {
        List<Checkpoint> checkpoints = new ArrayList<>();
        // EMA has no structural pivots; record only the last bar.
        checkpoints.add(Checkpoint.builder().bar(lastIdx).build());
        return checkpoints;
    }

    private static String instantToDateString(Instant instant) {
        LocalDateTime ldt = LocalDateTime.ofInstant(instant, ZoneId.of("Asia/Kolkata"));
        return ldt.format(DATE_FORMATTER);
    }

    private enum StackState {
        UNDEFINED("undefined"),
        BULLISH("bullish_stacked"),
        BEARISH("bearish_stacked"),
        TANGLED("tangled");

        final String label;
        StackState(String label) { this.label = label; }
    }
}
