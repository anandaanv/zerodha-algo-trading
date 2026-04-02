package com.dtech.kitecon.screener.elliott.dto;

import lombok.Builder;
import lombok.Data;
import java.time.Instant;

@Data
@Builder
public class TradeAlertDto {
    private Long suggestionId;
    private String event;       // ENTRY_HIT | INVALIDATED | TARGET_HIT | STOP_LOSS_HIT
    private String symbol;
    private String direction;
    private double price;
    private String message;
    private Instant timestamp;
}
