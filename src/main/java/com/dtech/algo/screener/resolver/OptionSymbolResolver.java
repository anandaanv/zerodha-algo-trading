package com.dtech.algo.screener.resolver;

/**
 * Resolves option/future instrument trading symbols from a base underlying symbol and a nomination.
 * Real implementation consults the instrument database using exchange conventions, expiry, ATM strikes etc.
 */
public interface OptionSymbolResolver {
    /**
     * Resolve an option instrument trading symbol for the given underlying and nomination.
     *
     * @param underlying the spot trading symbol
     * @param nomination the option nomination (type and offset)
     * @param ltp current last traded price of the underlying
     * @return the resolved trading symbol
     */
    String resolveOption(String underlying, OptionNomination nomination, Double ltp);

    /**
     * Resolve a future instrument trading symbol for the given underlying.
     *
     * @param underlying the spot trading symbol
     * @return the resolved trading symbol
     */
    String resolveFuture(String underlying);
}
