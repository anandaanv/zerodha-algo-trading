package com.dtech.kitecon.service;

import com.dtech.kitecon.service.ai.AIProvider;
import com.dtech.kitecon.service.ai.tools.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;

/**
 * AIValidationOrchestrator - Orchestrates pattern validation
 * Routes to appropriate tool, executes validation, escalates to AI if needed
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AIValidationOrchestrator {

    private final AIToolRegistry toolRegistry;
    private final List<AIProvider> aiProviders;

    private static final double AI_ESCALATION_THRESHOLD = 0.7;

    /**
     * Validate a pattern with full orchestration
     *
     * @param input Validation input with all necessary data
     * @return Validation result (programmatic + AI if needed)
     */
    public ValidationResult validatePattern(ValidationInput input) {
        PatternType patternType = input.getPatternType();

        if (patternType == null || patternType == PatternType.UNKNOWN) {
            // Try to identify pattern using AI
            patternType = identifyPatternWithAI(input.getUserComment());
            input.setPatternType(patternType);
        }

        if (patternType == PatternType.UNKNOWN) {
            return ValidationResult.builder()
                .isValid(false)
                .confidence(0.0)
                .reason("Unable to identify pattern type from description")
                .detailedFeedback("Please specify the pattern type more clearly. " +
                    "Examples: 'ascending triangle', 'trendline breakout', 'elliott wave'")
                .build();
        }

        // Step 1: Get appropriate tool
        AITool tool = toolRegistry.getToolForPattern(patternType).orElse(null);

        if (tool == null) {
            log.warn("No tool found for pattern type: {}", patternType);
            // Fall back to AI-only analysis
            return aiOnlyValidation(input, patternType);
        }

        // Step 2: Execute programmatic validation
        ValidationResult programmaticResult;
        try {
            log.info("Executing {} for pattern {}", tool.getToolName(), patternType);
            programmaticResult = tool.validate(input);
        } catch (Exception e) {
            log.error("Error executing tool validation", e);
            return ValidationResult.builder()
                .isValid(false)
                .confidence(0.0)
                .reason("Validation error: " + e.getMessage())
                .build();
        }

        // Step 3: Check if AI escalation is needed
        boolean needsAIEscalation = shouldEscalateToAI(programmaticResult);

        if (needsAIEscalation) {
            log.info("Escalating to AI due to low confidence: {}", programmaticResult.getConfidence());
            return escalateToAI(input, patternType, programmaticResult);
        }

        return programmaticResult;
    }

    /**
     * Identify pattern type from user comment using AI
     */
    private PatternType identifyPatternWithAI(String comment) {
        if (comment == null || comment.trim().isEmpty()) {
            return PatternType.UNKNOWN;
        }

        // First try keyword-based identification (fast, free)
        PatternType keywordBased = PatternType.fromComment(comment);
        if (keywordBased != PatternType.UNKNOWN) {
            return keywordBased;
        }

        // If keywords fail, try AI identification
        AIProvider provider = selectAIProvider();
        if (provider != null) {
            try {
                return provider.identifyPattern(comment);
            } catch (Exception e) {
                log.error("Error identifying pattern with AI", e);
            }
        }

        return PatternType.UNKNOWN;
    }

    /**
     * Check if result should be escalated to AI
     */
    private boolean shouldEscalateToAI(ValidationResult result) {
        // Escalate if confidence is below threshold
        if (result.getConfidence() < AI_ESCALATION_THRESHOLD) {
            return true;
        }

        // Escalate if there are violations but pattern is marked valid
        if (result.isValid() && !result.getViolations().isEmpty()) {
            return true;
        }

        // Escalate if there are alternative interpretations
        if (result.getAlternatives() != null && !result.getAlternatives().isEmpty()) {
            return true;
        }

        return false;
    }

    /**
     * Escalate validation to AI for enhanced analysis
     */
    private ValidationResult escalateToAI(
        ValidationInput input,
        PatternType patternType,
        ValidationResult programmaticResult
    ) {
        AIProvider provider = selectAIProvider();

        if (provider == null) {
            log.warn("No AI provider available for escalation");
            // Return programmatic result with note
            programmaticResult.addSuggestion("AI analysis unavailable. Consider manual review.");
            return programmaticResult;
        }

        try {
            log.info("Using AI provider: {}", provider.getProviderName());
            return provider.analyzePattern(input, patternType, programmaticResult);
        } catch (Exception e) {
            log.error("Error during AI escalation", e);
            // Return programmatic result with error note
            programmaticResult.addSuggestion("AI analysis failed: " + e.getMessage());
            return programmaticResult;
        }
    }

    /**
     * Perform AI-only validation (when no programmatic tool exists)
     */
    private ValidationResult aiOnlyValidation(ValidationInput input, PatternType patternType) {
        AIProvider provider = selectAIProvider();

        if (provider == null) {
            return ValidationResult.builder()
                .isValid(false)
                .confidence(0.0)
                .reason("No validation tool or AI provider available for this pattern")
                .detailedFeedback(String.format(
                    "Pattern type '%s' does not have a programmatic validator yet. " +
                    "AI analysis is also unavailable. Please try a different pattern type.",
                    patternType.getDisplayName()
                ))
                .build();
        }

        try {
            return provider.analyzePattern(input, patternType, null);
        } catch (Exception e) {
            log.error("Error in AI-only validation", e);
            return ValidationResult.builder()
                .isValid(false)
                .confidence(0.0)
                .reason("AI validation failed: " + e.getMessage())
                .build();
        }
    }

    /**
     * Select best available AI provider
     * Uses priority and availability
     */
    private AIProvider selectAIProvider() {
        return aiProviders.stream()
            .filter(AIProvider::isAvailable)
            .min(Comparator.comparingInt(AIProvider::getPriority))
            .orElse(null);
    }

    /**
     * Get pattern advice from AI (for user guidance)
     */
    public String getPatternAdvice(ValidationInput input, PatternType patternType) {
        AIProvider provider = selectAIProvider();

        if (provider == null) {
            return "AI advice unavailable. Please consult trading guides for " +
                patternType.getDisplayName() + " patterns.";
        }

        try {
            return provider.getPatternAdvice(input, patternType);
        } catch (Exception e) {
            log.error("Error getting pattern advice", e);
            return "Unable to provide AI advice at this time.";
        }
    }

    /**
     * Batch validate multiple patterns (for snapshot with multiple drawings)
     */
    public List<ValidationResult> validateMultiplePatterns(List<ValidationInput> inputs) {
        return inputs.stream()
            .map(this::validatePattern)
            .toList();
    }
}
