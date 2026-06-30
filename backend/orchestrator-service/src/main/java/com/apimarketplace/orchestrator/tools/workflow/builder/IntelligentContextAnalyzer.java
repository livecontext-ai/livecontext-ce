package com.apimarketplace.orchestrator.tools.workflow.builder;

import com.apimarketplace.orchestrator.domain.workflow.Step;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * IntelligentContextAnalyzer - Suggest next steps based on workflow state.
 *
 * PRINCIPLE: Context-aware recommendations to guide LLM toward complete, coherent workflows.
 *
 * Analyzes:
 * - What was just added
 * - Workflow completeness
 * - Data flow patterns
 * - Naming patterns (e.g., "daily" suggests schedule trigger)
 */
@Slf4j
@Component
public class IntelligentContextAnalyzer {

    /**
     * Recommendation strength levels.
     */
    public enum RecommendationStrength {
        STRONG,    // ⚠️ STRONG: Highly recommended action
        MEDIUM,    // 💡 MEDIUM: Suggested action
        WEAK,      // WEAK: Optional action
        INFO       // INFO: Informational only
    }

    /**
     * Recommendation for next step.
     */
    public record RecommendedNextStep(
        RecommendationStrength strength,
        String action,
        String reason,
        List<String> examples
    ) {}

    // ═══════════════════════════════════════════════════════════════════════════════
    // ANALYZE AFTER NODE ADDITION
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * Analyze workflow state after adding an agent.
     *
     * Checks:
     * - Does agent produce structured data? → Recommend saving to table
     * - Is agent the only processing node? → Suggest more processing or finalize
     *
     * @param session Workflow builder session
     * @param agent Agent that was just added
     * @return Recommendation
     */
    public RecommendedNextStep analyzeAfterAgent(WorkflowBuilderSession session, Map<String, Object> agent) {
        log.debug("Analyzing after agent: {}", agent.get("label"));

        // Check if agent produces structured data
        boolean producesStructuredData = detectStructuredOutput(agent);

        if (producesStructuredData) {
            String agentLabel = (String) agent.get("label");
            String normalizedLabel = WorkflowBuilderSession.normalizeLabel(agentLabel);
            return new RecommendedNextStep(
                RecommendationStrength.STRONG,
                "Save agent results to a table",
                "Agent produces structured data - save it to persist results",
                List.of(
                    "1. table(action='create', name='Results', columns=[{name:'data', type:'text'}]) → returns table_id",
                    "2. workflow(action='add_node', type='insert_row', label='Save', params={table_id: X, columns: {data: '{{agent:" + normalizedLabel + ".output}}'}}, connect_after='" + normalizedLabel + "')"
                )
            );
        }

        // Agent doesn't produce structured data - suggest finalize or add more processing
        return new RecommendedNextStep(
            RecommendationStrength.WEAK,
            "Add more processing steps or create workflow",
            "Agent added - workflow can be finalized or extended",
            List.of(
                "workflow(action='finish') - Finalize and save the workflow"
            )
        );
    }

    /**
     * Analyze workflow state after adding a step.
     *
     * Checks:
     * - Is step fetching data from API? → Suggest agent to process OR table to store
     * - Is step a table insert? → Workflow can be finalized
     *
     * @param session Workflow builder session
     * @param step Step that was just added
     * @return Recommendation
     */
    public RecommendedNextStep analyzeAfterStep(WorkflowBuilderSession session, Map<String, Object> step) {
        log.debug("Analyzing after step: {}", step.get("label"));

        String stepType = (String) step.get("type");

        // HTTP step - suggest processing or storage
        if ("http".equals(stepType)) {
            String stepLabel = (String) step.get("label");
            String normalizedLabel = WorkflowBuilderSession.normalizeLabel(stepLabel);
            return new RecommendedNextStep(
                RecommendationStrength.MEDIUM,
                "Process API data with agent OR save to table",
                "HTTP step fetches data - should be processed or stored",
                List.of(
                    "workflow(action='add_node', type='agent', label='Process Data', params={prompt: 'Extract key information from {{mcp:" + normalizedLabel + ".output.response}}'}, connect_after='" + normalizedLabel + "')",
                    "OR table(action='create', ...) + workflow(action='add_node', type='insert_row', label='Save', params={table_id: X, columns: {...}}, connect_after='" + normalizedLabel + "')"
                )
            );
        }

        // Table step - data is saved, workflow can continue or finalize
        if ("table".equals(stepType)) {
            String crudAction = (String) step.get("crud_action");
            if ("insert".equals(crudAction) || "update".equals(crudAction)) {
                return new RecommendedNextStep(
                    RecommendationStrength.WEAK,
                    "Data saved - workflow can be finalized or extended",
                    "Data is persisted in table",
                    List.of(
                        "workflow(action='finish') - Finalize and save the workflow"
                    )
                );
            }
        }

        // Default - no specific recommendation
        return new RecommendedNextStep(
            RecommendationStrength.WEAK,
            "Continue building or finalize workflow",
            "Step added successfully",
            List.of()
        );
    }

