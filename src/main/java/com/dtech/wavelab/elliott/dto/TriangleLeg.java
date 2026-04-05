package com.dtech.wavelab.elliott.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class TriangleLeg {
    @JsonProperty("present")
    private Boolean present;

    @JsonProperty("pivot_index")
    private Integer pivotIndex;
}
