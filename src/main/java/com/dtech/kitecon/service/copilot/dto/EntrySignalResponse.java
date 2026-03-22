package com.dtech.kitecon.service.copilot.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.util.List;

/** AI has determined entry conditions are met. Notifies expert — awaits approval. */
@Data
@SuperBuilder
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class EntrySignalResponse extends AIResponse {
    private Long hypothesisId;
    private String hypothesisLabel;
    /** ANTICIPATORY | CONFIRMATION */
    private String entryType;
    private String entryZone;
    private String sl;
    private String tp;
    private List<String> conditionsMet;
    /** What specifically triggered this signal */
    private String triggerDescription;
    /** Confidence summary across all 6 layers */
    private String confidenceSummary;
}
