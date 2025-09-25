package com.dtech.algo.screener.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Represents a single run configuration:
 * - timeframe: the interval to run on
 * - symbols: the list of symbols to process
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RunConfig {
    private String timeframe;
    private List<String> symbols;
}
