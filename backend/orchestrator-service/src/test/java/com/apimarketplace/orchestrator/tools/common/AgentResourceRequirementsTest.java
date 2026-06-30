package com.apimarketplace.orchestrator.tools.common;

import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlanParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("AgentResourceRequirements")
class AgentResourceRequirementsTest {

    private static WorkflowPlan planWithSubWorkflow(String workflowId) {
        Map<String, Object> planData = Map.of(
                "id", "wf",
                "tenantId", "tenant",
                "triggers", List.of(),
                "tools", List.of(),
                "cores", List.of(Map.of(
                        "id", "Call Child",
                        "label", "Call Child",
                        "type", "sub_workflow",
                        "subWorkflow", Map.of("workflowId", workflowId)
                )),
                "edges", List.of()
        );
        return WorkflowPlanParser.parse(planData, "wf", "tenant");
    }

    @Test
    @DisplayName("Extracts unique MCP integration names from node icons")
    void extractsUniqueMcpIntegrationNamesFromNodeIcons() {
        List<AgentResourceRequirements.RequiredIntegration> requirements =
                AgentResourceRequirements.integrationsFromNodeIcons(List.of(
                        Map.of("isMcp", true, "iconSlug", "Gmail"),
                        Map.of("isMcp", true, "iconSlug", "gmail"),
                        Map.of("isMcp", false, "iconSlug", "slack"),
                        Map.of("isMcp", true, "iconSlug", "serpapi")
                ));

        assertThat(requirements)
                .extracting(AgentResourceRequirements.RequiredIntegration::name)
                .containsExactly("gmail", "serpapi");
    }

    @Test
    @DisplayName("Extracts sub-workflow references from workflow plans")
    void extractsSubWorkflowReferencesFromWorkflowPlans() {
        UUID childWorkflowId = UUID.randomUUID();

        List<AgentResourceRequirements.RequiredSubWorkflow> requirements =
                AgentResourceRequirements.subWorkflowsFromPlan(planWithSubWorkflow(childWorkflowId.toString()));

        assertThat(requirements).hasSize(1);
        assertThat(requirements.getFirst().workflowId()).isEqualTo(childWorkflowId);
        assertThat(requirements.getFirst().name()).isEqualTo("Call Child");
    }

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("Builds readiness envelope with blockers for missing dependencies")
    void buildsReadinessEnvelopeWithBlockersForMissingDependencies() {
        UUID existingWorkflowId = UUID.randomUUID();
        UUID missingWorkflowId = UUID.randomUUID();

        Map<String, Object> envelope = AgentResourceRequirements.buildEnvelope(
                List.of(
                        new AgentResourceRequirements.RequiredIntegration("gmail"),
                        new AgentResourceRequirements.RequiredIntegration("slack")
                ),
                List.of(
                        new AgentResourceRequirements.RequiredSubWorkflow(existingWorkflowId, "Existing"),
                        new AgentResourceRequirements.RequiredSubWorkflow(missingWorkflowId, "Missing")
                ),
                Set.of("GMAIL"),
                Set.of(existingWorkflowId)
        );

        assertThat(envelope).containsEntry("ready", false);
        assertThat((List<Map<String, Object>>) envelope.get("integrations"))
                .containsExactly(
                        Map.of("name", "gmail", "configured", true),
                        Map.of("name", "slack", "configured", false)
                );
        assertThat((List<Map<String, Object>>) envelope.get("sub_workflows"))
                .containsExactly(
                        Map.of("workflow_id", existingWorkflowId.toString(), "name", "Existing", "exists", true),
                        Map.of("workflow_id", missingWorkflowId.toString(), "name", "Missing", "exists", false)
                );
        assertThat((List<String>) envelope.get("blockers"))
                .containsExactly(
                        "integration:slack not configured",
                        "sub_workflow:Missing not found in tenant"
                );
    }
}
