package com.dtech.kitecon.patternscanner;

import lombok.Data;

@Data
public class PatternScreenerRequest {
    private String name;
    private String segments; // "EQ,OPT"
    private String watchingTf;
    private String confirmTf;
    private String scheduleCron;
    private boolean enabled = true;
}
