package com.dtech.kitecon.service;

import com.dtech.kitecon.service.ai.OpenAIProviderService;
import com.dtech.kitecon.service.model.PromptBuildRequest;
import com.dtech.kitecon.service.model.PromptBuildResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class PromptBuilderService {

    private static final String META_SYSTEM_PROMPT = """
You are a prompt engineering assistant helping a trader craft a system prompt for an AI technical analyst.
The prompt will be used to analyze stock charts with drawing annotations and OHLC price data automatically injected.

Your job:
1. Understand what the trader wants the AI analyst to focus on (e.g. breakouts, Elliott Wave, volume, etc.).
2. Engage conversationally — ask clarifying questions if needed, suggest ideas.
3. At the END of every response, output the current best version of the system prompt enclosed in tags:

<<<PROMPT>>>
[system prompt text here]
<<<END_PROMPT>>>

The system prompt should:
- Be written in second person ("You are a senior technical analyst...")
- State the specific focus or methodology the trader wants
- Be concise but complete — the backend will automatically append chart drawings and OHLC data
- NOT mention injecting drawings or OHLC (that happens automatically)

Start by greeting the trader and asking what kind of analysis focus they want.
""";

    private final OpenAIProviderService openAI;

    public PromptBuildResponse build(PromptBuildRequest req) {
        String fullPrompt = buildFullPrompt(req.getHistory(), req.getUserMessage());
        String rawResponse = openAI.callOpenAIRaw(fullPrompt);
        return parseResponse(rawResponse);
    }

    private String buildFullPrompt(List<PromptBuildRequest.ConversationMessage> history, String userMessage) {
        StringBuilder sb = new StringBuilder();
        sb.append(META_SYSTEM_PROMPT);
        sb.append("\n\n=== CONVERSATION SO FAR ===\n");

        if (history != null) {
            for (PromptBuildRequest.ConversationMessage msg : history) {
                String roleLabel = "user".equalsIgnoreCase(msg.getRole()) ? "Trader" : "Assistant";
                sb.append(roleLabel).append(": ").append(msg.getContent()).append("\n\n");
            }
        }

        sb.append("Trader: ").append(userMessage).append("\n\n");
        sb.append("Assistant:");
        return sb.toString();
    }

    private PromptBuildResponse parseResponse(String rawResponse) {
        String startTag = "<<<PROMPT>>>";
        String endTag = "<<<END_PROMPT>>>";

        int startIdx = rawResponse.indexOf(startTag);
        int endIdx   = rawResponse.indexOf(endTag);

        String message;
        String extractedPrompt = "";

        if (startIdx >= 0 && endIdx > startIdx) {
            message = rawResponse.substring(0, startIdx).trim();
            extractedPrompt = rawResponse.substring(startIdx + startTag.length(), endIdx).trim();
        } else {
            // Tags not present yet — treat entire response as message
            message = rawResponse.trim();
        }

        return PromptBuildResponse.builder()
            .message(message)
            .extractedPrompt(extractedPrompt)
            .build();
    }
}
