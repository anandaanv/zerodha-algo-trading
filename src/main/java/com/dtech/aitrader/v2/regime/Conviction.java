package com.dtech.aitrader.v2.regime;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Conviction per regime-record-v1 schema. ADVISORY ONLY — algotrade's risk layer cannot let this
 * field override hard caps. The reader MAY use it to scale within bounds; it MUST NOT use it to
 * set size beyond bounds. Owner-level rule, not enforced in code.
 */
public enum Conviction {
    LOW("low"),
    NORMAL("normal"),
    HIGH("high");

    private final String wire;

    Conviction(String wire) {
        this.wire = wire;
    }

    @JsonValue
    public String getWire() {
        return wire;
    }

    @JsonCreator
    public static Conviction fromWire(String wire) {
        if (wire == null) return null;
        for (Conviction c : values()) {
            if (c.wire.equalsIgnoreCase(wire)) return c;
        }
        throw new IllegalArgumentException("Unknown conviction: " + wire + " — allowed: low, normal, high");
    }
}
