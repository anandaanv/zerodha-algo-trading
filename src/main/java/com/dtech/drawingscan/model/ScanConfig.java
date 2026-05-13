package com.dtech.drawingscan.model;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "drawing.scan")
@Data
public class ScanConfig {
    private double touchTolerancePct = 0.25;
    private int reversalLookaheadBars = 10;
    private double minReversalPct = 1.0;
    private int breakConfirmBars = 3;
}
