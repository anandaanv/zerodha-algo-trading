package com.dtech.aitrader.v2.rules.ew.signature;

import java.util.List;

/**
 * The watch + invalidation levels contributed by one live hypothesis to the engine's LEVEL-MAP.
 *
 * <p>Per owner's reframe ({@code 159ba913}): the engine's output is the level-map of (watch,
 * invalidation) per live hypothesis — NOT "SHORT 0.68 to 1152." A user/agent consumes the
 * level-map to decide what to actually do.
 *
 * @param watch          price levels where this hypothesis's next leg would complete (the "what
 *                       to wait for" levels). Empty list if the hypothesis has no actionable
 *                       next leg yet.
 * @param invalidation   price levels where this hypothesis dies. Should always have at least one
 *                       entry (every live hypothesis has a kill point).
 */
public record DerivedLevels(List<PriceLevel> watch, List<PriceLevel> invalidation) {

    public DerivedLevels {
        watch = watch == null ? List.of() : List.copyOf(watch);
        invalidation = invalidation == null ? List.of() : List.copyOf(invalidation);
    }
}
