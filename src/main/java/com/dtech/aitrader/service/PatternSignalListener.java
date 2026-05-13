package com.dtech.aitrader.service;

import com.dtech.aitrader.data.AgentDecision;
import com.dtech.aitrader.event.PatternSignalEvent;
import com.dtech.aitrader.model.PatternSignal;
import com.dtech.aitrader.repository.AiTraderConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * PatternSignalListener reacts to PatternSignalEvent by calling PatternAgentService
 * to make a trade decision.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PatternSignalListener {
    private final PatternAgentService patternAgentService;
    private final AiTraderConfigRepository configRepository;

    /**
     * Handles PatternSignalEvent.
     * Checks if pattern agent is enabled, then calls agent to decide.
     */
    @EventListener
    public void onPatternSignal(PatternSignalEvent event) {
        PatternSignal signal = event.getSignal();

        // Check if pattern.enabled config is true
        Optional<com.dtech.aitrader.data.AiTraderConfig> configOpt = configRepository.findByConfigKey("pattern.enabled");
        boolean enabled = configOpt.isPresent() && "true".equalsIgnoreCase(configOpt.get().getConfigValue());

        if (!enabled) {
            log.info("pattern.enabled=false, skipping AI decision for {} {} {}",
                signal.getSignalRef(), signal.getSymbol(), signal.getPatternType());
            return;
        }

        // TODO: resolve actual userId from context (currently hardcoded to 1 for v1)
        Long userId = 1L;

        log.info("PatternSignalListener: processing {} signal for {} {}",
            signal.getPatternType(), signal.getSymbol(), signal.getDirection());

        try {
            AgentDecision decision = patternAgentService.decide(signal, userId);
            log.info("PatternAgent decided {} for {} {}: confidence={}, reasoning={}",
                decision.getVerdict(), signal.getSymbol(), signal.getPatternType(),
                decision.getConfidence(), decision.getReasoning());
        } catch (Exception e) {
            log.error("Error in PatternSignalListener for {}: {}", signal.getSignalRef(), e.getMessage(), e);
        }
    }
}
