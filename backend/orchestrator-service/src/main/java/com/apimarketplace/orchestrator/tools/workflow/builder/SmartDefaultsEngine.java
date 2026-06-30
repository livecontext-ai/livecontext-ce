package com.apimarketplace.orchestrator.tools.workflow.builder;

import com.apimarketplace.agent.client.AgentClient;
import com.apimarketplace.orchestrator.config.AgentDefaultsConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * SmartDefaultsEngine - Auto-complete technical parameters LLM shouldn't choose.
 *
 * PRINCIPLE: LLM chooses WHAT (business logic), Backend chooses HOW (technical parameters).
 *
 * Examples:
 * - Agent: LLM provides task, backend selects provider/model
 * - Trigger: LLM provides cron expression, backend sets strategy to CRON
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SmartDefaultsEngine {

    private final AgentDefaultsConfig agentDefaults;
    private final AgentClient agentClient;

    // ═══════════════════════════════════════════════════════════════════════════════
    // AGENT DEFAULTS
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * Apply smart defaults for agent nodes.
     *
     * Defaults applied:
     * - provider: from config (if not provided)
     * - model: from config (if not provided)
     * - Rename 'task' → 'prompt' (internal field name)
     *
     * @param agent Agent data from LLM
     * @return Agent data with defaults applied
     */
    public Map<String, Object> applyAgentDefaults(Map<String, Object> agent) {
        log.debug("Applying agent defaults to: {}", agent);

        // Provider default from AI provider catalog (first available), fallback to config
        if (!agent.containsKey("provider") || agent.get("provider") == null) {
            String defaultProvider = resolveDefaultProvider();
            agent.put("provider", defaultProvider);
            log.debug("Applied default provider: {}", defaultProvider);
        }

        // Model default from AI provider catalog (first available), fallback to config
        if (!agent.containsKey("model") || agent.get("model") == null) {
            String defaultModel = resolveDefaultModel();
            agent.put("model", defaultModel);
            log.debug("Applied default model: {}", defaultModel);
        }

        // Rename task → prompt (internal field name)
        if (agent.containsKey("task")) {
            Object task = agent.remove("task");
            if (!agent.containsKey("prompt")) {
                agent.put("prompt", task);
                log.debug("Renamed 'task' to 'prompt'");
            }
        }

        return agent;
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // TRIGGER DEFAULTS
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * Apply smart defaults for trigger nodes.
     *
     * Defaults applied:
     * - strategy: always 'CRON' for schedule triggers (only cron expressions are supported)
     *
     * @param trigger Trigger data from LLM
     * @return Trigger data with defaults applied
     */
    public Map<String, Object> applyTriggerDefaults(Map<String, Object> trigger) {
        log.debug("Applying trigger defaults to: {}", trigger);

        String type = (String) trigger.get("type");

        // Schedule triggers always use CRON strategy
        if ("schedule".equals(type) && (!trigger.containsKey("strategy") || trigger.get("strategy") == null)) {
            trigger.put("strategy", "CRON");
            log.debug("Applied default strategy: CRON");
        }

        return trigger;
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // STEP DEFAULTS (Future)
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * Apply smart defaults for step nodes.
     * Currently no defaults needed, but method exists for future extension.
     *
     * @param step Step data from LLM
     * @return Step data with defaults applied
     */
    public Map<String, Object> applyStepDefaults(Map<String, Object> step) {
        log.debug("Applying step defaults to: {}", step);

        // Future: Could add defaults for HTTP method, headers, etc.

        return step;
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // DECISION DEFAULTS (Future)
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * Apply smart defaults for decision nodes.
     * Currently no defaults needed, but method exists for future extension.
     *
     * @param decision Decision data from LLM
     * @return Decision data with defaults applied
     */
    public Map<String, Object> applyDecisionDefaults(Map<String, Object> decision) {
        log.debug("Applying decision defaults to: {}", decision);

        // Future: Could add defaults for decision logic type, etc.

        return decision;
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // INTERFACE DEFAULTS
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * Apply smart defaults for interface nodes.
     *
     * Defaults applied:
     * - showPreview: always true
     * - isEntryInterface: false (if not provided)
     *
     * @param iface Interface data from LLM
     * @return Interface data with defaults applied
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> applyInterfaceDefaults(Map<String, Object> iface) {
        log.debug("Applying interface defaults to: {}", iface);

        // showPreview always true
        iface.put("showPreview", true);

        // isEntryInterface default
        if (!iface.containsKey("isEntryInterface") && !iface.containsKey("is_entry_interface")) {
            iface.put("isEntryInterface", false);
            log.debug("Applied default isEntryInterface: false");
        }

        return iface;
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // DYNAMIC MODEL RESOLUTION
    // ═══════════════════════════════════════════════════════════════════════════════

    @SuppressWarnings("unchecked")
    private String resolveDefaultModel() {
        try {
            Map<String, Object> info = agentClient.getModelsInfo();
            String model = (String) info.get("defaultModel");
            if (model != null) return model;
        } catch (Exception e) {
            log.debug("Failed to fetch models info: {}", e.getMessage());
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private String resolveDefaultProvider() {
        try {
            Map<String, Object> info = agentClient.getModelsInfo();
            String provider = (String) info.get("defaultProvider");
            if (provider != null) return provider;
        } catch (Exception e) {
            log.debug("Failed to fetch models info: {}", e.getMessage());
        }
        return null;
    }
}
