package com.dtech.kitecon.service;

import com.dtech.kitecon.data.ChartSnapshot;
import com.dtech.kitecon.data.UserSubscriptionPlan;
import com.dtech.kitecon.repository.ChartSnapshotRepository;
import com.dtech.kitecon.repository.UserSubscriptionPlanRepository;
import com.dtech.kitecon.service.ai.tools.PatternType;
import com.dtech.kitecon.service.ai.tools.ValidationInput;
import com.dtech.kitecon.service.ai.tools.ValidationResult;
import com.dtech.kitecon.service.model.SnapshotRequest;
import com.dtech.kitecon.service.model.SnapshotResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * ChartSnapshotService - Main service for chart snapshot management
 * Orchestrates snapshot creation, validation, and storage
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ChartSnapshotService {

    private final ChartSnapshotRepository snapshotRepository;
    private final UserSubscriptionPlanRepository subscriptionRepository;
    private final AIValidationOrchestrator validationOrchestrator;
    private final DrawingExtractorService drawingExtractor;
    private final TagValidationService tagValidationService;
    private final ObjectMapper objectMapper;

    /**
     * Create a new snapshot with validation
     */
    @Transactional
    public SnapshotResult createSnapshot(SnapshotRequest request, String username) {
        try {
            log.info("Creating snapshot for user: {}, symbol: {}", username, request.getSymbol());

            // Step 1: Check subscription limits
            if (request.getVisibility() == ChartSnapshot.SnapshotVisibility.PRIVATE) {
                if (!canCreatePrivateSnapshot(username)) {
                    return SnapshotResult.error("Private snapshot limit reached. Upgrade your plan or delete old snapshots.");
                }
            }

            // Step 2: Parse chart state JSON
            JsonNode chartStateJson;
            try {
                chartStateJson = objectMapper.readTree(request.getChartStateJson());
            } catch (Exception e) {
                log.error("Invalid chart state JSON", e);
                return SnapshotResult.error("Invalid chart state data");
            }

            // Step 3: Check if chart state has drawings
            // Don't parse them - just verify something exists and pass raw JSON to AI
            boolean hasDrawings = hasDrawingsInChartState(request.getChartStateJson());

            if (!hasDrawings) {
                log.warn("No drawings found in chart state for snapshot");
                return SnapshotResult.builder()
                    .success(false)
                    .message("No drawings found on chart. Please add pattern drawings before creating snapshot.")
                    .warnings("Draw your pattern on the chart (trendlines, triangles, etc.) and try again.")
                    .build();
            }

            log.debug("Chart state contains drawings");

            // Step 4: Validate and normalize pattern tags
            List<String> normalizedTags;
            if (request.getPatternTags() != null && !request.getPatternTags().isEmpty()) {
                normalizedTags = tagValidationService.validateAndNormalizeTags(request.getPatternTags());
                log.info("Validated pattern tags: {} -> {}", request.getPatternTags(), normalizedTags);
            } else {
                normalizedTags = List.of();
                log.warn("No pattern tags provided");
            }

            // Step 5: Identify pattern type (for backward compatibility with validation)
            PatternType patternType;
            if (request.getPatternType() != null && !request.getPatternType().isEmpty()) {
                try {
                    patternType = PatternType.valueOf(request.getPatternType().toUpperCase().replace(" ", "_"));
                } catch (IllegalArgumentException e) {
                    patternType = PatternType.fromComment(request.getUserComment());
                }
            } else if (!normalizedTags.isEmpty()) {
                // Try to derive from first tag
                patternType = PatternType.fromComment(normalizedTags.get(0));
            } else {
                patternType = PatternType.fromComment(request.getUserComment());
            }

            log.info("Identified pattern type for validation: {}", patternType);

            // Step 6: Perform validation (if requested)
            ValidationResult validationResult = null;
            boolean aiUsed = false;

            if (request.getPerformAiValidation() != null && request.getPerformAiValidation()) {
                // Pass raw chart state to AI - don't parse drawings ourselves
                // TradingView format can change, let AI handle it
                JsonNode chartStateNode = objectMapper.readTree(request.getChartStateJson());

                // Build validation input
                // Pass empty drawings list - AI will read from chartStateJson directly
                ValidationInput validationInput = ValidationInput.builder()
                    .symbol(request.getSymbol())
                    .timeframe(request.getTimeframe())
                    .patternType(patternType)
                    .drawings(List.of()) // Empty - AI reads raw chartStateJson
                    .priceData(List.of()) // TODO: Fetch OHLC data from database
                    .userComment(request.getUserComment())
                    .chartStateJson(chartStateNode)
                    .build();

                // Validate pattern
                validationResult = validationOrchestrator.validatePattern(validationInput);
                aiUsed = validationResult.getConfidence() < 0.7; // AI was likely used if confidence was low

                log.info("Validation result - Valid: {}, Confidence: {}",
                    validationResult.isValid(),
                    validationResult.getConfidence());

                // Check if validation suggests not creating snapshot
                if (!validationResult.isValid() && validationResult.getConfidence() < 0.3) {
                    return SnapshotResult.builder()
                        .success(false)
                        .message("Pattern validation failed")
                        .validationResult(validationResult)
                        .patternType(patternType.name())
                        .warnings("The pattern does not meet validation criteria. Review suggestions and try again.")
                        .build();
                }
            }

            // Step 6: Create snapshot entity
            ChartSnapshot snapshot = ChartSnapshot.builder()
                .username(username)
                .symbol(request.getSymbol())
                .timeframe(request.getTimeframe())
                .layoutId(request.getLayoutId())
                .snapshotTimestamp(LocalDateTime.now())
                .startDate(request.getStartDate())
                .endDate(request.getEndDate())
                .chartStateJson(request.getChartStateJson())
                .userComment(request.getUserComment())
                .patternType(patternType.name()) // Keep for backward compatibility
                .patternTags(normalizedTags.isEmpty() ? null : String.join(",", normalizedTags))
                .visibility(request.getVisibility() != null ?
                    request.getVisibility() :
                    ChartSnapshot.SnapshotVisibility.PRIVATE)
                .groupIds(request.getGroupIds())
                .likesCount(0)
                .viewsCount(0)
                .cloneCount(0)
                .build();

            // Add validation results to snapshot
            if (validationResult != null) {
                try {
                    snapshot.setAiValidation(validationResult.isValid() ? "valid" : "invalid");
                    snapshot.setAiAnalysis(objectMapper.writeValueAsString(validationResult));
                } catch (Exception e) {
                    log.error("Error serializing validation result", e);
                }
            }

            // Step 7: Save snapshot
            snapshot = snapshotRepository.save(snapshot);
            log.info("Snapshot created with ID: {}", snapshot.getId());

            // Step 8: Build result
            return SnapshotResult.builder()
                .snapshotId(snapshot.getId())
                .success(true)
                .message(buildSuccessMessage(validationResult))
                .validationResult(validationResult)
                .patternType(patternType.name())
                .aiUsed(aiUsed)
                .build();

        } catch (Exception e) {
            log.error("Error creating snapshot", e);
            return SnapshotResult.error("Failed to create snapshot: " + e.getMessage());
        }
    }

    /**
     * Validate a pattern without creating snapshot (preview)
     */
    public ValidationResult validateOnly(SnapshotRequest request) {
        try {
            // Check if chart state has drawings
            boolean hasDrawings = hasDrawingsInChartState(request.getChartStateJson());

            if (!hasDrawings) {
                return ValidationResult.builder()
                    .isValid(false)
                    .confidence(0.0)
                    .reason("No drawings found on chart")
                    .detailedFeedback("Please add pattern drawings to the chart before validation.")
                    .build();
            }

            // Identify pattern
            PatternType patternType = PatternType.fromComment(request.getUserComment());

            // Parse chart state - pass raw JSON to AI
            JsonNode chartStateJson = objectMapper.readTree(request.getChartStateJson());

            // Build validation input
            // Pass empty drawings list - AI will read from chartStateJson directly
            ValidationInput validationInput = ValidationInput.builder()
                .symbol(request.getSymbol())
                .timeframe(request.getTimeframe())
                .patternType(patternType)
                .drawings(List.of()) // Empty - AI reads raw chartStateJson
                .priceData(List.of()) // TODO: Fetch OHLC data
                .userComment(request.getUserComment())
                .chartStateJson(chartStateJson)
                .build();

            // Validate
            return validationOrchestrator.validatePattern(validationInput);

        } catch (Exception e) {
            log.error("Error in validate-only", e);
            return ValidationResult.builder()
                .isValid(false)
                .confidence(0.0)
                .reason("Validation error: " + e.getMessage())
                .build();
        }
    }

    /**
     * Check if user can create private snapshot (subscription limit)
     */
    private boolean canCreatePrivateSnapshot(String username) {
        // Get user's subscription plan
        UserSubscriptionPlan plan = subscriptionRepository.findByUsername(username)
            .orElse(null);

        if (plan == null) {
            // Create default free plan
            plan = UserSubscriptionPlan.builder()
                .username(username)
                .planType(UserSubscriptionPlan.PlanType.FREE)
                .privateSnapshotsLimit(10)
                .groupSharingEnabled(false)
                .build();
            subscriptionRepository.save(plan);
        }

        // Check if plan is active
        if (!plan.isActive()) {
            log.warn("User {} has expired subscription", username);
            return false;
        }

        // Check limit (-1 means unlimited)
        if (plan.getPrivateSnapshotsLimit() == -1) {
            return true;
        }

        // Count existing private snapshots
        long privateCount = snapshotRepository.countByUsernameAndVisibility(
            username,
            ChartSnapshot.SnapshotVisibility.PRIVATE
        );

        return privateCount < plan.getPrivateSnapshotsLimit();
    }

    /**
     * Build success message based on validation result
     */
    private String buildSuccessMessage(ValidationResult validation) {
        if (validation == null) {
            return "Snapshot created successfully (no validation performed)";
        }

        if (validation.isValid()) {
            return String.format(
                "Snapshot created! Pattern is valid with %.0f%% confidence.",
                validation.getConfidence() * 100
            );
        } else {
            return String.format(
                "Snapshot created with warnings. Pattern confidence: %.0f%%",
                validation.getConfidence() * 100
            );
        }
    }

    /**
     * Get snapshot by ID (with permission check)
     */
    public ChartSnapshot getSnapshot(Long id, String username) {
        ChartSnapshot snapshot = snapshotRepository.findById(id).orElse(null);

        if (snapshot == null) {
            return null;
        }

        // Check visibility permissions
        if (snapshot.getVisibility() == ChartSnapshot.SnapshotVisibility.PUBLIC) {
            // Increment view count
            snapshot.setViewsCount(snapshot.getViewsCount() + 1);
            snapshotRepository.save(snapshot);
            return snapshot;
        }

        if (snapshot.getVisibility() == ChartSnapshot.SnapshotVisibility.PRIVATE) {
            // Only owner can view
            if (!snapshot.getUsername().equals(username)) {
                return null;
            }
            return snapshot;
        }

        // Group visibility - check if user is in group
        // TODO: Implement group membership check

        return snapshot;
    }

    /**
     * Delete snapshot (with permission check)
     */
    @Transactional
    public boolean deleteSnapshot(Long id, String username) {
        ChartSnapshot snapshot = snapshotRepository.findByIdAndUsername(id, username).orElse(null);

        if (snapshot == null) {
            return false;
        }

        snapshotRepository.delete(snapshot);
        log.info("Deleted snapshot ID: {} by user: {}", id, username);
        return true;
    }

    /**
     * Update snapshot visibility
     */
    @Transactional
    public boolean updateVisibility(Long id, String username,
                                   ChartSnapshot.SnapshotVisibility newVisibility,
                                   String groupIds) {
        ChartSnapshot snapshot = snapshotRepository.findByIdAndUsername(id, username).orElse(null);

        if (snapshot == null) {
            return false;
        }

        snapshot.setVisibility(newVisibility);
        if (groupIds != null) {
            snapshot.setGroupIds(groupIds);
        }

        snapshotRepository.save(snapshot);
        log.info("Updated snapshot {} visibility to {}", id, newVisibility);
        return true;
    }

    /**
     * Simple check if chart state JSON contains drawings
     * Just checks for "drawings" array or other drawing indicators
     * Doesn't parse the drawings - let AI handle the raw format
     */
    private boolean hasDrawingsInChartState(String chartStateJson) {
        try {
            JsonNode chartState = objectMapper.readTree(chartStateJson);

            // Check for "drawings" array
            if (chartState.has("drawings")) {
                JsonNode drawings = chartState.get("drawings");
                if (drawings.isArray() && drawings.size() > 0) {
                    log.info("Found {} drawings in chart state", drawings.size());
                    return true;
                }
            }

            // Check for other possible locations (for future compatibility)
            if (chartState.has("sources") && chartState.get("sources").size() > 0) {
                return true;
            }

            if (chartState.has("lineTools") && chartState.get("lineTools").size() > 0) {
                return true;
            }

            return false;
        } catch (Exception e) {
            log.error("Error checking for drawings in chart state", e);
            return false;
        }
    }
}
