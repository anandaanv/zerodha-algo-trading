package com.dtech.algo.screener.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Canonical series nominations used in ScreenerContextLoader.SeriesSpec.
 *
 * Naming:
 * - CE1..CE5 and PE1..PE5 denote OTM offsets (positive).
 * - CE_1..CE_5 and PE_1..PE_5 denote ITM offsets (negative), underscore stands for '-'.
 * - FUT1 and FUT2 cover near and next futures (both map to resolveFuture in default resolver).
 */
public enum SeriesEnum {
    SPOT(-1),
    FUT1(0), FUT2(0),

    CE1(1), CE2(2), CE3(3), CE4(4), CE5(5),
    CE_1(1), CE_2(2), CE_3(3), CE_4(4), CE_5(5),

    PE1(1), PE2(2), PE3(3), PE4(4), PE5(5),
    PE_1(1), PE_2(2), PE_3(3), PE_4(4), PE_5(5);

    private final int distance;

    SeriesEnum(int distance) {
        this.distance = distance;
    }

    /**
     * Distance from spot for options (absolute strike steps). 0 for SPOT/FUTx.
     */
    public int distance() {
        return distance;
    }

    public boolean isSpot() {
        return this == SPOT;
    }

    public boolean isFuture() {
        return this == FUT1 || this == FUT2;
    }

    public boolean isOption() {
        return !isSpot() && !isFuture();
    }

    /**
     * Returns "CE" or "PE" for option types; null for SPOT/FUTx.
     */
    public String optionPrefix() {
        if (!isOption()) return null;
        return name().startsWith("CE") ? "CE" : "PE";
    }

    /**
     * Returns numeric offset for option enums.
     * Examples: CE1 -> 1, CE_1 -> -1, PE5 -> 5, PE_3 -> -3
     * Throws IllegalStateException if called for non-option values.
     */
    public int optionOffset() {
        if (!isOption()) {
            throw new IllegalStateException("optionOffset() only valid for CE/PE options");
        }
        String suffix = name().substring(2); // after CE/PE
        if (suffix.startsWith("_")) {
            return -Integer.parseInt(suffix.substring(1));
        }
        return Integer.parseInt(suffix);
    }

    private static final Pattern OPT_NEG = Pattern.compile("^(CE|PE)-(\\d+)$");

    /**
     * Parse tolerant string input into enum:
     * Accepts values like "SPOT", "FUT", "FUT1", "FUT2", "CE1", "CE-1", "CE_1", "PE+2", etc.
     * "FUT" normalizes to FUT1.
     */
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static SeriesEnum fromString(String raw) {
        if (raw == null) return SPOT;
        String s = raw.trim().toUpperCase(Locale.ROOT).replace(" ", "");
        // Normalize FUT -> FUT1 as a default
        if ("FUT".equals(s)) {
            return FUT1;
        }
        // Remove explicit '+' sign (OTM)
        s = s.replace("+", "");

        // Handle CE-1 / PE-2 forms
        Matcher m = OPT_NEG.matcher(s);
        if (m.matches()) {
            String type = m.group(1);
            String n = m.group(2);
            return SeriesEnum.valueOf(type + "_" + n);
        }

        // CE_1 / PE_1 are already in enum form; CE1/PE1 are direct
        return SeriesEnum.valueOf(s);
    }

    /**
     * Serialize enum name back to a friendly string, e.g. "CE_1" -> "CE-1", "CE1" -> "CE1".
     */
    @JsonValue
    public String toJson() {
        return name().replace("_", "-");
    }
}
