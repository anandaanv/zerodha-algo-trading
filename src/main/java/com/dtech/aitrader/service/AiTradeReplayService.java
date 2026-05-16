package com.dtech.aitrader.service;

import com.dtech.aitrader.data.AiLevels;
import com.dtech.aitrader.data.AgentDecision;
import com.dtech.aitrader.data.AiTradeReplay;
import com.dtech.aitrader.model.PatternSignal;
import com.dtech.aitrader.repository.AiTradeReplayRepository;
import com.dtech.kitecon.simulation.db.SimulationTrade;
import com.dtech.kitecon.simulation.db.SimulationTradeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class AiTradeReplayService {
    private final SimulationTradeRepository simulationTradeRepository;
    private final LevelsAgentService levelsAgentService;
    private final PatternAgentService patternAgentService;
    private final AiTradeReplayRepository replayRepository;

    @Transactional
    public AiTradeReplay replayTrade(Long simulationTradeId, Long userId) {
        long startMs = System.currentTimeMillis();
        var simTradeOpt = simulationTradeRepository.findById(simulationTradeId);
        if (simTradeOpt.isEmpty()) {
            throw new IllegalArgumentException("simulation_trade not found: " + simulationTradeId);
        }
        var st = simTradeOpt.get();

        // Extract fields from SimulationTrade entity
        String symbol = st.getSymbol();
        LocalDateTime entryTime = LocalDateTime.ofInstant(st.getEntryTime(), ZoneId.of("Asia/Kolkata"));
        String direction = st.getDirection();
        double entry = st.getEntryPrice();
        double sl = st.getStopInitial();
        double target = st.getTargetInitial();
        String exitReason = st.getExitReason();
        double pnlPct = st.getPnlPct();
        Long runIdFk = st.getRun().getId();

        log.info("Replay trade #{}: symbol={}, signal_time={}", simulationTradeId, symbol, entryTime);

        // 1. Levels agent (as-of signal time)
        AiLevels levels;
        try {
            levels = levelsAgentService.runForSymbol(symbol, userId, Optional.of(st.getEntryTime()));
        } catch (Exception e) {
            log.error("Levels agent failed for replay trade {}", simulationTradeId, e);
            throw new RuntimeException("Levels failed: " + e.getMessage(), e);
        }

        // 2. Build pattern signal from the simulation trade
        PatternSignal signal = PatternSignal.builder()
                .symbol(symbol)
                .patternType("REPLAY_" + (st.getPatternType() != null ? st.getPatternType() : "UNKNOWN"))
                .patternSource("REPLAY")
                .signalRef("sim_trade_" + simulationTradeId)
                .direction(direction)
                .suggestedEntry(entry)
                .suggestedSl(sl)
                .suggestedTarget(target)
                .signalTime(st.getEntryTime())
                .timeframe("OneHour")
                .build();

        // 3. Pattern agent decision
        AgentDecision decision;
        try {
            decision = patternAgentService.decide(signal, userId);
        } catch (Exception e) {
            log.error("Pattern agent failed for replay trade {}", simulationTradeId, e);
            throw new RuntimeException("Pattern failed: " + e.getMessage(), e);
        }

        long durationMs = System.currentTimeMillis() - startMs;

        BigDecimal totalCost = (levels.getCostUsd() != null ? levels.getCostUsd() : BigDecimal.ZERO)
                .add(decision.getCostUsd() != null ? decision.getCostUsd() : BigDecimal.ZERO);

        AiTradeReplay replay = AiTradeReplay.builder()
                .requestedAt(LocalDateTime.now())
                .userId(userId)
                .sourceSimulationTradeId(simulationTradeId)
                .sourceRunIdFk(runIdFk)
                .symbol(symbol)
                .signalTime(entryTime)
                .originalDirection(direction)
                .originalEntry(BigDecimal.valueOf(entry))
                .originalSl(BigDecimal.valueOf(sl))
                .originalTarget(BigDecimal.valueOf(target))
                .originalExitReason(exitReason)
                .originalPnlPct(pnlPct)
                .aiVerdict(decision.getVerdict())
                .aiDirection(decision.getDirection())
                .aiEntry(decision.getEntry())
                .aiSl(decision.getSl())
                .aiTarget(decision.getTarget())
                .aiConfidence(decision.getConfidence())
                .aiReasoning(decision.getReasoning())
                .levelsInputTokens(levels.getInputTokens())
                .levelsOutputTokens(levels.getOutputTokens())
                .patternInputTokens(decision.getInputTokens())
                .patternOutputTokens(decision.getOutputTokens())
                .totalCostUsd(totalCost)
                .modelUsed(decision.getModelUsed())
                .durationMs(durationMs)
                .build();

        return replayRepository.save(replay);
    }
}
