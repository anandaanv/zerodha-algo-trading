package com.dtech.kitecon.service.ai.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * AITool - Interface for programmatic pattern validation tools
 * Each tool validates a specific chart pattern using data-driven analysis
 */
public interface AITool {

    /**
     * Unique tool identifier
     */
    String getToolName();

    /**
     * Human-readable description of what this tool validates
     */
    String getDescription();

    /**
     * Pattern type this tool supports
     */
    PatternType getSupportedPattern();

    /**
     * Get JSON schema for input validation
     * Follows JSON Schema Draft 7 specification
     */
    ObjectNode getInputSchema();

    /**
     * Execute the validation logic
     * @param input Validated input data conforming to getInputSchema()
     * @return Validation result with confidence and feedback
     */
    ValidationResult validate(ValidationInput input);

    /**
     * Get tool definition in OpenAI function calling format
     * Used when escalating to AI for ambiguous cases
     */
    default ObjectNode getToolDefinitionForAI() {
        ObjectNode definition = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        definition.put("type", "function");

        ObjectNode function = definition.putObject("function");
        function.put("name", getToolName());
        function.put("description", getDescription());
        function.set("parameters", getInputSchema());

        return definition;
    }

    /**
     * Check if this tool can handle the given pattern
     */
    default boolean canHandle(PatternType pattern) {
        return getSupportedPattern() == pattern;
    }
}
