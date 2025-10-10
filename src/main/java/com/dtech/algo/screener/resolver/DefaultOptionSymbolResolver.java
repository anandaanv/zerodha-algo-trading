package com.dtech.algo.screener.resolver;

/**
 * A trivial OptionSymbolResolver used when no actual market resolver is available.
 * It synthesizes a name like UNDERLYING_CE1 or UNDERLYING_PE-1: this is only for development/testing.
 */
public class DefaultOptionSymbolResolver implements OptionSymbolResolver {
    @Override
    public String resolveOption(String underlying, OptionNomination nomination, Double ltp) {
        return underlying + "_" + nomination.toString();
    }

    @Override
    public String resolveFuture(String underlying) {
        return underlying + "_FUT";
    }
}
