package com.dtech.aitrader.v2.regime;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * The structural levels defining a regime. Per schema (a44c035a):
 * <ul>
 *   <li>{@code invalidation} is MANDATORY — the level that kills the regime read.</li>
 *   <li>{@code pivot} is the decision level price is reacting to.</li>
 *   <li>{@code support}/{@code resistance} are nodes (may be empty/null).</li>
 *   <li>{@code targets_if_resolves} are structural target nodes — reference, NOT orders.</li>
 * </ul>
 *
 * <p>algotrade builds its entry/stop/target from these — it does not treat them as orders.
 */
@Data
@Builder
@NoArgsConstructor(access = AccessLevel.PUBLIC)
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class DefiningLevels {
    /** The decision level price is reacting to. Nullable. */
    private Double pivot;
    /** Support nodes. May be empty. */
    private List<Double> support;
    /** Resistance nodes. May be empty. */
    private List<Double> resistance;
    /** The level that KILLS this regime read. Required. */
    private Double invalidation;
    /** Structural targets if the regime resolves. May be empty. */
    private List<Double> targets_if_resolves;
}
