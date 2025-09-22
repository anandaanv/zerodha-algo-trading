package com.dtech.algo.screener.web.dto;

import lombok.Data;

import java.util.Map;

/**
 * Payload for validating a screener script, including mapping and metadata.
 */
@Data
public class ScriptValidateRequest {
    private String script;
    private String timeframe; // optional; may be inferred from first mapping entry
    private String symbol;    // optional; defaults to "NIFTY" if absent
    private Integer nowIndex; // optional; defaults to 0 if absent

    // alias -> spec
    private Map<String, SeriesSpecPayload> mapping;

    @Data
    public static class SeriesSpecPayload {
        private String reference; // e.g., "SPOT", "FUT1", "CE1", "CE-1", "PE_2"
        private String interval;  // e.g., "DAY_1", "HOUR_1"
    }
}
