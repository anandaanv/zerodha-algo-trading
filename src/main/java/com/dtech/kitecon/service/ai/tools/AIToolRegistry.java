package com.dtech.kitecon.service.ai.tools;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * AIToolRegistry - Registry of all available AI validation tools
 * Manages tool discovery, selection, and execution
 */
@Component
@Slf4j
public class AIToolRegistry {

    private final Map<PatternType, AITool> toolsByPattern = new HashMap<>();
    private final Map<String, AITool> toolsByName = new HashMap<>();

    /**
     * Auto-register all AITool implementations via Spring dependency injection
     */
    @Autowired
    public AIToolRegistry(List<AITool> tools) {
        for (AITool tool : tools) {
            registerTool(tool);
        }
        log.info("Registered {} AI validation tools", tools.size());
    }

    /**
     * Register a tool
     */
    public void registerTool(AITool tool) {
        toolsByPattern.put(tool.getSupportedPattern(), tool);
        toolsByName.put(tool.getToolName(), tool);
        log.debug("Registered tool: {} for pattern: {}",
            tool.getToolName(),
            tool.getSupportedPattern());
    }

    /**
     * Get tool by pattern type
     */
    public Optional<AITool> getToolForPattern(PatternType pattern) {
        AITool tool = toolsByPattern.get(pattern);
        return Optional.ofNullable(tool);
    }

    /**
     * Get tool by name
     */
    public Optional<AITool> getToolByName(String name) {
        AITool tool = toolsByName.get(name);
        return Optional.ofNullable(tool);
    }

    /**
     * Get all registered tools
     */
    public List<AITool> getAllTools() {
        return List.copyOf(toolsByName.values());
    }

    /**
     * Check if a tool exists for given pattern
     */
    public boolean hasToolForPattern(PatternType pattern) {
        return toolsByPattern.containsKey(pattern);
    }

    /**
     * Get tool count
     */
    public int getToolCount() {
        return toolsByName.size();
    }

    /**
     * Get tool definitions for AI function calling (OpenAI format)
     */
    public List<com.fasterxml.jackson.databind.node.ObjectNode> getToolDefinitionsForAI() {
        return getAllTools().stream()
            .map(AITool::getToolDefinitionForAI)
            .toList();
    }
}
