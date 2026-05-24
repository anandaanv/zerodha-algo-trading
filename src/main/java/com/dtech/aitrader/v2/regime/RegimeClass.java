package com.dtech.aitrader.v2.regime;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Regime class enum per regime-record-v1 schema (memsys a44c035a). The ONLY allowed values for
 * a regime-record's {@code regime} field.
 */
public enum RegimeClass {
    TRENDING_UP("trending_up"),
    TRENDING_DOWN("trending_down"),
    RANGING("ranging"),
    SQUEEZE_COILED("squeeze_coiled"),
    REVERSAL_SETUP("reversal_setup"),
    BREAKOUT_PENDING("breakout_pending");

    private final String wire;

    RegimeClass(String wire) {
        this.wire = wire;
    }

    @JsonValue
    public String getWire() {
        return wire;
    }

    @JsonCreator
    public static RegimeClass fromWire(String wire) {
        if (wire == null) return null;
        for (RegimeClass r : values()) {
            if (r.wire.equalsIgnoreCase(wire)) return r;
        }
        throw new IllegalArgumentException("Unknown regime: " + wire
                + " — allowed: trending_up, trending_down, ranging, squeeze_coiled, "
                + "reversal_setup, breakout_pending");
    }
}
