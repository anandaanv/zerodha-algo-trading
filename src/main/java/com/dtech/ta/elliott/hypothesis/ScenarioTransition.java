package com.dtech.ta.elliott.hypothesis;

import com.dtech.ta.elliott.scenario.ScenarioStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ScenarioTransition {
    private String scenarioId;
    private ScenarioStatus fromStatus;   // null if this is a new scenario
    private ScenarioStatus toStatus;
    private long timestamp;              // epoch millis
    private String reason;
}
