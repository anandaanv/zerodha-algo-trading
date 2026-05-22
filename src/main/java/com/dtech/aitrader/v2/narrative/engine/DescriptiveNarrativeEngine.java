package com.dtech.aitrader.v2.narrative.engine;

import com.dtech.aitrader.v2.narrative.beat.*;
import com.dtech.aitrader.v2.narrative.pivot.DefaultSeriesPivotEngine;
import com.dtech.aitrader.v2.narrative.pivot.PivotKind;
import com.dtech.aitrader.v2.narrative.pivot.SeriesPivot;
import com.dtech.aitrader.v2.narrative.pivot.SignificanceParams;
import com.dtech.aitrader.v2.narrative.support.PriceContextBuilder;
import com.dtech.algo.series.Interval;
import com.dtech.chartdata.model.OhlcBarDTO;
import com.dtech.chartpattern.zigzag.ZigZagParams;
import com.dtech.chartpattern.zigzag.ZigZagPoint;
import com.dtech.chartpattern.zigzag.ZigZagService;
import com.dtech.kitecon.strategy.dataloader.BarsLoader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.ta4j.core.BarSeries;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Generic engine for the descriptive-indicator-narrative bundle, per memsys spec 533b3e85.
 * One engine + per-indicator {@link IndicatorConfig} → six (or more) indicators reuse the same
 * pipeline, verb emitters, tier filter, and honesty fixes.
 *
 * <p>Pipeline:
 * <ol>
 *   <li>{@code config.compute(bars)} → {@link IndicatorSeries}</li>
 *   <li>For each {@link PivotComponentSpec}, run adaptive-significance pivot detection;
 *       emit peaked/troughed (or thrust) beats.</li>
 *   <li>Detect price pivots via {@link ZigZagService} for {@code price_context} on every beat.</li>
 *   <li>For each {@link CrossoverSpec}, emit one of {regime_change, failed_attempt, crossed}
 *       per cross (Fix 3: one-cross-one-verb).</li>
 *   <li>For the (optional) {@link DivergenceSpec}, emit divergence beats with
 *       consequence-update on geometric invalidation (Fix 2).</li>
 *   <li>Assign tiers + filter by per-verb rank caps.</li>
 *   <li>Append the {@code currently} beat (delegated to config).</li>
 *   <li>Build verification slices (delegated to config).</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DescriptiveNarrativeEngine {

    private final ZigZagService zigZagService;
    private final DefaultSeriesPivotEngine seriesPivotEngine = new DefaultSeriesPivotEngine();
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    public Narrative extract(List<OhlcBarDTO> bars, String symbol, String timeframe, IndicatorConfig config) {
        if (bars == null || bars.isEmpty()) {
            throw new IllegalArgumentException("bars must not be null or empty");
        }
        EngineParams engineParams = config.getEngineParams();

        // 1. Compute indicator series
        IndicatorSeries series = config.compute(bars, symbol, timeframe);

        // 2. Pivots per component
        Map<IndicatorComponent, List<SeriesPivot>> pivotsByComponent = new EnumMap<>(IndicatorComponent.class);
        for (PivotComponentSpec spec : config.getPivotComponents()) {
            SignificanceParams params = spec.getSignificanceParams() != null
                    ? spec.getSignificanceParams()
                    : engineParams.getDefaultPivotParams();
            List<SeriesPivot> pivots = seriesPivotEngine.detect(series.getComponent(spec.getComponent()), params);
            pivotsByComponent.put(spec.getComponent(), pivots);
        }

        // 3. Price pivots + swing-state classification
        List<ZigZagPoint> pricePivots = computePricePivots(bars, symbol, timeframe);
        List<SwingState> swingStates = PriceContextBuilder.classifySwingStates(pricePivots);

        log.info("[narrative] {} {} {} bars; pivots per component: {}; price pivots: {}",
                symbol, config.getIndicatorName(), bars.size(),
                pivotsByComponent.entrySet().stream()
                        .map(e -> e.getKey() + "=" + e.getValue().size())
                        .collect(Collectors.joining(",")),
                pricePivots.size());
        if (pricePivots.isEmpty()) {
            log.error("[narrative] NO price pivots from ZigZagService — divergence detection will silently emit zero beats");
        }

        List<Beat> allBeats = new ArrayList<>();

        // 4. Peaked/troughed/thrust beats per pivot component
        for (PivotComponentSpec spec : config.getPivotComponents()) {
            allBeats.addAll(buildPivotBeats(spec, pivotsByComponent.get(spec.getComponent()),
                    series, bars, pricePivots, swingStates, config.getIndicatorName()));
        }

        // 5. Crossover beats: regime_change | failed_attempt | crossed (one verb per cross, Fix 3)
        for (CrossoverSpec cx : config.getCrossovers()) {
            allBeats.addAll(buildCrossoverBeats(cx, series, engineParams, config.getIndicatorName()));
        }

        // 6. Divergence (Fix 2: consequence-update on invalidation)
        config.getDivergence().ifPresent(divSpec ->
                allBeats.addAll(buildDivergenceBeats(divSpec, pivotsByComponent.get(divSpec.getComponent()),
                        series, bars, pricePivots, swingStates, config.getIndicatorName())));

        // 6a. Zone episodes (entered_zone / exited_zone)
        for (ZoneSpec zone : config.getZones()) {
            allBeats.addAll(buildZoneBeats(zone, series, bars, pricePivots, swingStates,
                    config.getIndicatorName()));
        }

        // 6b. Indicator-specific custom beats (Brown regime, lifecycle collapse, failure swings)
        allBeats.addAll(config.emitCustomBeats(series, bars, pricePivots, swingStates));

        // 7. Tier assignment + per-verb rank caps
        int lastIdx = series.length() - 1;
        Map<Tier, List<Beat>> tieredBeats = assignTiersAndFilter(allBeats, lastIdx, engineParams);

        // 8. Append the indicator-specific "currently" beat
        Beat currentlyBeat = config.buildCurrentlyBeat(lastIdx, series, bars);
        tieredBeats.computeIfAbsent(Tier.PRESENT, k -> new ArrayList<>()).add(currentlyBeat);

        // 9. Verification slices (delegated)
        IndicatorComponent primary = config.getPivotComponents().get(0).getComponent();
        VerificationSlices verificationSlices = VerificationSlices.builder()
                .comment("Checkpoints at history pivots and last bar for verification")
                .checkpoints(config.buildVerificationCheckpoints(series, pivotsByComponent.get(primary), lastIdx))
                .build();

        // 10. Assemble Narrative
        LastBar lastBar = LastBar.builder()
                .index(lastIdx)
                .date(instantToDateString(series.getBarTimestamps()[lastIdx]))
                .close(bars.get(lastIdx).getClose())
                .build();

        return Narrative.builder()
                .indicator(config.getIndicatorName())
                .params(null) // indicator-specific params block left to per-indicator wrapper if needed
                .symbol(symbol)
                .timeframe(timeframe)
                .bar0Date(instantToDateString(Instant.ofEpochSecond(bars.get(0).getTime())))
                .lastBar(lastBar)
                .tiers(Tiers.builder()
                        .history(tieredBeats.getOrDefault(Tier.HISTORY, Collections.emptyList()))
                        .recent(tieredBeats.getOrDefault(Tier.RECENT, Collections.emptyList()))
                        .present(tieredBeats.getOrDefault(Tier.PRESENT, Collections.emptyList()))
                        .build())
                .verificationSlices(verificationSlices)
                .build();
    }

    // ===== Verb emitters =====

    private List<Beat> buildPivotBeats(PivotComponentSpec spec, List<SeriesPivot> pivots,
                                       IndicatorSeries series, List<OhlcBarDTO> bars,
                                       List<ZigZagPoint> pricePivots, List<SwingState> swingStates,
                                       String indicatorName) {
        List<Beat> beats = new ArrayList<>();
        String refPrefix = spec.getRefPrefix() != null ? spec.getRefPrefix() :
                (indicatorName.toLowerCase() + "_" + (spec.getVerb() == BeatVerb.THRUST ? "thrust_" : "pk_"));
        String labelPrefix = spec.getLabelPrefix() != null ? spec.getLabelPrefix() : indicatorName;

        for (SeriesPivot pivot : pivots) {
            BeatVerb verb;
            String note;
            String ref;
            if (spec.getVerb() == BeatVerb.THRUST) {
                verb = BeatVerb.THRUST;
                note = labelPrefix + " histogram " + (pivot.kind() == PivotKind.PEAK ? "peaked" : "troughed");
                ref = refPrefix + pivot.idx();
            } else {
                verb = pivot.kind() == PivotKind.PEAK ? BeatVerb.PEAKED : BeatVerb.TROUGHED;
                String pivotWord = (verb == BeatVerb.PEAKED ? "peaked" : "troughed");
                String pkOrTr = (verb == BeatVerb.PEAKED ? "pk" : "tr");
                String basePrefix = spec.getRefPrefix() != null ? spec.getRefPrefix() :
                        (indicatorName.toLowerCase() + "_" + pkOrTr + "_");
                ref = basePrefix + pivot.idx();
                note = labelPrefix + " " + pivotWord + " at bar " + pivot.idx();
            }

            PriceContext priceContext = PriceContextBuilder.buildAt(pivot.idx(), bars, pricePivots, swingStates);
            beats.add(Beat.builder()
                    .what(verb)
                    .component(spec.getComponent())
                    .whenBar(pivot.idx())
                    .whenDate(instantToDateString(series.getBarTimestamps()[pivot.idx()]))
                    .value(pivot.value())
                    .significance(pivot.significance())
                    .consequence(Consequence.CONFIRMED)
                    .priceContext(priceContext)
                    .ref(ref)
                    .note(note)
                    .build());
        }
        return beats;
    }

    private List<Beat> buildCrossoverBeats(CrossoverSpec cx, IndicatorSeries series,
                                           EngineParams params, String indicatorName) {
        List<Beat> beats = new ArrayList<>();
        double[] primary = series.getComponent(cx.getPrimary());
        double[] reference = cx.getKind() == CrossoverSpec.Kind.VS_LINE
                ? series.getComponent(cx.getReference())
                : null;
        double level = cx.getKind() == CrossoverSpec.Kind.VS_LEVEL
                ? cx.getLevel() : 0.0;
        int threshold = params.getRegimeChangePersistenceBars();
        String refPrefix = indicatorName.toLowerCase();

        for (int i = 1; i < primary.length; i++) {
            double prevDiff, currDiff;
            if (cx.getKind() == CrossoverSpec.Kind.VS_LINE) {
                prevDiff = primary[i - 1] - reference[i - 1];
                currDiff = primary[i] - reference[i];
            } else {
                prevDiff = primary[i - 1] - level;
                currDiff = primary[i] - level;
            }
            boolean crossed = prevDiff * currDiff <= 0 && prevDiff != 0;
            if (!crossed) continue;

            String aboveLabel = cx.getAboveLabel() != null ? cx.getAboveLabel() : "above";
            String belowLabel = cx.getBelowLabel() != null ? cx.getBelowLabel() : "below";
            String from = prevDiff > 0 ? aboveLabel : belowLabel;
            String to = currDiff > 0 ? aboveLabel : belowLabel;

            if (cx.isRegimeRelevant()) {
                int persistedBars = countPersistence(primary, i, threshold,
                        cx.getKind() == CrossoverSpec.Kind.VS_LINE ? reference : null,
                        cx.getKind() == CrossoverSpec.Kind.VS_LEVEL ? level : 0.0,
                        cx.getKind());
                boolean enoughForwardBars = i + threshold <= primary.length - 1;

                if (persistedBars >= threshold) {
                    beats.add(Beat.builder()
                            .what(BeatVerb.REGIME_CHANGE)
                            .component(cx.getPrimary())
                            .whenBar(i)
                            .whenDate(instantToDateString(series.getBarTimestamps()[i]))
                            .value(primary[i])
                            .significance(0.8)
                            .persistedBars(persistedBars)
                            .consequence(Consequence.CONFIRMED)
                            .ref(refPrefix + "_regime_" + i)
                            .note((to.equals(aboveLabel) ? "Cross " + aboveLabel + "; regime turned positive, held " :
                                   "Cross " + belowLabel + "; regime turned negative, held ") + persistedBars + " bars")
                            .build());
                } else if (enoughForwardBars) {
                    beats.add(Beat.builder()
                            .what(BeatVerb.FAILED_ATTEMPT)
                            .component(cx.getPrimary())
                            .whenBar(i)
                            .whenDate(instantToDateString(series.getBarTimestamps()[i]))
                            .value(primary[i])
                            .significance(0.4)
                            .persistedBars(persistedBars)
                            .consequence(Consequence.FAILED)
                            .ref(refPrefix + "_failed_" + i)
                            .note("Cross " + to + " failed after " + persistedBars + " bars")
                            .build());
                } else {
                    beats.add(Beat.builder()
                            .what(BeatVerb.CROSSED)
                            .component(cx.getPrimary())
                            .whenBar(i)
                            .whenDate(instantToDateString(series.getBarTimestamps()[i]))
                            .value(primary[i])
                            .significance(0.5)
                            .persistedBars(persistedBars)
                            .consequence(Consequence.ONGOING)
                            .from(from)
                            .to(to)
                            .ref(refPrefix + "_cross_" + i)
                            .note("Crossed from " + from + " to " + to)
                            .build());
                }
            } else {
                beats.add(Beat.builder()
                        .what(BeatVerb.CROSSED)
                        .component(cx.getPrimary())
                        .whenBar(i)
                        .whenDate(instantToDateString(series.getBarTimestamps()[i]))
                        .value(primary[i])
                        .significance(0.5)
                        .consequence(Consequence.CONFIRMED)
                        .from(from)
                        .to(to)
                        .ref(refPrefix + "_cross_" + i)
                        .note("Crossed from " + from + " to " + to)
                        .build());
            }
        }
        return beats;
    }

    /**
     * Count how many consecutive bars (starting at crossBar) the primary stayed on its new side
     * of the reference (line or level). Used for regime persistence (Fix 3: one-verb-per-cross).
     */
    private int countPersistence(double[] primary, int crossBar, int window,
                                 double[] reference, double level, CrossoverSpec.Kind kind) {
        int persisted = 1;
        double crossDiff = kind == CrossoverSpec.Kind.VS_LINE
                ? primary[crossBar] - reference[crossBar]
                : primary[crossBar] - level;
        boolean isAbove = crossDiff > 0;

        for (int i = crossBar + 1; i < primary.length; i++) {
            double diff = kind == CrossoverSpec.Kind.VS_LINE
                    ? primary[i] - reference[i]
                    : primary[i] - level;
            if (isAbove && diff >= 0) persisted++;
            else if (!isAbove && diff < 0) persisted++;
            else break;
        }
        return persisted;
    }

    private List<Beat> buildDivergenceBeats(DivergenceSpec divSpec, List<SeriesPivot> pivots,
                                            IndicatorSeries series, List<OhlcBarDTO> bars,
                                            List<ZigZagPoint> pricePivots, List<SwingState> swingStates,
                                            String indicatorName) {
        if (pivots == null) {
            log.error("[narrative] divergence spec component {} has no pivots — must be listed in pivotComponents",
                    divSpec.getComponent());
            return Collections.emptyList();
        }
        List<Beat> beats = new ArrayList<>();
        String refPrefix = divSpec.getRefPrefix() != null ? divSpec.getRefPrefix() :
                (indicatorName.toLowerCase() + "_div_");
        IndicatorComponent beatComponent = divSpec.getBeatComponent() != null
                ? divSpec.getBeatComponent() : divSpec.getComponent();
        String componentLabel = divSpec.getComponentLabel() != null
                ? divSpec.getComponentLabel() : "indicator";

        // --- BEARISH divergences (between peaks) ---
        List<SeriesPivot> peaks = pivots.stream()
                .filter(p -> p.kind() == PivotKind.PEAK)
                .collect(Collectors.toList());
        for (int i = 1; i < peaks.size(); i++) {
            SeriesPivot p1 = peaks.get(i - 1);
            SeriesPivot p2 = peaks.get(i);
            double price1 = bars.get(p1.idx()).getClose();
            double price2 = bars.get(p2.idx()).getClose();
            if (!(price2 > price1 && p2.value() < p1.value())) continue;

            PivotPairEntry entry1 = PivotPairEntry.builder()
                    .bar(p1.idx()).date(instantToDateString(series.getBarTimestamps()[p1.idx()]))
                    .price(price1).macd(p1.value()).build();
            PivotPairEntry entry2 = PivotPairEntry.builder()
                    .bar(p2.idx()).date(instantToDateString(series.getBarTimestamps()[p2.idx()]))
                    .price(price2).macd(p2.value()).build();

            PivotPairEntry deeperAnchor = null;
            for (int j = i - 2; j >= 0; j--) {
                SeriesPivot p0 = peaks.get(j);
                if (p0.value() > p1.value()) {
                    deeperAnchor = PivotPairEntry.builder()
                            .bar(p0.idx())
                            .date(instantToDateString(series.getBarTimestamps()[p0.idx()]))
                            .price(bars.get(p0.idx()).getClose())
                            .macd(p0.value())
                            .build();
                    break;
                }
            }

            // Fix 2: consequence-update on invalidation
            Consequence consequence = Consequence.CONFIRMED;
            String invalidatedNote = "";
            for (int j = i + 1; j < peaks.size(); j++) {
                SeriesPivot pLater = peaks.get(j);
                double priceLater = bars.get(pLater.idx()).getClose();
                if (priceLater > price2 && pLater.value() > p2.value()) {
                    consequence = Consequence.FAILED;
                    invalidatedNote = " [INVALIDATED at bar " + pLater.idx() + ": price " + priceLater
                            + " and " + componentLabel + " " + pLater.value()
                            + " both broke the divergence ceiling]";
                    break;
                }
            }

            PriceContext priceContext = PriceContextBuilder.buildAt(p2.idx(), bars, pricePivots, swingStates);
            beats.add(Beat.builder()
                    .what(BeatVerb.DIVERGED_FROM_PRICE)
                    .component(beatComponent)
                    .whenBar(p2.idx())
                    .whenDate(instantToDateString(series.getBarTimestamps()[p2.idx()]))
                    .value(p2.value())
                    .significance(0.9)
                    .consequence(consequence)
                    .priceContext(priceContext)
                    .type("regular")
                    .direction("bearish")
                    .pivotPair(List.of(entry1, entry2))
                    .deeperAnchor(deeperAnchor)
                    .ref(refPrefix + p2.idx())
                    .note("Price HH (" + price2 + " vs " + price1 + ") while " + componentLabel + " LH ("
                            + p2.value() + " vs " + p1.value() + ") — bearish divergence" + invalidatedNote)
                    .build());
        }

        // --- BULLISH divergences (between troughs) ---
        List<SeriesPivot> troughs = pivots.stream()
                .filter(p -> p.kind() == PivotKind.TROUGH)
                .collect(Collectors.toList());
        for (int i = 1; i < troughs.size(); i++) {
            SeriesPivot t1 = troughs.get(i - 1);
            SeriesPivot t2 = troughs.get(i);
            double price1 = bars.get(t1.idx()).getClose();
            double price2 = bars.get(t2.idx()).getClose();
            if (!(price2 < price1 && t2.value() > t1.value())) continue;

            PivotPairEntry entry1 = PivotPairEntry.builder()
                    .bar(t1.idx()).date(instantToDateString(series.getBarTimestamps()[t1.idx()]))
                    .price(price1).macd(t1.value()).build();
            PivotPairEntry entry2 = PivotPairEntry.builder()
                    .bar(t2.idx()).date(instantToDateString(series.getBarTimestamps()[t2.idx()]))
                    .price(price2).macd(t2.value()).build();

            PivotPairEntry deeperAnchor = null;
            for (int j = i - 2; j >= 0; j--) {
                SeriesPivot t0 = troughs.get(j);
                if (t0.value() < t1.value()) {
                    deeperAnchor = PivotPairEntry.builder()
                            .bar(t0.idx())
                            .date(instantToDateString(series.getBarTimestamps()[t0.idx()]))
                            .price(bars.get(t0.idx()).getClose())
                            .macd(t0.value())
                            .build();
                    break;
                }
            }

            Consequence consequence = Consequence.CONFIRMED;
            String invalidatedNote = "";
            for (int j = i + 1; j < troughs.size(); j++) {
                SeriesPivot tLater = troughs.get(j);
                double priceLater = bars.get(tLater.idx()).getClose();
                if (priceLater < price2 && tLater.value() < t2.value()) {
                    consequence = Consequence.FAILED;
                    invalidatedNote = " [INVALIDATED at bar " + tLater.idx() + ": price " + priceLater
                            + " and " + componentLabel + " " + tLater.value()
                            + " both broke the divergence floor]";
                    break;
                }
            }

            PriceContext priceContext = PriceContextBuilder.buildAt(t2.idx(), bars, pricePivots, swingStates);
            beats.add(Beat.builder()
                    .what(BeatVerb.DIVERGED_FROM_PRICE)
                    .component(beatComponent)
                    .whenBar(t2.idx())
                    .whenDate(instantToDateString(series.getBarTimestamps()[t2.idx()]))
                    .value(t2.value())
                    .significance(0.9)
                    .consequence(consequence)
                    .priceContext(priceContext)
                    .type("regular")
                    .direction("bullish")
                    .pivotPair(List.of(entry1, entry2))
                    .deeperAnchor(deeperAnchor)
                    .ref(refPrefix + t2.idx())
                    .note("Price LL (" + price2 + " vs " + price1 + ") while " + componentLabel + " HL ("
                            + t2.value() + " vs " + t1.value() + ") — bullish divergence" + invalidatedNote)
                    .build());
        }
        return beats;
    }

    /**
     * Emit {@code entered_zone} at the bar a stretch begins, {@code exited_zone} at the bar it
     * ends (with {@code persisted_bars} = stay duration). Short pokes shorter than
     * {@link ZoneSpec#getMinPersistenceBars()} are dropped.
     *
     * <p>If the series ends still in-zone, an {@code entered_zone} beat is emitted with
     * {@code consequence=ongoing} (and no matching exit).
     */
    private List<Beat> buildZoneBeats(ZoneSpec zone, IndicatorSeries series, List<OhlcBarDTO> bars,
                                       List<ZigZagPoint> pricePivots, List<SwingState> swingStates,
                                       String indicatorName) {
        double[] values = series.getComponent(zone.getComponent());
        String refPrefix = zone.getRefPrefix() != null ? zone.getRefPrefix() :
                (indicatorName.toLowerCase() + "_" + zone.getName() + "_");

        List<Beat> beats = new ArrayList<>();
        boolean inZone = false;
        int entryBar = -1;

        for (int i = 0; i < values.length; i++) {
            boolean nowInZone = values[i] >= zone.getLower() && values[i] <= zone.getUpper();
            if (nowInZone && !inZone) {
                entryBar = i;
                inZone = true;
            } else if (!nowInZone && inZone) {
                int persisted = i - entryBar;
                if (persisted >= Math.max(1, zone.getMinPersistenceBars())) {
                    beats.add(zoneBeat(BeatVerb.ENTERED_ZONE, zone, entryBar, values[entryBar],
                            null, refPrefix + "in_" + entryBar,
                            "Entered " + zone.getName(),
                            series, bars, pricePivots, swingStates));
                    beats.add(zoneBeat(BeatVerb.EXITED_ZONE, zone, i, values[i],
                            persisted, refPrefix + "out_" + i,
                            "Exited " + zone.getName() + " after " + persisted + " bars",
                            series, bars, pricePivots, swingStates));
                }
                inZone = false;
            }
        }
        // Series ends in-zone — emit the entry as an ongoing episode
        if (inZone) {
            int persisted = values.length - entryBar;
            beats.add(Beat.builder()
                    .what(BeatVerb.ENTERED_ZONE)
                    .component(zone.getComponent())
                    .whenBar(entryBar)
                    .whenDate(instantToDateString(series.getBarTimestamps()[entryBar]))
                    .value(values[entryBar])
                    .significance(1.0)
                    .persistedBars(persisted)
                    .consequence(Consequence.ONGOING)
                    .priceContext(PriceContextBuilder.buildAt(entryBar, bars, pricePivots, swingStates))
                    .ref(refPrefix + "in_" + entryBar)
                    .note("Entered " + zone.getName() + " (ongoing, held " + persisted + " bars)")
                    .build());
        }
        return beats;
    }

    private Beat zoneBeat(BeatVerb verb, ZoneSpec zone, int bar, double value, Integer persisted,
                          String ref, String note, IndicatorSeries series, List<OhlcBarDTO> bars,
                          List<ZigZagPoint> pricePivots, List<SwingState> swingStates) {
        return Beat.builder()
                .what(verb)
                .component(zone.getComponent())
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

    // ===== Tier assignment + filtering =====

    private Map<Tier, List<Beat>> assignTiersAndFilter(List<Beat> allBeats, int lastIdx, EngineParams ep) {
        int presentBoundary = lastIdx - ep.getPresentWindowBars();
        int recentBoundary = lastIdx - ep.getRecentWindowBars();

        List<Beat> withTier = new ArrayList<>();
        for (Beat b : allBeats) {
            Tier t = b.getWhenBar() > presentBoundary ? Tier.PRESENT
                    : b.getWhenBar() > recentBoundary ? Tier.RECENT : Tier.HISTORY;
            withTier.add(Beat.builder()
                    .what(b.getWhat()).component(b.getComponent())
                    .whenBar(b.getWhenBar()).whenDate(b.getWhenDate())
                    .value(b.getValue()).significance(b.getSignificance())
                    .persistedBars(b.getPersistedBars()).consequence(b.getConsequence())
                    .priceContext(b.getPriceContext()).tier(t)
                    .ref(b.getRef()).note(b.getNote())
                    .macdLine(b.getMacdLine()).signalLine(b.getSignalLine()).histogram(b.getHistogram())
                    .from(b.getFrom()).to(b.getTo())
                    .type(b.getType()).direction(b.getDirection())
                    .pivotPair(b.getPivotPair()).deeperAnchor(b.getDeeperAnchor())
                    .build());
        }

        Map<Tier, List<Beat>> byTier = withTier.stream().collect(Collectors.groupingBy(Beat::getTier));
        Map<Tier, List<Beat>> filtered = new EnumMap<>(Tier.class);
        for (Tier tier : Tier.values()) {
            List<Beat> tBeats = byTier.getOrDefault(tier, Collections.emptyList());
            List<Beat> kept = new ArrayList<>();
            if (tier == Tier.PRESENT) {
                for (Beat b : tBeats) {
                    if (b.getWhat() != BeatVerb.CROSSED) kept.add(b);
                }
            } else if (tier == Tier.HISTORY) {
                kept.addAll(topN(tBeats, BeatVerb.PEAKED, ep.getHistoryPeakedCap()));
                kept.addAll(topN(tBeats, BeatVerb.TROUGHED, ep.getHistoryTroughedCap()));
                kept.addAll(tBeats.stream()
                        .filter(b -> b.getWhat() == BeatVerb.REGIME_CHANGE)
                        .sorted(Comparator.comparingInt(
                                (Beat b) -> -(b.getPersistedBars() == null ? 0 : b.getPersistedBars())))
                        .limit(ep.getHistoryRegimeCap())
                        .collect(Collectors.toList()));
                kept.addAll(tBeats.stream()
                        .filter(b -> b.getWhat() == BeatVerb.DIVERGED_FROM_PRICE)
                        .collect(Collectors.toList()));
            } else { // RECENT
                kept.addAll(topN(tBeats, BeatVerb.PEAKED, ep.getRecentPeakedCap()));
                kept.addAll(topN(tBeats, BeatVerb.TROUGHED, ep.getRecentTroughedCap()));
                kept.addAll(topN(tBeats, BeatVerb.THRUST, ep.getRecentThrustCap()));
                kept.addAll(tBeats.stream()
                        .filter(b -> b.getWhat() == BeatVerb.DIVERGED_FROM_PRICE
                                || b.getWhat() == BeatVerb.REGIME_CHANGE
                                || b.getWhat() == BeatVerb.FAILED_ATTEMPT
                                || b.getWhat() == BeatVerb.ENTERED_ZONE
                                || b.getWhat() == BeatVerb.EXITED_ZONE)
                        .collect(Collectors.toList()));
            }
            kept.sort(Comparator.comparingInt(Beat::getWhenBar));
            filtered.put(tier, kept);
        }
        return filtered;
    }

    private List<Beat> topN(List<Beat> beats, BeatVerb verb, int n) {
        Comparator<Beat> bySignificance = Comparator
                .comparingDouble((Beat b) -> b.getSignificance() != null ? -b.getSignificance() : 0.0);
        Comparator<Beat> byValue;
        if (verb == BeatVerb.PEAKED) {
            byValue = Comparator.comparingDouble((Beat b) -> -(b.getValue() != null ? b.getValue() : 0.0));
        } else if (verb == BeatVerb.TROUGHED) {
            byValue = Comparator.comparingDouble((Beat b) -> b.getValue() != null ? b.getValue() : 0.0);
        } else {
            byValue = Comparator.comparingDouble((Beat b) -> -Math.abs(b.getValue() != null ? b.getValue() : 0.0));
        }
        return beats.stream()
                .filter(b -> b.getWhat() == verb)
                .sorted(bySignificance.thenComparing(byValue))
                .limit(n)
                .collect(Collectors.toList());
    }

    // ===== Price pivots (shared across all indicators) =====

    private List<ZigZagPoint> computePricePivots(List<OhlcBarDTO> bars, String symbol, String timeframe) {
        BarSeries barSeries = new org.ta4j.core.BaseBarSeriesBuilder().withName(symbol).build();
        for (OhlcBarDTO bar : bars) {
            Instant instant = Instant.ofEpochSecond(bar.getTime());
            org.ta4j.core.Bar taBar = BarsLoader.getBar(
                    bar.getOpen(), bar.getHigh(), bar.getLow(), bar.getClose(),
                    bar.getVolume(), instant);
            barSeries.addBar(taBar);
        }
        Interval interval = Interval.valueOf(timeframe);
        ZigZagParams zigZagParams = zigZagService.resolveParams(symbol, interval);
        List<ZigZagPoint> rawPivots = zigZagService.detect(barSeries, zigZagParams);

        Map<Long, Integer> timestampToBarIndex = new HashMap<>();
        for (int i = 0; i < bars.size(); i++) {
            timestampToBarIndex.put(bars.get(i).getTime(), i);
        }

        List<ZigZagPoint> mapped = new ArrayList<>();
        for (ZigZagPoint pivot : rawPivots) {
            Integer barIndex = timestampToBarIndex.get(pivot.getTimestamp().getEpochSecond());
            if (barIndex != null) {
                mapped.add(ZigZagPoint.builder()
                        .type(pivot.getType()).timestamp(pivot.getTimestamp())
                        .barIndex(barIndex).sequence(pivot.getSequence())
                        .value(pivot.getValue()).atrAtPivot(pivot.getAtrAtPivot())
                        .retracementPct(pivot.getRetracementPct())
                        .extensionPct(pivot.getExtensionPct())
                        .legSizePct(pivot.getLegSizePct())
                        .legDurationBars(pivot.getLegDurationBars())
                        .legSpeed(pivot.getLegSpeed())
                        .build());
            }
        }
        return mapped;
    }

    static String instantToDateString(Instant instant) {
        LocalDateTime ldt = LocalDateTime.ofInstant(instant, ZoneId.of("Asia/Kolkata"));
        return ldt.format(DATE_FORMATTER);
    }
}
