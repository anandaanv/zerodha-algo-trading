package com.dtech.algo.screener.api;

import com.dtech.kitecon.data.Instrument;
import java.util.List;
import java.util.Map;

/**
 * Unified interface for all screener types (DSL, Elliott, Pattern).
 * Implementations wrap existing screener services behind a common contract.
 */
public interface Screener<T extends ScreenerResult> {

    /**
     * Human-readable name of this screener.
     */
    String getName();

    /**
     * Which screener type this is.
     */
    ScreenerType getType();

    /**
     * Run the screener on the given symbols with provided configuration.
     *
     * @param symbols list of trading symbols to scan
     * @param config  screener-specific configuration (timeframes, thresholds, etc.)
     * @return list of results for symbols that matched
     */
    List<T> scan(List<String> symbols, Map<String, Object> config);
}
