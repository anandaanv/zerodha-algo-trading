package com.dtech.kitecon.screener.elliott.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.*;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ElliottScreenerRequest {
    @NotBlank
    private String name;

    @NotBlank
    private String symbols;

    @NotBlank
    private String timeframes;

    private String primaryTimeframe;

    @NotBlank
    private String scheduleCron;
}
