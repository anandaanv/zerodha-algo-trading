package com.dtech.algo.service;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.JsonArray;
import com.openai.core.JsonField;
import com.openai.models.beta.threads.ThreadCreateAndRunParams;
import com.openai.models.beta.threads.ThreadCreateParams;
import com.openai.models.beta.threads.messages.*;
import com.openai.models.beta.threads.runs.Run;
import com.openai.models.beta.threads.runs.RunRetrieveParams;
import com.openai.models.beta.threads.runs.RunStatus;
import com.openai.models.evals.runs.RunCreateParams;
import com.openai.models.files.FileCreateParams;
import com.openai.models.files.FileObject;
import com.openai.models.files.FilePurpose;
import com.openai.models.graders.gradermodels.LabelModelGrader;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileUrlResource;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeTypeUtils;

import java.awt.*;
import java.io.File;
import java.net.MalformedURLException;
import java.util.*;
import java.util.List;
import java.util.stream.Stream;

/**
 * Service for interacting with OpenAI API
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class OpenAiClientService {

    @Value("${openai.model:gpt-5-nano-2025-08-07}")
    private String modelName;

    private ChatClient chatClient;

    private OpenAIClient openAIClient;

    private final ChatClient.Builder clientBuilder;

    private final OpenAiConversationsService conversationsService;

    @Value("${openai.key}")
    private String apiKey;

    @Value("${openai.use-conversations:true}")
    private boolean useConversations;

    private static final String OPENAI_API_URL = "https://api.openai.com/v1";

    @PostConstruct
    public void init() {
        openAIClient = OpenAIOkHttpClient.builder()
                .apiKey(apiKey)
                .baseUrl(OPENAI_API_URL)
                .maxRetries(4)
                .build();
        chatClient = clientBuilder.build();
    }


    public String analyzeCharts(List<File> chartFiles, String symbol) throws InterruptedException {
        return analyzeChartsWithPrompt(chartFiles, symbol, "Please predict this stock");
    }

    public String analyzeChartsWithPrompt(List<File> chartFiles, String symbol, String customPrompt) throws InterruptedException {
        // Upload all images to OpenAI
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
            return processUploadedFiles(uploadedFiles, customPrompt);
        } finally {
            uploadedFiles.forEach(file -> {
                openAIClient.files().delete(file.id());
            });
        }
    }

    @NotNull
    private String processUploadedFiles(List<FileObject> uploadedFiles) throws InterruptedException {
        return processUploadedFiles(uploadedFiles, "Please predict this stock");
    }

    @NotNull
    private String processUploadedFiles(List<FileObject> uploadedFiles, String prompt) throws InterruptedException {
        List<ImageFileContentBlock> images = uploadedFiles.stream().map(file -> {
            ImageFile image = ImageFile.builder().fileId(file.id()).build();
            return ImageFileContentBlock.builder().imageFile(image).build();
        }).toList();

        List<ThreadCreateAndRunParams.Thread.Message.Attachment> attachmentList = uploadedFiles.stream().map(file ->
                ThreadCreateAndRunParams.Thread.Message.Attachment.builder().fileId(file.id()).build()).toList();


        List<MessageContentPartParam> contentList = images.stream().map(image -> {
            return MessageContentPartParam.ofImageFile(image);
        }).toList();

        List<MessageContentPartParam> contentListModifiable = new ArrayList<>(contentList);
        contentListModifiable.add(MessageContentPartParam.ofText(TextContentBlockParam.builder().text(prompt).build()));
        ThreadCreateAndRunParams.Thread.Message message =
                ThreadCreateAndRunParams.Thread.Message.builder()
                        .role(ThreadCreateAndRunParams.Thread.Message.Role.USER)
                        .contentOfArrayOfContentParts(contentListModifiable)
                        .build();

        // 4) Build the thread
        ThreadCreateAndRunParams.Thread thread =
                ThreadCreateAndRunParams.Thread.builder()
                        .messages(Arrays.asList(message))
                        .build();
        

        // 5) Build params with assistantId + thread
        ThreadCreateAndRunParams params = ThreadCreateAndRunParams.builder()
                .assistantId("asst_fbYwT7J1IaSKktVh2sZFgB5w")
                .thread(thread)
                .build();

        // 6) Create and run
        Run run = openAIClient.beta().threads().createAndRun(params);
        while (true) {
            run = openAIClient.beta().threads().runs().retrieve(RunRetrieveParams.builder()
                    .threadId(run.threadId())
                    .runId(run.id()).build()
            );
            if (run.completedAt().isPresent() || run.failedAt().isPresent()) break;
            Thread.sleep(800);
        }

        StringBuilder msg = new StringBuilder();
        openAIClient.beta().threads().messages().list(run.threadId()).response()
                .data().stream()
                .filter(m -> m.assistantId().isPresent())
                .flatMap(m ->
                        m.content().stream())
                .filter(c -> c.isText())
                .forEach(c -> msg.append(c.asText().text().value()));
        System.out.println(msg);
        return msg.toString();
    }

    // DTO for detailed OpenAI result
    public static class OpenAiAnalysisResult {
        public final String threadId;
        public final String runId;
        public final String text;

        public OpenAiAnalysisResult(String threadId, String runId, String text) {
            this.threadId = threadId;
            this.runId = runId;
            this.text = text;
        }
    }

    // Public API that returns thread/run ids with text (reuses existing upload + run flow)
    public OpenAiAnalysisResult analyzeChartsWithPromptDetailed(String threadId, java.util.List<java.io.File> chartFiles, String prompt)
            throws InterruptedException {
        // Upload images
        java.util.List<com.openai.models.files.FileObject> uploadedFiles = chartFiles.stream()
                .map(file -> {
                    try {
                        com.openai.models.files.FileCreateParams params = com.openai.models.files.FileCreateParams.builder()
                                .file(file.toPath())
                                .purpose(com.openai.models.files.FilePurpose.VISION)
                                .build();
                        return openAIClient.files().create(params);
                    } catch (Exception e) {
                        log.error("Error uploading file {}: {}", file.getName(), e.getMessage());
                        throw new RuntimeException("Failed to upload file: " + file.getName(), e);
                    }
                })
                .toList();
        try {
            // Build image blocks
            java.util.List<com.openai.models.beta.threads.messages.ImageFileContentBlock> images = uploadedFiles.stream().map(file -> {
                com.openai.models.beta.threads.messages.ImageFile image =
                        com.openai.models.beta.threads.messages.ImageFile.builder().fileId(file.id()).build();
                return com.openai.models.beta.threads.messages.ImageFileContentBlock.builder().imageFile(image).build();
            }).toList();

            java.util.List<com.openai.models.beta.threads.messages.MessageContentPartParam> contentList =
                    new java.util.ArrayList<>(images.stream()
                            .map(com.openai.models.beta.threads.messages.MessageContentPartParam::ofImageFile)
                            .toList());
            contentList.add(com.openai.models.beta.threads.messages.MessageContentPartParam.ofText(
                    com.openai.models.beta.threads.messages.TextContentBlockParam.builder().text(prompt).build()
            ));

            com.openai.models.beta.threads.ThreadCreateAndRunParams.Thread.Message message =
                    com.openai.models.beta.threads.ThreadCreateAndRunParams.Thread.Message.builder()
                            .role(com.openai.models.beta.threads.ThreadCreateAndRunParams.Thread.Message.Role.USER)
                            .contentOfArrayOfContentParts(contentList)
                            .build();

            com.openai.models.beta.threads.ThreadCreateAndRunParams.Thread thread =
                    com.openai.models.beta.threads.ThreadCreateAndRunParams.Thread.builder()
                            .messages(java.util.Arrays.asList(message))
                            .build();

            com.openai.models.beta.threads.ThreadCreateAndRunParams params =
                    com.openai.models.beta.threads.ThreadCreateAndRunParams.builder()
                            .assistantId("asst_fbYwT7J1IaSKktVh2sZFgB5w")
                            .thread(thread)
                            .build();

            com.openai.models.beta.threads.runs.Run run = openAIClient.beta().threads().createAndRun(params);

            while (true) {
                run = openAIClient.beta().threads().runs().retrieve(
                        com.openai.models.beta.threads.runs.RunRetrieveParams.builder()
                                .threadId(run.threadId())
                                .runId(run.id())
                                .build()
                );
                if (run.completedAt().isPresent() || run.failedAt().isPresent()) break;
                Thread.sleep(800);
            }

            StringBuilder msg = new StringBuilder();
            openAIClient.beta().threads().messages().list(run.threadId()).response()
                    .data().stream()
                    .filter(m -> m.assistantId().isPresent())
                    .flatMap(m -> m.content().stream())
                    .filter(c -> c.isText())
                    .forEach(c -> msg.append(c.asText().text().value()));

            return new OpenAiAnalysisResult(run.threadId(), run.id(), msg.toString());
        } finally {
            uploadedFiles.forEach(file -> {
                try {
                    openAIClient.files().delete(file.id());
                } catch (Exception ignore) {
                }
            });
        }
    }

    // Overload: include OHLC JSON as a separate text content block
    public OpenAiAnalysisResult analyzeChartsWithPromptDetailed(String threadId,
                                                                java.util.List<java.io.File> chartFiles,
                                                                String prompt,
                                                                String ohlcJson)
            throws InterruptedException {
        java.util.List<com.openai.models.files.FileObject> uploadedFiles = chartFiles.stream()
                .map(file -> {
                    try {
                        com.openai.models.files.FileCreateParams params = com.openai.models.files.FileCreateParams.builder()
                                .file(file.toPath())
                                .purpose(com.openai.models.files.FilePurpose.VISION)
                                .build();
                        return openAIClient.files().create(params);
                    } catch (Exception e) {
                        log.error("Error uploading file {}: {}", file.getName(), e.getMessage());
                        throw new RuntimeException("Failed to upload file: " + file.getName(), e);
                    }
                })
                .toList();
        try {
            java.util.List<com.openai.models.beta.threads.messages.ImageFileContentBlock> images = uploadedFiles.stream().map(file -> {
                com.openai.models.beta.threads.messages.ImageFile image =
                        com.openai.models.beta.threads.messages.ImageFile.builder().fileId(file.id()).build();
                return com.openai.models.beta.threads.messages.ImageFileContentBlock.builder().imageFile(image).build();
            }).toList();

            java.util.List<com.openai.models.beta.threads.messages.MessageContentPartParam> parts = new java.util.ArrayList<>();
            parts.addAll(images.stream()
                    .map(com.openai.models.beta.threads.messages.MessageContentPartParam::ofImageFile)
                    .toList());
            // Prompt text
            parts.add(com.openai.models.beta.threads.messages.MessageContentPartParam.ofText(
                    com.openai.models.beta.threads.messages.TextContentBlockParam.builder().text(prompt).build()
            ));
            // OHLC JSON as a separate text block
            if (ohlcJson != null && !ohlcJson.isBlank()) {
                parts.add(com.openai.models.beta.threads.messages.MessageContentPartParam.ofText(
                        com.openai.models.beta.threads.messages.TextContentBlockParam.builder()
                                .text(ohlcJson).build()
                ));
            }

            com.openai.models.beta.threads.ThreadCreateAndRunParams.Thread.Message message =
                    com.openai.models.beta.threads.ThreadCreateAndRunParams.Thread.Message.builder()
                            .role(com.openai.models.beta.threads.ThreadCreateAndRunParams.Thread.Message.Role.USER)
                            .contentOfArrayOfContentParts(parts)
                            .build();

            com.openai.models.beta.threads.ThreadCreateAndRunParams.Thread thread =
                    com.openai.models.beta.threads.ThreadCreateAndRunParams.Thread.builder()
                            .messages(java.util.Arrays.asList(message))
                            .build();

            com.openai.models.beta.threads.ThreadCreateAndRunParams params =
                    com.openai.models.beta.threads.ThreadCreateAndRunParams.builder()
                            .assistantId("asst_fbYwT7J1IaSKktVh2sZFgB5w")
                            .thread(thread)
                            .build();

            com.openai.models.beta.threads.runs.Run run = openAIClient.beta().threads().createAndRun(params);

            while (true) {
                run = openAIClient.beta().threads().runs().retrieve(
                        com.openai.models.beta.threads.runs.RunRetrieveParams.builder()
                                .threadId(run.threadId())
                                .runId(run.id())
                                .build()
                );
                if (run.completedAt().isPresent() || run.failedAt().isPresent()) break;
                Thread.sleep(800);
            }

            StringBuilder msg = new StringBuilder();
            openAIClient.beta().threads().messages().list(run.threadId()).response()
                    .data().stream()
                    .filter(m -> m.assistantId().isPresent())
                    .flatMap(m -> m.content().stream())
                    .filter(c -> c.isText())
                    .forEach(c -> msg.append(c.asText().text().value()));

            return new OpenAiAnalysisResult(run.threadId(), run.id(), msg.toString());
        } finally {
            uploadedFiles.forEach(file -> {
                try {
                    openAIClient.files().delete(file.id());
                } catch (Exception ignore) {
                }
            });
        }
    }
}