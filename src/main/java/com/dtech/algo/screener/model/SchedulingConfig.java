package com.dtech.algo.screener.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Container for scheduling configuration on a screener.
 * Holds an array of RunConfig entries.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SchedulingConfig {

    @Builder.Default
    private List<RunConfig> runConfigs = new ArrayList<>();
}
