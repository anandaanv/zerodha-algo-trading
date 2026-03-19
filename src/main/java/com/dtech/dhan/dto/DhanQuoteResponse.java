package com.dtech.dhan.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Response DTO for Dhan Market Quote API
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DhanQuoteResponse {

    @JsonProperty("data")
    private List<QuoteData> data;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class QuoteData {
        @JsonProperty("security_id")
        private String securityId;

        @JsonProperty("exchange_segment")
        private String exchangeSegment;

        @JsonProperty("last_price")
        private Double lastPrice;

        @JsonProperty("last_quantity")
        private Long lastQuantity;

        @JsonProperty("last_trade_time")
        private Long lastTradeTime;

        @JsonProperty("average_price")
        private Double averagePrice;

        @JsonProperty("volume")
        private Long volume;

        @JsonProperty("total_sell_quantity")
        private Long totalSellQuantity;

        @JsonProperty("total_buy_quantity")
        private Long totalBuyQuantity;

        @JsonProperty("open")
        private Double open;

        @JsonProperty("high")
        private Double high;

        @JsonProperty("low")
        private Double low;

        @JsonProperty("close")
        private Double close;

        @JsonProperty("52_week_high")
        private Double weekHigh52;

        @JsonProperty("52_week_low")
        private Double weekLow52;

        @JsonProperty("open_interest")
        private Long openInterest;

        @JsonProperty("prev_close_price")
        private Double prevClosePrice;

        @JsonProperty("bid_price")
        private Double bidPrice;

        @JsonProperty("bid_quantity")
        private Long bidQuantity;

        @JsonProperty("ask_price")
        private Double askPrice;

        @JsonProperty("ask_quantity")
        private Long askQuantity;
    }
}
