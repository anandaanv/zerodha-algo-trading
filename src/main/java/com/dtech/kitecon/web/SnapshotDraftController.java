package com.dtech.kitecon.web;

import com.dtech.kitecon.data.ChartSnapshot;
import com.dtech.kitecon.data.SnapshotDraft;
import com.dtech.kitecon.repository.ChartSnapshotRepository;
import com.dtech.kitecon.repository.SnapshotDraftRepository;
import com.dtech.kitecon.service.ChartSnapshotService;
import com.dtech.kitecon.service.SnapshotInternalizationService;
import com.dtech.kitecon.service.TagValidationService;
import com.dtech.kitecon.service.ai.tools.ValidationResult;
import com.dtech.kitecon.service.model.SnapshotRequest;
import com.dtech.kitecon.service.model.SnapshotResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * SnapshotDraftController - REST API for snapshot draft management
 * Handles the pre-commit conversation phase where users refine their analysis
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/snapshots/drafts")
@CrossOrigin
@Slf4j
public class SnapshotDraftController {

    private final SnapshotDraftRepository draftRepository;
    private final ChartSnapshotRepository snapshotRepository;
    private final ChartSnapshotService snapshotService;
    private final SnapshotInternalizationService internalizationService;
    private final TagValidationService tagValidationService;
    private final ObjectMapper objectMapper;

    /**
     * Create a new draft
     * POST /api/snapshots/drafts
     */
    @PostMapping
    public ResponseEntity<DraftResponse> createDraft(
        @RequestBody DraftRequest request,
        Authentication auth
    ) {
        try {
            String username = auth.getName();
            log.info("Creating draft for user: {}, symbol: {}", username, request.getSymbol());

            // Validate tags
            List<String> normalizedTags = tagValidationService.validateAndNormalizeTags(request.getPatternTags());

            // Create draft
            SnapshotDraft draft = SnapshotDraft.builder()
                .username(username)
                .symbol(request.getSymbol())
                .timeframe(request.getTimeframe())
                .chartStateJson(request.getChartStateJson())
                .userComment(request.getUserComment())
                .patternTags(normalizedTags.isEmpty() ? null : String.join(",", normalizedTags))
                .status("DRAFT")
                .visibility(request.getVisibility())
                .performAiValidation(request.getPerformAiValidation() != null ? request.getPerformAiValidation() : true)
                .conversationHistory("[]") // Initialize empty conversation
                .build();

            draft = draftRepository.save(draft);

            return ResponseEntity.ok(DraftResponse.success(draft));

        } catch (Exception e) {
            log.error("Error creating draft", e);
            return ResponseEntity.status(500)
                .body(DraftResponse.error("Failed to create draft: " + e.getMessage()));
        }
    }

    /**
     * Update draft with new message (conversation refinement)
     * PUT /api/snapshots/drafts/{id}
     */
    @PutMapping("/{id}")
    public ResponseEntity<DraftResponse> updateDraft(
        @PathVariable Long id,
        @RequestBody UpdateDraftRequest request,
        Authentication auth
    ) {
        try {
            String username = auth.getName();

            SnapshotDraft draft = draftRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Draft not found"));

            // Verify ownership
            if (!draft.getUsername().equals(username)) {
                return ResponseEntity.status(403)
                    .body(DraftResponse.error("Unauthorized"));
            }

            // Update draft fields
            if (request.getUserComment() != null) {
                draft.setUserComment(request.getUserComment());
            }

            if (request.getPatternTags() != null) {
                List<String> normalizedTags = tagValidationService.validateAndNormalizeTags(request.getPatternTags());
                draft.setPatternTags(normalizedTags.isEmpty() ? null : String.join(",", normalizedTags));
            }

            if (request.getChartStateJson() != null) {
                draft.setChartStateJson(request.getChartStateJson());
            }

            // Add conversation message if provided
            if (request.getMessage() != null) {
                SnapshotInternalizationService.ConversationMessage userMsg =
                    SnapshotInternalizationService.ConversationMessage.user(request.getMessage());

                String updatedHistory = internalizationService.addMessageToHistory(
                    draft.getConversationHistory(),
                    userMsg
                );
                draft.setConversationHistory(updatedHistory);

                // TODO: Get AI response and add to conversation
                // For now, just acknowledge the refinement
                SnapshotInternalizationService.ConversationMessage aiMsg =
                    SnapshotInternalizationService.ConversationMessage.assistant(
                        "I've noted your refinement. Your analysis is looking more detailed."
                    );

                updatedHistory = internalizationService.addMessageToHistory(
                    updatedHistory,
                    aiMsg
                );
                draft.setConversationHistory(updatedHistory);
                draft.setAiFeedback(aiMsg.getMessage());
            }

            draft = draftRepository.save(draft);
            log.info("Updated draft {} for user {}", id, username);

            return ResponseEntity.ok(DraftResponse.success(draft));

        } catch (Exception e) {
            log.error("Error updating draft", e);
            return ResponseEntity.status(500)
                .body(DraftResponse.error("Failed to update draft: " + e.getMessage()));
        }
    }

