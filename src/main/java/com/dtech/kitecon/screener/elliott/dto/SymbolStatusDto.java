package com.dtech.kitecon.screener.elliott.dto;

import lombok.Builder;
import lombok.Data;
import java.time.Instant;

@Data
@Builder
public class SymbolStatusDto {
    private String symbol;
    private String lastStatus;       // PASSED, FAILED, SKIPPED, ERROR, or null if never scanned
    private Instant lastScannedAt;
    private Long processingMs;
    private Long suggestionId;
    private String suggestionState;
    private String suggestionDirection;
    private Long runId;
}
