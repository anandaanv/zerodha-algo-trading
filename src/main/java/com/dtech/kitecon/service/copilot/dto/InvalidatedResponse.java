package com.dtech.kitecon.service.copilot.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.util.List;

/** A hypothesis invalidation condition has been triggered. */
@Data
@SuperBuilder
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class InvalidatedResponse extends AIResponse {
    private Long hypothesisId;
    private String hypothesisLabel;
    private String invalidationReason;
    /** Specific price level or structure that triggered invalidation */
    private String triggerCondition;
    /** Alternate hypotheses that should now be reviewed */
    private List<String> alternateHypothesesToCheck;
    /** Whether a linked trade should be closed */
    private boolean linkedTradeExists;
    private String tradeCloseRecommendation;
}
