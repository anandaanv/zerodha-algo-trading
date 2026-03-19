package com.dtech.dhan.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Dhan Market Quote model
 * Represents real-time quote data from Dhan API
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DhanQuote {

    private String securityId;
    private String exchangeSegment;
    private Double lastTradedPrice;
    private Double lastTradedQuantity;
    private Long lastTradedTime;
    private Double averageTradedPrice;
    private Long volume;
    private Long totalBuyQuantity;
    private Long totalSellQuantity;
    private Double open;
    private Double high;
    private Double low;
    private Double close;
    private Double change;
    private Double changePercentage;
    private Long openInterest;

    // Best Bid/Ask
    private Double bidPrice;
    private Long bidQuantity;
    private Double askPrice;
    private Long askQuantity;
}
