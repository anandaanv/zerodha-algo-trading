package com.dtech.aitrader.v2.narrative.macd;

import com.dtech.aitrader.v2.narrative.beat.*;
import com.dtech.aitrader.v2.narrative.pivot.DefaultSeriesPivotEngine;
import com.dtech.aitrader.v2.narrative.pivot.PivotKind;
import com.dtech.aitrader.v2.narrative.pivot.SeriesPivot;
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
 * Orchestrator for extracting MACD narratives from OHLC bars.
 *
 * Coordinates:
 * 1. MACD computation
 * 2. Pivot detection on MACD line and histogram
 * 3. Price pivot detection
 * 4. Beat emission (peaked, troughed, crossed, regime_change, thrust, failed_attempt, diverged_from_price, currently)
 * 5. Tier assignment and significance filtering
 * 6. Narrative assembly with verification slices
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MacdNarrativeExtractor {
    private final ZigZagService zigZagService;
    private final DefaultSeriesPivotEngine seriesPivotEngine = new DefaultSeriesPivotEngine();
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /**
     * Extract a complete MACD narrative for a symbol and timeframe.
     *
     * @param bars OHLC bars (must be at least 50 bars for meaningful results)
     * @param symbol trading symbol (e.g., "RELIANCE")
     * @param timeframe timeframe string (e.g., "Week")
     * @param params narrative extraction parameters
     * @return assembled Narrative with tiered beats
     */
    public Narrative extract(List<OhlcBarDTO> bars, String symbol, String timeframe, MacdNarrativeParams params) {
        if (bars == null || bars.isEmpty()) {
            throw new IllegalArgumentException("bars must not be null or empty");
        }

        // Step 1: Compute MACD
        MacdSeries macd = MacdComputer.compute(bars, params.getFastPeriod(), params.getSlowPeriod(),
                params.getSignalPeriod(), symbol, timeframe);

        // Step 2: Detect MACD line pivots
        List<SeriesPivot> macdLinePivots = seriesPivotEngine.detect(macd.getMacdLine(), params.getPivotParams());

        // Step 3: Detect histogram pivots
        List<SeriesPivot> histogramPivots = seriesPivotEngine.detect(macd.getHistogram(), params.getPivotParams());

        // Step 4: Compute price pivots
        List<ZigZagPoint> pricePivots = computePricePivots(bars, symbol, timeframe);

        log.info("[macd-narrative] {} {} {} bars -> {} macd_line pivots, {} price pivots",
                symbol, timeframe, bars.size(), macdLinePivots.size(), pricePivots.size());
        if (pricePivots.isEmpty()) {
            log.error("[macd-narrative] NO price pivots from ZigZagService — divergence detection will silently emit zero beats");
        }

        // Step 5: Classify price swing states
        List<SwingState> swingStates = PriceContextBuilder.classifySwingStates(pricePivots);

        // Step 6-10: Build beats
        List<Beat> allBeats = new ArrayList<>();

        // Build peaked/troughed beats
        allBeats.addAll(buildPeakedTroughedBeats(macdLinePivots, bars, macd, pricePivots, swingStates));

        // Build crossed and regime_change beats
        allBeats.addAll(buildCrossedAndRegimeBeats(macd, params, bars));

        // Build thrust beats
        allBeats.addAll(buildThrustBeats(histogramPivots, bars, macd));

        // (Failed-attempt emission now happens inside buildCrossedAndRegimeBeats — one verb per cross.)

        // Build diverged_from_price beats (THE HEADLINE)
        List<Beat> divergedBeats = buildDivergedFromPriceBeats(macdLinePivots, pricePivots, bars, macd);
        allBeats.addAll(divergedBeats);

        // Step 12: Assign tiers and filter by significance
        int lastIdx = bars.size() - 1;
        Map<Tier, List<Beat>> tieredBeats = assignTiersAndFilter(allBeats, lastIdx, params, divergedBeats);

        // Step 13: Add the mandatory currently beat
        Beat currentlyBeat = buildCurrentlyBeat(lastIdx, bars, macd);
        tieredBeats.computeIfAbsent(Tier.PRESENT, k -> new ArrayList<>()).add(currentlyBeat);

        // Step 14: Build Narrative
        LastBar lastBar = LastBar.builder()
                .index(lastIdx)
                .date(instantToDateString(Instant.ofEpochSecond(bars.get(lastIdx).getTime())))
                .close(bars.get(lastIdx).getClose())
                .build();

        VerificationSlices verificationSlices = buildVerificationSlices(macdLinePivots, lastIdx, bars, macd);

        Narrative narrative = Narrative.builder()
                .indicator("MACD")
                .params(MacdParams.builder()
                        .fast(params.getFastPeriod())
                        .slow(params.getSlowPeriod())
                        .signal(params.getSignalPeriod())
                        .build())
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

        return narrative;
    }

    /**
     * Compute price pivots using ZigZagService.
     * Maps ZigZagPoints back to correct bar indices in input bars list.
     */
    private List<ZigZagPoint> computePricePivots(List<OhlcBarDTO> bars, String symbol, String timeframe) {
        // Build BarSeries from OhlcBarDTO using ta4j
        BarSeries barSeries = new org.ta4j.core.BaseBarSeriesBuilder()
                .withName(symbol)
                .build();

        for (OhlcBarDTO bar : bars) {
            Instant instant = Instant.ofEpochSecond(bar.getTime());
            org.ta4j.core.Bar taBar = BarsLoader.getBar(
                    bar.getOpen(), bar.getHigh(), bar.getLow(), bar.getClose(),
                    bar.getVolume(), instant
            );
            barSeries.addBar(taBar);
        }

        // Construct ZigZagParams via symbol and interval
        Interval interval = Interval.valueOf(timeframe);
        ZigZagParams zigZagParams = zigZagService.resolveParams(symbol, interval);

        // Detect price pivots
        List<ZigZagPoint> rawPivots = zigZagService.detect(barSeries, zigZagParams);

        // Map zigzag points back to correct bar indices in the input bars list
        // The zigzag service returns points with timestamps; match them to input bar timestamps
        Map<Long, Integer> timestampToBarIndex = new HashMap<>();
        for (int i = 0; i < bars.size(); i++) {
            timestampToBarIndex.put(bars.get(i).getTime(), i);
        }

        List<ZigZagPoint> mappedPivots = new ArrayList<>();
        for (ZigZagPoint pivot : rawPivots) {
            long pivotEpochSeconds = pivot.getTimestamp().getEpochSecond();
            Integer barIndex = timestampToBarIndex.get(pivotEpochSeconds);
            if (barIndex != null) {
                // Create a new ZigZagPoint with the correct bar index
                ZigZagPoint corrected = ZigZagPoint.builder()
                        .type(pivot.getType())
                        .timestamp(pivot.getTimestamp())
                        .barIndex(barIndex)
                        .sequence(pivot.getSequence())
                        .value(pivot.getValue())
                        .atrAtPivot(pivot.getAtrAtPivot())
                        .retracementPct(pivot.getRetracementPct())
                        .extensionPct(pivot.getExtensionPct())
                        .legSizePct(pivot.getLegSizePct())
                        .legDurationBars(pivot.getLegDurationBars())
                        .legSpeed(pivot.getLegSpeed())
                        .build();
                mappedPivots.add(corrected);
            }
        }

        return mappedPivots;
    }

    /**
     * Build peaked/troughed beats for MACD line pivots.
     */
    private List<Beat> buildPeakedTroughedBeats(List<SeriesPivot> macdLinePivots, List<OhlcBarDTO> bars,
                                                MacdSeries macd, List<ZigZagPoint> pricePivots,
                                                List<SwingState> swingStates) {
        List<Beat> beats = new ArrayList<>();

        for (int i = 0; i < macdLinePivots.size(); i++) {
            SeriesPivot pivot = macdLinePivots.get(i);
            BeatVerb verb = pivot.kind() == PivotKind.PEAK ? BeatVerb.PEAKED : BeatVerb.TROUGHED;

            PriceContext priceContext = PriceContextBuilder.buildAt(pivot.idx(), bars, pricePivots, swingStates);

            Beat beat = Beat.builder()
                    .what(verb)
                    .component(IndicatorComponent.MACD_LINE)
                    .whenBar(pivot.idx())
                    .whenDate(instantToDateString(macd.getBarTimestamps()[pivot.idx()]))
                    .value(pivot.value())
                    .significance(pivot.significance())
                    .consequence(Consequence.CONFIRMED)
                    .priceContext(priceContext)
                    .ref((verb == BeatVerb.PEAKED ? "macd_pk_" : "macd_tr_") + pivot.idx())
                    .note((verb == BeatVerb.PEAKED ? "MACD peaked" : "MACD troughed") + " at bar " + pivot.idx())
                    .build();

            beats.add(beat);
        }

        return beats;
    }

    /**
     * Build crossed, regime_change, and failed_attempt beats.
     *
     * Each MACD cross resolves to EXACTLY ONE verb at the cross bar:
     *   - zero_cross + persistedBars >= threshold       → REGIME_CHANGE (consequence=CONFIRMED)
     *   - zero_cross + persistedBars <  threshold       → FAILED_ATTEMPT (consequence=FAILED)
     *     (only if enough forward bars exist to evaluate; otherwise emit bare CROSSED)
     *   - signal_cross (without zero_cross)             → CROSSED (filtered out by tier rules)
     *
     * No double-emission for the same cross. The pre-Fix-3 design emitted REGIME_CHANGE and
     * a separate FAILED_ATTEMPT (anchored on the reversal bar, NOT the original cross) for the
     * same event — that produced contradictory verbs on the same bar (TATASTEEL 221, ADANIENT
     * 253/254). One cross, one verb, at the cross bar.
     */
    private List<Beat> buildCrossedAndRegimeBeats(MacdSeries macd, MacdNarrativeParams params, List<OhlcBarDTO> bars) {
        List<Beat> beats = new ArrayList<>();
        double[] macdLine = macd.getMacdLine();
        double[] signalLine = macd.getSignalLine();
        int threshold = params.getRegimeChangePersistenceBars();

        for (int i = 1; i < macdLine.length; i++) {
            boolean zerocrossPrev = macdLine[i - 1] * macdLine[i] <= 0 && macdLine[i - 1] != 0;
            boolean signalcrossPrev = (macdLine[i - 1] - signalLine[i - 1]) * (macdLine[i] - signalLine[i]) <= 0;

            if (!zerocrossPrev && !signalcrossPrev) {
                continue;
            }

            String from = macdLine[i - 1] > 0 ? "above_zero" : "below_zero";
            String to = macdLine[i] > 0 ? "above_zero" : "below_zero";
            if (from.equals(to)) {
                from = macdLine[i - 1] > signalLine[i - 1] ? "above_signal" : "below_signal";
                to = macdLine[i] > signalLine[i] ? "above_signal" : "below_signal";
            }

            if (zerocrossPrev) {
                int persistedBars = checkRegimePersistence(macdLine, i, threshold);
                boolean enoughForwardBars = i + threshold <= macdLine.length - 1;

                if (persistedBars >= threshold) {
                    beats.add(Beat.builder()
                            .what(BeatVerb.REGIME_CHANGE)
                            .component(IndicatorComponent.MACD_LINE)
                            .whenBar(i)
                            .whenDate(instantToDateString(macd.getBarTimestamps()[i]))
                            .value(macdLine[i])
                            .significance(0.8)
                            .persistedBars(persistedBars)
                            .consequence(Consequence.CONFIRMED)
                            .ref("macd_regime_" + i)
                            .note((macdLine[i] > 0 ? "Zero-up cross; momentum regime turned positive, held " :
                                   "Zero-down cross; momentum regime turned negative, held ") + persistedBars + " bars")
                            .build());
                } else if (enoughForwardBars) {
                    beats.add(Beat.builder()
                            .what(BeatVerb.FAILED_ATTEMPT)
                            .component(IndicatorComponent.MACD_LINE)
                            .whenBar(i)
                            .whenDate(instantToDateString(macd.getBarTimestamps()[i]))
                            .value(macdLine[i])
                            .significance(0.4)
                            .persistedBars(persistedBars)
                            .consequence(Consequence.FAILED)
                            .ref("macd_failed_" + i)
                            .note((macdLine[i] > 0 ? "Zero-up cross failed after " :
                                   "Zero-down cross failed after ") + persistedBars + " bars")
                            .build());
                } else {
                    // Cross too close to end of series to evaluate — leave as ongoing CROSSED
                    // (tier filter will drop bare CROSSED; the present-tier `currently` beat
                    // already reports the live posture).
                    beats.add(Beat.builder()
                            .what(BeatVerb.CROSSED)
                            .component(IndicatorComponent.MACD_LINE)
                            .whenBar(i)
                            .whenDate(instantToDateString(macd.getBarTimestamps()[i]))
                            .value(macdLine[i])
                            .significance(0.5)
                            .persistedBars(persistedBars)
                            .consequence(Consequence.ONGOING)
                            .from(from)
                            .to(to)
                            .ref("macd_cross_" + i)
                            .note("MACD crossed from " + from + " to " + to)
                            .build());
                }
            } else { // pure signal_cross (no zero_cross)
                beats.add(Beat.builder()
                        .what(BeatVerb.CROSSED)
                        .component(IndicatorComponent.MACD_LINE)
                        .whenBar(i)
                        .whenDate(instantToDateString(macd.getBarTimestamps()[i]))
                        .value(macdLine[i])
                        .significance(0.5)
                        .consequence(Consequence.CONFIRMED)
                        .from(from)
                        .to(to)
                        .ref("macd_cross_" + i)
                        .note("MACD crossed from " + from + " to " + to)
                        .build());
            }
        }

        return beats;
    }

    /**
     * Build thrust beats from histogram pivots.
     */
    private List<Beat> buildThrustBeats(List<SeriesPivot> histogramPivots, List<OhlcBarDTO> bars, MacdSeries macd) {
        List<Beat> beats = new ArrayList<>();

        for (SeriesPivot pivot : histogramPivots) {
            Beat beat = Beat.builder()
                    .what(BeatVerb.THRUST)
                    .component(IndicatorComponent.HISTOGRAM)
                    .whenBar(pivot.idx())
                    .whenDate(instantToDateString(macd.getBarTimestamps()[pivot.idx()]))
                    .value(pivot.value())
                    .significance(pivot.significance())
                    .consequence(Consequence.CONFIRMED)
                    .ref("macd_thrust_" + pivot.idx())
                    .note("MACD histogram " + (pivot.kind() == PivotKind.PEAK ? "peaked" : "troughed"))
                    .build();
            beats.add(beat);
        }

        return beats;
    }

    /**
     * Build diverged_from_price beats (THE HEADLINE).
     *
     * Algorithm: compare prices AT the MACD pivot bars themselves (using close[pivot.idx]),
     * NOT at the nearest price ZigZag pivot. This matches the reference output convention and
     * detects divergences in stretches where price ZigZag has no intervening pivot.
     *
     * For each consecutive pair of MACD peaks (p1, p2):
     *   - price1 = close at bar p1.idx, price2 = close at bar p2.idx
     *   - if price2 > price1 AND p2.value < p1.value → BEARISH regular divergence
     * Mirror for troughs → BULLISH regular divergence.
     */
    private List<Beat> buildDivergedFromPriceBeats(List<SeriesPivot> macdLinePivots, List<ZigZagPoint> pricePivots,
                                                   List<OhlcBarDTO> bars, MacdSeries macd) {
        List<Beat> beats = new ArrayList<>();

        try {
            List<SwingState> swingStates = PriceContextBuilder.classifySwingStates(pricePivots);

            // --- BEARISH divergences (between MACD peaks) ---
            List<SeriesPivot> peaks = macdLinePivots.stream()
                    .filter(p -> p.kind() == PivotKind.PEAK)
                    .collect(Collectors.toList());

            for (int i = 1; i < peaks.size(); i++) {
                SeriesPivot p1 = peaks.get(i - 1);
                SeriesPivot p2 = peaks.get(i);

                double price1 = bars.get(p1.idx()).getClose();
                double price2 = bars.get(p2.idx()).getClose();

                boolean priceHH = price2 > price1;
                boolean macdLH = p2.value() < p1.value();
                if (!(priceHH && macdLH)) continue;

                PivotPairEntry entry1 = PivotPairEntry.builder()
                        .bar(p1.idx())
                        .date(instantToDateString(macd.getBarTimestamps()[p1.idx()]))
                        .price(price1)
                        .macd(p1.value())
                        .build();
                PivotPairEntry entry2 = PivotPairEntry.builder()
                        .bar(p2.idx())
                        .date(instantToDateString(macd.getBarTimestamps()[p2.idx()]))
                        .price(price2)
                        .macd(p2.value())
                        .build();

                // Deeper anchor: earliest prior peak whose MACD value > p1's MACD value
                PivotPairEntry deeperAnchor = null;
                for (int j = i - 2; j >= 0; j--) {
                    SeriesPivot p0 = peaks.get(j);
                    if (p0.value() > p1.value()) {
                        deeperAnchor = PivotPairEntry.builder()
                                .bar(p0.idx())
                                .date(instantToDateString(macd.getBarTimestamps()[p0.idx()]))
                                .price(bars.get(p0.idx()).getClose())
                                .macd(p0.value())
                                .build();
                        break;
                    }
                }

                PriceContext priceContext = PriceContextBuilder.buildAt(p2.idx(), bars, pricePivots, swingStates);

                // Honesty check: does a later peak break the divergence geometry?
                // Bearish divergence says: price climbed while momentum sagged. If a subsequent
                // peak makes both price HH (above p2) AND MACD HH (above p2), the divergence
                // story is no longer true and we must say so — otherwise the LLM inherits a
                // false "confirmed" premise it cannot easily cross-check.
                Consequence consequence = Consequence.CONFIRMED;
                String invalidatedNote = "";
                for (int j = i + 1; j < peaks.size(); j++) {
                    SeriesPivot pLater = peaks.get(j);
                    double priceLater = bars.get(pLater.idx()).getClose();
                    if (priceLater > price2 && pLater.value() > p2.value()) {
                        consequence = Consequence.FAILED;
                        invalidatedNote = " [INVALIDATED at bar " + pLater.idx() + ": price " + priceLater
                                + " and MACD " + pLater.value() + " both broke the divergence ceiling]";
                        break;
                    }
                }

                Beat beat = Beat.builder()
                        .what(BeatVerb.DIVERGED_FROM_PRICE)
                        .component(IndicatorComponent.MACD_ALL)
                        .whenBar(p2.idx())
                        .whenDate(instantToDateString(macd.getBarTimestamps()[p2.idx()]))
                        .value(p2.value())
                        .significance(0.9)
                        .consequence(consequence)
                        .priceContext(priceContext)
                        .type("regular")
                        .direction("bearish")
                        .pivotPair(List.of(entry1, entry2))
                        .deeperAnchor(deeperAnchor)
                        .ref("macd_div_" + p2.idx())
                        .note("Price HH (" + price2 + " vs " + price1 + ") while MACD LH ("
                                + p2.value() + " vs " + p1.value() + ") — bearish divergence" + invalidatedNote)
                        .build();
                beats.add(beat);
            }

            // --- BULLISH divergences (between MACD troughs) ---
            List<SeriesPivot> troughs = macdLinePivots.stream()
                    .filter(p -> p.kind() == PivotKind.TROUGH)
                    .collect(Collectors.toList());

            for (int i = 1; i < troughs.size(); i++) {
                SeriesPivot t1 = troughs.get(i - 1);
                SeriesPivot t2 = troughs.get(i);

                double price1 = bars.get(t1.idx()).getClose();
                double price2 = bars.get(t2.idx()).getClose();

                boolean priceLL = price2 < price1;
                boolean macdHL = t2.value() > t1.value();
                if (!(priceLL && macdHL)) continue;

                PivotPairEntry entry1 = PivotPairEntry.builder()
                        .bar(t1.idx())
                        .date(instantToDateString(macd.getBarTimestamps()[t1.idx()]))
                        .price(price1)
                        .macd(t1.value())
                        .build();
                PivotPairEntry entry2 = PivotPairEntry.builder()
                        .bar(t2.idx())
                        .date(instantToDateString(macd.getBarTimestamps()[t2.idx()]))
                        .price(price2)
                        .macd(t2.value())
                        .build();

                // Deeper anchor: earliest prior trough whose MACD value < t1's MACD value
                PivotPairEntry deeperAnchor = null;
                for (int j = i - 2; j >= 0; j--) {
                    SeriesPivot t0 = troughs.get(j);
                    if (t0.value() < t1.value()) {
                        deeperAnchor = PivotPairEntry.builder()
                                .bar(t0.idx())
                                .date(instantToDateString(macd.getBarTimestamps()[t0.idx()]))
                                .price(bars.get(t0.idx()).getClose())
                                .macd(t0.value())
                                .build();
                        break;
                    }
                }

                PriceContext priceContext = PriceContextBuilder.buildAt(t2.idx(), bars, pricePivots, swingStates);

                // Mirror honesty check for bullish: invalidated when a later trough breaks
                // both axes below the divergence anchor (price LL beyond t2 AND MACD LL beyond t2).
                Consequence consequence = Consequence.CONFIRMED;
                String invalidatedNote = "";
                for (int j = i + 1; j < troughs.size(); j++) {
                    SeriesPivot tLater = troughs.get(j);
                    double priceLater = bars.get(tLater.idx()).getClose();
                    if (priceLater < price2 && tLater.value() < t2.value()) {
                        consequence = Consequence.FAILED;
                        invalidatedNote = " [INVALIDATED at bar " + tLater.idx() + ": price " + priceLater
                                + " and MACD " + tLater.value() + " both broke the divergence floor]";
                        break;
                    }
                }

                Beat beat = Beat.builder()
                        .what(BeatVerb.DIVERGED_FROM_PRICE)
                        .component(IndicatorComponent.MACD_ALL)
                        .whenBar(t2.idx())
                        .whenDate(instantToDateString(macd.getBarTimestamps()[t2.idx()]))
                        .value(t2.value())
                        .significance(0.9)
                        .consequence(consequence)
                        .priceContext(priceContext)
                        .type("regular")
                        .direction("bullish")
                        .pivotPair(List.of(entry1, entry2))
                        .deeperAnchor(deeperAnchor)
                        .ref("macd_div_" + t2.idx())
                        .note("Price LL (" + price2 + " vs " + price1 + ") while MACD HL ("
                                + t2.value() + " vs " + t1.value() + ") — bullish divergence" + invalidatedNote)
                        .build();
                beats.add(beat);
            }
        } catch (Exception e) {
            log.error("[macd-narrative] Exception in buildDivergedFromPriceBeats: " + e.getMessage(), e);
        }

        return beats;
    }

    /**
     * Assign tiers to beats and filter by rank-based significance.
     *
     * Per-tier filtering caps:
     *   HISTORY: top-3 peaks + top-3 troughs + ALL divergences + ALL regime_changes + ALL crossed + ALL failed_attempt
     *   RECENT:  top-4 peaks + top-4 troughs + top-3 thrusts + ALL divergences + ALL regime_changes + ALL crossed + ALL failed_attempt
     *   PRESENT: keep everything
     */
    private Map<Tier, List<Beat>> assignTiersAndFilter(List<Beat> allBeats, int lastIdx, MacdNarrativeParams params,
                                                       List<Beat> divergedBeats) {
        int presentBoundary = lastIdx - params.getPresentWindowBars();
        int recentBoundary = lastIdx - params.getRecentWindowBars();

        // First: assign tier to every beat
        List<Beat> withTier = new ArrayList<>();
        for (Beat b : allBeats) {
            Tier t;
            if (b.getWhenBar() > presentBoundary) t = Tier.PRESENT;
            else if (b.getWhenBar() > recentBoundary) t = Tier.RECENT;
            else t = Tier.HISTORY;

            Beat tieredBeat = Beat.builder()
                    .what(b.getWhat())
                    .component(b.getComponent())
                    .whenBar(b.getWhenBar())
                    .whenDate(b.getWhenDate())
                    .value(b.getValue())
                    .significance(b.getSignificance())
                    .persistedBars(b.getPersistedBars())
                    .consequence(b.getConsequence())
                    .priceContext(b.getPriceContext())
                    .tier(t)
                    .ref(b.getRef())
                    .note(b.getNote())
                    .macdLine(b.getMacdLine())
                    .signalLine(b.getSignalLine())
                    .histogram(b.getHistogram())
                    .from(b.getFrom())
                    .to(b.getTo())
                    .type(b.getType())
                    .direction(b.getDirection())
                    .pivotPair(b.getPivotPair())
                    .deeperAnchor(b.getDeeperAnchor())
                    .build();
            withTier.add(tieredBeat);
        }

        // Group by tier
        Map<Tier, List<Beat>> byTier = withTier.stream().collect(Collectors.groupingBy(Beat::getTier));

        // Per-tier filtering with rank-based caps.
        //
        // Reference output (memsys d1f56d5c) shape:
        //   HISTORY: ~4 beats — top peaks/troughs only; the long-arc anchors
        //   RECENT: ~6 beats — the swings that matter for the current setup
        //   PRESENT: bar-detailed
        //
        // CROSSED beats are dropped entirely (the verb is in the grammar but bare crosses
        // are noise; they get promoted to REGIME_CHANGE on persistence or FAILED_ATTEMPT on reversal).
        Map<Tier, List<Beat>> filtered = new EnumMap<>(Tier.class);
        for (Tier tier : Tier.values()) {
            List<Beat> tBeats = byTier.getOrDefault(tier, Collections.emptyList());
            List<Beat> kept = new ArrayList<>();
            if (tier == Tier.PRESENT) {
                // Keep everything except bare crossed
                for (Beat b : tBeats) {
                    if (b.getWhat() != BeatVerb.CROSSED) kept.add(b);
                }
            } else if (tier == Tier.HISTORY) {
                // History: only the long-arc structural anchors.
                // Per spec a2b5e3f3: "major beats only — record peaks/troughs, regime changes
                // that held, structural breaks". Regime changes that persisted long are
                // structural anchors and belong in HISTORY.
                kept.addAll(topN(tBeats, BeatVerb.PEAKED, 2));
                kept.addAll(topN(tBeats, BeatVerb.TROUGHED, 2));
                // Keep the top-3 most-persistent regime_changes in history.
                // Top-3 captures the longest-held regime flips — these are structural anchors
                // by the spec's definition. Reference RELIANCE has the bar-196 (their 197)
                // zero-down as one of the major collapses; with persistence ~29 it ranks #3
                // in our history slice and needs to survive.
                kept.addAll(tBeats.stream()
                        .filter(b -> b.getWhat() == BeatVerb.REGIME_CHANGE)
                        .sorted(Comparator.comparingInt(
                                (Beat b) -> -(b.getPersistedBars() == null ? 0 : b.getPersistedBars())))
                        .limit(3)
                        .collect(Collectors.toList()));
                kept.addAll(tBeats.stream()
                        .filter(b -> b.getWhat() == BeatVerb.DIVERGED_FROM_PRICE)
                        .collect(Collectors.toList()));
            } else { // RECENT
                kept.addAll(topN(tBeats, BeatVerb.PEAKED, 4));
                kept.addAll(topN(tBeats, BeatVerb.TROUGHED, 4));
                kept.addAll(topN(tBeats, BeatVerb.THRUST, 2));
                kept.addAll(tBeats.stream()
                        .filter(b -> b.getWhat() == BeatVerb.DIVERGED_FROM_PRICE
                                || b.getWhat() == BeatVerb.REGIME_CHANGE
                                || b.getWhat() == BeatVerb.FAILED_ATTEMPT)
                        .collect(Collectors.toList()));
            }
            // Sort by whenBar ascending within tier
            kept.sort(Comparator.comparingInt(Beat::getWhenBar));
            filtered.put(tier, kept);
        }

        return filtered;
    }

    /**
     * Rank the top N beats of a given verb.
     *
     * Ranking criterion is direction-aware:
     *   - PEAKED: highest VALUE wins (a higher peak is a stronger peak)
     *   - TROUGHED: lowest VALUE wins (a deeper trough is a stronger trough — negative is stronger)
     *   - THRUST and others: highest |value| wins
     * Significance ties broken by the value criterion. Since the pivot engine often clamps
     * significance to 1.0, value-based ranking is the dominant order in practice.
     */
    private List<Beat> topN(List<Beat> beats, BeatVerb verb, int n) {
        Comparator<Beat> bySignificance = Comparator
                .comparingDouble((Beat b) -> b.getSignificance() != null ? -b.getSignificance() : 0.0);
        Comparator<Beat> byValue;
        if (verb == BeatVerb.PEAKED) {
            // higher value = stronger peak
            byValue = Comparator.comparingDouble((Beat b) -> -(b.getValue() != null ? b.getValue() : 0.0));
        } else if (verb == BeatVerb.TROUGHED) {
            // lower (more negative) value = stronger trough
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

    /**
     * Build the currently beat (mandatory, always present tier).
     */
    private Beat buildCurrentlyBeat(int lastIdx, List<OhlcBarDTO> bars, MacdSeries macd) {
        double[] macdLine = macd.getMacdLine();
        double[] signalLine = macd.getSignalLine();
        double[] histogram = macd.getHistogram();

        String note = "MACD posture at last bar: line=" + String.format("%.2f", macdLine[lastIdx]) +
                ", signal=" + String.format("%.2f", signalLine[lastIdx]) +
                ", histogram=" + String.format("%.2f", histogram[lastIdx]);

        return Beat.builder()
                .what(BeatVerb.CURRENTLY)
                .component(IndicatorComponent.MACD_ALL)
                .whenBar(lastIdx)
                .whenDate(instantToDateString(macd.getBarTimestamps()[lastIdx]))
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

    /**
     * Build verification slices (checkpoints).
     */
    private VerificationSlices buildVerificationSlices(List<SeriesPivot> macdLinePivots, int lastIdx,
                                                       List<OhlcBarDTO> bars, MacdSeries macd) {
        double[] macdLine = macd.getMacdLine();
        double[] signalLine = macd.getSignalLine();
        double[] histogram = macd.getHistogram();

        List<Checkpoint> checkpoints = new ArrayList<>();

        // Add checkpoints at history-tier beats + last bar
        for (SeriesPivot pivot : macdLinePivots) {
            if (pivot.idx() <= lastIdx - 100) { // history tier cutoff
                checkpoints.add(Checkpoint.builder()
                        .bar(pivot.idx())
                        .macdLine(macdLine[pivot.idx()])
                        .signalLine(signalLine[pivot.idx()])
                        .histogram(histogram[pivot.idx()])
                        .build());
            }
        }

        // Add last bar
        checkpoints.add(Checkpoint.builder()
                .bar(lastIdx)
                .macdLine(macdLine[lastIdx])
                .signalLine(signalLine[lastIdx])
                .histogram(histogram[lastIdx])
                .build());

        return VerificationSlices.builder()
                .comment("Checkpoints at history pivots and last bar for verification")
                .checkpoints(checkpoints)
                .build();
    }

    // ===== Helper methods =====

    /**
     * Count how many consecutive bars (starting at crossBar) MACD stayed on its new side of zero.
     * The result tells how long the regime actually held — used both for the regime_change
     * persistence threshold AND for the emitted beat's `persisted_bars` field. Counts the
     * full run, not just up to the threshold (otherwise the field is uselessly capped).
     *
     * @param macdLine  the MACD line series
     * @param crossBar  the bar at which the zero-cross was detected (macdLine[crossBar] is on the new side)
     * @return number of bars (≥ 1) on the new side of zero starting at crossBar, before MACD
     *         crosses back. Returns the full run up to the end of the series if it never crosses back.
     */
    private int checkRegimePersistence(double[] macdLine, int crossBar, int persistenceWindow) {
        int persisted = 1;  // Count the cross bar itself
        boolean isAboveZero = macdLine[crossBar] > 0;

        for (int i = crossBar + 1; i < macdLine.length; i++) {
            if (isAboveZero && macdLine[i] >= 0) {
                persisted++;
            } else if (!isAboveZero && macdLine[i] < 0) {
                persisted++;
            } else {
                break;
            }
        }

        return persisted;
    }

    private double computeStddev(double[] series, int window) {
        if (series.length == 0) return 1.0;
        double sum = 0.0;
        int count = Math.min(window, series.length);
        for (int i = series.length - count; i < series.length; i++) {
            sum += series[i];
        }
        double mean = sum / count;
        double variance = 0.0;
        for (int i = series.length - count; i < series.length; i++) {
            variance += (series[i] - mean) * (series[i] - mean);
        }
        return Math.sqrt(variance / count);
    }

    private ZigZagPoint findNearestPrecedingHigh(List<ZigZagPoint> highs, int barIdx) {
        for (int i = highs.size() - 1; i >= 0; i--) {
            if (highs.get(i).getBarIndex() <= barIdx) {
                return highs.get(i);
            }
        }
        return null;
    }

    private ZigZagPoint findNearestPrecedingLow(List<ZigZagPoint> lows, int barIdx) {
        for (int i = lows.size() - 1; i >= 0; i--) {
            if (lows.get(i).getBarIndex() <= barIdx) {
                return lows.get(i);
            }
        }
        return null;
    }

    private String instantToDateString(Instant instant) {
        LocalDateTime ldt = LocalDateTime.ofInstant(instant, ZoneId.of("Asia/Kolkata"));
        return ldt.format(DATE_FORMATTER);
    }
}
