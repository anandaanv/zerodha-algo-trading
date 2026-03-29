package com.dtech.ta.elliott.hypothesis;

import com.dtech.ta.elliott.scenario.ScoredScenario;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HypothesisSnapshot {
    private String symbol;
    private String primaryTimeframe;
    private long snapshotTime;
    private List<ScoredScenario> scoredScenarios;
    private List<ScenarioTransition> transitions;
    private int relabelCount;
    private String leadingScenarioId;
}
