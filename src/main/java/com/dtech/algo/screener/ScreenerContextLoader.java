package com.dtech.algo.screener;

import com.dtech.algo.runner.candle.LatestBarSeriesProvider;
import com.dtech.algo.series.IntervalBarSeries;
import com.dtech.kitecon.controller.BarSeriesHelper;
import com.dtech.algo.screener.enums.SeriesEnum;
import com.dtech.kitecon.repository.InstrumentLtpRepository;
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
    private final InstrumentResolver instrumentResolver;
    private final InstrumentLtpRepository instrumentLtpRepository;

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

        // Get LTP for the base symbol (null acceptable, will use default resolver)
        Double ltp = instrumentLtpRepository.findByTradingSymbol(baseSymbol)
                .orElseThrow( () -> new IllegalArgumentException("No LTP found for " + baseSymbol))
                .getLtp();
        // TODO: Get LTP from market data if needed

        for (Map.Entry<String, SeriesSpec> e : mapping.entrySet()) {
            String alias = e.getKey();
            SeriesSpec spec = e.getValue();

            SeriesEnum reference = spec.reference();
            String interval = spec.interval().trim();

            String instrumentToLoad = instrumentResolver.resolveInstrument(baseSymbol, reference, ltp);

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
}
