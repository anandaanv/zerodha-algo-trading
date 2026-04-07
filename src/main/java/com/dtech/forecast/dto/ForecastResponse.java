package com.dtech.forecast.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class ForecastResponse {
    private String symbol;
    private String interval;
    private int horizon;
    private long anchorTimestamp;
    private double anchorPrice;
    private List<Long> futureTimestamps;
    private ModelForecast chronos;
    private ModelForecast lagLlama;
    private ModelForecast granite;
}
