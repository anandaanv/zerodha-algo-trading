package com.dtech.kitecon.screener.elliott.dto;

import com.dtech.kitecon.screener.elliott.entity.ElliottScreener;
import lombok.*;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ElliottScreenerResponse {
    private Long id;
    private Long userId;
    private String name;
    private String symbols;
    private String timeframes;
    private String primaryTimeframe;
    private String scheduleCron;
    private boolean enabled;
    private Instant nextRunAt;
    private Instant lastRunAt;
    private Instant createdAt;
    private Instant updatedAt;

    public static ElliottScreenerResponse from(ElliottScreener e) {
        return ElliottScreenerResponse.builder()
                .id(e.getId())
                .userId(e.getUserId())
                .name(e.getName())
                .symbols(e.getSymbols())
                .timeframes(e.getTimeframes())
                .primaryTimeframe(e.getPrimaryTimeframe())
                .scheduleCron(e.getScheduleCron())
                .enabled(e.isEnabled())
                .nextRunAt(e.getNextRunAt())
                .lastRunAt(e.getLastRunAt())
                .createdAt(e.getCreatedAt())
                .updatedAt(e.getUpdatedAt())
                .build();
    }
}
