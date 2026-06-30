package com.apimarketplace.agent.registry;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Default implementation of AgentToolRegistry.
 * Thread-safe in-memory registry for agent tools.
 */
@Slf4j
@Component
public class DefaultAgentToolRegistry implements AgentToolRegistry {

    private final Map<String, AgentToolDefinition> tools = new ConcurrentHashMap<>();

    @Override
    public void register(AgentToolDefinition tool) {
        if (tool == null || tool.name() == null) {
            log.warn("Attempted to register null tool or tool with null name");
            return;
        }
        tools.put(tool.name(), tool);
        log.debug("Registered tool: {} in category {}", tool.name(), tool.category());
    }

    @Override
    public void registerAll(List<AgentToolDefinition> toolList) {
        if (toolList == null) return;
        for (AgentToolDefinition tool : toolList) {
            register(tool);
        }
        log.info("Registered {} tools", toolList.size());
    }

    @Override
    public List<AgentToolDefinition> getAllTools() {
        return new ArrayList<>(tools.values());
    }

    @Override
    public List<AgentToolDefinition> getToolsByCategory(ToolCategory category) {
        if (category == null) return List.of();
        return tools.values().stream()
            .filter(t -> t.category() == category)
            .collect(Collectors.toList());
    }

    @Override
    public Optional<AgentToolDefinition> getToolByName(String name) {
        if (name == null) return Optional.empty();
        return Optional.ofNullable(tools.get(name));
    }

    @Override
    public List<AgentToolDefinition> searchTools(String query, int maxResults) {
        if (query == null || query.isBlank()) {
            return getAllTools().stream()
                .limit(maxResults)
                .collect(Collectors.toList());
        }

        String lowerQuery = query.toLowerCase();

        return tools.values().stream()
            .filter(t -> matchesTool(t, lowerQuery))
            .sorted((a, b) -> scoreMatch(b, lowerQuery) - scoreMatch(a, lowerQuery))
            .limit(maxResults)
            .collect(Collectors.toList());
    }

    private boolean matchesTool(AgentToolDefinition tool, String query) {
        return tool.name().toLowerCase().contains(query)
            || tool.description().toLowerCase().contains(query)
            || tool.category().getSlug().contains(query)
            || (tool.tags() != null && tool.tags().stream().anyMatch(t -> t.toLowerCase().contains(query)));
    }

    private int scoreMatch(AgentToolDefinition tool, String query) {
        int score = 0;
        if (tool.name().toLowerCase().equals(query)) score += 100;
        if (tool.name().toLowerCase().startsWith(query)) score += 50;
        if (tool.name().toLowerCase().contains(query)) score += 20;
        if (tool.description().toLowerCase().contains(query)) score += 10;
        if (tool.category().getSlug().equals(query)) score += 30;
        return score;
    }

    @Override
    public Map<String, Object> getToolInputSchema(String toolName) {
        return getToolByName(toolName)
            .map(AgentToolDefinition::inputSchema)
            .orElse(Map.of());
    }

    @Override
    public Map<String, Object> getToolOutputSchema(String toolName) {
        return getToolByName(toolName)
            .map(AgentToolDefinition::outputSchema)
            .orElse(Map.of());
    }

    @Override
    public ToolDocumentation getToolDocumentation(String toolName) {
        return getToolByName(toolName)
            .map(t -> new ToolDocumentation(
                t.name(),
                t.description(),
                t.helpText(),
                t.examples(),
                t.inputSchema(),
                t.outputSchema(),
                t.category(),
                t.tags()
            ))
            .orElse(null);
    }

    @Override
    public Map<String, Integer> getCategoryCounts() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (ToolCategory category : ToolCategory.values()) {
            long count = tools.values().stream()
                .filter(t -> t.category() == category)
                .count();
            counts.put(category.getSlug(), (int) count);
        }
        return counts;
    }

    @Override
    public List<Map<String, Object>> getToolsInMcpFormat() {
        return tools.values().stream()
            .map(AgentToolDefinition::toMcpFormat)
            .collect(Collectors.toList());
    }

    @Override
    public boolean hasTool(String toolName) {
        return toolName != null && tools.containsKey(toolName);
    }

    @Override
    public int getToolCount() {
        return tools.size();
    }
}
