package com.dtech.algo.screener;

import com.dtech.algo.screener.enums.SeriesEnum;
import com.dtech.algo.screener.resolver.OptionNomination;
import com.dtech.algo.screener.resolver.OptionSymbolResolver;
import com.dtech.algo.series.InstrumentType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Resolves instrument trading symbols based on SeriesEnum references.
 * This is the common logic extracted from ScreenerContextLoader.
 */
@Component
@RequiredArgsConstructor
public class InstrumentResolver {

    private final OptionSymbolResolver optionSymbolResolver;

    /**
     * Resolves the instrument trading symbol for a given SeriesEnum reference.
     *
     * @param baseSymbol the underlying symbol (e.g., "NIFTY", "RELIANCE")
     * @param reference the series reference (SPOT, FUT1, CE1, PE-1, etc.)
     * @param ltp current last traded price of the underlying (required for options)
     * @return the resolved instrument trading symbol
     */
    public String resolveInstrument(String baseSymbol, SeriesEnum reference, Double ltp) {
        if (reference == SeriesEnum.SPOT) {
            return baseSymbol;
        } else if (reference.isFuture()) {
            return optionSymbolResolver.resolveFuture(baseSymbol);
        } else if (reference.isOption()) {
            OptionNomination nomination = new OptionNomination(
                    InstrumentType.valueOf(reference.optionPrefix()),
                    reference.optionOffset()
            );
            return optionSymbolResolver.resolveOption(baseSymbol, nomination, ltp);
        } else {
            return reference.name();
        }
    }
}
