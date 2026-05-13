package com.dtech.aitrader.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class WatchTradeResponse {
    private String symbol;
    private String id;
    private String direction;
    private BigDecimal entry;
    private BigDecimal stop;
    private BigDecimal target;
    private BigDecimal rr;
    private BigDecimal confidence;
    @JsonProperty("trigger_condition")
    private String triggerCondition;
    private String rationale;
    @JsonProperty("validity_until")
    private String validityUntil;
}
