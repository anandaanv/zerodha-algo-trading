package com.dtech.aitrader.v2.regime;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Directional bias per regime-record-v1 schema. {@code NEUTRAL} is ONLY valid in combination with
 * {@link RegimeClass#SQUEEZE_COILED} — that constraint is enforced by
 * {@link RegimeRecordValidator}.
 */
public enum Bias {
    LONG("long"),
    SHORT("short"),
    NEUTRAL("neutral");

    private final String wire;

    Bias(String wire) {
        this.wire = wire;
    }

    @JsonValue
    public String getWire() {
        return wire;
    }

    @JsonCreator
    public static Bias fromWire(String wire) {
        if (wire == null) return null;
        for (Bias b : values()) {
            if (b.wire.equalsIgnoreCase(wire)) return b;
        }
        throw new IllegalArgumentException("Unknown bias: " + wire + " — allowed: long, short, neutral");
    }
}
