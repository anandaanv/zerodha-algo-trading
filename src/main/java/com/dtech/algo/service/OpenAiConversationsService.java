package com.dtech.algo.service;

import com.dtech.algo.openai.OpenAiConversation;
import com.dtech.algo.openai.OpenAiConversationRepository;
import com.dtech.algo.openai.OpenAiResponse;
import com.dtech.algo.openai.OpenAiResponseRepository;
import com.dtech.algo.series.Interval;
import com.openai.client.OpenAIClient;
import com.openai.models.conversations.Conversation;
import com.openai.models.conversations.items.ItemCreateParams;
import com.openai.models.files.FileCreateParams;
import com.openai.models.files.FileObject;
import com.openai.models.files.FilePurpose;
import com.openai.models.responses.*;
import com.openai.services.blocking.ConversationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.List;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class OpenAiConversationsService {
    @Value("${openai.model:gpt-5-nano-2025-08-07}")
    private String modelName;

    private final OpenAiConversationRepository conversationRepository;
    private final OpenAiResponseRepository openAiResponseRepository;

    private final OpenAIClient openAIClient;

    private OpenAiConversation findOrCreateConversation(String symbol, Interval timeframe) {
        return conversationRepository.findBySymbolAndTimeframe(symbol, timeframe.toString())
                .orElseGet(() -> {
                    OpenAiConversation newConv = new OpenAiConversation();
                    newConv.setSymbol(symbol);
                    newConv.setTimeframe(timeframe.toString());
                    return conversationRepository.save(newConv);
                });
    }

    // ---------- Public API (functional parity with legacy client) ----------


    public String analyzeCharts(List<File> chartFiles, String symbol, Interval timeframe) {
        List<FileObject> uploadedFiles = chartFiles.stream()
                .map(file -> {
                    try {
                        FileCreateParams params = FileCreateParams.builder()
                                .file(file.toPath())
                                .purpose(FilePurpose.VISION)
                                .build();
                        return openAIClient.files().create(params);
                    } catch (Exception e) {
                        log.error("Error uploading file {}: {}", file.getName(), e.getMessage());
                        throw new RuntimeException("Failed to upload file: " + file.getName(), e);
                    }
                })
                .toList();
        try {
            return processUploadedFiles(uploadedFiles, symbol, timeframe);
        } finally {
            uploadedFiles.forEach(file -> {
                openAIClient.files().delete(file.id());
            });
        }
    }


    String processUploadedFiles(List<FileObject> chartFiles, String symbol, Interval timeframe) {
        ConversationService conversations = openAIClient.conversations();
        ConversationMapping conversationMapping = retrieveConversation(symbol, timeframe, conversations);
        Conversation conversation = conversationMapping.conversation();
        ItemCreateParams.Builder itemBuilder = ItemCreateParams.builder();
        itemBuilder.conversationId(conversation.id());
        itemBuilder.addItem(ResponseInputItem.Message.builder()
                .addInputTextContent("Predict " + symbol)
                .role(ResponseInputItem.Message.Role.USER)
                .build());
        conversations.items().create(itemBuilder.build());

        List<ResponseInputItem> images = chartFiles.stream().map(file -> {
            return
                    ResponseInputItem.ofMessage(
                    ResponseInputItem.Message.builder()
                    .addContent(ResponseInputImage.builder().fileId(file.id())
                            .detail(ResponseInputImage.Detail.AUTO)
                            .build())
                    .role(ResponseInputItem.Message.Role.USER).build());
        }).toList();
        Response response = openAIClient.responses().create(
                ResponseCreateParams.builder()
                        .conversation(conversation.id())
                        .model(modelName)
                        .input(
                                ResponseCreateParams.Input.ofResponse(images)
                        ).prompt(ResponsePrompt.builder()
                                .id("pmpt_68b9b389e030819686b559b7ed501b87012cff9ff78b8159")
                                .build())
                        .build()
        );

        String responseString = response.output().stream()
                .filter(ResponseOutputItem::isMessage)
                .map(
                resItem -> resItem.asMessage().content().stream()
                        .filter(ResponseOutputMessage.Content::isOutputText)
                        .map(c -> c.asOutputText().text())
                        .collect(Collectors.joining()))
                .collect(Collectors.joining());
        System.out.println(responseString);
        storeResponse(conversationMapping.dbConversation, conversation, response, responseString);
        return responseString;
    }

    private void storeResponse(OpenAiConversation dbConversation, Conversation conversation, Response response, String responseString) {
        OpenAiResponse openAiResponse = new OpenAiResponse();
        openAiResponse.setConversation(dbConversation);
        openAiResponse.setOpenaiConversationId(conversation.id());
        openAiResponse.setOpenaiResponseId(response.id());
        openAiResponse.setResponseText(responseString);
        openAiResponseRepository.save(openAiResponse);
    }

    private record ConversationMapping(OpenAiConversation dbConversation, Conversation conversation) {
    }

    @NotNull
    private ConversationMapping retrieveConversation(String symbol, Interval timeframe, ConversationService conversations) {
        OpenAiConversation dbConversation = conversationRepository.findBySymbolAndTimeframe(
                        symbol, timeframe.toString())
                .orElseGet(() -> {
                    OpenAiConversation newConv = new OpenAiConversation();
                    newConv.setSymbol(symbol);
                    newConv.setTimeframe(timeframe.toString());
                    return conversationRepository.save(newConv);
                });

        Conversation conversation = conversations.create();
        dbConversation.setOpenaiThreadId(conversation.id());
        conversationRepository.save(dbConversation);
        return new ConversationMapping(dbConversation, conversation);
    }
}
