package com.apimarketplace.orchestrator.trigger;

import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Utility for detecting trigger types in workflow plans.
 * Provides methods to check for specific trigger types in both WorkflowPlan objects and raw Map representations.
 */
@Component
public class TriggerTypeDetector {

    /**
     * Checks if a WorkflowPlan has a webhook trigger.
     */
    public boolean hasWebhookTrigger(WorkflowPlan plan) {
        if (plan == null || plan.getTriggers() == null) return false;
        return plan.getTriggers().stream()
            .anyMatch(trigger -> "webhook".equals(trigger.type()));
    }

    /**
     * Checks if a plan Map has a webhook trigger.
     */
    public boolean hasWebhookTrigger(Map<String, Object> planMap) {
        if (planMap == null) return false;
        Object triggersObj = planMap.get("triggers");
        if (triggersObj instanceof List<?> triggers) {
            for (Object trigger : triggers) {
                if (trigger instanceof Map<?, ?> triggerMap) {
                    Object type = triggerMap.get("type");
                    if ("webhook".equals(type)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Checks if a WorkflowPlan has a datasource trigger.
     * Datasource triggers behave like other reusable triggers in editor runs (wait
     * for explicit fire); production firing happens via the trigger-service
     * subscription registry on real row events.
     */
    public boolean hasDatasourceTrigger(WorkflowPlan plan) {
        if (plan == null || plan.getTriggers() == null) return false;
        return plan.getTriggers().stream()
            .anyMatch(trigger -> "datasource".equalsIgnoreCase(trigger.type()));
    }

    /**
     * Checks if a plan Map has a datasource trigger.
     */
    public boolean hasDatasourceTrigger(Map<String, Object> planMap) {
        if (planMap == null) return false;
        Object triggersObj = planMap.get("triggers");
        if (triggersObj instanceof List<?> triggers) {
            for (Object trigger : triggers) {
                if (trigger instanceof Map<?, ?> triggerMap) {
                    Object type = triggerMap.get("type");
                    if (type instanceof String typeStr && "datasource".equalsIgnoreCase(typeStr)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Checks if a WorkflowPlan has a reusable trigger (webhook, manual, chat, or datasource).
     * Reusable triggers require waiting for an external event before execution.
     * Uses centralized TriggerType enum for consistency.
     */
    public boolean hasReusableTrigger(WorkflowPlan plan) {
        if (plan == null || plan.getTriggers() == null) return false;
        return plan.getTriggers().stream()
            .anyMatch(trigger -> TriggerType.isReusableTriggerType(trigger.type()));
    }

    /**
     * Checks if a plan Map has a reusable trigger.
     */
    public boolean hasReusableTrigger(Map<String, Object> planMap) {
        if (planMap == null) return false;
        Object triggersObj = planMap.get("triggers");
        if (triggersObj instanceof List<?> triggers) {
            for (Object trigger : triggers) {
                if (trigger instanceof Map<?, ?> triggerMap) {
                    Object type = triggerMap.get("type");
                    if (type instanceof String typeStr && TriggerType.isReusableTriggerType(typeStr)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Checks if a WorkflowPlan has an external trigger (webhook, schedule, or workflow).
     * External triggers require cancellation of stale runs when creating new ones.
     */
    public boolean hasExternalTrigger(WorkflowPlan plan) {
        if (plan == null || plan.getTriggers() == null) return false;
        return plan.getTriggers().stream()
            .anyMatch(trigger -> TriggerType.isExternalTrigger(trigger.type()));
    }

    /**
     * Checks if a WorkflowPlan has a manual trigger.
     */
    public boolean hasManualTrigger(WorkflowPlan plan) {
        if (plan == null || plan.getTriggers() == null) return false;
        return plan.getTriggers().stream()
            .anyMatch(trigger -> "manual".equalsIgnoreCase(trigger.type()));
    }

    /**
     * Checks if a WorkflowPlan has a chat trigger.
     */
    public boolean hasChatTrigger(WorkflowPlan plan) {
        if (plan == null || plan.getTriggers() == null) return false;
        return plan.getTriggers().stream()
            .anyMatch(trigger -> "chat".equalsIgnoreCase(trigger.type()));
    }

    /**
     * Checks if a WorkflowPlan has a schedule trigger.
     */
    public boolean hasScheduleTrigger(WorkflowPlan plan) {
        if (plan == null || plan.getTriggers() == null) return false;
        return plan.getTriggers().stream()
            .anyMatch(trigger -> "schedule".equalsIgnoreCase(trigger.type()));
    }

    /**
     * Checks if a WorkflowPlan has a form trigger.
     */
    public boolean hasFormTrigger(WorkflowPlan plan) {
        if (plan == null || plan.getTriggers() == null) return false;
        return plan.getTriggers().stream()
            .anyMatch(trigger -> "form".equalsIgnoreCase(trigger.type()));
    }

    /**
     * Gets the type of the first trigger in the plan.
     */
    public String getFirstTriggerType(WorkflowPlan plan) {
        if (plan == null || plan.getTriggers() == null || plan.getTriggers().isEmpty()) {
            return null;
        }
        return plan.getTriggers().get(0).type();
    }
}
