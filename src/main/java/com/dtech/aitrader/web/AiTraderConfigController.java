package com.dtech.aitrader.web;

import com.dtech.aitrader.service.AiTraderConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/ai-trader/config")
@RequiredArgsConstructor
@Slf4j
public class AiTraderConfigController {
    private final AiTraderConfigService configService;

    @GetMapping
    public Map<String, String> getConfig(Authentication auth) {
        log.debug("GET /api/ai-trader/config");
        Map<String, String> config = new HashMap<>();
        config.put("levels.model", configService.getString("levels.model", "claude-sonnet-4-5-20251022"));
        config.put("pattern.model", configService.getString("pattern.model", "claude-sonnet-4-5-20251022"));
        config.put("pattern.enabled", configService.getString("pattern.enabled", "false"));
        return config;
    }

    @PatchMapping
    public Map<String, String> updateConfig(
            @RequestBody ConfigRequest request,
            Authentication auth) {

        log.info("PATCH /api/ai-trader/config - key={}, value={}", request.getConfigKey(), request.getConfigValue());

        configService.set(request.getConfigKey(), request.getConfigValue());

        Map<String, String> response = new HashMap<>();
        response.put(request.getConfigKey(), request.getConfigValue());
        return response;
    }
}
