package com.dtech.aitrader.v2.agent;

import com.dtech.aitrader.service.AiProviderResolver;
import com.dtech.aitrader.service.LevelsAgentService;
import com.dtech.aitrader.service.LlmGateway;
import com.dtech.kitecon.service.copilot.UserAiProviderService;
import com.dtech.kitecon.data.copilot.UserAiProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Single-shot completion call to Agent 1 (Strategic Planner). Loads the v1.1 system prompt
 * from {@code classpath:prompts/agent1_v1_1.txt}, hands the user-message bundle to
 * {@link LlmGateway}, parses + schema-validates the returned JSON.
 *
 * Provider selection:
 *  - {@code agent1.model} property pins the Agent 1 model (defaults to Sonnet 4.6).
 *  - The user's own provider config (key + base_url) is still resolved via
 *    {@link AiProviderResolver}, but the resolved model is overridden for the call so
 *    Agent 1 always lands on its tier — see "AI Trader v2 Tiered Model Strategy" in the
 *    v1.2 plan.
 *
 * Schema fail → one retry with appended instruction → fail-soft (returns null and logs).
 * The orchestrator decides whether to skip the scan or write an audit memory.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class Agent1Client {

    private final AiProviderResolver providerResolver;
    private final UserAiProviderService userAiProviderService;
    private final LlmGateway llmGateway;
    private final ObjectMapper mapper;

    @Value("${agent1.model:claude-sonnet-4-5-20250929}")
    private String agent1Model;

    @Value("${agent1.max-tokens:4096}")
    private int maxTokens;

    /**
     * Override API key for Agent 1 (typically Anthropic). When set, Agent 1 uses this key + base URL
     * instead of the user's resolved provider. Empty → fall back to user's provider (likely fails if
     * user's provider doesn't speak the agent1.model — that's the routing the trader will see in dev).
     */
    @Value("${agent1.api-key:}")
    private String agent1ApiKey;

    /** Base URL for Agent 1 provider. Default points at Anthropic. */
    @Value("${agent1.base-url:https://api.anthropic.com/v1}")
    private String agent1BaseUrl;

    private volatile String cachedSystemPrompt;

    /**
     * Invoke Agent 1 for one (user, symbol). Returns the parsed output, or null on schema fail.
     *
     * @param userId       caller — drives provider config selection (api key + base url)
     * @param userMessage  the rendered bundle string built by the orchestrator
     */
    public Agent1Invocation invoke(Long userId, String userMessage) {
        String systemPrompt = loadSystemPrompt();
        AiProviderResolver.ProviderConfig baseCfg = providerResolver.resolveForUser(userId);

        // Agent 1 has its own provider routing so we don't send Claude models to an
        // OpenAI-compatible endpoint (like NVIDIA Nemotron) the user might have set as
        // their default. Resolution order:
        //   1. agent1.api-key property — explicit override
        //   2. The user's saved Anthropic provider row (active or not) from user_ai_provider
        //   3. Fall back to the user's resolved active provider (will likely 404 if model
        //      doesn't match — clear signal to configure)
        String apiKey;
        String baseUrl;
        if (agent1ApiKey != null && !agent1ApiKey.isBlank()) {
            apiKey = agent1ApiKey;
            baseUrl = agent1BaseUrl;
        } else {
            Optional<UserAiProvider> anthropicProvider =
                    userAiProviderService.findByUserIdAndBaseUrlContains(userId, "anthropic.com");
            Optional<String> anthropicKey = anthropicProvider.flatMap(userAiProviderService::getDecryptedApiKey);
            if (anthropicProvider.isPresent() && anthropicKey.isPresent()) {
                apiKey = anthropicKey.get();
                baseUrl = anthropicProvider.get().getBaseUrl();
                log.info("[agent1] using user's saved Anthropic provider (id={}, active={})",
                        anthropicProvider.get().getId(), anthropicProvider.get().isActive());
            } else {
                apiKey = baseCfg.apiKey();
                baseUrl = baseCfg.baseUrl();
                log.warn("[agent1] no Anthropic provider configured for user {} — falling back to active provider {}",
                        userId, baseUrl);
            }
        }
        boolean isAnthropic = baseUrl != null && baseUrl.contains("anthropic.com");

        AiProviderResolver.ProviderConfig agent1Cfg = new AiProviderResolver.ProviderConfig(
                apiKey,
                agent1Model,
                baseUrl,
                isAnthropic,
                baseCfg.isLocal()
        );

        log.info("[agent1] invoking model={} for userId={}", agent1Cfg.model(), userId);
        long t0 = System.currentTimeMillis();
        LlmGateway.LlmResponse response = llmGateway.call(agent1Cfg, systemPrompt, userMessage, maxTokens);
        long elapsedMs = System.currentTimeMillis() - t0;

        Agent1Output parsed = tryParse(response.text());
        if (parsed == null) {
            // One retry with a corrective prompt addendum.
            String retryMessage = userMessage
                    + "\n\n---\nYour previous response was malformed JSON. Return only the JSON object — no markdown, no commentary.";
            log.warn("[agent1] schema fail; retrying with corrective addendum");
            response = llmGateway.call(agent1Cfg, systemPrompt, retryMessage, maxTokens);
            parsed = tryParse(response.text());
        }

        log.info("[agent1] done model={} tokens_in={} tokens_out={} cost=${} elapsed={}ms parsed={}",
                response.modelUsed(), response.inputTokens(), response.outputTokens(),
                response.costUsd(), elapsedMs, parsed != null);

        return new Agent1Invocation(parsed, response, elapsedMs);
    }

    private Agent1Output tryParse(String rawText) {
        try {
            String jsonText = LevelsAgentService.extractJsonObject(rawText);
            return mapper.readValue(jsonText, Agent1Output.class);
        } catch (Exception e) {
            log.warn("[agent1] failed to parse Agent 1 response: {}; first 500 chars: {}",
                    e.getMessage(),
                    rawText == null ? "(null)" : rawText.substring(0, Math.min(500, rawText.length())));
            return null;
        }
    }

    private String loadSystemPrompt() {
        if (cachedSystemPrompt != null) return cachedSystemPrompt;
        synchronized (this) {
            if (cachedSystemPrompt != null) return cachedSystemPrompt;
            try {
                var resource = new ClassPathResource("prompts/agent1_v1_1.txt");
                try (var is = resource.getInputStream()) {
                    cachedSystemPrompt = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                }
                log.info("[agent1] loaded system prompt ({} chars)", cachedSystemPrompt.length());
                return cachedSystemPrompt;
            } catch (IOException e) {
                throw new IllegalStateException("agent1_v1_1.txt missing from classpath:prompts/", e);
            }
        }
    }

    public record Agent1Invocation(Agent1Output output, LlmGateway.LlmResponse response, long elapsedMs) {
        public boolean ok() { return output != null; }
    }
}
