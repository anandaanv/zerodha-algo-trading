package com.dtech.wavelab.elliott.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class TriangleEvaluatorOutput {

    @JsonProperty("final_triangle_type")
    private String finalTriangleType;

    @JsonProperty("final_status")
    private String finalStatus;

    @JsonProperty("final_confidence")
    private Double finalConfidence;

    @JsonProperty("selected_source")
    private String selectedSource;

    @JsonProperty("why_selected")
    private String whySelected;

    @JsonProperty("rejected_model_notes")
    private Map<String, String> rejectedModelNotes = new LinkedHashMap<>();

    @JsonProperty("final_legs")
    private Map<String, TriangleLeg> finalLegs = new LinkedHashMap<>();
}
