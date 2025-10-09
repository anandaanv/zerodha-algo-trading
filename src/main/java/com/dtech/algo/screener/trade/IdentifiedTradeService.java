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
            IdentifiedTrade trade;
            if (existing.isPresent()) {
                trade = existing.get();
                String logLine = "trade confirmed at " + now + " (runId=" + runId + ")";
                trade.setLogs((trade.getLogs() == null || trade.getLogs().isBlank()) ? logLine : trade.getLogs() + "\n" + logLine);
            } else {
                trade = new IdentifiedTrade();
                trade.setScript(script);
                trade.setTimeframe(interval);
                trade.setSide(side);
                trade.setEntry(ai != null ? ai.getEntry() : null);
                trade.setTarget(ai != null ? ai.getTarget() : null);
                trade.setStoploss(ai != null ? ai.getStoploss() : null);
                trade.setTimeTriggered(now);
                trade.setRunId(runId);
                trade.setOpen(true);
                String logLine = "trade opened at " + now + " (runId=" + runId + ")";
                if (trade.getEntry() == null) {
                    logLine += " | entry not provided; use LTP";
                }
                trade.setLogs(logLine);
                trade.setCreatedAt(now);
            }
            trade.setUpdatedAt(now);
            repository.save(trade);
        } catch (Exception e) {
            log.warn("IdentifiedTrade upsert failed (runId={}): {}", runId, e.toString());
        }
    }
}
