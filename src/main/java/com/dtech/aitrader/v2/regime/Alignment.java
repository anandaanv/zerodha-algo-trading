package com.dtech.aitrader.v2.regime;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Evidence trace per regime-record-v1: which stories agreed, which disagreed, prose summary.
 * Used for audit + algotrade confidence weighting.
 */
@Data
@Builder
@NoArgsConstructor(access = AccessLevel.PUBLIC)
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class Alignment {
    /** Stories that agree on this regime (e.g. ["elliott", "pattern", "indicator"]). */
    private List<String> stories_agreeing;
    /** Stories that conflict (e.g. ["elliott_mf4_minority"]). Nullable. */
    private List<String> stories_conflicting;
    /** Short prose explaining WHY these stories converge on this regime. */
    private String agreement_note;
}
