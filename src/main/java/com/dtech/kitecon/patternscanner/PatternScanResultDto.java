package com.dtech.kitecon.patternscanner;

import lombok.Builder;
import lombok.Data;
import java.util.List;
import java.util.Map;

@Data
@Builder
public class PatternScanResultDto {
    String symbol;
    String watchingTf;
    String confirmTf;
    List<PatternDto> patterns;
    // overlays per timeframe key ("1h", "15m", etc.) — same format as SuggestionChartLayout overlaysJson
    Map<String, Object> watchingTfOverlays;
    Map<String, Object> confirmTfOverlays;
}
