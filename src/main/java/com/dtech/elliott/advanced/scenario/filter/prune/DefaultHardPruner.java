package com.dtech.elliott.advanced.scenario.filter.prune;

import com.dtech.elliott.advanced.domain.scenario.Scenario;
import com.dtech.elliott.advanced.scenario.filter.config.FilterConfig;
import com.dtech.ta.elliott.scenario.ScenarioStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public class DefaultHardPruner implements HardPruner {

    private static final Logger log = LoggerFactory.getLogger(DefaultHardPruner.class);

    @Override
    public List<Scenario> prune(List<Scenario> rawScenarios, FilterConfig config) {
        if (rawScenarios == null || rawScenarios.isEmpty()) return List.of();

        List<Scenario> result = rawScenarios.stream()
                .filter(s -> !shouldPrune(s, config))
                .toList();

        log.debug("HardPruner: {} → {} after hard prune", rawScenarios.size(), result.size());
        return result;
    }

    private boolean shouldPrune(Scenario s, FilterConfig config) {
        if (s.status() == ScenarioStatus.INVALIDATED) return true;
        if (s.status() == ScenarioStatus.COMPLETED) return true;
        if (Double.isNaN(s.invalidationLevel()) || s.invalidationLevel() == 0.0) return true;
        if (s.direction() == null) return true;
        if (s.alignedPatterns().isEmpty()
                && s.conflictingPatterns().isEmpty()
                && s.relevantPatterns().isEmpty()) return true;
        // totalScore is 0–100; floor is 0.0–1.0 fraction
        if (s.totalScore() / 100.0 < config.hardPruneStructuralScoreFloor()) return true;
        return false;
    }
}
