package com.apimarketplace.storage.config;

import com.apimarketplace.storage.client.OrchestratorSubWorkflowLineageClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wiring for the CASA LC-037 (gap 2) sub-workflow-lineage client used by
 * {@code FileController}/{@code MonolithFileController} to extend an APPLICATION share's file
 * access to a file produced inside a {@code core:sub_workflow} child run.
 *
 * <p>{@code @ConditionalOnMissingBean}: mirrors interface-service's
 * {@code OrchestratorClientConfig} - an integration test harness can supply a mock (orchestrator
 * isn't running in the test env, so the real client's HTTP call would fail and, being fail-closed,
 * silently deny every sub-workflow-file share read it is asked about).
 */
@Configuration
public class OrchestratorClientConfig {

    @Bean
    @ConditionalOnMissingBean
    public OrchestratorSubWorkflowLineageClient orchestratorSubWorkflowLineageClient(
            @Value("${services.orchestrator-url:http://localhost:8099}") String orchestratorUrl) {
        return new OrchestratorSubWorkflowLineageClient(orchestratorUrl);
    }
}