    /**
     * Commit draft to final snapshot
     * POST /api/snapshots/drafts/{id}/commit
     */
    @PostMapping("/{id}/commit")
    public ResponseEntity<SnapshotResult> commitDraft(
        @PathVariable Long id,
        Authentication auth
    ) {
        try {
            String username = auth.getName();

            SnapshotDraft draft = draftRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Draft not found"));

            // Verify ownership
            if (!draft.getUsername().equals(username)) {
                return ResponseEntity.status(403)
                    .body(SnapshotResult.error("Unauthorized"));
            }

            // Verify not already committed
            if ("COMMITTED".equals(draft.getStatus())) {
                return ResponseEntity.badRequest()
                    .body(SnapshotResult.error("Draft already committed"));
            }

            log.info("Committing draft {} for user {}", id, username);

            // Internalize conversation to memory
            String conversationSummary = internalizationService.internalizeDraft(draft);

            // Convert draft to snapshot request
            SnapshotRequest request = new SnapshotRequest();
            request.setSymbol(draft.getSymbol());
            request.setTimeframe(draft.getTimeframe());
            request.setChartStateJson(draft.getChartStateJson());
            request.setUserComment(draft.getUserComment());
            request.setPatternTags(draft.getPatternTagsList());
            request.setVisibility(ChartSnapshot.SnapshotVisibility.valueOf(
                draft.getVisibility() != null ? draft.getVisibility().toUpperCase() : "PRIVATE"
            ));
            request.setPerformAiValidation(draft.getPerformAiValidation());

            // Create snapshot
            SnapshotResult result = snapshotService.createSnapshot(request, username);

            if (result.isSuccess()) {
                // Update snapshot with conversation summary
                if (conversationSummary != null && result.getSnapshotId() != null) {
                    ChartSnapshot snapshot = snapshotRepository.findById(result.getSnapshotId()).orElse(null);
                    if (snapshot != null) {
                        snapshot.setConversationSummary(conversationSummary);
                        snapshotRepository.save(snapshot);
                        log.info("Added conversation summary to snapshot {}", result.getSnapshotId());
                    }
                }

                // Mark draft as committed
                draft.setStatus("COMMITTED");
                draftRepository.save(draft);

                log.info("Draft {} committed to snapshot {}", id, result.getSnapshotId());
            }

            return ResponseEntity.ok(result);

        } catch (Exception e) {
            log.error("Error committing draft", e);
            return ResponseEntity.status(500)
                .body(SnapshotResult.error("Failed to commit draft: " + e.getMessage()));
        }
    }

    /**
     * Get user's drafts
     * GET /api/snapshots/drafts
     */
    @GetMapping
    public ResponseEntity<List<SnapshotDraft>> getUserDrafts(Authentication auth) {
        try {
            String username = auth.getName();
            List<SnapshotDraft> drafts = draftRepository
                .findByUsernameAndStatusOrderByLastModifiedAtDesc(username, "DRAFT");

            return ResponseEntity.ok(drafts);

        } catch (Exception e) {
            log.error("Error fetching user drafts", e);
            return ResponseEntity.status(500).body(List.of());
        }
    }

    /**
     * Get draft by ID
     * GET /api/snapshots/drafts/{id}
     */
    @GetMapping("/{id}")
    public ResponseEntity<SnapshotDraft> getDraft(
        @PathVariable Long id,
        Authentication auth
    ) {
        try {
            String username = auth.getName();

            SnapshotDraft draft = draftRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Draft not found"));

            // Verify ownership
            if (!draft.getUsername().equals(username)) {
                return ResponseEntity.status(403).build();
            }

            return ResponseEntity.ok(draft);

        } catch (Exception e) {
            log.error("Error fetching draft", e);
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * Delete draft
     * DELETE /api/snapshots/drafts/{id}
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, String>> deleteDraft(
        @PathVariable Long id,
        Authentication auth
    ) {
        try {
            String username = auth.getName();

            SnapshotDraft draft = draftRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Draft not found"));

            // Verify ownership
            if (!draft.getUsername().equals(username)) {
                return ResponseEntity.status(403)
                    .body(Map.of("error", "Unauthorized"));
            }

            draftRepository.delete(draft);
            log.info("Deleted draft {} for user {}", id, username);

            return ResponseEntity.ok(Map.of("message", "Draft deleted"));

        } catch (Exception e) {
            log.error("Error deleting draft", e);
            return ResponseEntity.status(500)
                .body(Map.of("error", "Failed to delete draft"));
        }
    }

    // ============ DTOs ============

    @Data
    public static class DraftRequest {
        private String symbol;
        private String timeframe;
        private String chartStateJson;
        private String userComment;
        private List<String> patternTags;
        private String visibility;
        private Boolean performAiValidation;
    }

    @Data
    public static class UpdateDraftRequest {
        private String chartStateJson;
        private String userComment;
        private List<String> patternTags;
        private String message; // New conversation message
    }

    @Data
    @lombok.Builder
    public static class DraftResponse {
        private boolean success;
        private String message;
        private SnapshotDraft draft;
        private ValidationResult validationResult;

        public static DraftResponse success(SnapshotDraft draft) {
            return DraftResponse.builder()
                .success(true)
                .draft(draft)
                .build();
        }

        public static DraftResponse error(String message) {
            return DraftResponse.builder()
                .success(false)
                .message(message)
                .build();
        }
    }
}
