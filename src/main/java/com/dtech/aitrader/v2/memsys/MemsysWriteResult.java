package com.dtech.aitrader.v2.memsys;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/** Response from memory_write / memory_update / memory_supersede. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class MemsysWriteResult {
    private String id;
    private Integer version;
    private Boolean deduped;
    @JsonProperty("merged_into") private String mergedInto;
    @JsonProperty("created_at") private Instant createdAt;
    @JsonProperty("request_id") private String requestId;
}
