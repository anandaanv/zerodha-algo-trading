package com.dtech.aitrader.v2.rules;

import com.dtech.chartdata.model.OhlcBarDTO;
import com.dtech.kitecon.service.copilot.dto.MarketStructurePoint;
import lombok.Builder;
import lombok.Value;
import org.ta4j.core.BarSeries;

import java.time.LocalDate;
import java.util.List;

/**
 * Read-only point-in-time universe a rule sees when evaluating at {@code asOf}. Built by
 * {@code ContextLoader.build(symbol, asOf, tf)}; the loader is the single chokepoint that enforces
 * the no-leakage invariant: no bar / pivot / indicator value visible to a rule has a date later
 * than {@link #asOf}.
 *
 * <p>Pilot scope: one TF per call (DAILY). Multi-TF context comes after the pilot passes.
 */
@Value
@Builder(toBuilder = true)
public class SymbolContext {

    String symbol;

    /** Cutoff date — everything inside this context honours {@code date ≤ asOf}. */
    LocalDate asOf;

    /** Timeframe label, e.g. {@code "DAILY"}. */
    String tf;

    /** Raw bars up to and including {@link #asOf}, oldest first. */
    List<OhlcBarDTO> bars;

    /** ta4j series built from {@link #bars} — convenient for indicator computation. */
    BarSeries series;

    /**
     * Pivots with structure labels (HH/HL/LH/LL/CHOCH/BOS) up to {@link #asOf}, oldest first.
     * Produced by {@link com.dtech.kitecon.service.copilot.MarketStructureService} over the
     * zigzag detector output.
     */
    List<MarketStructurePoint> pivots;

    /** The probe result every rule reads to attach role / context_signature. */
    ContextProbeResult probe;

    /**
     * Lazy indicator accessor — shared by all rules in this context. Caches MACD/RSI/ADX/EMA/ATR/BB
     * indicator instances so Pass-5 rules don't repeat the boilerplate. See {@link IndicatorAccessor}.
     */
    IndicatorAccessor indicators;

    /**
     * Trader annotations (Rule 0.35) lifted from the scan-context. Weight ≥2 annotations feed the
     * Pass-1 {@code EwAnnotationIntakeRule} and Pass-4 {@code EwAnnotationPriorRule}. May be
     * {@code null} or empty for symbols without trader overlays.
     */
    List<AnnotationEntry> annotations;

    /**
     * Multi-TF pivot map per owner Q1 approval ({@code 75b20b10}): keyed by TF label
     * ({@code "Week"} / {@code "Day"} / {@code "OneHour"}), values are the pivot list for that
     * TF as parsed from the scan-context. Pass-1 EW rules read {@code Week}; Pass-5
     * leg-substructure walks {@code Day} / {@code OneHour} sub-pivots of each macro leg.
     *
     * <p>For the legacy single-TF path (pilot rules), {@link #pivots} stays populated as before
     * — this map is additive.
     */
    java.util.Map<String, List<com.dtech.kitecon.service.copilot.dto.MarketStructurePoint>> pivotsByTf;

    /**
     * Dwell pivots (SPEC-010 Phase 1 per {@code 60d21c43}, design per SPEC-009 {@code 36b585f6}
     * + dwell spec {@code e603dbf9} + owner refinements {@code 59fa728f} / {@code f1201a45}). A
     * SEPARATE explicit collection from the alternating reversal-pivot series — owner
     * constraint {@code 59fa728f}: dwell pivots MUST NOT be spliced into the H-L-H-L
     * reversal-pivot series; they are LEVELS (consolidation shelves), not turns. Each entry
     * carries its own {@code tf}, so consumers filter by TF.
     *
     * <p>Populated by {@code DwellPivotAttacher} (service pattern, sibling to
     * {@code PatternContextAttacher}) — the engine never mutates context; pre-attach happens
     * BEFORE the multi-pass engine runs.
     *
     * <p>The reversal-pivot series ({@link #pivots} / {@link #pivotsByTf}) is UNTOUCHED — EW
     * wave-counting depends on the H-L-H-L alternation invariant.
     */
    List<com.dtech.aitrader.v2.rules.ew.dwell.DwellPivot> dwellPivots;
}
