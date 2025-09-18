package com.dtech.algo.screener.web.dto;

import com.dtech.algo.screener.db.Screener;
import lombok.Builder;

@Builder
public record ScreenerResponse(
        long id,
        String timeframe,
        String script,
        String configJson,
        String promptJson,
        String chartsJson
) {
    public static ScreenerResponse from(Screener s) {
        return ScreenerResponse.builder()
                .id(s.getId())
                .timeframe(s.getTimeframe())
                .script(s.getScript())
                .configJson(s.getConfigJson())
                .promptJson(s.getPromptJson())
                .chartsJson(s.getChartsJson())
                .build();
    }
}
