package com.dtech.kitecon.screener.elliott.dto;

import com.dtech.kitecon.screener.elliott.entity.ElliottScreenerRun;
import lombok.*;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ElliottScreenerRunResponse {
    private Long id;
    private Long screenerId;
    private String status;
    private int totalSymbols;
    private int processedSymbols;
    private int suggestionsCreated;
    private int duplicatesSkipped;
    private String errorSummary;
    private Instant startedAt;
    private Instant completedAt;
    private Instant createdAt;

    public static ElliottScreenerRunResponse from(ElliottScreenerRun r) {
        return ElliottScreenerRunResponse.builder()
                .id(r.getId())
                .screenerId(r.getScreenerId())
                .status(r.getStatus())
                .totalSymbols(r.getTotalSymbols())
                .processedSymbols(r.getProcessedSymbols())
                .suggestionsCreated(r.getSuggestionsCreated())
                .duplicatesSkipped(r.getDuplicatesSkipped())
                .errorSummary(r.getErrorSummary())
                .startedAt(r.getStartedAt())
                .completedAt(r.getCompletedAt())
                .createdAt(r.getCreatedAt())
                .build();
    }
}
