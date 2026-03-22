package com.dtech.kitecon.service.copilot.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * Base class for all six structured AI response types.
 *
 * Every AI call in the system returns exactly one of these types.
 * The 'type' field is used by the application to route accordingly.
 */
@Data
@SuperBuilder
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = NeedsDataResponse.class,   name = "NEEDS_DATA"),
    @JsonSubTypes.Type(value = NeedsExpertResponse.class, name = "NEEDS_EXPERT"),
    @JsonSubTypes.Type(value = FindingResponse.class,     name = "FINDING"),
    @JsonSubTypes.Type(value = EntrySignalResponse.class, name = "ENTRY_SIGNAL"),
    @JsonSubTypes.Type(value = MonitoringResponse.class,  name = "MONITORING"),
    @JsonSubTypes.Type(value = InvalidatedResponse.class, name = "INVALIDATED"),
    @JsonSubTypes.Type(value = OrchestratorResponse.class, name = "ORCHESTRATOR")
})
public abstract class AIResponse {
    private AIResponseType type;
    /** Brief explanation of why the AI returned this response */
    private String reasoning;
}
