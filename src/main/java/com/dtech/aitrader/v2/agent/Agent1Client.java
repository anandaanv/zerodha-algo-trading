package com.dtech.aitrader.v2.agent;

import com.dtech.aitrader.service.AiProviderResolver;
import com.dtech.aitrader.service.LevelsAgentService;
import com.dtech.aitrader.service.LlmGateway;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

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
    private final LlmGateway llmGateway;
    private final ObjectMapper mapper;

    @Value("${agent1.model:claude-sonnet-4-5-20250929}")
    private String agent1Model;

    @Value("${agent1.max-tokens:4096}")
    private int maxTokens;

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
        AiProviderResolver.ProviderConfig agent1Cfg = new AiProviderResolver.ProviderConfig(
                baseCfg.apiKey(),
                agent1Model,
                baseCfg.baseUrl(),
                baseCfg.isAnthropic(),
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
