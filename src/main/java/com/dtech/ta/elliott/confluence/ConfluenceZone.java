package com.dtech.ta.elliott.confluence;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConfluenceZone {
    private String id;
    private double lowerPrice;
    private double upperPrice;
    private double midPrice;
    private List<PriceLevel> contributingLevels;
    private int factorCount;
    private int factorDiversity;
    private double score;
    private String zoneType;          // "FIB_CLUSTER", "SR_ONLY", "MIXED", "DECISION"
    private List<String> explanation;
}
