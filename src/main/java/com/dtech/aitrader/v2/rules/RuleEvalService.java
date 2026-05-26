package com.dtech.aitrader.v2.rules;

import lombok.RequiredArgsConstructor;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Runs the pilot eval SQL ({@code rule × context_signature → hit_rate / MFE / MAE / N}) over the
 * persisted {@code rule_firing} + {@code firing_outcome} tables. Exposed via REST so we can read
 * results without a separate mysql session.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RuleEvalService {

    private final JdbcTemplate jdbc;

    /** Diagnostic: row counts + a few sample digests so we can sanity-check the dedup behaviour. */
    public java.util.Map<String, Object> diagnostics(String symbol, String tf) {
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("total_rows", jdbc.queryForObject(
                "SELECT COUNT(*) FROM rule_firing WHERE symbol=? AND tf=?", Integer.class, symbol, tf));
        out.put("verdict_rows", jdbc.queryForObject(
                "SELECT COUNT(*) FROM rule_firing WHERE symbol=? AND tf=? AND fires_on='VERDICT'",
                Integer.class, symbol, tf));
        out.put("classification_rows", jdbc.queryForObject(
                "SELECT COUNT(*) FROM rule_firing WHERE symbol=? AND tf=? AND fires_on='CLASSIFICATION'",
                Integer.class, symbol, tf));
        out.put("by_fires_on", jdbc.queryForList(
                "SELECT fires_on, COUNT(*) AS n FROM rule_firing WHERE symbol=? AND tf=? GROUP BY fires_on",
                symbol, tf));
        out.put("sample_classification_digests", jdbc.queryForList(
                "SELECT id, firing_digest, refs_json FROM rule_firing " +
                "WHERE symbol=? AND tf=? AND fires_on='CLASSIFICATION' LIMIT 3",
                symbol, tf));
        return out;
    }

    /** Diagnostic: truncate rule_firing + firing_outcome (clean slate for dedup verification). */
    public int truncateAll() {
        jdbc.execute("DELETE FROM firing_outcome");
        return jdbc.update("DELETE FROM rule_firing");
    }

    /** Eval summary grouped by (rule_id, context_signature), filtered to buckets with N≥minN. */
    public Result evalBySignature(String symbol, String tf, int minN) {
        String sql = """
                SELECT f.rule_id, f.context_signature,
                       COUNT(*) AS n,
                       SUM(CASE WHEN o.outcome='HIT' THEN 1 ELSE 0 END) AS hits,
                       SUM(CASE WHEN o.outcome='INVALIDATED' THEN 1 ELSE 0 END) AS invs,
                       SUM(CASE WHEN o.outcome='MISS' THEN 1 ELSE 0 END) AS misses,
                       SUM(CASE WHEN o.outcome='PENDING' THEN 1 ELSE 0 END) AS pendings,
                       AVG(o.mfe_pct) AS avg_mfe,
                       AVG(o.mae_pct) AS avg_mae,
                       AVG(o.bars_to_target) AS avg_bars_to_target
                  FROM rule_firing f
                  JOIN firing_outcome o ON o.firing_id = f.id
                 WHERE f.symbol = ? AND f.tf = ?
              GROUP BY f.rule_id, f.context_signature
                HAVING COUNT(*) >= ?
              ORDER BY f.rule_id, hits * 1.0 / COUNT(*) DESC
                """;
        List<Bucket> buckets = jdbc.query(sql, (rs, i) -> new Bucket(
                rs.getString("rule_id"),
                rs.getString("context_signature"),
                rs.getInt("n"),
                rs.getInt("hits"),
                rs.getInt("invs"),
                rs.getInt("misses"),
                rs.getInt("pendings"),
                rs.getDouble("avg_mfe"),
                rs.getDouble("avg_mae"),
                rs.getObject("avg_bars_to_target") == null ? null : rs.getDouble("avg_bars_to_target")
        ), symbol, tf, minN);

        // Compute per-rule kill-test gap (max - min hit_rate across buckets where N>=minN).
        List<RuleGap> gaps = new ArrayList<>();
        java.util.Map<String, List<Bucket>> byRule = new java.util.LinkedHashMap<>();
        for (Bucket b : buckets) {
            byRule.computeIfAbsent(b.getRuleId(), k -> new ArrayList<>()).add(b);
        }
        for (var entry : byRule.entrySet()) {
            List<Bucket> bs = entry.getValue();
            if (bs.size() < 2) {
                gaps.add(new RuleGap(entry.getKey(), bs.size(), 0.0, null, null));
                continue;
            }
            double maxRate = bs.stream().mapToDouble(Bucket::hitRate).max().orElse(0);
            double minRate = bs.stream().mapToDouble(Bucket::hitRate).min().orElse(0);
            String bestSig = bs.stream().max((a, b2) -> Double.compare(a.hitRate(), b2.hitRate()))
                    .map(Bucket::getContextSignature).orElse(null);
            String worstSig = bs.stream().min((a, b2) -> Double.compare(a.hitRate(), b2.hitRate()))
                    .map(Bucket::getContextSignature).orElse(null);
            gaps.add(new RuleGap(entry.getKey(), bs.size(), maxRate - minRate, bestSig, worstSig));
        }

        // Overall counts (no minN filter) for diagnostics.
        Integer totalFirings = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rule_firing WHERE symbol=? AND tf=?",
                Integer.class, symbol, tf);
        Integer totalSignatures = jdbc.queryForObject(
                "SELECT COUNT(DISTINCT context_signature) FROM rule_firing WHERE symbol=? AND tf=?",
                Integer.class, symbol, tf);
        return new Result(symbol, tf, minN,
                totalFirings == null ? 0 : totalFirings,
                totalSignatures == null ? 0 : totalSignatures,
                buckets, gaps);
    }

    @Value
    public static class Bucket {
        String ruleId;
        String contextSignature;
        int n;
        int hits;
        int invs;
        int misses;
        int pendings;
        double avgMfePct;
        double avgMaePct;
        Double avgBarsToTarget;
        public double hitRate() { return n > 0 ? hits / (double) n : 0; }
    }

    @Value
    public static class RuleGap {
        String ruleId;
        int distinctSignatures;
        double maxMinusMinHitRate;
        String bestSignature;
        String worstSignature;
    }

    @Value
    public static class Result {
        String symbol;
        String tf;
        int minN;
        int totalFirings;
        int totalSignatures;
        List<Bucket> buckets;
        List<RuleGap> killTestGaps;
    }
}
