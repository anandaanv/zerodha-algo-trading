package com.dtech.aitrader.v2.rules;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Locks the digest behaviour for idempotent re-runs (owner Q1, ratification {@code 7885ad63}).
 *
 * <p>The digest is the unique key the DB indexes on — re-running a backtest must produce the SAME
 * digest for each firing so the unique-index UPSERT rejects the duplicate. Any property of the
 * digest function that lets identical-logical-firings hash differently breaks idempotency.
 * Hence: deterministic JSON serialisation (sorted map keys), refs sorted before hashing, nulls
 * handled.
 */
class FiringDigestTest {

    @Test
    void same_inputs_yield_same_digest() {
        String d1 = FiringDigest.compute("DB_DETECT", "RELIANCE", LocalDate.of(2024, 6, 1),
                List.of("a", "b"), Map.of("k", "v"));
        String d2 = FiringDigest.compute("DB_DETECT", "RELIANCE", LocalDate.of(2024, 6, 1),
                List.of("a", "b"), Map.of("k", "v"));
        assertEquals(d1, d2);
    }

    @Test
    void refs_order_does_not_matter() {
        String d1 = FiringDigest.compute("R", "S", LocalDate.of(2024, 6, 1),
                List.of("a", "b"), Map.of());
        String d2 = FiringDigest.compute("R", "S", LocalDate.of(2024, 6, 1),
                List.of("b", "a"), Map.of());
        assertEquals(d1, d2, "refs are content-equivalent regardless of input list order");
    }

    @Test
    void payload_map_key_order_does_not_matter() {
        Map<String, Object> p1 = new LinkedHashMap<>();
        p1.put("a", 1); p1.put("b", 2);
        Map<String, Object> p2 = new LinkedHashMap<>();
        p2.put("b", 2); p2.put("a", 1);
        assertEquals(
                FiringDigest.compute("R", "S", LocalDate.of(2024, 6, 1), null, p1),
                FiringDigest.compute("R", "S", LocalDate.of(2024, 6, 1), null, p2),
                "payload key order is just serialisation accident — same content must hash same");
    }

    @Test
    void different_payload_yields_different_digest() {
        assertNotEquals(
                FiringDigest.compute("R", "S", LocalDate.of(2024, 6, 1), null, Map.of("k", "v1")),
                FiringDigest.compute("R", "S", LocalDate.of(2024, 6, 1), null, Map.of("k", "v2"))
        );
    }

    @Test
    void different_ruleId_yields_different_digest() {
        assertNotEquals(
                FiringDigest.compute("A", "S", LocalDate.of(2024, 6, 1), null, null),
                FiringDigest.compute("B", "S", LocalDate.of(2024, 6, 1), null, null)
        );
    }

    @Test
    void different_asOf_yields_different_digest() {
        assertNotEquals(
                FiringDigest.compute("R", "S", LocalDate.of(2024, 6, 1), null, null),
                FiringDigest.compute("R", "S", LocalDate.of(2024, 6, 2), null, null)
        );
    }

    @Test
    void nulls_handled_gracefully() {
        // No NPEs — every field is independently nullable.
        assertNotNull(FiringDigest.compute(null, null, null, null, null));
    }

    @Test
    void digest_is_64_hex_chars() {
        String d = FiringDigest.compute("R", "S", LocalDate.now(), null, null);
        assertEquals(64, d.length(), "sha-256 → 32 bytes → 64 hex chars");
        assertTrue(d.matches("[0-9a-f]+"), "lowercase hex only");
    }
}
