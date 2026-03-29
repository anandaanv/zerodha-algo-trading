package com.dtech.ta.elliott.hypothesis;

import com.dtech.ta.elliott.scenario.ScoredScenario;
import com.dtech.ta.elliott.scenario.ScenarioStatus;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class HypothesisManager {

    private final Map<String, HypothesisSnapshot> snapshots = new ConcurrentHashMap<>();

    /**
     * Update the hypothesis state for a symbol after a new analysis run.
     */
    public HypothesisSnapshot update(String symbol, String primaryTf, List<ScoredScenario> newScenarios) {
        String key = symbol + "_" + primaryTf;
        HypothesisSnapshot prev = snapshots.get(key);

        List<ScenarioTransition> transitions = computeTransitions(prev, newScenarios);
        String leadingId = findLeadingId(newScenarios);
        int relabelCount = computeRelabelCount(prev, leadingId);

        HypothesisSnapshot snapshot = HypothesisSnapshot.builder()
                .symbol(symbol)
                .primaryTimeframe(primaryTf)
                .snapshotTime(System.currentTimeMillis())
                .scoredScenarios(new ArrayList<>(newScenarios))
                .transitions(transitions)
                .relabelCount(relabelCount)
                .leadingScenarioId(leadingId)
                .build();

        snapshots.put(key, snapshot);
        return snapshot;
    }

    public HypothesisSnapshot get(String symbol, String primaryTf) {
        return snapshots.get(symbol + "_" + primaryTf);
    }

    public void reset(String symbol, String primaryTf) {
        snapshots.remove(symbol + "_" + primaryTf);
    }

    private List<ScenarioTransition> computeTransitions(HypothesisSnapshot prev,
                                                         List<ScoredScenario> newScenarios) {
        List<ScenarioTransition> transitions = new ArrayList<>();
        long now = System.currentTimeMillis();

        // Build map of prev scenario id → status
        Map<String, ScenarioStatus> prevStatusMap = new HashMap<>();
        if (prev != null && prev.getScoredScenarios() != null) {
            for (ScoredScenario ss : prev.getScoredScenarios()) {
                if (ss.getScenario() != null && ss.getScenario().getId() != null) {
                    prevStatusMap.put(ss.getScenario().getId(), ss.getStatus());
                }
            }
        }

        // Build set of new scenario ids
        Set<String> newIds = new HashSet<>();
        for (ScoredScenario ss : newScenarios) {
            if (ss.getScenario() == null || ss.getScenario().getId() == null) continue;
            String id = ss.getScenario().getId();
            newIds.add(id);

            ScenarioStatus fromStatus = prevStatusMap.get(id);
            ScenarioStatus toStatus = ss.getStatus();

            if (fromStatus == null) {
                // New scenario not seen before
                transitions.add(ScenarioTransition.builder()
                        .scenarioId(id)
                        .fromStatus(null)
                        .toStatus(toStatus)
                        .timestamp(now)
                        .reason("new_scenario")
                        .build());
            } else if (fromStatus != toStatus) {
                // Status changed
                transitions.add(ScenarioTransition.builder()
                        .scenarioId(id)
                        .fromStatus(fromStatus)
                        .toStatus(toStatus)
                        .timestamp(now)
                        .reason("status_change")
                        .build());
            }
        }

        // Scenarios that disappeared (in prev but not in new) → INVALIDATED
        for (Map.Entry<String, ScenarioStatus> entry : prevStatusMap.entrySet()) {
            if (!newIds.contains(entry.getKey())) {
                transitions.add(ScenarioTransition.builder()
                        .scenarioId(entry.getKey())
                        .fromStatus(entry.getValue())
                        .toStatus(ScenarioStatus.INVALIDATED)
                        .timestamp(now)
                        .reason("scenario_dropped")
                        .build());
            }
        }

        return transitions;
    }

    private String findLeadingId(List<ScoredScenario> scenarios) {
        return scenarios.stream()
                .filter(ss -> ss.getStatus() == ScenarioStatus.LEADING)
                .map(ss -> ss.getScenario().getId())
                .findFirst()
                .orElse(null);
    }

    private int computeRelabelCount(HypothesisSnapshot prev, String newLeadingId) {
        if (prev == null) return 0;
        String prevLeadingId = prev.getLeadingScenarioId();
        int prevCount = prev.getRelabelCount();
        if (prevLeadingId == null && newLeadingId == null) return prevCount;
        if (!Objects.equals(prevLeadingId, newLeadingId)) return prevCount + 1;
        return prevCount;
    }
}
