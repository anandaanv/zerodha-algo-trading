package com.dtech.ta.elliott.trigger;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EntryCandidate {
    private String id;
    private String symbol;
    private String scenarioId;
    private String hypothesisId;
    private boolean bullish;
    private String entryStyle;       // "AGGRESSIVE", "MODERATE", "CONSERVATIVE"
    private TriggerType triggerType;
    private double entryPrice;
    private double stopLoss;
    private double target1;
    private double target2;
    private double riskRewardRatio;
    private List<String> rationale;
}
