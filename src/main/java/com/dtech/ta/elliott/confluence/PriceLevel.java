package com.dtech.ta.elliott.confluence;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PriceLevel {
    private double price;
    private String label;
    private String sourceType;   // "FIB_RETRACEMENT", "FIB_EXTENSION", "HORIZONTAL_SR"
    private double tolerance;    // ± price tolerance for zone matching
}
