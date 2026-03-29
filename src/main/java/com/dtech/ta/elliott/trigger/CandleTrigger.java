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
public class CandleTrigger {
    private TriggerType type;
    private int barIndex;
    private double triggerPrice;
    private boolean bullish;
    private List<String> reasons;
}
