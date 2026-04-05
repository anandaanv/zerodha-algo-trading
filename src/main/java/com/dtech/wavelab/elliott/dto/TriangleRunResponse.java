package com.dtech.wavelab.elliott.dto;

import com.dtech.wavelab.elliott.entity.WleTriangleRun;
import lombok.Builder;
import lombok.Value;

import java.time.Instant;

@Value
@Builder
public class TriangleRunResponse {
    Long id;
    String symbol;
    String timeframe;
    Integer candleCount;
    String status;

    String proposerA;
    String proposerB;
    String evaluator;

    String finalTriangleType;
    String finalStatus;
    Double finalConfidence;
    String selectedSource;
    String finalReason;

    String proposerAOutputJson;
    String proposerBOutputJson;
    String evaluatorOutputJson;
    String errorMessage;

    Instant createdAt;
    Instant updatedAt;

    public static TriangleRunResponse from(WleTriangleRun run) {
        return TriangleRunResponse.builder()
                .id(run.getId())
                .symbol(run.getSymbol())
                .timeframe(run.getTimeframe())
                .candleCount(run.getCandleCount())
                .status(run.getStatus())
                .proposerA(run.getProposerA())
                .proposerB(run.getProposerB())
                .evaluator(run.getEvaluator())
                .finalTriangleType(run.getFinalTriangleType())
                .finalStatus(run.getFinalStatus())
                .finalConfidence(run.getFinalConfidence())
                .selectedSource(run.getSelectedSource())
                .finalReason(run.getFinalReason())
                .proposerAOutputJson(run.getProposerAOutputJson())
                .proposerBOutputJson(run.getProposerBOutputJson())
                .evaluatorOutputJson(run.getEvaluatorOutputJson())
                .errorMessage(run.getErrorMessage())
                .createdAt(run.getCreatedAt())
                .updatedAt(run.getUpdatedAt())
                .build();
    }
}
