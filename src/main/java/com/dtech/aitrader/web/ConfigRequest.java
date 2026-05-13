package com.dtech.aitrader.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConfigRequest {
    @JsonProperty("config_key")
    private String configKey;

    @JsonProperty("config_value")
    private String configValue;
}
