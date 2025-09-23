package com.dtech.algo.screener;

import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Result of executing a screener. Indicates pass/fail and optional debug payload.
 */
@Data
@Builder
public class ScreenerOutput {
    /**
     * Whether the screener criteria passed.
     */
    private boolean passed;

    /**
     * Optional debug or extra data returned by script.
     */
    private Map<String, Object> debug;
}
