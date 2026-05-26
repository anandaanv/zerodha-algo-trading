package com.dtech.aitrader.v2.rules.scancontext;

import com.dtech.aitrader.v2.memsys.MemsysClient;
import com.dtech.aitrader.v2.memsys.MemsysMemory;
import com.dtech.aitrader.v2.rules.SymbolContext;
import com.dtech.kitecon.service.copilot.dto.MarketStructurePoint;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

/**
 * Builds a {@link SymbolContext} from a memsys scan-context bundle (e.g. {@code dffe1f75}).
 * Per owner directive {@code 75b20b10}: EW rules must run against REAL bundles, not enriched
 * fixtures. This loader is the bridge.
 *
 * <p>Pipeline:
 * <ol>
 *   <li>Fetch the memsys memory by id (or by tag query if the caller wants the latest).</li>
 *   <li>{@link ScanContextParser} extracts per-TF pivot lists + annotations from the markdown.</li>
 *   <li>Build a {@link SymbolContext} populating {@link SymbolContext#getPivotsByTf()} +
 *       {@link SymbolContext#getAnnotations()}.</li>
 * </ol>
 *
 * <p>The legacy single-TF {@code pivots} field is populated with the Wk pivots so existing
 * EW Pass-1 rules that read {@code ctx.getPivots()} as a fallback continue to work.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ScanContextLoader {

    private final MemsysClient memsys;

    /**
     * Fetch a scan-context by memsys id and build the SymbolContext.
     *
     * @param userId      memsys tenant
     * @param memoryId    UUID of the scan-context memsys memory (e.g. {@code dffe1f75-...})
     * @param symbol      stock symbol (used to tag the resulting context)
     * @param asOf        analysis as-of date (typically the scan-context's data cutoff)
     */
    public SymbolContext loadById(Long userId, String memoryId, String symbol, LocalDate asOf) {
        MemsysMemory mem = memsys.getMemory(userId, memoryId);
        if (mem == null) {
            log.warn("[scan-ctx-loader] memory {} not found for user {}", memoryId, userId);
            return null;
        }
        return buildContext(mem, symbol, asOf);
    }

    /**
     * Find the most recent scan-context for a symbol via tag search. Useful when the caller knows
     * the symbol but not the specific memory id.
     */
    public SymbolContext loadLatest(Long userId, String symbol, LocalDate asOf) {
        List<MemsysMemory> hits = memsys.searchMemories(
                userId,
                symbol + " scan context",
                List.of("ai-trader-scan-context", "ai-trader-v2", "symbol-" + symbol),
                /*type*/ null, /*parentId*/ null, /*since*/ null, /*until*/ null, /*limit*/ 1);
        if (hits == null || hits.isEmpty()) {
            log.warn("[scan-ctx-loader] no scan-context found for symbol {}", symbol);
            return null;
        }
        return buildContext(hits.get(0), symbol, asOf);
    }

    private SymbolContext buildContext(MemsysMemory mem, String symbol, LocalDate asOf) {
        ScanContextParser.ParsedContext parsed = ScanContextParser.parse(mem.getContent());
        Map<String, List<MarketStructurePoint>> pivotsByTf = parsed.getPivotsByTf();
        List<MarketStructurePoint> weekly = pivotsByTf.get("Week");

        // Derive as-of from the latest Wk pivot if caller didn't supply one.
        LocalDate effectiveAsOf = asOf;
        if (effectiveAsOf == null && weekly != null && !weekly.isEmpty()) {
            effectiveAsOf = LocalDate.ofInstant(
                    weekly.get(weekly.size() - 1).getTimestamp(),
                    ZoneId.of("Asia/Kolkata"));
        }

        log.info("[scan-ctx-loader] loaded {} as_of={} memId={} Wk={} Day={} Hr={} annotations={}",
                symbol, effectiveAsOf, mem.getId(),
                weekly != null ? weekly.size() : 0,
                pivotsByTf.getOrDefault("Day", List.of()).size(),
                pivotsByTf.getOrDefault("OneHour", List.of()).size(),
                parsed.getAnnotations().size());

        return SymbolContext.builder()
                .symbol(symbol)
                .asOf(effectiveAsOf)
                .tf("Week")  // EW runs against weekly anchor by convention
                .pivots(weekly != null ? weekly : List.of())
                .pivotsByTf(pivotsByTf)
                .annotations(parsed.getAnnotations())
                .build();
    }
}
