package com.dtech.kitecon.service.copilot;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseInputItem;
import com.openai.models.responses.ResponseOutputItem;
import com.openai.models.responses.ResponseOutputMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * AI call service for the Co-Pilot system.
 * Uses the user's own OpenAI API key (stored in DB per user).
 * Falls back to the system-level key if the user hasn't configured their own.
 *
 * Uses the OpenAI Responses API (SDK 4.x) with proper role separation:
 * - instructions() → developer/system role (model instructions)
 * - input USER message → user role (investigation data)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CopilotAIService {

    private final UserOpenAiCredentialService credentialService; // kept for legacy /api/copilot/credentials
    private final UserAiProviderService providerService;

    @Value("${openai.key:}")
    private String systemApiKey;

    @Value("${openai.model}")
    private String systemModel;

    @Value("${openai.baseUrl:https://api.openai.com/v1}")
    private String systemBaseUrl;

    /**
     * Call OpenAI with separate system instructions and user data.
     * Routes to Chat Completions API for local providers, Responses API for OpenAI.
     *
     * @param userId        the authenticated user (for per-user key lookup)
     * @param instructions  the skill/instruction prompt (developer role)
     * @param userMessage   the investigation context / data (user role)
     * @return raw response text from the AI
     */
    public String call(Long userId, String instructions, String userMessage) {
        String apiKey = resolveApiKey(userId);
        String model = resolveModel(userId);
        String baseUrl = resolveBaseUrl(userId);
        boolean local = isLocalProvider(userId);

        // Local providers don't require a real API key; use a placeholder
        if (!local && (apiKey == null || apiKey.isBlank())) {
            throw new IllegalStateException("OpenAI API key not configured. Please add your API key in Settings → Co-Pilot.");
        }
        String effectiveKey = (apiKey == null || apiKey.isBlank()) ? "local" : apiKey;

        OpenAIClient client = OpenAIOkHttpClient.builder()
                .apiKey(effectiveKey)
                .baseUrl(baseUrl)
                .maxRetries(2)
                .build();

        try {
            int instrChars = instructions != null ? instructions.length() : 0;
            int dataChars  = userMessage  != null ? userMessage.length()  : 0;
            log.info("[AI] provider={} model={} instructions={}chars(~{}tok) data={}chars(~{}tok) total~{}tok",
                    local ? "local" : "openai", model,
                    instrChars, instrChars / 4,
                    dataChars,  dataChars  / 4,
                    (instrChars + dataChars) / 4);

            if (local) {
                return callChatCompletions(client, model, instructions, userMessage);
            } else {
                return callResponsesApi(client, model, instructions, userMessage);
            }

        } catch (Exception e) {
            log.error("AI call failed for user {} model {}: {}", userId, model, e.getMessage());
            throw new RuntimeException("AI call failed: " + e.getMessage(), e);
        }
    }

    private String callResponsesApi(OpenAIClient client, String model, String instructions, String userMessage) {
        ResponseInputItem userMsg = ResponseInputItem.ofMessage(
                ResponseInputItem.Message.builder()
                        .addInputTextContent(userMessage)
                        .role(ResponseInputItem.Message.Role.USER)
                        .build());

        ResponseCreateParams params = ResponseCreateParams.builder()
                .model(model)
                .instructions(instructions)
                .input(ResponseCreateParams.Input.ofResponse(List.of(userMsg)))
                .store(false)
                .build();

        Response response = client.responses().create(params);

        return response.output().stream()
                .filter(ResponseOutputItem::isMessage)
                .map(item -> item.asMessage().content().stream()
                        .filter(ResponseOutputMessage.Content::isOutputText)
                        .map(c -> c.asOutputText().text())
                        .collect(Collectors.joining()))
                .collect(Collectors.joining());
    }

    private String callChatCompletions(OpenAIClient client, String model, String instructions, String userMessage) {
        var params = ChatCompletionCreateParams.builder()
                .model(model)
                .addSystemMessage(instructions)
                .addUserMessage(userMessage)
                .build();

        var completion = client.chat().completions().create(params);
        return completion.choices().stream()
                .findFirst()
                .map(c -> c.message().content().orElse(""))
                .orElse("");
    }

    private String resolveApiKey(Long userId) {
        // Prefer new multi-provider system
        if (userId != null && providerService.hasAnyProvider(userId)) {
            return providerService.getActiveApiKey(userId).orElse(null);
        }
        // Fall back to legacy single-credential system
        if (userId != null) {
            Optional<String> userKey = credentialService.getApiKey(userId);
            if (userKey.isPresent()) return userKey.get();
        }
        return systemApiKey;
    }

    private String resolveModel(Long userId) {
        if (userId != null && providerService.hasAnyProvider(userId)) {
            return providerService.getActiveProvider(userId)
                    .map(com.dtech.kitecon.data.copilot.UserAiProvider::getModel)
                    .orElse(systemModel);
        }
        if (userId != null && credentialService.hasCredential(userId)) {
            return credentialService.getModel(userId);
        }
        return systemModel;
    }

    private String resolveBaseUrl(Long userId) {
        if (userId != null && providerService.hasAnyProvider(userId)) {
            return providerService.getActiveProvider(userId)
                    .map(com.dtech.kitecon.data.copilot.UserAiProvider::getBaseUrl)
                    .orElse(systemBaseUrl);
        }
        if (userId != null && credentialService.hasCredential(userId)) {
            return credentialService.getBaseUrl(userId);
        }
        return systemBaseUrl;
    }

    private boolean isLocalProvider(Long userId) {
        if (userId != null && providerService.hasAnyProvider(userId)) {
            return providerService.getActiveProvider(userId)
                    .map(com.dtech.kitecon.data.copilot.UserAiProvider::isLocalProvider)
                    .orElse(false);
        }
        if (userId != null && credentialService.hasCredential(userId)) {
            return credentialService.isLocalProvider(userId);
        }
        return systemBaseUrl != null && !systemBaseUrl.contains("api.openai.com");
    }
}
