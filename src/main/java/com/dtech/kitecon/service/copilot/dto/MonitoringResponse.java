package com.dtech.kitecon.service.copilot.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.util.List;

/** Entry conditions not yet fully met. System continues monitoring. */
@Data
@SuperBuilder
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class MonitoringResponse extends AIResponse {
    private Long hypothesisId;
    private String hypothesisLabel;
    private List<String> conditionsMet;
    private List<String> conditionsPending;
    /** e.g. "on_next_15min_close", "on_break_of_X_level" */
    private String nextCheckTrigger;
    private String progressSummary;
}
