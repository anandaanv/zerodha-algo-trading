package com.dtech.aitrader.service;

import com.dtech.kitecon.service.copilot.UserAiProviderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class AiProviderResolver {
    private final UserAiProviderService userAiProviderService;

    /**
     * Resolves the active AI provider configuration for a user.
     * Throws IllegalStateException if no active provider is configured.
     */
    public ProviderConfig resolveForUser(Long userId) {
        String apiKey = userAiProviderService.getActiveApiKey(userId)
                .orElseThrow(() -> new IllegalStateException(
                        "No active AI provider configured for user. Please configure one via /api/settings/ai-providers"));

        var provider = userAiProviderService.getActiveProvider(userId)
                .orElseThrow(() -> new IllegalStateException("Active provider not found"));

        String baseUrl = provider.getBaseUrl();
        String model = provider.getModel();
        boolean isAnthropic = baseUrl.contains("anthropic.com");
        boolean isLocal = provider.isLocalProvider();

        return new ProviderConfig(apiKey, model, baseUrl, isAnthropic, isLocal);
    }

    public record ProviderConfig(String apiKey, String model, String baseUrl, boolean isAnthropic, boolean isLocal) {}
}
