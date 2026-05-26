package com.dtech.aitrader.v2.rules;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Applies the Path-A Q1+Q4 schema changes (owner ratification {@code 7885ad63}):
 * <ul>
 *   <li>Drop NOT NULL on {@code bias}, {@code trigger_price}, {@code invalidation_price},
 *       {@code context_signature}, {@code final_conviction} so intermediate (non-VERDICT) firings
 *       can store proper NULLs instead of placeholders.</li>
 *   <li>Add UNIQUE INDEX {@code ux_rule_firing_digest} on the {@code firing_digest} column so
 *       backtest re-runs are idempotent (duplicate firings are rejected at INSERT time).</li>
 * </ul>
 *
 * <p>Runs once at every startup. Idempotent: re-applying {@code MODIFY COLUMN ... NULL} when
 * already NULL is a no-op in MySQL; the unique-index step checks information_schema first.
 *
 * <p>Why a CommandLineRunner and not a Flyway script: project policy is "Flyway on hold" (see
 * {@code feedback_flyway_hold.md}). When Flyway lands, this runner can be deleted and replaced by
 * a versioned migration file.
 */
@Component
@RequiredArgsConstructor
@Slf4j
@Order(50)
public class PathAMigrationRunner implements CommandLineRunner {

    private final JdbcTemplate jdbc;

    @Override
    public void run(String... args) {
        log.info("[path-a-migration] starting Q1+Q4 schema changes");

        List<String> alters = List.of(
                "ALTER TABLE rule_firing MODIFY COLUMN bias VARCHAR(8) NULL",
                "ALTER TABLE rule_firing MODIFY COLUMN trigger_price DOUBLE NULL",
                "ALTER TABLE rule_firing MODIFY COLUMN invalidation_price DOUBLE NULL",
                "ALTER TABLE rule_firing MODIFY COLUMN context_signature VARCHAR(128) NULL",
                "ALTER TABLE rule_firing MODIFY COLUMN final_conviction DOUBLE NULL",
                // O1 (ratification ada56b20): id is now SHA-256 hex (64 chars), not UUID (36).
                "ALTER TABLE rule_firing MODIFY COLUMN id VARCHAR(64) NOT NULL",
                "ALTER TABLE firing_outcome MODIFY COLUMN firing_id VARCHAR(64) NOT NULL"
        );
        for (String sql : alters) {
            applyIdempotent(sql);
        }
        applyUniqueIndex();

        log.info("[path-a-migration] done");
    }

    private void applyIdempotent(String sql) {
        try {
            jdbc.execute(sql);
            log.info("[path-a-migration] applied: {}", sql);
        } catch (Exception e) {
            // Re-applying ALTER TABLE MODIFY COLUMN to the same nullability is a no-op in MySQL,
            // but if the table/column doesn't exist yet, log and continue.
            log.warn("[path-a-migration] skipped ({}): {}", e.getMessage(), sql);
        }
    }

    private void applyUniqueIndex() {
        String indexName = "ux_rule_firing_digest";
        try {
            Integer exists = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.statistics " +
                    "WHERE table_schema = DATABASE() " +
                    "AND table_name = 'rule_firing' " +
                    "AND index_name = ?",
                    Integer.class, indexName);
            if (exists != null && exists > 0) {
                log.info("[path-a-migration] unique index {} already exists — skipping", indexName);
                return;
            }
            jdbc.execute(
                    "ALTER TABLE rule_firing ADD UNIQUE INDEX " + indexName + " (firing_digest)");
            log.info("[path-a-migration] added unique index {}", indexName);
        } catch (Exception e) {
            log.warn("[path-a-migration] could not add unique index {}: {}", indexName, e.getMessage());
        }
    }
}
