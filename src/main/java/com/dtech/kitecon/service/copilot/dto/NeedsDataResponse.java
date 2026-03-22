package com.dtech.kitecon.service.copilot.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.util.List;

/** AI requires additional data before it can continue reasoning. */
@Data
@SuperBuilder
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class NeedsDataResponse extends AIResponse {
    /** e.g. "1h", "15min" */
    private String timeframe;
    /** ZIGZAG | CANDLES | INDICATORS | MARKET_STRUCTURE */
    private String dataType;
    /** e.g. "last_50_candles", "zigzag_pivots" */
    private String dataScope;
    /** Why this data is needed */
    private String reason;
    /** Specific indicator names if dataType=INDICATORS */
    private List<String> indicatorsNeeded;
}
