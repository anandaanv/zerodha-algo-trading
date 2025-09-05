package com.dtech.algo.controller.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Response for ASTA screening, containing per-symbol signal results.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ASTAScreenResponse {

    private List<ASTASignalResult> results;
}
