package com.dtech.aitrader.service;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MergeResult {
    private Integer mergedCount;
    private Integer suppressedCount;
    private List<String> suppressedIds;
}
