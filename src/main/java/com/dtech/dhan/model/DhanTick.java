package com.dtech.dhan.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Dhan WebSocket Tick model
 * Represents real-time tick data from Dhan WebSocket
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DhanTick {

    private String securityId;
    private String exchangeSegment;
    private Double lastTradedPrice;
    private Long lastTradedQuantity;
    private Long lastTradedTime;
    private Long volume;
    private Double open;
    private Double high;
    private Double low;
    private Double close;
    private Long openInterest;

    // Ticker mode - basic data
    private String mode; // "ticker", "quote", "full"

    // Quote mode - additional fields
    private Double bidPrice;
    private Long bidQuantity;
    private Double askPrice;
    private Long askQuantity;

    // Full mode - market depth (20 levels)
    private MarketDepth[] depth;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MarketDepth {
        private Double buyPrice;
        private Long buyQuantity;
        private Double sellPrice;
        private Long sellQuantity;
    }
}
