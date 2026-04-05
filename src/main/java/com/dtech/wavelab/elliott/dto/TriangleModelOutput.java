package com.dtech.wavelab.elliott.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class TriangleModelOutput {

    @JsonProperty("triangle_type")
    private String triangleType;

    @JsonProperty("status")
    private String status;

    @JsonProperty("confidence")
    private Double confidence;

    @JsonProperty("legs")
    private Map<String, TriangleLeg> legs = new LinkedHashMap<>();

    @JsonProperty("validity_checks")
    private List<Map<String, Object>> validityChecks = new ArrayList<>();

    @JsonProperty("invalidation_reason")
    private String invalidationReason;

    @JsonProperty("evidence_notes")
    private List<String> evidenceNotes = new ArrayList<>();
}
