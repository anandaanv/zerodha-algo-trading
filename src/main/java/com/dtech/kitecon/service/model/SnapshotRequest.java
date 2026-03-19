package com.dtech.kitecon.service.model;

import com.dtech.kitecon.data.ChartSnapshot;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.List;

/**
 * SnapshotRequest - Request to create a new chart snapshot
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class SnapshotRequest {

    /**
     * Symbol being analyzed
     */
    private String symbol;

    /**
     * Timeframe of the chart
     */
    private String timeframe;

    /**
     * Chart layout ID (optional)
     */
    private Long layoutId;

    /**
     * Start date of visible chart range
     */
    private LocalDate startDate;

    /**
     * End date of visible chart range
     */
    private LocalDate endDate;

    /**
     * Complete chart state JSON (from TradingView widget.exportData())
     */
    private String chartStateJson;

    /**
     * User's comment/analysis
     */
    private String userComment;

    /**
     * Pattern type (optional, will be auto-detected if not provided)
     * @deprecated Use patternTags instead
     */
    private String patternType;

    /**
     * Pattern tags (multiple tags allowed)
     * e.g., ["Head and Shoulders", "Breakout", "Resistance"]
     */
    private List<String> patternTags;

    /**
     * Visibility level
     */
    private ChartSnapshot.SnapshotVisibility visibility;

    /**
     * Group IDs for group sharing (JSON array as string)
     */
    private String groupIds;

    /**
     * Whether to perform AI validation
     */
    @Builder.Default
    private Boolean performAiValidation = true;
}
