package com.apimarketplace.orchestrator.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration properties for the WorkflowStateManager.
 *
 * <p>The StateManager is the centralized state management system for workflow execution.
 * It handles node status tracking, ready state propagation, and streaming event emission.
 *
 * @see com.apimarketplace.orchestrator.services.state.WorkflowStateManager
 * @see com.apimarketplace.orchestrator.services.state.StateManagerIntegrationService
 */
@Configuration
@ConfigurationProperties(prefix = "orchestrator.state-manager")
public class StateManagerConfig {

    /**
     * Whether the StateManager is enabled for tracking workflow state.
     * Default: true
     */
    private boolean enabled = true;

    // Getters and setters

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
