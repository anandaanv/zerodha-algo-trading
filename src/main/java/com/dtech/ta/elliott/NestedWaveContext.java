package com.dtech.ta.elliott;

import lombok.Builder;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * Higher-degree corrective context derived from the existing wave/pattern state.
 *
 * This intentionally stores the nested narrative that traders use in practice,
 * even when the current engine's core wave count is still flatter than a true
 * parent-child Elliott tree.
 */
@Data
@Builder
public class NestedWaveContext {

    private String timeframe;
    private String higherDegreeWave;
    private String correctiveLeg;
    private String activeSubwave;
    private String dominantStructure;
    private String narrative;
    private Double anchorLevel;
    private Double invalidationLevel;

    @Builder.Default
    private List<BranchHypothesis> branches = new ArrayList<>();

    @Data
    @Builder
    public static class BranchHypothesis {
        private String code;
        private String label;
        private double probability;
        private Double targetLevel;
        private String trigger;
        private String rationale;
        private String nextHigherDegreeMove;

        /** True if the branch trigger condition has already been met by current price */
        private boolean triggered;
        /** The anchor/trigger price this branch was evaluated against */
        private Double triggerPrice;
        /** The current price at time of evaluation */
        private Double currentPrice;
    }
}
