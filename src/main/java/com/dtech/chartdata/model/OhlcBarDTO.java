package com.dtech.chartdata.model;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class OhlcBarDTO {
    // UNIX epoch seconds
    private long time;
    private double open;
    private double high;
    private double low;
    private double close;
    private double volume;
}
