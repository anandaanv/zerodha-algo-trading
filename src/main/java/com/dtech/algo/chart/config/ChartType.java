package com.dtech.algo.chart.config;

/**
 * An enumeration of supported chart types.
 */
public enum ChartType {
    CANDLESTICK,  // Traditional OHLC candlestick chart
    LINE,         // Simple line chart (usually for close prices)
    BAR,          // OHLC bar chart
    AREA,         // Area chart (filled line chart)
    HOLLOW_CANDLE, // Hollow candlestick chart (bullish candles are hollow)
    HEIKIN_ASHI    // Heikin-Ashi candlestick chart (smoothed version of traditional candlestick)
}