package com.dtech.forecast.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class ModelForecast {
    private String model;
    private boolean available;
    private String error;
    private List<Double> forecastLow;
    private List<Double> forecastMedian;
    private List<Double> forecastHigh;
    private double convergenceMetric;
}
