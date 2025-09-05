package com.dtech.algo.controller.dto;

import com.dtech.algo.service.SignalType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Result of ASTA signal screening for a single symbol.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ASTASignalResult {

    private String symbol;
    private String timeframeSummary;

    List<SignalType> signals;

    // Optional notes or debug info
    private String notes;
}
