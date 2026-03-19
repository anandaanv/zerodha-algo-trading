package com.dtech.dhan.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Dhan Historical OHLC Data model
 * Represents historical candlestick data from Dhan API
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DhanHistoricalData {

    private String timestamp; // ISO format timestamp
    private Double open;
    private Double high;
    private Double low;
    private Double close;
    private Long volume;
    private Long openInterest; // For derivatives
}
