package com.dtech.aitrader.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class LlmGateway {
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    // Provider type enum
    public enum ProviderType {
        ANTHROPIC,
        OPENAI_COMPATIBLE  // Covers OpenAI, NVIDIA NIM, Groq, local servers, etc.
    }

    // Token cost rates $/M tokens (input, output)
    private static final Map<String, double[]> TOKEN_RATES = new HashMap<>();

    static {
        TOKEN_RATES.put("claude-sonnet-4-5-20251022", new double[]{3.0, 15.0});
        TOKEN_RATES.put("claude-opus-4-7-20251022", new double[]{15.0, 75.0});
        TOKEN_RATES.put("gpt-4o", new double[]{5.0, 15.0});
        TOKEN_RATES.put("gpt-4-turbo", new double[]{10.0, 30.0});
        TOKEN_RATES.put("nvidia/llama-3.1-nemotron-70b-instruct", new double[]{0.20, 0.20});
        TOKEN_RATES.put("nvidia/llama-3.3-nemotron-70b-instruct", new double[]{0.20, 0.20});
    }

    /**
     * Detects the LLM provider type from the base URL.
     */
    public ProviderType detectProvider(String baseUrl) {
        if (baseUrl.contains("anthropic.com")) {
            return ProviderType.ANTHROPIC;
        }
        return ProviderType.OPENAI_COMPATIBLE;
    }

    /**
     * Calls an LLM provider (Anthropic or OpenAI-compatible) with the given prompts.
     */
    public LlmResponse call(AiProviderResolver.ProviderConfig cfg, String systemPrompt, String userPrompt, int maxTokens) {
        long startTime = System.currentTimeMillis();

        try {
            ProviderType provider = detectProvider(cfg.baseUrl());
            if (provider == ProviderType.ANTHROPIC) {
                return callAnthropicApi(cfg, systemPrompt, userPrompt, maxTokens, startTime);
            } else {
                log.debug("Detected OpenAI-compatible provider for base URL: {}", cfg.baseUrl());
                return callOpenAiApi(cfg, systemPrompt, userPrompt, maxTokens, startTime);
            }
        } catch (Exception e) {
            log.error("LLM API call failed for model {}", cfg.model(), e);
            throw new RuntimeException("LLM API call failed: " + e.getMessage(), e);
        }
    }

    private LlmResponse callAnthropicApi(AiProviderResolver.ProviderConfig cfg, String systemPrompt, String userPrompt, int maxTokens, long startTime) throws IOException, InterruptedException {
        String endpoint = cfg.baseUrl() + (cfg.baseUrl().endsWith("/") ? "" : "/") + "messages";

        // Build system blocks with cache control
        ArrayNode systemBlocks = objectMapper.createArrayNode();
        ObjectNode systemBlock = objectMapper.createObjectNode();
        systemBlock.put("type", "text");
        systemBlock.put("text", systemPrompt);
        ObjectNode cacheControl = objectMapper.createObjectNode();
        cacheControl.put("type", "ephemeral");
        systemBlock.set("cache_control", cacheControl);
        systemBlocks.add(systemBlock);

        // Build user message
        ObjectNode messageNode = objectMapper.createObjectNode();
        messageNode.put("role", "user");
        messageNode.put("content", userPrompt);

        ArrayNode messagesArray = objectMapper.createArrayNode();
        messagesArray.add(messageNode);

        ObjectNode requestBody = objectMapper.createObjectNode();
        requestBody.put("model", cfg.model());
        requestBody.put("max_tokens", maxTokens);
        requestBody.put("temperature", 0.2);
        requestBody.set("system", systemBlocks);  // Changed from put to set (array)
        requestBody.set("messages", messagesArray);

        String jsonBody = objectMapper.writeValueAsString(requestBody);

        // Build HTTP request
        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                .header("Content-Type", "application/json")
                .header("x-api-key", cfg.apiKey())
                .header("anthropic-version", "2023-06-01")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .timeout(Duration.ofSeconds(120))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new RuntimeException("Anthropic API returned status " + response.statusCode() + ": " + response.body());
        }

        JsonNode responseNode = objectMapper.readTree(response.body());
        String text = responseNode.path("content").get(0).path("text").asText();
        int inputTokens = responseNode.path("usage").path("input_tokens").asInt();
        int outputTokens = responseNode.path("usage").path("output_tokens").asInt();
        int cacheCreationTokens = responseNode.path("usage").path("cache_creation_input_tokens").asInt(0);
        int cacheReadTokens = responseNode.path("usage").path("cache_read_input_tokens").asInt(0);
        String modelUsed = responseNode.path("model").asText(cfg.model());

        BigDecimal costUsd = calculateCostWithCache(cfg.model(), inputTokens, cacheCreationTokens, cacheReadTokens, outputTokens);
        long duration = System.currentTimeMillis() - startTime;

        log.info("Anthropic API call succeeded. Model: {}, Input: {} (cache_write: {}, cache_read: {}), Output: {}, Cost: ${}, Duration: {}ms",
                modelUsed, inputTokens, cacheCreationTokens, cacheReadTokens, outputTokens, costUsd, duration);

        return new LlmResponse(text, inputTokens, outputTokens, modelUsed, costUsd);
    }

    private LlmResponse callOpenAiApi(AiProviderResolver.ProviderConfig cfg, String systemPrompt, String userPrompt, int maxTokens, long startTime) throws IOException, InterruptedException {
        String endpoint = cfg.baseUrl() + (cfg.baseUrl().endsWith("/") ? "" : "/") + "chat/completions";

        // Build messages array
        ObjectNode sysMsg = objectMapper.createObjectNode();
        sysMsg.put("role", "system");
        sysMsg.put("content", systemPrompt);

        ObjectNode userMsg = objectMapper.createObjectNode();
        userMsg.put("role", "user");
        userMsg.put("content", userPrompt);

        ArrayNode messagesArray = objectMapper.createArrayNode();
        messagesArray.add(sysMsg);
        messagesArray.add(userMsg);

        // Build request body
        ObjectNode requestBody = objectMapper.createObjectNode();
        requestBody.put("model", cfg.model());
        requestBody.put("max_tokens", maxTokens);
        requestBody.put("temperature", 0.2);
        requestBody.set("messages", messagesArray);

        String jsonBody = objectMapper.writeValueAsString(requestBody);

        // Build HTTP request
        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + cfg.apiKey())
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .timeout(Duration.ofSeconds(120))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new RuntimeException("OpenAI API returned status " + response.statusCode() + ": " + response.body());
        }

        JsonNode responseNode = objectMapper.readTree(response.body());
        String text = responseNode.path("choices").get(0).path("message").path("content").asText();
        int inputTokens = responseNode.path("usage").path("prompt_tokens").asInt();
        int outputTokens = responseNode.path("usage").path("completion_tokens").asInt();
        String modelUsed = responseNode.path("model").asText(cfg.model());

        BigDecimal costUsd = calculateCost(cfg.model(), inputTokens, outputTokens);
        long duration = System.currentTimeMillis() - startTime;

        log.info("OpenAI API call succeeded. Model: {}, Input: {}, Output: {}, Cost: ${}, Duration: {}ms",
                modelUsed, inputTokens, outputTokens, costUsd, duration);

        return new LlmResponse(text, inputTokens, outputTokens, modelUsed, costUsd);
    }

    private BigDecimal calculateCost(String model, int inputTokens, int outputTokens) {
        double[] rates = TOKEN_RATES.get(model);
        if (rates == null) {
            log.warn("Model {} not found in TOKEN_RATES, using zero cost", model);
            rates = new double[]{0.0, 0.0};
        }
        double inputCost = (inputTokens / 1_000_000.0) * rates[0];
        double outputCost = (outputTokens / 1_000_000.0) * rates[1];
        return new BigDecimal(inputCost + outputCost).setScale(6, java.math.RoundingMode.HALF_UP);
    }

    private BigDecimal calculateCostWithCache(String model, int inputTokens, int cacheCreationTokens, int cacheReadTokens, int outputTokens) {
        double[] rates = TOKEN_RATES.get(model);
        if (rates == null) {
            log.warn("Model {} not found in TOKEN_RATES, using zero cost", model);
            rates = new double[]{0.0, 0.0};
        }
        double inputRate = rates[0];
        double outputRate = rates[1];

        double cost =
            inputTokens * inputRate / 1_000_000.0
          + cacheCreationTokens * inputRate * 1.25 / 1_000_000.0
          + cacheReadTokens * inputRate * 0.10 / 1_000_000.0
          + outputTokens * outputRate / 1_000_000.0;

        return new BigDecimal(cost).setScale(6, java.math.RoundingMode.HALF_UP);
    }

    public record LlmResponse(String text, int inputTokens, int outputTokens, String modelUsed, BigDecimal costUsd) {}
}
