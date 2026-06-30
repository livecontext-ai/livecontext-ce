package com.apimarketplace.agent.controller;

import com.apimarketplace.agent.prompt.DefaultSystemPrompts;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * REST Controller for Agent Prompts API.
 * Exposes system prompts for debugging and visualization.
 */
@Slf4j
@RestController
@RequestMapping("/api/agent-prompts")
@RequiredArgsConstructor
public class AgentPromptsController {

    /**
     * List all available system prompts.
     * GET /api/agent-prompts
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> listPrompts() {
        List<Map<String, Object>> prompts = List.of(
            createPromptEntry("VERSATILE_AGENT", "Default agent for general conversations (all modules)",
                DefaultSystemPrompts.VERSATILE_AGENT, true)
        );

        return ResponseEntity.ok(Map.of(
            "prompts", prompts,
            "count", prompts.size(),
            "modules", DefaultSystemPrompts.ALL_RESOURCE_MODULES.stream()
                .map(m -> Map.of("key", m.key(), "toolNames", m.toolNames()))
                .toList()
        ));
    }

    /**
     * Get a specific prompt by name.
     * GET /api/agent-prompts/{name}
     */
    @GetMapping("/{name}")
    public ResponseEntity<Map<String, Object>> getPrompt(@PathVariable String name) {
        String prompt = DefaultSystemPrompts.getByType(name);
        if (prompt == null) {
            return ResponseEntity.notFound().build();
        }

        String description = switch (name.toLowerCase()) {
            case "versatile", "versatile_agent", "default" -> "Default agent for general conversations with full platform capabilities";
            default -> "System prompt";
        };

        return ResponseEntity.ok(Map.of(
            "name", name.toUpperCase(),
            "description", description,
            "content", prompt,
            "tokenEstimate", estimateTokens(prompt),
            "lineCount", prompt.split("\n").length
        ));
    }

    /**
     * Get all prompt building blocks.
     * GET /api/agent-prompts/blocks
     */
    @GetMapping("/blocks")
    public ResponseEntity<Map<String, Object>> getPromptBlocks() {
        // Using LinkedHashMap to preserve order
        Map<String, Map<String, Object>> blocks = new LinkedHashMap<>();

        blocks.put("CORE_RULES", Map.of(
            "description", "Fundamental behavior rules for all agents",
            "content", DefaultSystemPrompts.getCoreRules(),
            "usedIn", List.of("VERSATILE_AGENT", "buildWorkflowPrompt")
        ));

        blocks.put("RESPONSE_STYLE", Map.of(
            "description", "Response formatting guidelines",
            "content", DefaultSystemPrompts.getResponseStyle(),
            "usedIn", List.of("VERSATILE_AGENT", "buildWorkflowPrompt")
        ));

        // Resource modules
        for (DefaultSystemPrompts.PromptModule module : DefaultSystemPrompts.ALL_RESOURCE_MODULES) {
            blocks.put("MODULE_" + module.key().toUpperCase(), Map.of(
                "description", "Resource module: " + module.key(),
                "content", module.promptSection(),
                "toolNames", module.toolNames()
            ));
        }

        return ResponseEntity.ok(Map.of(
            "blocks", blocks,
            "count", blocks.size()
        ));
    }

    /**
     * Build a workflow-specific prompt (for preview).
     * POST /api/agent-prompts/build-workflow
     */
    @PostMapping("/build-workflow")
    public ResponseEntity<Map<String, Object>> buildWorkflowPrompt(@RequestBody Map<String, String> request) {
        String workflowName = request.getOrDefault("workflowName", "My Workflow");
        String workflowId = request.getOrDefault("workflowId", "wf-123");
        String workflowStatus = request.getOrDefault("workflowStatus", "DRAFT");
        String flowDiagram = request.getOrDefault("flowDiagram", "(no flow)");
        String datasourceId = request.getOrDefault("datasourceId", "1");
        String lastRunInfo = request.getOrDefault("lastRunInfo", "No execution history");

        String prompt = DefaultSystemPrompts.buildWorkflowPrompt(
            workflowName, workflowId, workflowStatus, flowDiagram, datasourceId, lastRunInfo
        );

        return ResponseEntity.ok(Map.of(
            "name", "WORKFLOW_CONVERSATION",
            "description", "Dynamic prompt for workflow editing context",
            "content", prompt,
            "tokenEstimate", estimateTokens(prompt),
            "lineCount", prompt.split("\n").length,
            "context", Map.of(
                "workflowName", workflowName,
                "workflowId", workflowId,
                "workflowStatus", workflowStatus
            )
        ));
    }

    private Map<String, Object> createPromptEntry(String name, String description, String content, boolean isDefault) {
        return Map.of(
            "name", name,
            "description", description,
            "isDefault", isDefault,
            "tokenEstimate", estimateTokens(content),
            "lineCount", content.split("\n").length,
            "preview", content.length() > 200 ? content.substring(0, 200) + "..." : content
        );
    }

    /**
     * Rough token estimation (4 chars ~ 1 token).
     */
    private int estimateTokens(String text) {
        return text.length() / 4;
    }
}
