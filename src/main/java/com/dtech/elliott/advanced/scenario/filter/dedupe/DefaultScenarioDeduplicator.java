package com.dtech.elliott.advanced.scenario.filter.dedupe;

import com.dtech.elliott.advanced.scenario.filter.config.FilterConfig;
import com.dtech.elliott.advanced.scenario.filter.domain.NormalizedScenario;
import com.dtech.elliott.advanced.scenario.filter.domain.ScenarioCluster;
import com.dtech.elliott.advanced.scenario.filter.domain.ScenarioSignature;

import java.util.*;
import java.util.stream.Collectors;

public class DefaultScenarioDeduplicator implements ScenarioDeduplicator {

    private final SignatureBuilder signatureBuilder;

    public DefaultScenarioDeduplicator(SignatureBuilder signatureBuilder) {
        this.signatureBuilder = signatureBuilder;
    }

    @Override
    public List<ScenarioCluster> deduplicate(List<NormalizedScenario> scenarios, FilterConfig config) {
        if (scenarios == null || scenarios.isEmpty()) return List.of();

        // Group by signature
        Map<ScenarioSignature, List<NormalizedScenario>> grouped = new LinkedHashMap<>();
        for (NormalizedScenario s : scenarios) {
            ScenarioSignature sig = signatureBuilder.build(s, config);
            grouped.computeIfAbsent(sig, k -> new ArrayList<>()).add(s);
        }

        // Build clusters
        List<ScenarioCluster> clusters = grouped.entrySet().stream()
                .map(entry -> buildCluster(entry.getKey(), entry.getValue(), config))
                .sorted(Comparator.comparingDouble(ScenarioCluster::aggregatedStructuralScore).reversed())
                .toList();

        return clusters;
    }

    private ScenarioCluster buildCluster(ScenarioSignature sig, List<NormalizedScenario> all, FilterConfig config) {
        List<NormalizedScenario> members = all.size() > config.maxMembersPerCluster()
                ? all.subList(0, config.maxMembersPerCluster())
                : all;

        double structuralScore = members.stream().mapToDouble(NormalizedScenario::structuralScore).average().orElse(0.0);
        double tradeUtility = members.stream().mapToDouble(NormalizedScenario::tradeUtilityScore).average().orElse(0.0);
        double confluenceScore = members.stream().mapToDouble(NormalizedScenario::confluenceScore).average().orElse(0.0);
        double momentumScore = members.stream().mapToDouble(NormalizedScenario::momentumScore).average().orElse(0.0);

        List<String> mergedPatterns = dedup(members.stream().map(NormalizedScenario::supportingPatternTypes).toList());
        List<String> mergedStructures = dedup(members.stream().map(NormalizedScenario::supportingStructureTypes).toList());
        List<String> mergedReasonCodes = dedup(members.stream().map(NormalizedScenario::reasonCodes).toList());
        List<String> mergedExplanation = dedup(members.stream().map(NormalizedScenario::explanation).toList());

        return new ScenarioCluster(sig, List.copyOf(members),
                structuralScore, tradeUtility, confluenceScore, momentumScore,
                mergedPatterns, mergedStructures, mergedReasonCodes, mergedExplanation);
    }

    private List<String> dedup(List<List<String>> lists) {
        Set<String> seen = new LinkedHashSet<>();
        lists.forEach(seen::addAll);
        return List.copyOf(seen);
    }
}
