package com.dtech.algo.screener;

import lombok.Builder;
import lombok.Data;

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

    /**
     * Final verdict produced by the screener decision logic (e.g., BUY/SELL/WAIT).
     */
    private com.dtech.algo.screener.enums.Verdict finalVerdict;
}
