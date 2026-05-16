package com.dtech.drawingscan.model;

public record Candle(
    long epochSec,
    double open,
    double high,
    double low,
    double close
) {}
