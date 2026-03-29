package com.dtech.elliott.advanced.scenario.filter.domain;

import java.util.List;
import java.util.Map;

public record ReasoningPayloadCompression(
        String symbol,
        String anchorTimeframe,
        Map<String, Object> leadingScenario,
        List<Map<String, Object>> alternates,
        List<Map<String, Object>> decisionZones,
        List<Map<String, Object>> triggerWatchList,
        List<String> contradictionSummary
) {}
