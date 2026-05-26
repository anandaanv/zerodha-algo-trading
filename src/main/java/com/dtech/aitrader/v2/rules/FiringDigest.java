package com.dtech.aitrader.v2.rules;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * SHA-256 fingerprint of the logical identity of a firing — used by the unique-index UPSERT
 * pattern to make backtest re-runs idempotent (owner Q1, ratification {@code 7885ad63}).
 *
 * <p>Digest input = {@code rule_id | symbol | as_of | sorted(refs) | canonical(payload)}.
 *
 * <p>"Canonical" means: payload keys sorted alphabetically (via Jackson's
 * {@link SerializationFeature#ORDER_MAP_ENTRIES_BY_KEYS}). Without this, two re-runs that produce
 * the same logical firing in different in-memory map orderings would hash differently and the
 * unique index would reject neither — defeating the whole purpose.
 *
 * <p>Refs are sorted before serialisation for the same reason (a firing that depends on candidates
 * X and Y is logically identical regardless of which is listed first).
 */
public final class FiringDigest {

    private static final ObjectMapper CANONICAL_JSON = JsonMapper.builder()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
            .build();

    private FiringDigest() {}

    public static String compute(String ruleId, String symbol, LocalDate asOf,
                                   List<String> refs, Map<String, Object> payload) {
        try {
            StringBuilder sb = new StringBuilder(256);
            sb.append(ruleId == null ? "" : ruleId).append('|');
            sb.append(symbol == null ? "" : symbol).append('|');
            sb.append(asOf == null ? "" : asOf.toString()).append('|');

            List<String> sortedRefs = refs == null ? Collections.emptyList() : new ArrayList<>(refs);
            Collections.sort(sortedRefs);
            sb.append(CANONICAL_JSON.writeValueAsString(sortedRefs)).append('|');

            sb.append(payload == null ? "" : CANONICAL_JSON.writeValueAsString(payload));

            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new IllegalStateException("Firing digest compute failed", e);
        }
    }
}
