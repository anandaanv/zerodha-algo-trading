package com.dtech.aitrader.v2.narrative.engine;

import com.dtech.aitrader.v2.narrative.beat.Beat;
import com.dtech.aitrader.v2.narrative.beat.Checkpoint;
import com.dtech.aitrader.v2.narrative.pivot.SeriesPivot;
import com.dtech.chartdata.model.OhlcBarDTO;

import java.util.List;
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
