package com.dtech.kitecon.service.ai;

import com.dtech.kitecon.service.ai.tools.PatternType;
import com.dtech.kitecon.service.ai.tools.ValidationInput;
import com.dtech.kitecon.service.ai.tools.ValidationResult;

/**
 * AIProvider - Interface for AI service providers
 * Allows pluggable AI backends (OpenAI, Claude, Gemini, Local LLM)
 */
public interface AIProvider {

    /**
     * Provider name (e.g., "openai", "claude", "gemini")
     */
    String getProviderName();

    /**
     * Check if this provider is properly configured and available
     */
    boolean isAvailable();

    /**
     * Analyze a pattern using AI
     * This is called when programmatic validation needs AI assistance
     *
     * @param input Validation input with chart data
     * @param patternType Type of pattern to analyze
     * @param programmaticResult Result from programmatic tool (can be null)
     * @return AI-enhanced validation result
     */
    ValidationResult analyzePattern(
        ValidationInput input,
        PatternType patternType,
        ValidationResult programmaticResult
    );

    /**
     * Ask AI to identify pattern type from user comment
     *
     * @param comment User's description
     * @return Identified pattern type
     */
    PatternType identifyPattern(String comment);

    /**
     * Get general analysis/advice about a chart pattern
     *
     * @param input Validation input
     * @param patternType Pattern type
     * @return Natural language analysis
     */
    String getPatternAdvice(ValidationInput input, PatternType patternType);

    /**
     * Priority of this provider (lower = higher priority)
     * Used for fallback selection
     */
    default int getPriority() {
        return 100;
    }

    /**
     * Get cost estimate for this provider (relative scale 1-10)
     * Used for cost-aware provider selection
     */
    default int getCostEstimate() {
        return 5;
    }
}
