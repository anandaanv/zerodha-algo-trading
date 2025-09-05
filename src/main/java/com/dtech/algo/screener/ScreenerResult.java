package com.dtech.algo.screener;

import lombok.Builder;
import lombok.Data;

import java.util.Collections;
import java.util.Map;
import java.util.Set;

@Data
@Builder
public class ScreenerResult {
    private boolean entry;
    private boolean exit;
    private Double score;
    @Builder.Default
    private Set<String> tags = Collections.emptySet();
    @Builder.Default
    private Map<String, Object> debug = Collections.emptyMap();
}
