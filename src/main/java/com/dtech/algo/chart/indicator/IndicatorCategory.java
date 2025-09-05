package com.dtech.algo.chart.indicator;

/**
 * Enum representing the category of a technical indicator.
 * Indicators are divided into two main categories based on their visualization requirements.
 */
public enum IndicatorCategory {
    /**
     * Overlay indicators are displayed directly on the price chart.
     * Examples: Moving Averages, Bollinger Bands, Keltner Channels, Parabolic SAR
     */
    OVERLAY,
    
    /**
     * Separate panel indicators are displayed in dedicated panels below the price chart.
     * Examples: MACD, RSI, ADX, Stochastic Oscillator, Volume
     */
    SEPARATE_PANEL
}