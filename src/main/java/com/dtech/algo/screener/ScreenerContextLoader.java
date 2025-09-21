package com.dtech.algo.screener;

import com.dtech.algo.runner.candle.LatestBarSeriesProvider;
import com.dtech.algo.series.IntervalBarSeries;
import com.dtech.kitecon.controller.BarSeriesHelper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBarSeries;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.dtech.algo.screener.enums.SeriesEnum;
import com.dtech.algo.screener.enums.SeriesEnum;

/**
 * Loads a ScreenerContext from a mapping of alias -> SeriesSpec (reference and interval).
 *
 * Input mapping examples:
 *   "wave" -> SeriesSpec.of("SPOT", "15min")
 *   "ride" -> SeriesSpec.of("SPOT", "1h")
 *   "atmpewave" -> SeriesSpec.of("PE1", "15min")
 *   "itmpewave" -> SeriesSpec.of("PE-1", "15min")
 *
 * Nomination rules (as requested):
 *   - SPOT -> underlying spot instrument
 *   - FUT  -> underlying future instrument
 *   - CE/PE with numeric suffixes: CE1, CE2, CE-1, PE1, PE-2 etc.
 *       PE1, PE2, PE3 are strikes below spot
 *       PE-1, PE-2, PE-3 are strikes above spot
 *       (Same convention used for CE prefixes)
 *
 * This class delegates:
 *   - resolving actual instrument symbols for option nominations to OptionSymbolResolver
 *   - loading/creating BarSeries for a given instrument + interval to BarSeriesProvider
 *
 * The loader populates ScreenerContext.aliases with alias -> BarSeries.
 */
@RequiredArgsConstructor
@Service
public class ScreenerContextLoader {

    private static final Pattern OPTION_PATTERN = Pattern.compile("^(CE|PE)(-?\\d+)$", Pattern.CASE_INSENSITIVE);

    private final BarSeriesHelper barSeriesProvider;
    private final OptionSymbolResolver optionSymbolResolver = new DefaultOptionSymbolResolver();

    /**
     * Build a ScreenerContext for the provided mapping.
     *
     * @param baseSymbol base underlying symbol (e.g. "NIFTY" or "RELIANCE")
     * @param mapping alias -> SeriesSpec (reference, interval)
     * @return ScreenerContext with aliases and metadata
     */
    public ScreenerContext load(String baseSymbol, Map<String, SeriesSpec> mapping, int nowIndex, String timeframe) {
        Objects.requireNonNull(mapping, "mapping must not be null");
        Map<String, IntervalBarSeries> aliases = new HashMap<>();

        for (Map.Entry<String, SeriesSpec> e : mapping.entrySet()) {
            String alias = e.getKey();
            SeriesSpec spec = e.getValue();

            SeriesEnum reference = spec.reference();
            String interval = spec.interval().trim();

            String instrumentToLoad;

            if (reference == SeriesEnum.SPOT) {
                instrumentToLoad = baseSymbol; // treat as spot
            } else if (reference.isFuture()) {
                instrumentToLoad = optionSymbolResolver.resolveFuture(baseSymbol);
            } else if (reference.isOption()) {
                OptionNomination nomination = new OptionNomination(
                        InstrumentType.valueOf(reference.optionPrefix()),
                        reference.optionOffset()
                );
                instrumentToLoad = optionSymbolResolver.resolveOption(baseSymbol, nomination);
            } else {
                // Fallback: treat as literal instrument name (shouldn't occur with enum)
                instrumentToLoad = reference.name();
            }

            IntervalBarSeries series = barSeriesProvider.getIntervalBarSeries(instrumentToLoad, interval);
            aliases.put(alias, series);
        }

        Map<String, Object> params = new HashMap<>();
        params.put("mapping", mapping);

        return ScreenerContext.builder()
                .aliases(aliases)
                .params(params)
                .nowIndex(nowIndex)
                .symbol(baseSymbol)
                .timeframe(timeframe)
                .build();
    }

    /**
     * Simple container for series specification.
     */
    public static record SeriesSpec(SeriesEnum reference, String interval) {
        public static SeriesSpec of(SeriesEnum reference, String interval) {
            return new SeriesSpec(reference, interval);
        }
        public static SeriesSpec of(String reference, String interval) {
            return new SeriesSpec(SeriesEnum.fromString(reference), interval);
        }
    }

    /**
     * Instrument types we know about for options.
     */
    public enum InstrumentType {
        CE, PE
    }

    /**
     * Nomination for an option relative to spot.
     * offset: positive or negative integer according to the naming convention.
     * - For this project convention:
     *     PE1, PE2, PE3 are strikes below spot
     *     PE-1, PE-2 are strikes above spot
     *   The offset value carries the numeric part (can be negative).
     */
    public static final class OptionNomination {
        private final InstrumentType type;
        private final int offset;

        public OptionNomination(InstrumentType type, int offset) {
            this.type = type;
            this.offset = offset;
        }

        public InstrumentType type() {
            return type;
        }

        public int offset() {
            return offset;
        }

        @Override
        public String toString() {
            return type.name() + (offset >= 0 ? String.valueOf(offset) : String.valueOf(offset));
        }
    }


    /**
     * Delegate to resolve option/future instrument symbol strings from a base underlying symbol and a nomination.
     * Real implementation would consult exchange conventions, expiry, ATM strikes etc.
     * A trivial default implementation is provided below for testing / dev.
     */
    public interface OptionSymbolResolver {
        /**
         * Resolve an option instrument symbol for the given underlying and nomination.
         */
        String resolveOption(String underlying, OptionNomination nomination);

        /**
         * Resolve a future instrument symbol for the given underlying.
         */
        String resolveFuture(String underlying);
    }


    /**
     * A trivial OptionSymbolResolver used when no actual market resolver is available.
     * It synthesizes a name like UNDERLYING_CE1 or UNDERLYING_PE-1: this is only for development/testing.
     */
    public static class DefaultOptionSymbolResolver implements OptionSymbolResolver {
        @Override
        public String resolveOption(String underlying, OptionNomination nomination) {
            return underlying + "_" + nomination.toString();
        }

        @Override
        public String resolveFuture(String underlying) {
            return underlying + "_FUT";
        }
    }
}
