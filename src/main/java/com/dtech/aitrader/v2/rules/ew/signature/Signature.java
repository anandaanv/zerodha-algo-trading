package com.dtech.aitrader.v2.rules.ew.signature;

import java.util.List;

/**
 * Declarative description of an EW form's expected leg-character sequence. Each
 * {@link com.dtech.aitrader.v2.rules.ew.signature.EwSignatureRule} declares one signature; the
 * registry matches observed leg characters against the signature.
 *
 * <p><b>Future DSL grammar:</b> this record is the structural part of an EW rule declaration.
 * When the DSL extraction happens (per decision {@code eec5c5dd}, after stability gate), a YAML/
 * JSON rule will deserialize directly into this shape:
 * <pre>{@code
 *   form: zigzag
 *   legs: [A, B, C]
 *   expected: [FIVE, THREE, FIVE]
 * }</pre>
 *
 * <p><b>Examples:</b>
 * <pre>
 *   Zigzag   : legs=[A,B,C]              expected=[FIVE, THREE, FIVE]
 *   Flat     : legs=[A,B,C]              expected=[THREE, THREE, FIVE]
 *   Triangle : legs=[A,B,C,D,E]          expected=[THREE, THREE, THREE, THREE, THREE]
 *   Impulse  : legs=[W1,W2,W3,W4,W5]     expected=[FIVE, THREE, FIVE, THREE, FIVE]
 * </pre>
 *
 * @param formName   form identifier, e.g. {@code "zigzag"}, {@code "flat"}, {@code "triangle"},
 *                   {@code "impulse"}, {@code "bigger-impulse"}, {@code "truncated-c"}.
 * @param legLabels  ordered leg labels in EW role naming (size must equal {@code expected}).
 * @param expected   ordered character expectations per leg position.
 */
public record Signature(String formName, List<String> legLabels, List<LegCharacter> expected) {

    public Signature {
        if (legLabels == null || expected == null) {
            throw new IllegalArgumentException("legLabels and expected required");
        }
        if (legLabels.size() != expected.size()) {
            throw new IllegalArgumentException(
                    "legLabels (" + legLabels.size() + ") and expected (" + expected.size() + ") must match");
        }
        legLabels = List.copyOf(legLabels);
        expected = List.copyOf(expected);
    }

    public int legCount() {
        return legLabels.size();
    }
}
