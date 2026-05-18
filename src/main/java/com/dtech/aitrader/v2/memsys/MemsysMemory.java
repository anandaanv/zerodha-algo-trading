package com.dtech.aitrader.v2.memsys;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

/**
 * A memsys memory as returned by the memsys MCP. Mirrors the shape returned by memory_get
 * and memory_search results. Tolerant of unknown fields so the schema can evolve server-side.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class MemsysMemory {
    private String id;
    private String content;
    /** One of: note, decision, fact, snippet, question. */
    private String type;
    private List<String> tags;
    private JsonNode metadata;

    private Integer version;
    @JsonProperty("is_current") private Boolean isCurrent;
    private String supersedes;
    @JsonProperty("superseded_by") private String supersededBy;

    /** Native threading — populated only when threading feature is in use. */
    @JsonProperty("parent_id") private String parentId;

    @JsonProperty("created_at") private Instant createdAt;
    @JsonProperty("updated_at") private Instant updatedAt;
    @JsonProperty("deleted_at") private Instant deletedAt;
}
