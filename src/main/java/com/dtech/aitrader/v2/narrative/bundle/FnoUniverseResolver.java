package com.dtech.aitrader.v2.narrative.bundle;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/**
 * Resolves the NSE F&O underlying equity universe from the local {@code instrument} master.
 *
 * <p>Source per owner: NFO segment (NFO-FUT) distinct underlying {@code name} column, joined to
 * NSE-EQ rows where {@code exchange='NSE'} and {@code instrument_type IS NULL OR 'EQ'}. The join
 * filters out indices (NIFTY/BANKNIFTY/NIFTYNXT50) which trade futures but have no equity series.
 *
 * <p>~250/261 underlyings have a tradable NSE-EQ counterpart as of 2026-05-23.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class FnoUniverseResolver {

    private final JdbcTemplate jdbc;

    /**
     * Returns the alphabetically sorted list of NSE F&O underlying equity {@code tradingsymbol}s.
     * Indices excluded (no tradable equity series). Snapshot of the instrument master at call time.
     */
    public Resolved resolve() {
        String sql =
                "SELECT DISTINCT i.name " +
                "FROM instrument i " +
                "INNER JOIN instrument e ON e.tradingsymbol = i.name " +
                "    AND e.exchange = 'NSE' " +
                "    AND (e.instrument_type = 'EQ' OR e.instrument_type IS NULL) " +
                "WHERE i.segment = 'NFO-FUT' " +
                "  AND i.instrument_type = 'FUT' " +
                "ORDER BY i.name";
        List<String> symbols = jdbc.queryForList(sql, String.class);
        LocalDate asOf = LocalDate.now(ZoneId.of("Asia/Kolkata"));
        log.info("[fno-universe] resolved {} F&O underlying equities as of {}", symbols.size(), asOf);
        return new Resolved(symbols, asOf);
    }

    /** Resolver output: the symbol list + the as-of trading date used as the snapshot timestamp. */
    public record Resolved(List<String> symbols, LocalDate asOfDate) {}
}
