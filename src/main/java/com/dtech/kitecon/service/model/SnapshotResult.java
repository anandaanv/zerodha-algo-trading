package com.dtech.kitecon.service.model;

import com.dtech.kitecon.service.ai.tools.ValidationResult;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * SnapshotResult - Result of snapshot creation with validation
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class SnapshotResult {

    /**
     * ID of created snapshot
     */
    private Long snapshotId;

    /**
     * Was snapshot created successfully?
     */
    private boolean success;

    /**
     * Message to user
     */
    private String message;

    /**
     * Validation result (if validation was performed)
     */
    private ValidationResult validationResult;

    /**
     * Pattern type identified
     */
    private String patternType;

    /**
     * Number of drawings found in chart
     */
    private Integer drawingsCount;

    /**
     * Was AI used in validation?
     */
    private Boolean aiUsed;

    /**
     * Any warnings or notes
     */
    private String warnings;

    /**
     * Quick success result
     */
    public static SnapshotResult success(Long snapshotId, ValidationResult validation) {
        return SnapshotResult.builder()
            .snapshotId(snapshotId)
            .success(true)
            .message("Snapshot created successfully")
            .validationResult(validation)
            .build();
    }

    /**
     * Quick error result
     */
    public static SnapshotResult error(String message) {
        return SnapshotResult.builder()
            .success(false)
            .message(message)
            .build();
    }
}
