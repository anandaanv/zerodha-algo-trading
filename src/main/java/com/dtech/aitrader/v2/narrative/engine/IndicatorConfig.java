package com.dtech.aitrader.v2.narrative.engine;

import com.dtech.aitrader.v2.narrative.beat.Beat;
import com.dtech.aitrader.v2.narrative.beat.Checkpoint;
import com.dtech.aitrader.v2.narrative.beat.IndicatorComponent;
import com.dtech.aitrader.v2.narrative.beat.SwingState;
import com.dtech.aitrader.v2.narrative.pivot.SeriesPivot;
import com.dtech.chartdata.model.OhlcBarDTO;
import com.dtech.chartpattern.zigzag.ZigZagPoint;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Per-indicator configuration plugged into {@link DescriptiveNarrativeEngine}. Implements the
 * "delta" pattern from the Narrative Core spec (memsys 533b3e85): the engine supplies all shared
 * machinery; the config supplies only indicator-specific facts.
 *
 * <p>A new indicator is added by writing one class that implements this interface; the engine
 * loop is reused unchanged.
 */
public interface IndicatorConfig {

    /** Display name, e.g. {@code "MACD"}, {@code "RSI"}. Used in logs + narrative.indicator. */
    String getIndicatorName();

    /** Whether this indicator gets the full divergence/thrust engine or only regime-episodes. */
    NarrativeTier getNarrativeTier();

    /** Shared engine knobs (significance, tier windows, persistence). */
    EngineParams getEngineParams();

    /** Compute the indicator series from OHLC bars. */
    IndicatorSeries compute(List<OhlcBarDTO> bars, String symbol, String timeframe);

    /**
     * Components to run adaptive-significance pivot detection on. The first entry is treated as
     * the "primary" component — the one whose pivots drive divergence detection (if any) and
     * verification checkpoints.
     */
    List<PivotComponentSpec> getPivotComponents();

    /** Crossover rules (zero-cross, signal-cross, DI-cross, centerline-cross, etc.). May be empty. */
    List<CrossoverSpec> getCrossovers();

    /** Divergence detection rule (which component to pair with close price). Empty for ADX/EMA/etc. */
    Optional<DivergenceSpec> getDivergence();

    /**
     * Named zones for {@code entered_zone}/{@code exited_zone} beats. Empty for indicators
     * without natural zones (MACD).
     */
    default List<ZoneSpec> getZones() {
        return Collections.emptyList();
    }

    /**
     * Escape hatch for indicator-specific beat logic that does not fit the shared verb emitters:
     * RSI Brown-range regime classifier, RSI Wilder failure-swings, EMA-stack lifecycle collapse,
     * Cardwell reversals, etc.
     *
     * <p>The engine appends the returned beats to the pool before tier filtering, so they take
     * part in the same noise/tier rules as engine-emitted beats. The {@code pivotsByComponent}
     * map exposes the engine's already-computed pivots so each config can do
     * structural/sequential analysis without re-running pivot detection.
     */
    default List<Beat> emitCustomBeats(IndicatorSeries series, List<OhlcBarDTO> bars,
                                       List<ZigZagPoint> pricePivots, List<SwingState> swingStates,
                                       Map<IndicatorComponent, List<SeriesPivot>> pivotsByComponent) {
        return Collections.emptyList();
    }

    /**
     * Build the present-tier "currently" beat at the last bar. Each indicator describes its
     * own posture differently (MACD: line/signal/histogram; RSI: rsi + zone; ADX: adx + regime).
     */
    Beat buildCurrentlyBeat(int lastIdx, IndicatorSeries series, List<OhlcBarDTO> bars);

    /**
     * Build verification checkpoints (typically at history-tier pivots + the last bar). Different
     * indicators may want different fields populated on each Checkpoint.
     *
     * @param primaryPivots pivots of the {@link #getPivotComponents()} primary component
     * @param lastIdx       index of last bar in the series
     */
    List<Checkpoint> buildVerificationCheckpoints(IndicatorSeries series,
                                                  List<SeriesPivot> primaryPivots,
                                                  int lastIdx);
}
