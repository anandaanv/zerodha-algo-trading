package com.dtech.algo.screener.trade;

import com.dtech.algo.screener.dto.OpenAiTradeOutput;
import com.dtech.algo.screener.enums.Verdict;
import com.dtech.algo.series.Interval;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
@RequiredArgsConstructor
@Slf4j
public class IdentifiedTradeService {

    private final IdentifiedTradeRepository repository;

    @Transactional
    public void upsertOnMarkFinal(String script, Interval interval, Verdict verdict, boolean passed, Long runId, OpenAiTradeOutput ai) {
        try {
            if (!passed || verdict == null || verdict == Verdict.WAIT) {
                return; // not an active trade
            }
            String side = verdict.name();
            Instant now = Instant.now();

            var existing = repository.findTopByScriptAndTimeframeAndSideAndOpenIsTrue(script, interval, side);
            if (existing.isPresent()) {
                IdentifiedTrade t = existing.get();
                String logLine = "trade confirmed at " + now + " (runId=" + runId + ")";
                t.setLogs((t.getLogs() == null || t.getLogs().isBlank()) ? logLine : t.getLogs() + "\n" + logLine);
                t.setUpdatedAt(now);
                repository.save(t);
            } else {
                IdentifiedTrade t = new IdentifiedTrade();
                t.setScript(script);
                t.setTimeframe(interval);
                t.setSide(side);
                t.setEntry(ai != null ? ai.getEntry() : null);
                t.setTarget(ai != null ? ai.getTarget() : null);
                t.setStoploss(ai != null ? ai.getStoploss() : null);
                t.setTimeTriggered(now);
                t.setRunId(runId);
                t.setOpen(true);
                String logLine = "trade opened at " + now + " (runId=" + runId + ")";
                if (t.getEntry() == null) {
                    logLine += " | entry not provided; use LTP";
                }
                t.setLogs(logLine);
                t.setCreatedAt(now);
                t.setUpdatedAt(now);
                repository.save(t);
            }
        } catch (Exception e) {
            log.warn("IdentifiedTrade upsert failed (runId={}): {}", runId, e.toString());
        }
    }
}
