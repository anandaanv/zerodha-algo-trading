package com.dtech.ta.elliott.scenario;

import com.dtech.ta.elliott.WaveScenario;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ScoredScenario {
    private WaveScenario scenario;
    private ScenarioStatus status;
    private double totalScore;
    private List<String> statusReasons;
}
