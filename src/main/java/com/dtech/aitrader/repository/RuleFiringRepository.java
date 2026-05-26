package com.dtech.aitrader.repository;

import com.dtech.aitrader.data.RuleFiring;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

/**
 * Append-only store for {@link RuleFiring} rows emitted by the rule-engine pilot. Reads serve
 * (a) the outcome scorer (find firings still missing an outcome), (b) eval queries grouped by
 * {@code context_signature}, (c) per-symbol replays.
 */
@Repository
public interface RuleFiringRepository extends JpaRepository<RuleFiring, String> {

    /** All firings for a symbol/TF in [from, to], chronological — used by the backtest replay. */
    List<RuleFiring> findBySymbolAndTfAndAsOfBetweenOrderByAsOfAsc(
            String symbol, String tf, LocalDate from, LocalDate to);

    /** All firings for one rule on one symbol — used for per-rule density / sanity checks. */
    List<RuleFiring> findByRuleIdAndSymbolOrderByAsOfAsc(String ruleId, String symbol);

    /**
     * Used by the scorer to find firings still needing outcome computation. ONLY VERDICT firings
     * are scored (Q7) — intermediate Pass 1-5 firings exist for audit / replay but are not
     * outcome-bearing. Pilot-era rows (where {@code fires_on} is NULL because they predate the
     * multi-pass schema) are also scored, for backward compatibility with the original 16-firing
     * pilot run.
     */
    @org.springframework.data.jpa.repository.Query(
            "SELECT f FROM RuleFiring f WHERE f.symbol = :symbol AND f.tf = :tf " +
            "AND (f.firesOn = 'VERDICT' OR f.firesOn IS NULL) " +
            "AND f.id NOT IN (SELECT o.firingId FROM FiringOutcome o) " +
            "ORDER BY f.asOf ASC")
    List<RuleFiring> findUnscored(
            @org.springframework.data.repository.query.Param("symbol") String symbol,
            @org.springframework.data.repository.query.Param("tf") String tf);
}