    /**
     * Analyze workflow state after adding a trigger.
     *
     * Checks:
     * - Does workflow have processing yet? → Recommend add_mcp or add_agent
     *
     * @param session Workflow builder session
     * @param trigger Trigger that was just added
     * @return Recommendation
     */
    public RecommendedNextStep analyzeAfterTrigger(WorkflowBuilderSession session, Map<String, Object> trigger) {
        log.debug("Analyzing after trigger: {}", trigger.get("label"));

        // Trigger added - suggest adding processing
        return new RecommendedNextStep(
            RecommendationStrength.STRONG,
            "Add a step or agent to process data",
            "Workflow has a trigger - now add processing logic",
            List.of(
                "workflow(action='add_node', type='<http-tool-uuid>', label='Fetch Data', params={url: '...'}, connect_after='...')",
                "workflow(action='add_node', type='agent', label='Process Data', params={prompt: '...'}, connect_after='...')"
            )
        );
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // CONTEXTUAL WARNINGS
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * Detect contextual warnings based on workflow state.
     *
     * Warnings:
     * - Workflow name suggests "daily" but no schedule trigger
     * - Agent produces data but no table to persist
     *
     * @param session Workflow builder session
     * @return List of warnings
     */
    public List<String> detectContextualWarnings(WorkflowBuilderSession session) {
        List<String> warnings = new ArrayList<>();
        String workflowName = session.getWorkflowName();

        if (workflowName != null) {
            String nameLower = workflowName.toLowerCase();

            // Schedule suggestion based on workflow name
            if ((nameLower.contains("daily") || nameLower.contains("quotidien") ||
                 nameLower.contains("every day") || nameLower.contains("chaque jour")) &&
                !hasScheduleTrigger(session)) {
                warnings.add("💡 Workflow name suggests daily schedule - consider using schedule trigger: workflow(action='add_node', type='schedule', label='Daily', params={schedule: '0 9 * * *'})");
            }

            // Hourly pattern
            if ((nameLower.contains("hourly") || nameLower.contains("every hour") ||
                 nameLower.contains("chaque heure")) &&
                !hasScheduleTrigger(session)) {
                warnings.add("💡 Workflow name suggests hourly schedule - consider using schedule trigger: workflow(action='add_node', type='schedule', label='Hourly', params={schedule: '0 * * * *'})");
            }
        }

        // Agent without persistence warning
        if (hasAgentWithoutStorage(session)) {
            warnings.add("⚠️ Workflow has agent producing data but no table - results will be lost! Add insert_row to save results.");
        }

        return warnings;
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // HELPER METHODS
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * Detect if agent produces structured data.
     *
     * Heuristics:
     * - Task contains keywords: extract, analyze, summarize, categorize
     * - Task length > 50 characters (detailed task)
     *
     * @param agent Agent data
     * @return true if likely produces structured data
     */
    private boolean detectStructuredOutput(Map<String, Object> agent) {
        String task = (String) agent.get("task");
        String prompt = (String) agent.get("prompt");
        String text = task != null ? task : prompt;

        if (text == null || text.isBlank()) {
            return false;
        }

        String lower = text.toLowerCase();

        // Structured output keywords
        String[] structuredKeywords = {
            "extract", "extraire",
            "analyze", "analyser",
            "summarize", "résumer",
            "categorize", "catégoriser",
            "classify", "classifier",
            "parse", "parser",
            "identify", "identifier",
            "find", "trouver",
            "list", "lister"
        };

        for (String keyword : structuredKeywords) {
            if (lower.contains(keyword)) {
                return true;
            }
        }

        // Long task = likely complex analysis
        return text.length() > 50;
    }

    /**
     * Check if workflow has a schedule trigger.
     */
    private boolean hasScheduleTrigger(WorkflowBuilderSession session) {
        return session.getTriggers().stream()
            .anyMatch(t -> "schedule".equals(t.get("type")));
    }

    /**
     * Check if workflow has agent without table storage step.
     * Returns true if there's an agent but no crud/* step to persist results.
     */
    private boolean hasAgentWithoutStorage(WorkflowBuilderSession session) {
        // Check if workflow has any agents
        boolean hasAgent = session.getMcps().stream()
            .anyMatch(s -> Boolean.TRUE.equals(s.get("isAgent")));

        if (!hasAgent) {
            return false;
        }

        // Check if there's a table storage step (crud/*)
        boolean hasTableStep = session.getMcps().stream()
            .anyMatch(s -> {
                String toolId = (String) s.get("id");
                return toolId != null && toolId.startsWith("crud/");
            });

        return !hasTableStep;
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // COMPLETENESS ASSESSMENT
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * Completeness report for a workflow.
     */
    public record CompletenessReport(
        boolean hasTrigger,
        boolean hasProcessing,
        boolean hasStorage,
        boolean hasDisplay,
        boolean isComplete
    ) {}

    /**
     * Assess workflow completeness.
     *
     * A workflow is complete if:
     * - Has at least one trigger
     * - Has at least one processing node (step, agent, or control node)
     *
     * Optional (improves quality):
     * - Has storage (table step)
     * - Has display (interface step)
     *
     * @param session Workflow builder session
     * @return Completeness report
     */
    public CompletenessReport assessCompleteness(WorkflowBuilderSession session) {
        // Implementation depends on session structure
        // For now, return minimal implementation
        return new CompletenessReport(
            false,  // hasTrigger
            false,  // hasProcessing
            false,  // hasStorage
            false,  // hasDisplay
            false   // isComplete
        );
    }
}
